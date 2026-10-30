package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.ConfigTransport;
import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.client.transport.HttpStatusException;
import com.moveinsync.config.client.transport.PasswordTokenProvider;
import com.moveinsync.config.client.transport.TokenProvider;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.SnapshotHolder;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The library every service embeds.
 *
 * <p><b>Read path:</b> {@link #view(ResolutionContext)} returns a view over the current immutable
 * snapshot: a volatile read plus a few hash lookups, no network, no locks.
 *
 * <p><b>Freshness:</b> an SSE stream pushes each committed change (normally milliseconds). Heartbeats on
 * the stream and an independent periodic version check (default every 2 s, jittered) detect anything
 * the push missed and fetch the delta, which bounds staleness for a connected client to about one poll
 * interval plus a round trip - inside the 5 s budget.
 *
 * <p><b>Ordering:</b> versions only move forward. A duplicate or older event is dropped; a gap triggers
 * a delta fetch instead of applying out of order.
 *
 * <p><b>Failure:</b> when the server is unreachable the last good snapshot keeps serving; every change is
 * saved to disk so a restart can come up without the server. Reconnects use exponential backoff with
 * full jitter.
 */
public final class ConfigClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigClient.class);
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 5_000;

    private final ConfigTransport transport;
    private final SnapshotStore store;
    private final Duration pollInterval;
    private final Duration streamIdleTimeout;
    private final Backoff backoff;
    private final boolean requireInitialData;
    private final Duration initialSyncTimeout;
    private final String name;

    private final SnapshotHolder holder = new SnapshotHolder(Snapshot.empty());
    private final ClientMetrics metrics = new ClientMetrics();
    private final ReentrantLock syncLock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Snapshot> pendingDiskWrite = new AtomicReference<>();
    private final AtomicBoolean diskWriterScheduled = new AtomicBoolean();
    private volatile ConfigTransport.StreamHandle currentStream;
    private volatile long lastStreamActivity;
    private volatile boolean streamConnected;
    private Thread streamThread;
    private Thread pollThread;

    private ConfigClient(Builder b) {
        this.transport = Objects.requireNonNull(b.transport, "transport");
        this.store = b.snapshotFile == null ? null : new SnapshotStore(b.snapshotFile);
        this.pollInterval = b.pollInterval;
        this.streamIdleTimeout = b.streamIdleTimeout;
        this.backoff = new Backoff(b.backoffBase, b.backoffCap);
        this.requireInitialData = b.requireInitialData;
        this.initialSyncTimeout = b.initialSyncTimeout;
        this.name = b.name;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Loads the disk copy (if any), tries one synchronous sync with the server, then starts the push
     * stream and the periodic check. Succeeds without the server when a disk copy exists.
     *
     * @throws IllegalStateException when neither the server nor a valid disk copy is available and
     *                               {@code requireInitialData} is set
     */
    public ConfigClient start() {
        if (!running.compareAndSet(false, true)) {
            return this;
        }
        if (store != null) {
            try {
                store.load().ifPresent(s -> {
                    install(s);
                    log.info("[{}] loaded snapshot v{} from {}", name, s.version(), store.file());
                });
            } catch (IOException e) {
                metrics.diskFailures.incrementAndGet();
                log.warn("[{}] ignoring unusable snapshot file: {}", name, e.getMessage());
            }
        }
        try {
            initialSync();
        } catch (IOException e) {
            metrics.syncErrors.incrementAndGet();
            if (holder.version() == 0 && requireInitialData) {
                running.set(false);
                throw new IllegalStateException("config server unreachable and no snapshot on disk", e);
            }
            log.warn("[{}] server unreachable at startup ({}); serving snapshot v{}", name, e.getMessage(), holder.version());
        }
        streamThread = Thread.ofVirtual().name("config-stream-" + name).start(this::streamLoop);
        pollThread = Thread.ofVirtual().name("config-poll-" + name).start(this::pollLoop);
        return this;
    }

    /**
     * One sync at startup, retried with jittered backoff for up to {@code initialSyncTimeout} when it is
     * worth waiting: the server answered "busy" (503 / 429), or the connection failed and there is no disk
     * copy to serve instead (e.g. hundreds of instances cold-starting at once overflow the accept queue).
     * With a disk copy, an unreachable server fails at once so startup is not delayed.
     */
    private void initialSync() throws IOException {
        long deadline = System.currentTimeMillis() + initialSyncTimeout.toMillis();
        for (int attempt = 0; ; attempt++) {
            try {
                catchUp();
                return;
            } catch (IOException e) {
                long retryAfter = e instanceof HttpStatusException h ? h.retryAfterMillis() : 0;
                boolean busy = e instanceof HttpStatusException h && (h.status() == 503 || h.status() == 429);
                boolean nothingToServe = holder.version() == 0 && !(e instanceof HttpStatusException);
                long wait = retryAfter + backoff.delayMillis(attempt);
                if (!(busy || nothingToServe) || System.currentTimeMillis() + wait > deadline) {
                    throw e;
                }
                metrics.syncErrors.incrementAndGet();
                sleep(wait);
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ConfigTransport.StreamHandle h = currentStream;
        if (h != null) {
            h.close();
        }
        if (streamThread != null) {
            streamThread.interrupt();
        }
        if (pollThread != null) {
            pollThread.interrupt();
        }
        flushDisk();
        transport.close();
    }

    // ----------------------------------------------------------------------- reads

    /** A view pinned to the current snapshot. Never blocks, never calls the network. */
    public ConfigView view(ResolutionContext ctx) {
        return new ConfigView(holder.current(), ctx);
    }

    public ConfigView view(String clientId, String officeId, String shiftId) {
        return view(ResolutionContext.of(clientId, officeId, shiftId));
    }

    public long version() {
        return holder.version();
    }

    public Snapshot snapshot() {
        return holder.current();
    }

    public ClientMetrics metrics() {
        return metrics;
    }

    public boolean streamConnected() {
        return streamConnected;
    }

    /** Called (on a background thread) after every newly installed snapshot. */
    public void addListener(Consumer<Snapshot> listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------- sync core

    /** Applies one pushed event, or fetches the delta when it reveals a gap. */
    void onEvent(Wire.Event wire) {
        ChangeEvent e = WireCodec.fromWire(wire);
        SnapshotHolder.Outcome outcome;
        syncLock.lock();
        try {
            outcome = holder.offer(e);
        } finally {
            syncLock.unlock();
        }
        switch (outcome) {
            case APPLIED -> {
                metrics.eventsApplied.incrementAndGet();
                metrics.lastLagMillis.set(Math.max(0, System.currentTimeMillis() - e.committedAtMillis()));
                installed(holder.current());
            }
            case STALE -> metrics.staleDropped.incrementAndGet();
            case GAP -> {
                metrics.gapsDetected.incrementAndGet();
                safeCatchUp();
            }
        }
    }

    void onServerVersion(long serverVersion, boolean fromHeartbeat) {
        long local = holder.version();
        if (serverVersion > local) {
            if (fromHeartbeat) {
                metrics.heartbeatHeals.incrementAndGet();
            } else {
                metrics.pollHeals.incrementAndGet();
            }
            safeCatchUp();
        } else if (serverVersion < local) {
            metrics.serverBehind.incrementAndGet(); // never go backwards; keep serving what we have
        }
    }

    private void safeCatchUp() {
        try {
            catchUp();
        } catch (IOException e) {
            metrics.syncErrors.incrementAndGet();
        }
    }

    /** Fetches and applies everything after the local version. Serialised; concurrent callers coalesce. */
    void catchUp() throws IOException {
        syncLock.lock();
        try {
            for (int round = 0; round < 3; round++) {
                long local = holder.version();
                Wire.Delta delta = transport.fetchDelta(local);
                metrics.lastServerContactMillis.set(System.currentTimeMillis());
                if (delta.full()) {
                    Snapshot s = WireCodec.fromWire(delta.snapshot());
                    if (s.version() > local) {
                        metrics.fullSnapshots.incrementAndGet();
                        install(s);
                    } else if (s.version() < local) {
                        metrics.serverBehind.incrementAndGet();
                    }
                    return;
                }
                boolean gap = false;
                for (Wire.Event w : delta.events()) {
                    ChangeEvent e = WireCodec.fromWire(w);
                    SnapshotHolder.Outcome o = holder.offer(e);
                    if (o == SnapshotHolder.Outcome.APPLIED) {
                        metrics.eventsApplied.incrementAndGet();
                        metrics.lastLagMillis.set(Math.max(0, System.currentTimeMillis() - e.committedAtMillis()));
                    } else if (o == SnapshotHolder.Outcome.GAP) {
                        gap = true;
                        break;
                    }
                }
                if (holder.version() > local) {
                    installed(holder.current());
                }
                if (!gap) {
                    return;
                }
            }
        } finally {
            syncLock.unlock();
        }
    }

    private void install(Snapshot s) {
        if (holder.replaceIfNewer(s)) {
            installed(s);
        }
    }

    private void installed(Snapshot s) {
        scheduleDiskWrite(s);
        for (Consumer<Snapshot> l : listeners) {
            try {
                l.accept(s);
            } catch (RuntimeException e) {
                log.warn("[{}] snapshot listener failed: {}", name, e.toString());
            }
        }
    }

    // --------------------------------------------------------------- background

    private void streamLoop() {
        int attempt = 0;
        while (running.get()) {
            ConfigTransport.StreamHandle handle = new ConfigTransport.StreamHandle();
            currentStream = handle;
            boolean receivedSomething = false;
            try {
                lastStreamActivity = System.currentTimeMillis();
                TrackingListener listener = new TrackingListener();
                transport.stream(holder.version(), listener, handle);
                receivedSomething = listener.received;
            } catch (HttpStatusException e) {
                metrics.syncErrors.incrementAndGet();
                if (e.retryAfterMillis() > 0) {
                    sleep(e.retryAfterMillis() + backoff.delayMillis(attempt));
                }
            } catch (IOException | RuntimeException e) {
                metrics.syncErrors.incrementAndGet();
            } finally {
                streamConnected = false;
            }
            if (!running.get()) {
                return;
            }
            attempt = receivedSomething ? 0 : Math.min(attempt + 1, 30);
            metrics.reconnectAttempts.incrementAndGet();
            sleep(backoff.delayMillis(attempt));
        }
    }

    private final class TrackingListener implements ConfigTransport.StreamListener {
        boolean received;

        private void touch() {
            if (!received) {
                received = true;
                streamConnected = true;
                metrics.streamConnects.incrementAndGet();
            }
            lastStreamActivity = System.currentTimeMillis();
            metrics.lastServerContactMillis.set(lastStreamActivity);
        }

        @Override
        public void onChange(Wire.Event event) {
            touch();
            onEvent(event);
        }

        @Override
        public void onHeartbeat(long serverVersion) {
            touch();
            onServerVersion(serverVersion, true);
        }

        @Override
        public void onResync(long serverVersion) {
            touch();
            safeCatchUp();
        }
    }

    private void pollLoop() {
        int failures = 0;
        while (running.get()) {
            long delay = failures == 0 ? Backoff.jittered(pollInterval, 0.2)
                    : Math.max(pollInterval.toMillis(), backoff.delayMillis(failures));
            sleep(delay);
            if (!running.get()) {
                return;
            }
            try {
                metrics.pollChecks.incrementAndGet();
                long serverVersion = transport.fetchVersion();
                metrics.lastServerContactMillis.set(System.currentTimeMillis());
                failures = 0;
                onServerVersion(serverVersion, false);
            } catch (IOException | RuntimeException e) {
                metrics.syncErrors.incrementAndGet();
                failures = Math.min(failures + 1, 30);
            }
            // a stream that never delivered its first event, or went silent (half-open TCP), is closed so the
            // stream loop reconnects; the server sends a heartbeat immediately and then periodically
            ConfigTransport.StreamHandle h = currentStream;
            long silentFor = System.currentTimeMillis() - lastStreamActivity;
            long limit = streamConnected ? streamIdleTimeout.toMillis() : HANDSHAKE_TIMEOUT_MILLIS;
            if (h != null && silentFor > limit) {
                h.close();
            }
        }
    }

    private void scheduleDiskWrite(Snapshot s) {
        if (store == null) {
            return;
        }
        pendingDiskWrite.set(s);
        if (diskWriterScheduled.compareAndSet(false, true)) {
            Thread.ofVirtual().name("config-disk-" + name).start(() -> {
                try {
                    flushDisk();
                } finally {
                    diskWriterScheduled.set(false);
                    if (pendingDiskWrite.get() != null) {
                        scheduleDiskWrite(pendingDiskWrite.get());
                    }
                }
            });
        }
    }

    private synchronized void flushDisk() {
        Snapshot s = pendingDiskWrite.getAndSet(null);
        if (s == null || store == null) {
            return;
        }
        try {
            store.save(s);
            metrics.diskWrites.incrementAndGet();
        } catch (IOException e) {
            metrics.diskFailures.incrementAndGet();
            log.warn("[{}] could not save snapshot: {}", name, e.getMessage());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            // close() interrupts the loops; they re-check `running` and exit
        }
    }

    // ------------------------------------------------------------------- builder

    public static final class Builder {
        private ConfigTransport transport;
        private Path snapshotFile;
        private Duration pollInterval = Duration.ofSeconds(2);
        private Duration streamIdleTimeout = Duration.ofSeconds(20);
        private Duration backoffBase = Duration.ofMillis(250);
        private Duration backoffCap = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private boolean requireInitialData = true;
        private Duration initialSyncTimeout = Duration.ofSeconds(15);
        private String name = "config";
        private String serverUrl;
        private TokenProvider tokens;
        private String[] credentials;
        private HttpClient httpClient;

        /** HTTP transport against {@code serverUrl}, authenticating with service credentials. */
        public Builder server(String serverUrl, String username, String password) {
            this.serverUrl = serverUrl;
            this.tokens = null;
            this.credentials = new String[]{username, password};
            return this;
        }

        public Builder server(String serverUrl, TokenProvider tokens) {
            this.serverUrl = serverUrl;
            this.tokens = tokens;
            return this;
        }

        /** Share one HttpClient across many clients (used by the load simulator). */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder transport(ConfigTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder snapshotFile(Path file) {
            this.snapshotFile = file;
            return this;
        }

        public Builder pollInterval(Duration d) {
            this.pollInterval = d;
            return this;
        }

        public Builder streamIdleTimeout(Duration d) {
            this.streamIdleTimeout = d;
            return this;
        }

        public Builder backoff(Duration base, Duration cap) {
            this.backoffBase = base;
            this.backoffCap = cap;
            return this;
        }

        public Builder requestTimeout(Duration d) {
            this.requestTimeout = d;
            return this;
        }

        public Builder requireInitialData(boolean require) {
            this.requireInitialData = require;
            return this;
        }

        public Builder initialSyncTimeout(Duration d) {
            this.initialSyncTimeout = d;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public ConfigClient build() {
            if (transport == null) {
                Objects.requireNonNull(serverUrl, "server url or transport is required");
                HttpClient http = httpClient != null ? httpClient : HttpConfigTransport.defaultClient();
                TokenProvider t = tokens != null ? tokens : new PasswordTokenProvider(http, serverUrl, credentials[0], credentials[1]);
                transport = new HttpConfigTransport(http, serverUrl, t, requestTimeout);
            }
            return new ConfigClient(this);
        }
    }
}
