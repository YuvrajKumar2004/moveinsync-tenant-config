package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tenant isolation for anything sent to a client: GLOBAL data plus the data of the allowed tenants.
 * Events are filtered but never dropped, so version numbers stay gap-free for every subscriber.
 */
public record TenantFilter(boolean all, Set<String> tenants) {

    public static final TenantFilter ALL = new TenantFilter(true, Set.of());

    public TenantFilter {
        tenants = Set.copyOf(tenants);
    }

    public static TenantFilter of(Set<String> tenants) {
        return tenants.contains("*") ? ALL : new TenantFilter(false, tenants);
    }

    public boolean allows(Scope scope) {
        return all || scope.clientId() == null || tenants.contains(scope.clientId());
    }

    public boolean allowsTenant(String clientId) {
        return all || tenants.contains(clientId);
    }

    public ChangeEvent filter(ChangeEvent e) {
        if (all) {
            return e;
        }
        List<Op> kept = new ArrayList<>();
        for (Op op : e.ops()) {
            switch (op) {
                case Op.SetOverride s -> {
                    if (allows(s.scope())) kept.add(s);
                }
                case Op.RemoveOverride r -> {
                    if (allows(r.scope())) kept.add(r);
                }
                case Op.Stage st -> {
                    StagedRollout f = filter(new StagedRollout(e.version(), st.targets(), st.items()));
                    if (f != null) kept.add(new Op.Stage(f.targets(), f.items()));
                }
                default -> kept.add(op); // definitions, constraints, unstage carry no tenant data
            }
        }
        return new ChangeEvent(e.version(), e.committedAtMillis(), e.kind(), kept);
    }

    /** Null when nothing of the rollout is visible to these tenants. */
    public StagedRollout filter(StagedRollout r) {
        if (all) {
            return r;
        }
        Set<String> targets = r.targets().stream()
                .filter(t -> tenants.contains(t.substring(0, t.indexOf('/'))))
                .collect(Collectors.toSet());
        List<Op.SetOverride> items = r.items().stream().filter(i -> allows(i.scope())).toList();
        return targets.isEmpty() || items.isEmpty() ? null : new StagedRollout(r.version(), targets, items);
    }
}
