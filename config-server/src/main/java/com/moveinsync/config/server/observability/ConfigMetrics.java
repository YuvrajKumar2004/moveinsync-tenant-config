package com.moveinsync.config.server.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The counters an on-call engineer needs: current version, commits and rejections, push fan-out
 * (subscribers, queue depth, send failures), sync responses (delta vs full) and push-to-commit lag.
 */
@Component
public class ConfigMetrics {

    private final MeterRegistry registry;
    private final AtomicLong version = new AtomicLong();
    private final AtomicInteger subscribers = new AtomicInteger();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final Timer commitLatency;
    private final Timer pushLag;

    public ConfigMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("config.version.current", version, AtomicLong::get).description("latest committed version").register(registry);
        Gauge.builder("config.stream.subscribers", subscribers, AtomicInteger::get).description("open SSE streams").register(registry);
        Gauge.builder("config.stream.queue.depth", queueDepth, AtomicInteger::get).description("pending push deliveries").register(registry);
        this.commitLatency = Timer.builder("config.commit.latency").description("validate + persist time").register(registry);
        this.pushLag = Timer.builder("config.push.lag").description("commit to SSE write time")
                .publishPercentiles(0.5, 0.99).register(registry);
    }

    public void version(long v) {
        version.set(v);
    }

    public AtomicInteger subscribers() {
        return subscribers;
    }

    public AtomicInteger queueDepth() {
        return queueDepth;
    }

    public Timer commitLatency() {
        return commitLatency;
    }

    public Timer pushLag() {
        return pushLag;
    }

    public void committed(String kind) {
        counter("config.changes.committed", "kind", kind);
    }

    /** reason: validation, conflict, forbidden. */
    public void rejected(String reason) {
        counter("config.changes.rejected", "reason", reason);
    }

    public void error(String type) {
        counter("config.errors", "type", type);
    }

    public void pushSent() {
        counter("config.stream.events.sent", "result", "ok");
    }

    public void pushFailed() {
        counter("config.stream.events.sent", "result", "failed");
    }

    public void pushDroppedByFault() {
        counter("config.faults.push.dropped", "fault", "drop");
    }

    public void handshakeRejected(String reason) {
        counter("config.stream.handshake.rejected", "reason", reason);
    }

    /** type: events, full, empty. */
    public void delta(String type) {
        counter("config.sync.delta", "type", type);
    }

    public void snapshotCache(boolean hit) {
        counter("config.sync.snapshot.cache", "result", hit ? "hit" : "miss");
    }

    private void counter(String name, String tag, String value) {
        Counter.builder(name).tag(tag, value).register(registry).increment();
    }
}
