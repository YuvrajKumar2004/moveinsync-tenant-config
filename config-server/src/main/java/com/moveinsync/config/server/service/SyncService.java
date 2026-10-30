package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.TenantFilter;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;
import com.moveinsync.config.server.config.AppProperties;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.observability.ConfigMetrics;
import com.moveinsync.config.server.security.AuthUser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Pull side of synchronisation: version checks and "what changed since N". Answers with the missing
 * events when they are still in memory, otherwise with a full snapshot. Full snapshots are the
 * expensive case during a reconnect storm, so they are cached per (version, tenant set) in an LRU and
 * their construction is bounded by a semaphore; callers beyond the bound get 503 + Retry-After and back
 * off with jitter.
 */
@Service
public class SyncService {

    private final ConfigStateService state;
    private final ConfigMetrics metrics;
    private final LruCache<String, Wire.SnapshotData> snapshotCache;
    private final Semaphore fullSnapshotPermits;

    public SyncService(ConfigStateService state, ConfigMetrics metrics, AppProperties props) {
        this.state = state;
        this.metrics = metrics;
        this.snapshotCache = new LruCache<>(props.sync().snapshotCacheEntries());
        this.fullSnapshotPermits = new Semaphore(props.sync().maxConcurrentFullSnapshots());
    }

    public long version() {
        return state.version();
    }

    public Wire.Delta delta(AuthUser user, long since) {
        if (since < 0) {
            throw ApiException.badRequest("since must be >= 0");
        }
        Snapshot current = state.current();
        TenantFilter filter = user.readFilter();
        if (since == current.version()) {
            metrics.delta("empty");
            return new Wire.Delta(since, since, false, List.of(), null);
        }
        if (since > 0 && since < current.version()) {
            Optional<List<ChangeEvent>> events = state.eventsAfter(since, current.version());
            if (events.isPresent()) {
                metrics.delta("events");
                List<Wire.Event> wire = events.get().stream().map(e -> WireCodec.toWire(filter.filter(e))).toList();
                return new Wire.Delta(since, current.version(), false, wire, null);
            }
        }
        // since == 0 (new client), too old, or ahead of the server (server restored from older state)
        metrics.delta("full");
        return new Wire.Delta(since, current.version(), true, List.of(), fullSnapshot(current, filter));
    }

    public Wire.SnapshotData snapshot(AuthUser user) {
        return fullSnapshot(state.current(), user.readFilter());
    }

    private Wire.SnapshotData fullSnapshot(Snapshot current, TenantFilter filter) {
        String key = current.version() + "|" + (filter.all() ? "*" : String.join(",", new TreeSet<>(filter.tenants())));
        Wire.SnapshotData cached = snapshotCache.get(key);
        if (cached != null) {
            metrics.snapshotCache(true);
            return cached;
        }
        metrics.snapshotCache(false);
        boolean acquired;
        try {
            acquired = fullSnapshotPermits.tryAcquire(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            metrics.handshakeRejected("snapshot-busy");
            throw ApiException.unavailable("snapshot builds are saturated; retry with backoff");
        }
        try {
            return snapshotCache.computeIfAbsent(key, k -> WireCodec.toWire(current.filtered(filter)));
        } finally {
            fullSnapshotPermits.release();
        }
    }
}
