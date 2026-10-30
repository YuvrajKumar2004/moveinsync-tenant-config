package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSetValidator;
import com.moveinsync.config.core.change.DiffEntry;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.error.PiiRedactor;
import com.moveinsync.config.server.observability.ConfigMetrics;
import com.moveinsync.config.server.repository.ChangeSetRecord;
import com.moveinsync.config.server.repository.ChangeSetRepository;
import com.moveinsync.config.server.security.AuthUser;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single write path. Every change (plain change set, definition, stage, promote, abort, rollback)
 * goes through {@link #commit}: authorize, validate the whole set against current state, persist it
 * atomically with the next gap-free version, swap the in-memory snapshot, then publish. Nothing is
 * pushed before it is durable, and nothing invalid is ever stored.
 */
@Service
public class ChangeSetService {

    private static final Logger log = LoggerFactory.getLogger(ChangeSetService.class);

    public record CommitRequest(ChangeKind kind, AuthUser author, String reason, List<Op> ops,
                                String idempotencyKey, Long expectedVersion) {
    }

    public record CommitResult(long version, ChangeKind kind, Instant committedAt, String author, String reason,
                               List<DiffEntry> diff, boolean replayed) {
    }

    private final ChangeSetRepository repository;
    private final ConfigStateService state;
    private final AccessPolicy access;
    private final ChangePublisher publisher;
    private final ConfigMetrics metrics;
    private final EventJson json;
    private final TransactionTemplate tx;
    private final ChangeSetValidator validator = new ChangeSetValidator();
    private final ReentrantLock writeLock = new ReentrantLock(true);

    public ChangeSetService(ChangeSetRepository repository, ConfigStateService state, AccessPolicy access,
                            ChangePublisher publisher, ConfigMetrics metrics, EventJson json, TransactionTemplate tx) {
        this.repository = repository;
        this.state = state;
        this.access = access;
        this.publisher = publisher;
        this.metrics = metrics;
        this.json = json;
        this.tx = tx;
    }

    public CommitResult commit(CommitRequest req) {
        ChangeEvent event;
        CommitResult result;
        lock();
        try {
            if (req.idempotencyKey() != null) {
                Optional<ChangeSetRecord> previous = repository.findByIdempotencyKey(req.author().subject(), req.idempotencyKey());
                if (previous.isPresent()) {
                    return replay(previous.get());
                }
            }
            Snapshot base = state.current();
            access.checkCanWrite(req.author(), req.ops(), base);
            if (req.expectedVersion() != null && req.expectedVersion() != base.version()) {
                throw ApiException.conflict("VERSION_CONFLICT", "expected version " + req.expectedVersion()
                        + " but current is " + base.version() + "; re-read and retry");
            }
            Timer.Sample sample = Timer.start();
            Snapshot candidate = validator.validate(base, req.ops());
            List<DiffEntry> diff = diff(base, candidate, req.ops());
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            event = new ChangeEvent(candidate.version(), now.toEpochMilli(), req.kind(), req.ops());
            ChangeSetRecord record = new ChangeSetRecord(candidate.version(), req.kind().name(), req.author().subject(),
                    req.reason(), now, tenantsColumn(req.ops()), touchesGlobal(req.ops()), req.idempotencyKey(),
                    json.ops(req.ops()), json.diff(diff));
            persist(record, base.version());
            state.committed(candidate, event);
            sample.stop(metrics.commitLatency());
            metrics.committed(req.kind().name());
            result = new CommitResult(record.version(), req.kind(), now, record.author(), record.reason(), diff, false);
            log.info("committed version={} kind={} author={} ops={} tenants={} reason=\"{}\"", record.version(), req.kind(),
                    record.author(), req.ops().size(), record.tenants(), PiiRedactor.redact(abbreviate(req.reason())));
        } finally {
            writeLock.unlock();
        }
        publisher.publish(event);
        return result;
    }

    private void lock() {
        try {
            if (!writeLock.tryLock(5, TimeUnit.SECONDS)) {
                throw ApiException.unavailable("the write path is busy; retry shortly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("interrupted");
        }
    }

    private void persist(ChangeSetRecord record, long baseVersion) {
        try {
            tx.executeWithoutResult(status -> {
                long dbVersion = repository.lockCurrentVersion();
                if (dbVersion != baseVersion) {
                    throw new StateDivergedException();
                }
                repository.append(record);
            });
        } catch (StateDivergedException e) {
            // another writer committed (should not happen with one instance): refresh and make the caller retry
            state.catchUp();
            throw ApiException.conflict("STATE_CHANGED", "configuration changed concurrently; re-read and retry");
        } catch (DuplicateKeyException e) {
            state.catchUp();
            throw ApiException.conflict("STATE_CHANGED", "configuration changed concurrently; re-read and retry");
        }
    }

    private CommitResult replay(ChangeSetRecord r) {
        return new CommitResult(r.version(), ChangeKind.valueOf(r.kind()), r.committedAt(), r.author(), r.reason(),
                json.diff(r.diffJson()), true);
    }

    /** Live override changes for every (scope, key) the ops touch, including promoted rollouts. */
    static List<DiffEntry> diff(Snapshot before, Snapshot after, List<Op> ops) {
        Set<Snapshot.ScopedKey> touched = new TreeSet<>(Snapshot.ScopedKey.ORDER);
        for (Op op : ops) {
            if (op instanceof Op.SetOverride s) {
                touched.add(new Snapshot.ScopedKey(s.scope(), s.key()));
            } else if (op instanceof Op.RemoveOverride r) {
                touched.add(new Snapshot.ScopedKey(r.scope(), r.key()));
            }
        }
        List<DiffEntry> out = new ArrayList<>();
        for (Snapshot.ScopedKey k : touched) {
            String from = raw(before.override(k.scope(), k.key()));
            String to = raw(after.override(k.scope(), k.key()));
            if (from == null ? to != null : !from.equals(to)) {
                out.add(new DiffEntry(k.scope(), k.key(), from, to));
            }
        }
        return out;
    }

    private static String raw(SettingValue v) {
        return v == null ? null : v.raw();
    }

    static String tenantsColumn(List<Op> ops) {
        Set<String> tenants = new LinkedHashSet<>();
        for (Op op : ops) {
            switch (op) {
                case Op.SetOverride s -> addTenant(tenants, s.scope());
                case Op.RemoveOverride r -> addTenant(tenants, r.scope());
                case Op.Stage st -> {
                    st.items().forEach(i -> addTenant(tenants, i.scope()));
                    st.targets().forEach(t -> tenants.add(t.substring(0, t.indexOf('/'))));
                }
                default -> {
                }
            }
        }
        return tenants.isEmpty() ? "," : "," + String.join(",", tenants) + ",";
    }

    private static void addTenant(Set<String> tenants, Scope s) {
        if (s.clientId() != null) {
            tenants.add(s.clientId());
        }
    }

    static boolean touchesGlobal(List<Op> ops) {
        return ops.stream().anyMatch(op -> switch (op) {
            case Op.SetOverride s -> s.scope().clientId() == null;
            case Op.RemoveOverride r -> r.scope().clientId() == null;
            case Op.Stage st -> st.items().stream().anyMatch(i -> i.scope().clientId() == null);
            case Op.DefineSetting d -> true;
            case Op.DefineConstraint c -> true;
            case Op.Unstage u -> false;
        });
    }

    private static String abbreviate(String s) {
        return s == null || s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    private static final class StateDivergedException extends RuntimeException {
    }
}
