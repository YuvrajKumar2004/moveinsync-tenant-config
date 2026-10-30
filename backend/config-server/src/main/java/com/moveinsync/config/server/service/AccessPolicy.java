package com.moveinsync.config.server.service;

import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.StagedRollout;
import com.moveinsync.config.server.error.ApiException;
import com.moveinsync.config.server.security.AuthUser;
import com.moveinsync.config.server.security.Role;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tenant-level authorization. Role-level checks are in the security filter chain; this class enforces
 * that an ADMIN only ever touches scopes of their own client and that reads stay inside the caller's
 * tenants. Denials use one generic message so they do not reveal whether another tenant exists.
 */
@Component
public class AccessPolicy {

    public void checkCanWrite(AuthUser user, List<Op> ops, Snapshot current) {
        if (user.role() == Role.OPERATOR) {
            return;
        }
        if (user.role() != Role.ADMIN || user.tenant() == null) {
            throw ApiException.forbidden("not allowed to change configuration");
        }
        String tenant = user.tenant();
        for (Op op : ops) {
            boolean ok = switch (op) {
                case Op.SetOverride s -> s.scope().belongsTo(tenant);
                case Op.RemoveOverride r -> r.scope().belongsTo(tenant);
                case Op.Stage st -> st.targets().stream().allMatch(t -> t.startsWith(tenant + "/"))
                        && st.items().stream().allMatch(i -> i.scope().belongsTo(tenant));
                case Op.Unstage u -> {
                    StagedRollout r = current.rollouts().get(u.rolloutVersion());
                    yield r == null || ownedBy(r, tenant);
                }
                case Op.DefineSetting d -> false;
                case Op.DefineConstraint c -> false;
            };
            if (!ok) {
                throw ApiException.forbidden("admins may only change their own client's CLIENT/OFFICE/SHIFT values");
            }
        }
    }

    public void checkCanRead(AuthUser user, String clientId) {
        if (!user.canRead(clientId)) {
            throw ApiException.forbidden("not allowed to read this client's configuration");
        }
    }

    /** GLOBAL rollback is operator-only; a client rollback needs operator or that client's admin. */
    public void checkCanRollback(AuthUser user, Scope scope) {
        if (user.role() == Role.OPERATOR) {
            return;
        }
        if (user.role() == Role.ADMIN && scope.clientId() != null && scope.clientId().equals(user.tenant())) {
            return;
        }
        throw ApiException.forbidden("not allowed to roll back this scope");
    }

    public boolean ownedBy(StagedRollout r, String tenant) {
        return r.targets().stream().allMatch(t -> t.startsWith(tenant + "/"))
                && r.items().stream().allMatch(i -> i.scope().belongsTo(tenant));
    }
}
