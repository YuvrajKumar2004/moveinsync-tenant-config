package com.moveinsync.config.core.change;

import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.StagedRollout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Plans a scoped rollback: restore the overrides inside {@code scope} to what they were in the
 * historical snapshot. The result is applied as a <em>new</em> version, so versions only move forward.
 * Active staged rollouts touching the scope are aborted, since they were planned against the state
 * being rolled back. Definitions and constraints are not rolled back.
 */
public final class RollbackPlanner {

    public record Plan(List<Op> ops, List<DiffEntry> diff, List<Long> abortedRollouts) {
        public boolean isEmpty() {
            return ops.isEmpty();
        }
    }

    public Plan plan(Snapshot current, Snapshot target, Predicate<Scope> scope) {
        List<DiffEntry> diff = DiffEntry.between(current.overridesMatching(scope), target.overridesMatching(scope));
        List<Op> ops = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
        for (StagedRollout r : current.rollouts().values()) {
            if (r.items().stream().anyMatch(i -> scope.test(i.scope()))
                    || r.targets().stream().anyMatch(t -> scope.test(Scope.office(t.split("/")[0], t.split("/")[1])))) {
                ops.add(new Op.Unstage(r.version()));
                aborted.add(r.version());
            }
        }
        for (DiffEntry d : diff) {
            ops.add(d.to() == null ? new Op.RemoveOverride(d.scope(), d.key()) : new Op.SetOverride(d.scope(), d.key(), d.to()));
        }
        return new Plan(ops, diff, aborted);
    }

    /** Scope filter for "everything belonging to client {@code clientId}". */
    public static Predicate<Scope> tenant(String clientId) {
        return s -> clientId.equals(s.clientId());
    }

    /** Scope filter for GLOBAL-level overrides only. */
    public static Predicate<Scope> globalOnly() {
        return s -> s.clientId() == null;
    }
}
