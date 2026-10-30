package com.moveinsync.config.core.change;

import com.moveinsync.config.core.model.Scope;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One operation inside an atomic change set. */
public sealed interface Op {

    /** Set (or replace) the value of {@code key} at {@code scope}. */
    record SetOverride(Scope scope, String key, String value) implements Op {
        public SetOverride {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Remove the value of {@code key} at {@code scope}; reads fall back to the parent level. */
    record RemoveOverride(Scope scope, String key) implements Op {
        public RemoveOverride {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(key, "key");
        }
    }

    /** Create or redefine a setting (type, rules, global default). */
    record DefineSetting(String key, String type, Map<String, String> rules, String defaultValue,
                         String description) implements Op {
        public DefineSetting {
            rules = rules == null ? Map.of() : Map.copyOf(rules);
        }
    }

    /** Create or replace a cross-key constraint. */
    record DefineConstraint(String name, String type, Map<String, String> params) implements Op {
        public DefineConstraint {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /**
     * Stage overrides for a set of target offices ("clientId/officeId"). The rollout's id is the
     * version of the change set that contains this op.
     */
    record Stage(Set<String> targets, List<SetOverride> items) implements Op {
        public Stage {
            targets = Set.copyOf(targets);
            items = List.copyOf(items);
        }
    }

    /** Remove an active staged rollout (used by promote and abort). */
    record Unstage(long rolloutVersion) implements Op {
    }
}
