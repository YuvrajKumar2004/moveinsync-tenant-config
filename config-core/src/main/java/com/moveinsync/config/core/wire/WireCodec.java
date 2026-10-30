package com.moveinsync.config.core.wire;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.constraint.Constraint;
import com.moveinsync.config.core.constraint.Constraints;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.StagedRollout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Converts between domain objects and {@link Wire} records. */
public final class WireCodec {

    private WireCodec() {
    }

    // ---------------------------------------------------------------- scopes

    public static Scope scope(String level, String clientId, String officeId, String shiftId) {
        if (level == null) {
            throw new IllegalArgumentException("level is required");
        }
        return Scope.of(Level.valueOf(level.toUpperCase()), clientId, officeId, shiftId);
    }

    public static Wire.OverrideEntry entry(Scope s, String key, String value) {
        return new Wire.OverrideEntry(s.level().name(), s.clientId(), s.officeId(), s.shiftId(), key, value);
    }

    public static Op.SetOverride toSet(Wire.OverrideEntry e) {
        return new Op.SetOverride(scope(e.level(), e.clientId(), e.officeId(), e.shiftId()), e.key(), e.value());
    }

    // ------------------------------------------------------------------- ops

    public static Wire.Op toWire(Op op) {
        return switch (op) {
            case Op.SetOverride s -> new Wire.Op("SET", s.scope().level().name(), s.scope().clientId(), s.scope().officeId(),
                    s.scope().shiftId(), s.key(), s.value(), null, null, null, null, null);
            case Op.RemoveOverride r -> new Wire.Op("REMOVE", r.scope().level().name(), r.scope().clientId(),
                    r.scope().officeId(), r.scope().shiftId(), r.key(), null, null, null, null, null, null);
            case Op.DefineSetting d -> new Wire.Op("DEFINE", null, null, null, null, d.key(), null,
                    new Wire.Definition(d.key(), d.type(), sorted(d.rules()), d.defaultValue(), d.description()),
                    null, null, null, null);
            case Op.DefineConstraint c -> new Wire.Op("DEFINE_CONSTRAINT", null, null, null, null, null, null, null,
                    new Wire.ConstraintDef(c.name(), c.type(), sorted(c.params())), null, null, null);
            case Op.Stage st -> new Wire.Op("STAGE", null, null, null, null, null, null, null, null,
                    st.targets().stream().sorted().toList(),
                    st.items().stream().map(i -> entry(i.scope(), i.key(), i.value())).toList(), null);
            case Op.Unstage u -> new Wire.Op("UNSTAGE", null, null, null, null, null, null, null, null, null, null,
                    u.rolloutVersion());
        };
    }

    public static Op fromWire(Wire.Op w) {
        return switch (w.type()) {
            case "SET" -> new Op.SetOverride(scope(w.level(), w.clientId(), w.officeId(), w.shiftId()), w.key(), w.value());
            case "REMOVE" -> new Op.RemoveOverride(scope(w.level(), w.clientId(), w.officeId(), w.shiftId()), w.key());
            case "DEFINE" -> new Op.DefineSetting(w.definition().key(), w.definition().type(), w.definition().rules(),
                    w.definition().defaultValue(), w.definition().description());
            case "DEFINE_CONSTRAINT" -> new Op.DefineConstraint(w.constraint().name(), w.constraint().type(), w.constraint().params());
            case "STAGE" -> new Op.Stage(new HashSet<>(w.targets()), w.items().stream().map(WireCodec::toSet).toList());
            case "UNSTAGE" -> new Op.Unstage(w.rolloutVersion());
            default -> throw new IllegalArgumentException("unknown op type");
        };
    }

    public static List<Wire.Op> toWire(List<Op> ops) {
        return ops.stream().map(WireCodec::toWire).toList();
    }

    public static List<Op> fromWire(List<Wire.Op> ops) {
        return ops.stream().map(WireCodec::fromWire).toList();
    }

    // ---------------------------------------------------------------- events

    public static Wire.Event toWire(ChangeEvent e) {
        return new Wire.Event(e.version(), e.committedAtMillis(), e.kind().name(), toWire(e.ops()));
    }

    public static ChangeEvent fromWire(Wire.Event e) {
        return new ChangeEvent(e.version(), e.committedAtMillis(), ChangeKind.valueOf(e.kind()), fromWire(e.ops()));
    }

    // ------------------------------------------------------------- snapshots

    public static Wire.SnapshotData toWire(Snapshot s) {
        List<Wire.Definition> defs = new TreeMap<>(s.definitions()).values().stream()
                .map(d -> new Wire.Definition(d.key(), d.typeName(), sorted(d.rules()), d.defaultValue().raw(), d.description()))
                .toList();
        List<Wire.ConstraintDef> cons = new TreeMap<>(s.constraints()).values().stream()
                .map(c -> new Wire.ConstraintDef(c.name(), c.type(), sorted(c.params())))
                .toList();
        List<Wire.OverrideEntry> overrides = new ArrayList<>();
        s.allOverrides().forEach((sk, v) -> overrides.add(entry(sk.scope(), sk.key(), v.raw())));
        List<Wire.Rollout> rollouts = new TreeMap<>(s.rollouts()).values().stream()
                .map(r -> new Wire.Rollout(r.version(), r.targets().stream().sorted().toList(),
                        r.items().stream().map(i -> entry(i.scope(), i.key(), i.value())).toList()))
                .toList();
        return new Wire.SnapshotData(s.version(), defs, cons, overrides, rollouts);
    }

    public static Snapshot fromWire(Wire.SnapshotData w) {
        List<SettingDefinition> defs = w.definitions().stream()
                .map(d -> SettingDefinition.of(d.key(), d.type(), d.rules(), d.defaultValue(), d.description()))
                .toList();
        List<Constraint> cons = w.constraints().stream()
                .map(c -> Constraints.create(c.name(), c.type(), c.params()))
                .toList();
        Map<Snapshot.ScopedKey, String> overrides = new LinkedHashMap<>();
        for (Wire.OverrideEntry e : w.overrides()) {
            overrides.put(new Snapshot.ScopedKey(scope(e.level(), e.clientId(), e.officeId(), e.shiftId()), e.key()), e.value());
        }
        List<StagedRollout> rollouts = w.rollouts().stream()
                .map(r -> new StagedRollout(r.version(), new HashSet<>(r.targets()), r.items().stream().map(WireCodec::toSet).toList()))
                .toList();
        return Snapshot.restore(w.version(), defs, cons, overrides, rollouts);
    }

    private static Map<String, String> sorted(Map<String, String> m) {
        return m == null ? Map.of() : new TreeMap<>(m);
    }
}
