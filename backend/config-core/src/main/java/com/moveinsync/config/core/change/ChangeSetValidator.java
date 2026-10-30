package com.moveinsync.config.core.change;

import com.moveinsync.config.core.constraint.Constraint;
import com.moveinsync.config.core.constraint.Constraints;
import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.StagedRollout;
import com.moveinsync.config.core.validation.ConfigValidationException;
import com.moveinsync.config.core.validation.InvalidValueException;
import com.moveinsync.config.core.validation.Violation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a whole change set against the current snapshot <em>before</em> anything is stored or
 * published. Per-value rules are checked first; then every cross-key constraint touched by the change
 * is evaluated on the resulting snapshot at every hierarchy point the change can affect, so related
 * keys (window start and end) are validated together.
 */
public final class ChangeSetValidator {

    public static final int MAX_OPS = 500;

    /** Returns the candidate snapshot at {@code base.version() + 1}; throws when anything is invalid. */
    public Snapshot validate(Snapshot base, List<Op> ops) {
        List<Violation> violations = new ArrayList<>();
        if (ops == null || ops.isEmpty()) {
            throw new ConfigValidationException("EMPTY_CHANGE_SET", "ops", "a change set needs at least one operation");
        }
        if (ops.size() > MAX_OPS) {
            throw new ConfigValidationException("TOO_MANY_OPS", "ops", "at most " + MAX_OPS + " operations per change set");
        }
        Map<String, SettingDefinition> defs = new HashMap<>(base.definitions());
        Set<String> touchedKeys = new HashSet<>();
        Set<Snapshot.ScopedKey> seen = new HashSet<>();
        Set<Long> unstaged = new HashSet<>();

        for (Op op : ops) {
            switch (op) {
                case Op.DefineSetting d -> {
                    try {
                        SettingDefinition def = SettingDefinition.of(d.key(), d.type(), d.rules(), d.defaultValue(), d.description());
                        for (Scope s : base.overrideScopes(def.key())) {
                            String raw = base.override(s, def.key()).raw();
                            try {
                                def.type().parse(raw);
                            } catch (InvalidValueException e) {
                                violations.add(new Violation("INCOMPATIBLE_REDEFINITION", def.key() + "@" + s,
                                        "existing override would become invalid: " + e.getMessage()));
                            }
                        }
                        defs.put(def.key(), def);
                        touchedKeys.add(def.key());
                    } catch (InvalidValueException e) {
                        violations.add(new Violation("INVALID_DEFINITION", String.valueOf(d.key()), e.getMessage()));
                    }
                }
                case Op.DefineConstraint c -> {
                    try {
                        Constraint constraint = Constraints.create(c.name(), c.type(), c.params());
                        constraint.verifyDefinitions(defs);
                        touchedKeys.addAll(constraint.keys());
                    } catch (InvalidValueException e) {
                        violations.add(new Violation("INVALID_CONSTRAINT", String.valueOf(c.name()), e.getMessage()));
                    }
                }
                case Op.SetOverride s -> {
                    checkSet(base, defs, s, seen, violations, unstaged, false);
                    touchedKeys.add(s.key());
                }
                case Op.RemoveOverride r -> {
                    String target = r.key() + "@" + r.scope();
                    if (!seen.add(new Snapshot.ScopedKey(r.scope(), r.key()))) {
                        violations.add(new Violation("DUPLICATE_TARGET", target, "the same key and scope appear twice"));
                    } else if (base.override(r.scope(), r.key()) == null) {
                        violations.add(new Violation("NOT_FOUND", target, "no override to remove"));
                    }
                    checkNotStaged(base, r.scope(), r.key(), unstaged, violations);
                    touchedKeys.add(r.key());
                }
                case Op.Stage st -> checkStage(base, defs, st, violations, touchedKeys);
                case Op.Unstage u -> {
                    StagedRollout r = base.rollouts().get(u.rolloutVersion());
                    if (r == null) {
                        violations.add(new Violation("NOT_FOUND", "rollout " + u.rolloutVersion(), "no active staged rollout with that version"));
                    } else {
                        unstaged.add(u.rolloutVersion());
                        r.items().forEach(i -> touchedKeys.add(i.key()));
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ConfigValidationException(violations);
        }

        Snapshot candidate;
        try {
            candidate = base.applyOps(base.version() + 1, ops);
        } catch (InvalidValueException e) {
            throw new ConfigValidationException("INVALID_VALUE", "ops", e.getMessage());
        }
        checkConstraints(candidate, touchedKeys, violations);
        if (!violations.isEmpty()) {
            throw new ConfigValidationException(violations);
        }
        return candidate;
    }

    private void checkSet(Snapshot base, Map<String, SettingDefinition> defs, Op.SetOverride s,
                          Set<Snapshot.ScopedKey> seen, List<Violation> violations, Set<Long> unstaged, boolean staged) {
        String target = s.key() + "@" + s.scope();
        SettingDefinition def = defs.get(s.key());
        if (def == null) {
            violations.add(new Violation("UNKNOWN_KEY", target, "no setting with this key is defined"));
            return;
        }
        if (!seen.add(new Snapshot.ScopedKey(s.scope(), s.key()))) {
            violations.add(new Violation("DUPLICATE_TARGET", target, "the same key and scope appear twice"));
        }
        try {
            def.type().parse(s.value());
        } catch (InvalidValueException e) {
            violations.add(new Violation("INVALID_VALUE", target, e.getMessage()));
        }
        checkNotStaged(base, s.scope(), s.key(), unstaged, violations);
    }

    private void checkNotStaged(Snapshot base, Scope scope, String key, Set<Long> unstaged, List<Violation> violations) {
        Long rollout = base.stagedRolloutFor(scope, key);
        if (rollout != null && !unstaged.contains(rollout)) {
            violations.add(new Violation("ROLLOUT_IN_PROGRESS", key + "@" + scope,
                    "staged rollout " + rollout + " covers this key and scope; promote or abort it first"));
        }
    }

    private void checkStage(Snapshot base, Map<String, SettingDefinition> defs, Op.Stage st,
                            List<Violation> violations, Set<String> touchedKeys) {
        if (st.targets().isEmpty()) {
            violations.add(new Violation("NO_TARGETS", "targets", "a staged rollout needs at least one target office"));
        }
        for (String t : st.targets()) {
            String[] parts = t.split("/", -1);
            if (parts.length != 2 || !Scope.validId(parts[0]) || !Scope.validId(parts[1])) {
                violations.add(new Violation("INVALID_TARGET", t, "targets must look like clientId/officeId"));
            }
        }
        if (st.items().isEmpty()) {
            violations.add(new Violation("EMPTY_CHANGE_SET", "items", "a staged rollout needs at least one item"));
        }
        Set<Snapshot.ScopedKey> seen = new HashSet<>();
        for (Op.SetOverride item : st.items()) {
            checkSet(base, defs, item, seen, violations, Set.of(), true);
            touchedKeys.add(item.key());
            Scope s = item.scope();
            boolean reachable = switch (s.level()) {
                case GLOBAL -> true;
                case CLIENT -> st.targets().stream().anyMatch(t -> t.startsWith(s.clientId() + "/"));
                case OFFICE, SHIFT -> st.targets().contains(s.officeKey());
            };
            if (!reachable) {
                violations.add(new Violation("UNREACHABLE_ITEM", item.key() + "@" + s,
                        "no target office lies under this scope, so the staged value would never apply"));
            }
        }
    }

    /** Evaluates touched constraints at every hierarchy point where their keys can resolve differently. */
    private void checkConstraints(Snapshot candidate, Set<String> touchedKeys, List<Violation> violations) {
        for (Constraint c : candidate.constraints().values()) {
            if (c.keys().stream().noneMatch(touchedKeys::contains)) {
                continue;
            }
            for (ResolutionContext ctx : evaluationPoints(candidate, c.keys())) {
                try {
                    c.check(key -> candidate.resolve(ctx, key));
                } catch (InvalidValueException e) {
                    violations.add(new Violation("CONSTRAINT_VIOLATION", c.name() + "@" + ctx, e.getMessage()));
                }
            }
        }
    }

    /**
     * A scope with no override of any constrained key resolves exactly like its nearest ancestor that has
     * one, so checking GLOBAL, every scope holding such an override and every staged target office covers
     * every distinct combination.
     */
    static Set<ResolutionContext> evaluationPoints(Snapshot s, Set<String> keys) {
        Set<String> seen = new LinkedHashSet<>();
        List<ResolutionContext> points = new ArrayList<>();
        add(points, seen, ResolutionContext.GLOBAL);
        for (String key : keys) {
            for (Scope scope : s.overrideScopes(key)) {
                add(points, seen, ResolutionContext.at(scope));
            }
        }
        for (StagedRollout r : s.rollouts().values()) {
            if (r.items().stream().noneMatch(i -> keys.contains(i.key()))) {
                continue;
            }
            for (String t : r.targets()) {
                String[] p = t.split("/");
                add(points, seen, ResolutionContext.of(p[0], p[1], null));
            }
            for (Op.SetOverride i : r.items()) {
                if (i.scope().shiftId() != null) {
                    add(points, seen, ResolutionContext.at(i.scope()));
                }
            }
        }
        return new LinkedHashSet<>(points);
    }

    private static void add(List<ResolutionContext> points, Set<String> seen, ResolutionContext ctx) {
        if (seen.add(ctx.toString())) {
            points.add(ctx);
        }
    }
}
