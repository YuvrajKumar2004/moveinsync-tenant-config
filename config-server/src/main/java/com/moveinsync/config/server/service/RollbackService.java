package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.DiffEntry;
import com.moveinsync.config.core.change.RollbackPlanner;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.security.AuthUser;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Predicate;

/**
 * One-call rollback of a scope (GLOBAL values, or everything of one client) to its state at an earlier
 * version. Applied as a new version with a diff; {@code dryRun} returns the diff without committing.
 */
@Service
public class RollbackService {

    public record Result(boolean dryRun, long currentVersion, long targetVersion, Long newVersion,
                         List<DiffEntry> diff, List<Long> abortedRollouts) {
    }

    private final ChangeSetService changes;
    private final ConfigStateService state;
    private final AccessPolicy access;
    private final RollbackPlanner planner = new RollbackPlanner();

    public RollbackService(ChangeSetService changes, ConfigStateService state, AccessPolicy access) {
        this.changes = changes;
        this.state = state;
        this.access = access;
    }

    public Result rollback(AuthUser user, Scope scope, long toVersion, String reason, boolean dryRun) {
        if (scope.level() != Level.GLOBAL && scope.level() != Level.CLIENT) {
            throw ApiException.badRequest("rollback scope must be GLOBAL or CLIENT");
        }
        access.checkCanRollback(user, scope);
        Snapshot current = state.current();
        if (toVersion < 1 || toVersion > current.version()) {
            throw ApiException.badRequest("toVersion must be between 1 and " + current.version());
        }
        Predicate<Scope> filter = scope.level() == Level.GLOBAL ? RollbackPlanner.globalOnly()
                : RollbackPlanner.tenant(scope.clientId());
        RollbackPlanner.Plan plan = planner.plan(current, state.snapshotAt(toVersion), filter);
        if (dryRun || plan.isEmpty()) {
            return new Result(dryRun, current.version(), toVersion, null, plan.diff(), plan.abortedRollouts());
        }
        ChangeSetService.CommitResult r = changes.commit(new ChangeSetService.CommitRequest(ChangeKind.ROLLBACK, user,
                reason, plan.ops(), null, current.version()));
        return new Result(false, current.version(), toVersion, r.version(), plan.diff(), plan.abortedRollouts());
    }
}
