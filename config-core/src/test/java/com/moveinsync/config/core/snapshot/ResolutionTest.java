package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.UnknownSettingException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.moveinsync.config.core.DemoCatalog.BOOKING_CUTOFF;
import static com.moveinsync.config.core.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolutionTest {

    private final ResolutionContext blr1Night = ResolutionContext.of("acme", "blr-1", "night");
    private final ResolutionContext blr1Day = ResolutionContext.of("acme", "blr-1", "day");
    private final ResolutionContext blr2 = ResolutionContext.of("acme", "blr-2", null);
    private final ResolutionContext globex = ResolutionContext.of("globex", "pune-1", null);

    @Test
    void defaultWhenNothingIsOverridden() {
        Snapshot s = base();
        Resolution r = s.explain(blr1Night, BOOKING_CUTOFF);
        assertThat(r.value().value()).isEqualTo(Duration.ofMinutes(60));
        assertThat(r.source()).isEqualTo(Resolution.Source.DEFAULT);
        assertThat(r.level()).isEqualTo(Level.GLOBAL);
    }

    @Test
    void mostSpecificLevelWinsAtEveryDepth() {
        Snapshot s = commit(base(),
                set(Scope.GLOBAL, BOOKING_CUTOFF, "PT50M"),
                set(ACME, BOOKING_CUTOFF, "PT40M"),
                set(BLR1, BOOKING_CUTOFF, "PT30M"),
                set(BLR1_NIGHT, BOOKING_CUTOFF, "PT20M"));

        assertThat(s.explain(blr1Night, BOOKING_CUTOFF)).extracting(Resolution::level, r -> r.value().raw())
                .containsExactly(Level.SHIFT, "PT20M");
        assertThat(s.explain(blr1Day, BOOKING_CUTOFF)).extracting(Resolution::level, r -> r.value().raw())
                .containsExactly(Level.OFFICE, "PT30M");
        assertThat(s.explain(blr2, BOOKING_CUTOFF)).extracting(Resolution::level, r -> r.value().raw())
                .containsExactly(Level.CLIENT, "PT40M");
        assertThat(s.explain(globex, BOOKING_CUTOFF)).extracting(Resolution::level, r -> r.value().raw())
                .containsExactly(Level.GLOBAL, "PT50M");
        assertThat(s.explain(globex, BOOKING_CUTOFF).source()).isEqualTo(Resolution.Source.OVERRIDE);
    }

    @Test
    void bengaluruExampleOnlyThatOfficeChanges() {
        Snapshot s = commit(base(), set(BLR1, BOOKING_CUTOFF, "PT30M"));
        assertThat(s.resolve(ResolutionContext.of("acme", "blr-1", null), BOOKING_CUTOFF).value()).isEqualTo(Duration.ofMinutes(30));
        assertThat(s.resolve(blr2, BOOKING_CUTOFF).value()).isEqualTo(Duration.ofMinutes(60));
        assertThat(s.resolve(globex, BOOKING_CUTOFF).value()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void removingAnOverrideFallsBackToParent() {
        Snapshot s = commit(base(), set(ACME, BOOKING_CUTOFF, "PT40M"), set(BLR1, BOOKING_CUTOFF, "PT30M"));
        s = commit(s, new Op.RemoveOverride(BLR1, BOOKING_CUTOFF));
        Resolution r = s.explain(blr1Day, BOOKING_CUTOFF);
        assertThat(r.level()).isEqualTo(Level.CLIENT);
        assertThat(r.value().raw()).isEqualTo("PT40M");
    }

    @Test
    void stagedValueAppliesOnlyToTargetOffices() {
        Snapshot s = commit(base(), new Op.Stage(Set.of("acme/blr-1", "globex/pune-1"),
                List.of(set(Scope.GLOBAL, BOOKING_CUTOFF, "PT45M"))));
        long rollout = s.version();
        Resolution r = s.explain(blr1Day, BOOKING_CUTOFF);
        assertThat(r.source()).isEqualTo(Resolution.Source.STAGED);
        assertThat(r.rolloutVersion()).isEqualTo(rollout);
        assertThat(s.resolve(globex, BOOKING_CUTOFF).raw()).isEqualTo("PT45M");
        assertThat(s.resolve(blr2, BOOKING_CUTOFF).raw()).isEqualTo("PT1H");
        assertThat(s.resolve(ResolutionContext.of("acme", null, null), BOOKING_CUTOFF).raw()).isEqualTo("PT1H");
    }

    @Test
    void moreSpecificLiveOverrideStillBeatsAStagedGlobalValue() {
        Snapshot s = commit(base(), set(BLR1, BOOKING_CUTOFF, "PT30M"));
        s = commit(s, new Op.Stage(Set.of("acme/blr-1"), List.of(set(Scope.GLOBAL, BOOKING_CUTOFF, "PT45M"))));
        assertThat(s.resolve(blr1Day, BOOKING_CUTOFF).raw()).isEqualTo("PT30M");
    }

    @Test
    void unknownKeyFails() {
        assertThatThrownBy(() -> base().resolve(blr2, "nope")).isInstanceOf(UnknownSettingException.class);
    }

    @Test
    void contextsMustBeConsistent() {
        assertThatThrownBy(() -> ResolutionContext.of("acme", null, "night")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResolutionContext.of(null, "blr-1", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scope.office("acme", "bad id!")).isInstanceOf(IllegalArgumentException.class);
    }
}
