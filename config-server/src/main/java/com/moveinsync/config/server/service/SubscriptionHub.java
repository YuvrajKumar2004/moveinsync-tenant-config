package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.snapshot.TenantFilter;
import com.moveinsync.config.core.wire.WireCodec;
import com.moveinsync.config.server.config.AppProperties;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.observability.ConfigMetrics;
import com.moveinsync.config.server.security.AuthUser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Server-sent-events fan-out. Each subscriber remembers the last version it was sent; delivery always
 * sends the contiguous range (lastSent, target] from the in-memory log, so a subscriber never receives a
 * gap even when publishes race. Delivery runs on virtual threads; one slow subscriber never blocks others.
 *
 * <p>Events: {@code change} (id = version, data = event), {@code heartbeat} (current version, also used
 * by clients to detect a missed push) and {@code resync} (events no longer retained; fetch a delta).
 */
@Component
public class SubscriptionHub implements ChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionHub.class);

    private final ConfigStateService state;
    private final EventJson json;
    private final ConfigMetrics metrics;
    private final FaultInjector faults;
    private final int maxSubscribers;
    private final long streamLifetimeMillis;
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final class Subscriber {
        final SseEmitter emitter;
        final TenantFilter filter;
        long lastSent;
        boolean closed;

        Subscriber(SseEmitter emitter, TenantFilter filter, long since) {
            this.emitter = emitter;
            this.filter = filter;
            this.lastSent = since;
        }
    }

    public SubscriptionHub(ConfigStateService state, EventJson json, ConfigMetrics metrics, FaultInjector faults,
                           AppProperties props) {
        this.state = state;
        this.json = json;
        this.metrics = metrics;
        this.faults = faults;
        this.maxSubscribers = props.sync().maxSubscribers();
        this.streamLifetimeMillis = props.sync().streamLifetime() == null ? 30 * 60_000L : props.sync().streamLifetime().toMillis();
    }

    public SseEmitter subscribe(AuthUser user, long since) {
        if (subscribers.size() >= maxSubscribers) {
            metrics.handshakeRejected("capacity");
            throw ApiException.unavailable("too many subscribers; retry with backoff");
        }
        // Streams are retired after a bounded lifetime; the client reconnects and resumes from its version.
        // That also rebalances connections after a restart and frees anything a half-dead peer holds.
        SseEmitter emitter = new SseEmitter(streamLifetimeMillis);
        Subscriber s = new Subscriber(emitter, user.readFilter(), Math.max(0, since));
        emitter.onCompletion(() -> remove(s));
        emitter.onTimeout(() -> remove(s));
        emitter.onError(e -> remove(s));
        subscribers.add(s);
        metrics.subscribers().set(subscribers.size());
        long current = state.version();
        // Backlog and first heartbeat are written before the emitter is returned: Spring buffers these early
        // sends and flushes them in order when the response starts, so the client sees headers + data at once.
        synchronized (s) {
            if (s.lastSent > current) {
                // client is ahead of this server (e.g. restored from an older backup): tell it our version
                s.lastSent = current;
            } else {
                deliver(s, current);
            }
            send(s, "heartbeat", null, json.write(Map.of("version", current)));
        }
        return emitter;
    }

    @Override
    public void publish(ChangeEvent event) {
        long target = event.version();
        for (Subscriber s : subscribers) {
            schedule(() -> {
                synchronized (s) {
                    deliver(s, target);
                }
            });
        }
    }

    /** Must hold the subscriber's lock. */
    private void deliver(Subscriber s, long target) {
        if (s.closed || s.lastSent >= target) {
            return;
        }
        Optional<List<ChangeEvent>> events = state.eventsAfter(s.lastSent, target);
        if (events.isEmpty()) {
            send(s, "resync", null, json.write(Map.of("version", target)));
            s.lastSent = target;
            return;
        }
        FaultInjector.Faults f = faults.get();
        ChangeEvent previous = null;
        for (ChangeEvent e : events.get()) {
            if (f.dropPushes()) {
                metrics.pushDroppedByFault();
            } else {
                String data = json.write(WireCodec.toWire(s.filter.filter(e)));
                if (send(s, "change", e.version(), data)) {
                    metrics.pushSent();
                    metrics.pushLag().record(Math.max(0, System.currentTimeMillis() - e.committedAtMillis()), TimeUnit.MILLISECONDS);
                }
                if (f.duplicatePushes()) {
                    send(s, "change", e.version(), data);
                }
                if (f.outOfOrderPushes() && previous != null) {
                    send(s, "change", previous.version(), json.write(WireCodec.toWire(s.filter.filter(previous))));
                }
            }
            previous = e;
            s.lastSent = e.version();
        }
    }

    private boolean send(Subscriber s, String name, Long id, String data) {
        if (s.closed) {
            return false;
        }
        try {
            SseEmitter.SseEventBuilder b = SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
            if (id != null) {
                b.id(Long.toString(id));
            }
            s.emitter.send(b);
            return true;
        } catch (IOException | IllegalStateException e) {
            metrics.pushFailed();
            s.closed = true;
            remove(s);
            return false;
        }
    }

    @Scheduled(fixedRateString = "${app.sync.heartbeat-interval}")
    void heartbeat() {
        long v = state.version();
        String data = json.write(Map.of("version", v));
        for (Subscriber s : subscribers) {
            schedule(() -> {
                synchronized (s) {
                    deliver(s, v); // also repairs a subscriber that missed a publish
                    send(s, "heartbeat", null, data);
                }
            });
        }
    }

    private void schedule(Runnable task) {
        metrics.queueDepth().incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    metrics.error("push");
                    log.warn("push delivery failed: {}", e.getClass().getSimpleName());
                } finally {
                    metrics.queueDepth().decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {
            metrics.queueDepth().decrementAndGet();
        }
    }

    private void remove(Subscriber s) {
        if (subscribers.remove(s)) {
            metrics.subscribers().set(subscribers.size());
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Runs before the web server's graceful shutdown (higher phase stops first): open SSE responses never
     * finish on their own, so they are completed here and clients reconnect elsewhere / later.
     */
    @Bean
    static SmartLifecycle subscriptionHubCloser(@Lazy SubscriptionHub hub) {
        return new SmartLifecycle() {
            private volatile boolean running = true;

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                hub.completeAll();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE;
            }
        };
    }

    void completeAll() {
        for (Subscriber s : subscribers) {
            synchronized (s) {
                s.closed = true;
                try {
                    s.emitter.complete();
                } catch (RuntimeException ignored) {
                    // connection already gone
                }
            }
        }
        subscribers.clear();
        metrics.subscribers().set(0);
    }

    @PreDestroy
    void shutdown() {
        for (Subscriber s : subscribers) {
            synchronized (s) {
                s.closed = true;
                try {
                    s.emitter.complete();
                } catch (RuntimeException ignored) {
                    // connection already gone
                }
            }
        }
        subscribers.clear();
        executor.shutdownNow();
    }
}
