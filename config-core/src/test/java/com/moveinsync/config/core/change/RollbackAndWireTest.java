package com.moveinsync.config.core.change;

import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.TenantFilter;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.moveinsync.config.core.DemoCatalog.*;
import static com.moveinsync.config.core.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class RollbackAndWireTest {

    private final Scope globexOffice = Scope.office("globex", "pune-1");

    @Test
    void scopedRollbackRestoresOnlyThatTenantAndReportsTheDiff() {
        Snapshot v2 = commit(base(), set(BLR1, BOOKING_CUTOFF, "PT45M"));
        Snapshot v3 = commit(v2, set(BLR1, BOOKING_CUTOFF, "PT30M"), set(ACME, MAX_POOL, "3"), set(globexOffice, MAX_POOL, "2"));

        RollbackPlanner.Plan plan = new RollbackPlanner().plan(v3, v2, RollbackPlanner.tenant("acme"));
        assertThat(plan.diff()).containsExactly(
                new DiffEntry(ACME, MAX_POOL, "3", null),
                new DiffEntry(BLR1, BOOKING_CUTOFF, "PT30M", "PT45M"));

        Snapshot v4 = commit(v3, plan.ops().toArray(Op[]::new));
        assertThat(v4.version()).isEqualTo(4); // a rollback is a new version
        assertThat(v4.override(BLR1, BOOKING_CUTOFF).raw()).isEqualTo("PT45M");
        assertThat(v4.override(ACME, MAX_POOL)).isNull();
        assertThat(v4.override(globexOffice, MAX_POOL).raw()).isEqualTo("2"); // other tenant untouched
        assertThat(new RollbackPlanner().plan(v4, v2, RollbackPlanner.tenant("acme")).isEmpty()).isTrue();
    }

    @Test
    void rollbackAbortsRolloutsInScope() {
        Snapshot v2 = commit(base(), new Op.Stage(Set.of("acme/blr-1"), List.of(set(BLR1, BOOKING_CUTOFF, "PT45M"))));
        RollbackPlanner.Plan plan = new RollbackPlanner().plan(v2, base(), RollbackPlanner.tenant("acme"));
        assertThat(plan.abortedRollouts()).containsExactly(2L);
        assertThat(commit(v2, plan.ops().toArray(Op[]::new)).rollouts()).isEmpty();
    }

    @Test
    void snapshotAndEventsRoundTripThroughTheWireFormat() {
        Snapshot s = commit(base(), set(BLR1_NIGHT, BOOKING_CUTOFF, "PT20M"), set(ACME, CAB_TYPES, "SUV"));
        s = commit(s, new Op.Stage(Set.of("acme/blr-2"), List.of(set(ACME, MAX_POOL, "2"))));
        Wire.SnapshotData wire = WireCodec.toWire(s);
        Snapshot back = WireCodec.fromWire(wire);
        assertThat(WireCodec.toWire(back)).isEqualTo(wire);
        assertThat(back.version()).isEqualTo(s.version());
        assertThat(back.rollouts()).hasSize(1);

        var e = event(9, set(BLR1, BOOKING_CUTOFF, "PT1M"), new Op.RemoveOverride(ACME, CAB_TYPES), new Op.Unstage(3));
        assertThat(WireCodec.fromWire(WireCodec.toWire(e))).isEqualTo(e);
    }

    @Test
    void tenantFilterHidesOtherClientsButKeepsVersionsGapFree() {
        Snapshot s = commit(base(), set(BLR1, BOOKING_CUTOFF, "PT30M"), set(globexOffice, BOOKING_CUTOFF, "PT10M"),
                set(Scope.GLOBAL, MAX_POOL, "5"));
        TenantFilter acmeOnly = TenantFilter.of(Set.of("acme"));
        Snapshot f = s.filtered(acmeOnly);
        assertThat(f.override(BLR1, BOOKING_CUTOFF)).isNotNull();
        assertThat(f.override(globexOffice, BOOKING_CUTOFF)).isNull();
        assertThat(f.override(Scope.GLOBAL, MAX_POOL)).isNotNull();
        assertThat(f.version()).isEqualTo(s.version());

        var e = acmeOnly.filter(event(7, set(globexOffice, BOOKING_CUTOFF, "PT5M")));
        assertThat(e.version()).isEqualTo(7);
        assertThat(e.ops()).isEmpty();
        assertThat(TenantFilter.of(Set.of("*")).all()).isTrue();
    }
}
