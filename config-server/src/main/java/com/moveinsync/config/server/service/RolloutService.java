package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.snapshot.StagedRollout;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.security.AuthUser;
import com.moveinsync.config.server.security.Role;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Staged rollout: a change first visible only to chosen offices, later promoted to everyone (one atomic
 * change set: unstage + live overrides) or aborted. The rollout's id is the version that staged it.
 */
@Service
public class RolloutService {

    private final ChangeSetService changes;
    private final ConfigStateService state;
    private final AccessPolicy access;

    public RolloutService(ChangeSetService changes, ConfigStateService state, AccessPolicy access) {
        this.changes = changes;
        this.state = state;
        this.access = access;
    }

    public ChangeSetService.CommitResult stage(AuthUser user, Set<String> targets, List<Op.SetOverride> items,
                                               String reason, String idempotencyKey) {
        return changes.commit(new ChangeSetService.CommitRequest(ChangeKind.STAGE, user, reason,
                List.of(new Op.Stage(targets, items)), idempotencyKey, null));
    }

    public ChangeSetService.CommitResult promote(AuthUser user, long rolloutVersion, String reason) {
        StagedRollout r = find(user, rolloutVersion);
        List<Op> ops = new ArrayList<>();
        ops.add(new Op.Unstage(rolloutVersion));
        ops.addAll(r.items());
        return changes.commit(new ChangeSetService.CommitRequest(ChangeKind.PROMOTE, user, reason, ops, null, null));
    }

    public ChangeSetService.CommitResult abort(AuthUser user, long rolloutVersion, String reason) {
        find(user, rolloutVersion);
        return changes.commit(new ChangeSetService.CommitRequest(ChangeKind.ABORT_ROLLOUT, user, reason,
                List.of(new Op.Unstage(rolloutVersion)), null, null));
    }

    /** Active rollouts the caller may see. */
    public Collection<StagedRollout> list(AuthUser user) {
        return state.current().rollouts().values().stream()
                .filter(r -> user.role() == Role.OPERATOR || access.ownedBy(r, user.tenant()))
                .sorted((a, b) -> Long.compare(a.version(), b.version()))
                .toList();
    }

    private StagedRollout find(AuthUser user, long version) {
        StagedRollout r = state.current().rollouts().get(version);
        if (r == null || (user.role() != Role.OPERATOR && !access.ownedBy(r, user.tenant()))) {
            throw ApiException.notFound("active rollout " + version);
        }
        return r;
    }
}
