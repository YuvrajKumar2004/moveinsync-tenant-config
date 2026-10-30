package com.moveinsync.config.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plain counters, so the library does not force a metrics framework on its host. A Spring host binds
 * them to Micrometer gauges (see demo-booking-service).
 */
public final class ClientMetrics {

    final AtomicLong eventsApplied = new AtomicLong();
    final AtomicLong staleDropped = new AtomicLong();
    final AtomicLong gapsDetected = new AtomicLong();
    final AtomicLong fullSnapshots = new AtomicLong();
    final AtomicLong pollChecks = new AtomicLong();
    final AtomicLong pollHeals = new AtomicLong();
    final AtomicLong heartbeatHeals = new AtomicLong();
    final AtomicLong streamConnects = new AtomicLong();
    final AtomicLong reconnectAttempts = new AtomicLong();
    final AtomicLong syncErrors = new AtomicLong();
    final AtomicLong serverBehind = new AtomicLong();
    final AtomicLong historyResets = new AtomicLong();
    final AtomicLong diskWrites = new AtomicLong();
    final AtomicLong diskFailures = new AtomicLong();
    final AtomicLong lastLagMillis = new AtomicLong(-1);
    final AtomicLong lastServerContactMillis = new AtomicLong();

    /** Milliseconds since the server last answered; grows while it is down. */
    public long stalenessMillis() {
        long t = lastServerContactMillis.get();
        return t == 0 ? -1 : System.currentTimeMillis() - t;
    }

    public long eventsApplied() {
        return eventsApplied.get();
    }

    public long staleDropped() {
        return staleDropped.get();
    }

    public long gapsDetected() {
        return gapsDetected.get();
    }

    public long fullSnapshots() {
        return fullSnapshots.get();
    }

    public long pollHeals() {
        return pollHeals.get();
    }

    public long heartbeatHeals() {
        return heartbeatHeals.get();
    }

    public long reconnectAttempts() {
        return reconnectAttempts.get();
    }

    public long syncErrors() {
        return syncErrors.get();
    }

    public long historyResets() {
        return historyResets.get();
    }

    public long diskFailures() {
        return diskFailures.get();
    }

    public Map<String, Long> asMap() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("events_applied", eventsApplied.get());
        m.put("stale_dropped", staleDropped.get());
        m.put("gaps_detected", gapsDetected.get());
        m.put("full_snapshots", fullSnapshots.get());
        m.put("poll_checks", pollChecks.get());
        m.put("poll_heals", pollHeals.get());
        m.put("heartbeat_heals", heartbeatHeals.get());
        m.put("stream_connects", streamConnects.get());
        m.put("reconnect_attempts", reconnectAttempts.get());
        m.put("sync_errors", syncErrors.get());
        m.put("server_behind", serverBehind.get());
        m.put("history_resets", historyResets.get());
        m.put("disk_writes", diskWrites.get());
        m.put("disk_failures", diskFailures.get());
        m.put("last_lag_ms", lastLagMillis.get());
        m.put("staleness_ms", stalenessMillis());
        return m;
    }
}
