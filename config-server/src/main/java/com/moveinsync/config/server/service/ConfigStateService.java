package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.SnapshotHolder;
import com.moveinsync.config.server.config.AppProperties;
import com.moveinsync.config.server.observability.ConfigMetrics;
import com.moveinsync.config.server.repository.ChangeSetRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The server's authoritative in-memory state, rebuilt at startup by replaying the durable change log,
 * plus a bounded in-memory window of recent events used to answer "what changed since N" cheaply.
 */
@Service
public class ConfigStateService {

    private static final Logger log = LoggerFactory.getLogger(ConfigStateService.class);

    private final ChangeSetRepository repository;
    private final EventJson json;
    private final ConfigMetrics metrics;
    private final int retained;
    private final SnapshotHolder holder = new SnapshotHolder(Snapshot.empty());
    private final ConcurrentSkipListMap<Long, ChangeEvent> recent = new ConcurrentSkipListMap<>();
    private volatile String historyId;

    public ConfigStateService(ChangeSetRepository repository, EventJson json, ConfigMetrics metrics, AppProperties props) {
        this.repository = repository;
        this.json = json;
        this.metrics = metrics;
        this.retained = Math.max(1, props.sync().retainedEvents());
    }

    @PostConstruct
    void load() {
        long start = System.nanoTime();
        historyId = repository.historyId(UUID.randomUUID().toString());
        catchUp();
        log.info("state rebuilt from change log: history={} version={} overrides={} in {}ms", historyId, holder.version(),
                holder.current().overrideCount(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Identity of the change log; version numbers are only comparable within one history. */
    public String historyId() {
        return historyId;
    }

    /** Applies any change sets present in the database but not yet in memory. */
    public synchronized void catchUp() {
        repository.forEachInRange(holder.version(), Long.MAX_VALUE, r -> {
            ChangeEvent e = json.event(r);
            if (holder.offer(e) == SnapshotHolder.Outcome.APPLIED) {
                remember(e);
            }
        });
        metrics.version(holder.version());
    }

    public Snapshot current() {
        return holder.current();
    }

    public long version() {
        return holder.version();
    }

    /** Called by the single writer after the change set is durably committed. */
    synchronized void committed(Snapshot candidate, ChangeEvent event) {
        if (!holder.replaceIfNewer(candidate)) {
            throw new IllegalStateException("committed version " + candidate.version() + " is not newer");
        }
        remember(event);
        metrics.version(candidate.version());
    }

    private void remember(ChangeEvent e) {
        recent.put(e.version(), e);
        while (recent.size() > retained) {
            recent.pollFirstEntry();
        }
    }

    /**
     * Events with version in (since, upTo], or empty when some of them are no longer retained in memory
     * (the caller then sends a full snapshot).
     */
    public Optional<List<ChangeEvent>> eventsAfter(long since, long upTo) {
        if (since >= upTo) {
            return Optional.of(List.of());
        }
        Long first = recent.isEmpty() ? null : recent.firstKey();
        if (first == null || since + 1 < first) {
            return Optional.empty();
        }
        List<ChangeEvent> out = new ArrayList<>(recent.subMap(since, false, upTo, true).values());
        return out.size() == upTo - since ? Optional.of(out) : Optional.empty();
    }

    /** State as of {@code version}, rebuilt from the durable log (used by rollback). */
    public Snapshot snapshotAt(long version) {
        SnapshotHolder h = new SnapshotHolder(Snapshot.empty());
        repository.forEachInRange(0, version, r -> h.offer(json.event(r)));
        return h.current();
    }
}
