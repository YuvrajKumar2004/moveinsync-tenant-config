package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.constraint.Constraint;
import com.moveinsync.config.core.constraint.Constraints;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.model.UnknownSettingException;
import com.moveinsync.config.core.validation.InvalidValueException;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Immutable, versioned view of all configuration. Applying a change produces a new snapshot
 * (copy-on-write on the touched keys only) so that a reference swap publishes the whole change at once:
 * a reader holding a snapshot can never observe half of a change set.
 *
 * <p>Reads are a handful of hash lookups (at most two per hierarchy level) and allocate nothing.
 */
public final class Snapshot {

    private static final Snapshot EMPTY = new Snapshot(0, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private final long version;
    private final Map<String, SettingDefinition> definitions;
    private final Map<String, Constraint> constraints;
    /** key -> scope -> value. */
    private final Map<String, Map<Scope, SettingValue>> overrides;
    private final Map<Long, StagedRollout> rollouts;
    /** key -> scope -> staged value (derived from {@link #rollouts}). */
    private final Map<String, Map<Scope, StagedValue>> staged;

    record StagedValue(SettingValue value, long rolloutVersion, Set<String> targets) {
    }

    private Snapshot(long version, Map<String, SettingDefinition> definitions, Map<String, Constraint> constraints,
                     Map<String, Map<Scope, SettingValue>> overrides, Map<Long, StagedRollout> rollouts,
                     Map<String, Map<Scope, StagedValue>> staged) {
        this.version = version;
        this.definitions = definitions;
        this.constraints = constraints;
        this.overrides = overrides;
        this.rollouts = rollouts;
        this.staged = staged;
    }

    public static Snapshot empty() {
        return EMPTY;
    }

    public long version() {
        return version;
    }

    // ------------------------------------------------------------------ reads

    /** Most-specific-wins resolution. Staged values apply only when the context's office is a target. */
    public SettingValue resolve(ResolutionContext ctx, String key) {
        SettingDefinition def = definitions.get(key);
        if (def == null) {
            throw new UnknownSettingException(key);
        }
        Map<Scope, SettingValue> ov = overrides.get(key);
        Map<Scope, StagedValue> st = staged.get(key);
        String officeKey = ctx.officeKey();
        for (Scope s : ctx.chain()) {
            if (st != null && officeKey != null) {
                StagedValue sv = st.get(s);
                if (sv != null && sv.targets().contains(officeKey)) {
                    return sv.value();
                }
            }
            if (ov != null) {
                SettingValue v = ov.get(s);
                if (v != null) {
                    return v;
                }
            }
        }
        return def.defaultValue();
    }

    /** Same lookup as {@link #resolve} but reports which level supplied the value. */
    public Resolution explain(ResolutionContext ctx, String key) {
        SettingDefinition def = definitions.get(key);
        if (def == null) {
            throw new UnknownSettingException(key);
        }
        Map<Scope, SettingValue> ov = overrides.getOrDefault(key, Map.of());
        Map<Scope, StagedValue> st = staged.getOrDefault(key, Map.of());
        String officeKey = ctx.officeKey();
        for (Scope s : ctx.chain()) {
            StagedValue sv = st.get(s);
            if (sv != null && officeKey != null && sv.targets().contains(officeKey)) {
                return new Resolution(key, sv.value(), s.level(), s, Resolution.Source.STAGED, sv.rolloutVersion(), version);
            }
            SettingValue v = ov.get(s);
            if (v != null) {
                return new Resolution(key, v, s.level(), s, Resolution.Source.OVERRIDE, null, version);
            }
        }
        return new Resolution(key, def.defaultValue(), Level.GLOBAL, Scope.GLOBAL, Resolution.Source.DEFAULT, null, version);
    }

    public boolean isDefined(String key) {
        return definitions.containsKey(key);
    }

    public SettingDefinition definition(String key) {
        return definitions.get(key);
    }

    public Map<String, SettingDefinition> definitions() {
        return definitions;
    }

    public Map<String, Constraint> constraints() {
        return constraints;
    }

    public Map<Long, StagedRollout> rollouts() {
        return rollouts;
    }

    public SettingValue override(Scope scope, String key) {
        Map<Scope, SettingValue> ov = overrides.get(key);
        return ov == null ? null : ov.get(scope);
    }

    /** All scopes holding an override for {@code key}. */
    public Set<Scope> overrideScopes(String key) {
        return overrides.getOrDefault(key, Map.of()).keySet();
    }

    /** Active staged rollout covering (scope, key), or null. */
    public Long stagedRolloutFor(Scope scope, String key) {
        StagedValue sv = staged.getOrDefault(key, Map.of()).get(scope);
        return sv == null ? null : sv.rolloutVersion();
    }

    /** Overrides whose scope matches the filter, as "scope|key" -> raw value, sorted for stable diffs. */
    public Map<ScopedKey, String> overridesMatching(Predicate<Scope> filter) {
        Map<ScopedKey, String> out = new TreeMap<>(ScopedKey.ORDER);
        overrides.forEach((key, byScope) -> byScope.forEach((scope, value) -> {
            if (filter.test(scope)) {
                out.put(new ScopedKey(scope, key), value.raw());
            }
        }));
        return out;
    }

    /** Every override, used for serialisation. */
    public Map<ScopedKey, SettingValue> allOverrides() {
        Map<ScopedKey, SettingValue> out = new TreeMap<>(ScopedKey.ORDER);
        overrides.forEach((key, byScope) -> byScope.forEach((scope, v) -> out.put(new ScopedKey(scope, key), v)));
        return out;
    }

    public int overrideCount() {
        int n = 0;
        for (Map<Scope, SettingValue> m : overrides.values()) {
            n += m.size();
        }
        return n;
    }

    /** (scope, key) pair. */
    public record ScopedKey(Scope scope, String key) {
        public static final Comparator<ScopedKey> ORDER =
                Comparator.comparing((ScopedKey k) -> k.scope().toString()).thenComparing(ScopedKey::key);
    }

    // ----------------------------------------------------------------- writes

    /**
     * Applies a committed event. Requires {@code event.version() > version()}. Lenient: operations that no
     * longer make sense (unknown rollout, missing override) are skipped, because the server has already
     * validated the change and a filtered tenant view may legitimately lack the referenced data.
     */
    public Snapshot apply(ChangeEvent event) {
        if (event.version() <= version) {
            throw new IllegalStateException("event version " + event.version() + " is not newer than " + version);
        }
        return applyOps(event.version(), event.ops());
    }

    /** Applies ops producing a snapshot at {@code newVersion}. Values are parsed under the (new) definitions. */
    public Snapshot applyOps(long newVersion, List<Op> ops) {
        Builder b = new Builder(this);
        for (Op op : ops) {
            b.apply(op, newVersion);
        }
        return b.build(newVersion);
    }

    /** Snapshot keeping only data visible to {@code filter} (GLOBAL data is always kept). */
    public Snapshot filtered(TenantFilter filter) {
        if (filter.all()) {
            return this;
        }
        Builder b = new Builder(this);
        b.overrides.replaceAll((key, byScope) -> {
            Map<Scope, SettingValue> kept = new HashMap<>();
            byScope.forEach((scope, v) -> {
                if (filter.allows(scope)) {
                    kept.put(scope, v);
                }
            });
            return kept;
        });
        b.overrides.values().removeIf(Map::isEmpty);
        b.touched.addAll(b.overrides.keySet());
        Map<Long, StagedRollout> kept = new HashMap<>();
        for (StagedRollout r : rollouts.values()) {
            StagedRollout f = filter.filter(r);
            if (f != null) {
                kept.put(f.version(), f);
            }
        }
        b.rollouts.clear();
        b.rollouts.putAll(kept);
        return b.build(version);
    }

    /** Rebuilds a snapshot from its parts (used when loading from disk or the wire). */
    public static Snapshot restore(long version, List<SettingDefinition> defs, List<Constraint> constraints,
                                   Map<ScopedKey, String> overrides, List<StagedRollout> rollouts) {
        Builder b = new Builder(EMPTY);
        defs.forEach(d -> b.definitions.put(d.key(), d));
        constraints.forEach(c -> b.constraints.put(c.name(), c));
        overrides.forEach((sk, raw) -> b.putOverride(sk.scope(), sk.key(), raw));
        rollouts.forEach(r -> b.rollouts.put(r.version(), r));
        return b.build(version);
    }

    private static final class Builder {
        final Map<String, SettingDefinition> definitions;
        final Map<String, Constraint> constraints;
        final Map<String, Map<Scope, SettingValue>> overrides;
        final Map<Long, StagedRollout> rollouts;
        final Set<String> touched = new HashSet<>();
        boolean rolloutsChanged;
        final Snapshot base;

        Builder(Snapshot base) {
            this.base = base;
            this.definitions = new HashMap<>(base.definitions);
            this.constraints = new HashMap<>(base.constraints);
            this.overrides = new HashMap<>(base.overrides);
            this.rollouts = new HashMap<>(base.rollouts);
        }

        private Map<Scope, SettingValue> mutable(String key) {
            if (touched.add(key)) {
                overrides.put(key, new HashMap<>(overrides.getOrDefault(key, Map.of())));
            }
            return overrides.get(key);
        }

        void putOverride(Scope scope, String key, String raw) {
            SettingDefinition def = definitions.get(key);
            if (def == null) {
                return; // definition not visible (or removed) - nothing to resolve against
            }
            mutable(key).put(scope, def.type().parse(raw));
        }

        void apply(Op op, long version) {
            switch (op) {
                case Op.SetOverride s -> putOverride(s.scope(), s.key(), s.value());
                case Op.RemoveOverride r -> {
                    if (overrides.containsKey(r.key())) {
                        mutable(r.key()).remove(r.scope());
                    }
                }
                case Op.DefineSetting d -> {
                    SettingDefinition def = SettingDefinition.of(d.key(), d.type(), d.rules(), d.defaultValue(), d.description());
                    definitions.put(def.key(), def);
                    if (overrides.containsKey(def.key())) {
                        Map<Scope, SettingValue> m = mutable(def.key());
                        m.replaceAll((scope, v) -> reparse(def, v));
                        m.values().removeIf(v -> v == null);
                    }
                    rolloutsChanged = true;
                }
                case Op.DefineConstraint c -> constraints.put(c.name(), Constraints.create(c.name(), c.type(), c.params()));
                case Op.Stage st -> {
                    rollouts.put(version, new StagedRollout(version, st.targets(), st.items()));
                    rolloutsChanged = true;
                }
                case Op.Unstage u -> {
                    if (rollouts.remove(u.rolloutVersion()) != null) {
                        rolloutsChanged = true;
                    }
                }
            }
        }

        private static SettingValue reparse(SettingDefinition def, SettingValue v) {
            try {
                return def.type().parse(v.raw());
            } catch (InvalidValueException e) {
                return null; // only reachable for unvalidated input; the server rejects such redefinitions
            }
        }

        Snapshot build(long version) {
            overrides.values().removeIf(Map::isEmpty);
            for (String key : touched) {
                Map<Scope, SettingValue> m = overrides.get(key);
                if (m != null) {
                    overrides.put(key, Collections.unmodifiableMap(m));
                }
            }
            Map<String, Map<Scope, StagedValue>> staged = rolloutsChanged || base == EMPTY || !rollouts.equals(base.rollouts)
                    ? indexStaged() : base.staged;
            return new Snapshot(version, Map.copyOf(definitions), Map.copyOf(constraints),
                    Collections.unmodifiableMap(overrides), Map.copyOf(rollouts), staged);
        }

        private Map<String, Map<Scope, StagedValue>> indexStaged() {
            Map<String, Map<Scope, StagedValue>> idx = new HashMap<>();
            for (StagedRollout r : rollouts.values()) {
                for (Op.SetOverride item : r.items()) {
                    SettingDefinition def = definitions.get(item.key());
                    if (def == null) {
                        continue;
                    }
                    SettingValue v;
                    try {
                        v = def.type().parse(item.value());
                    } catch (InvalidValueException e) {
                        continue;
                    }
                    idx.computeIfAbsent(item.key(), k -> new HashMap<>())
                            .put(item.scope(), new StagedValue(v, r.version(), r.targets()));
                }
            }
            idx.replaceAll((k, m) -> Collections.unmodifiableMap(m));
            return Collections.unmodifiableMap(idx);
        }
    }
}
