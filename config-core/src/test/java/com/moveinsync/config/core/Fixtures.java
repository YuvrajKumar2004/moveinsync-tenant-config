package com.moveinsync.config.core;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSetValidator;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.util.List;

public final class Fixtures {

    public static final Scope ACME = Scope.client("acme");
    public static final Scope BLR1 = Scope.office("acme", "blr-1");
    public static final Scope BLR2 = Scope.office("acme", "blr-2");
    public static final Scope BLR1_NIGHT = Scope.shift("acme", "blr-1", "night");

    private Fixtures() {
    }

    /** Snapshot v1 holding the demo catalog. */
    public static Snapshot base() {
        return new ChangeSetValidator().validate(Snapshot.empty(), DemoCatalog.bootstrapOps());
    }

    /** Validates and applies ops as the next version. */
    public static Snapshot commit(Snapshot s, Op... ops) {
        return new ChangeSetValidator().validate(s, List.of(ops));
    }

    public static ChangeEvent event(long version, Op... ops) {
        return new ChangeEvent(version, System.currentTimeMillis(), ChangeKind.APPLY, List.of(ops));
    }

    public static Op.SetOverride set(Scope s, String key, String value) {
        return new Op.SetOverride(s, key, value);
    }
}
