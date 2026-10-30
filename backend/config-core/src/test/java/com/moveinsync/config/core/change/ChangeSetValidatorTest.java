package com.moveinsync.config.core.change;

import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.validation.ConfigValidationException;
import com.moveinsync.config.core.validation.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.moveinsync.config.core.DemoCatalog.*;
import static com.moveinsync.config.core.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSetValidatorTest {

    private final ChangeSetValidator validator = new ChangeSetValidator();

    private List<String> codes(Snapshot s, Op... ops) {
        try {
            validator.validate(s, List.of(ops));
            return List.of();
        } catch (ConfigValidationException e) {
            return e.violations().stream().map(Violation::code).toList();
        }
    }

    @Test
    void candidateHasNextVersionAndBaseIsUntouched() {
        Snapshot base = base();
        Snapshot next = validator.validate(base, List.of(set(BLR1, BOOKING_CUTOFF, "PT30M")));
        assertThat(next.version()).isEqualTo(base.version() + 1);
        assertThat(base.override(BLR1, BOOKING_CUTOFF)).isNull();
    }

    @Test
    void badValuesAreRejectedBeforePublishing() {
        assertThat(codes(base(), set(BLR1, BOOKING_CUTOFF, "-30m"))).containsExactly("INVALID_VALUE");
        assertThat(codes(base(), set(BLR1, ESCORT_WINDOW, "06:00-22:00"))).containsExactly("INVALID_VALUE");
        assertThat(codes(base(), set(BLR1, "no.such.key", "1"))).containsExactly("UNKNOWN_KEY");
        assertThat(codes(base(), set(BLR1, BOOKING_CUTOFF, "PT10M"), set(BLR1, BOOKING_CUTOFF, "PT20M")))
                .containsExactly("DUPLICATE_TARGET");
        assertThat(codes(base(), new Op.RemoveOverride(BLR1, BOOKING_CUTOFF))).containsExactly("NOT_FOUND");
        assertThatThrownBy(() -> validator.validate(base(), List.of())).isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void allViolationsAreReportedTogether() {
        assertThat(codes(base(), set(BLR1, BOOKING_CUTOFF, "-1m"), set(BLR1, MAX_POOL, "9")))
                .containsExactly("INVALID_VALUE", "INVALID_VALUE");
    }

    @Test
    void nightWindowStartAndEndAreValidatedTogether() {
        // moving only the start to 07:00 against the default end 06:00 gives a 23h window: rejected
        assertThat(codes(base(), set(ACME, NIGHT_START, "07:00"))).containsExactly("CONSTRAINT_VIOLATION");
        // moving both in one change set is fine, including across midnight
        Snapshot s = validator.validate(base(), List.of(set(ACME, NIGHT_START, "22:00"), set(ACME, NIGHT_END, "05:00")));
        assertThat(s.resolve(ResolutionContext.of("acme", null, null), NIGHT_END).raw()).isEqualTo("05:00");
        // equal start and end is rejected
        assertThat(codes(base(), set(ACME, NIGHT_START, "06:00"))).containsExactly("CONSTRAINT_VIOLATION");
    }

    @Test
    void crossLevelCombinationIsCheckedWhereItResolves() {
        // office sets the end to 08:00 (window 20:00-08:00 = 12h, fine)
        Snapshot s = commit(base(), set(BLR1, NIGHT_END, "08:00"));
        // client moves start to 21:00: client-level window 21:00-06:00 is fine, but at blr-1 it becomes
        // 21:00-08:00 = 11h which is still fine
        s = commit(s, set(ACME, NIGHT_START, "21:00"));
        // client moves start to 17:00: client level 17:00-06:00 = 13h ok, blr-1 17:00-08:00 = 15h > 14h
        List<String> c = codes(s, set(ACME, NIGHT_START, "17:00"));
        assertThat(c).containsExactly("CONSTRAINT_VIOLATION");
    }

    @Test
    void stagedRolloutRules() {
        assertThat(codes(base(), new Op.Stage(Set.of(), List.of(set(Scope.GLOBAL, BOOKING_CUTOFF, "PT45M")))))
                .contains("NO_TARGETS");
        assertThat(codes(base(), new Op.Stage(Set.of("acme/blr-1"), List.of(set(BLR2, BOOKING_CUTOFF, "PT45M")))))
                .containsExactly("UNREACHABLE_ITEM");
        assertThat(codes(base(), new Op.Stage(Set.of("acme"), List.of(set(Scope.GLOBAL, BOOKING_CUTOFF, "PT45M")))))
                .containsExactly("INVALID_TARGET");

        Snapshot staged = commit(base(), new Op.Stage(Set.of("acme/blr-1", "acme/blr-2"),
                List.of(set(ACME, BOOKING_CUTOFF, "PT45M"))));
        long rollout = staged.version();
        // a live change to a staged (key, scope) is refused until promote/abort
        assertThat(codes(staged, set(ACME, BOOKING_CUTOFF, "PT10M"))).containsExactly("ROLLOUT_IN_PROGRESS");
        // promote = unstage + live set in one atomic change set
        Snapshot promoted = commit(staged, new Op.Unstage(rollout), set(ACME, BOOKING_CUTOFF, "PT45M"));
        assertThat(promoted.rollouts()).isEmpty();
        assertThat(promoted.resolve(ResolutionContext.of("acme", "blr-9", null), BOOKING_CUTOFF).raw()).isEqualTo("PT45M");
        assertThat(codes(promoted, new Op.Unstage(rollout))).containsExactly("NOT_FOUND");
    }

    @Test
    void stagedValuesAreCheckedAgainstConstraintsAtTargetOffices() {
        assertThat(codes(base(), new Op.Stage(Set.of("acme/blr-1"), List.of(set(Scope.GLOBAL, NIGHT_START, "07:00")))))
                .containsExactly("CONSTRAINT_VIOLATION");
    }

    @Test
    void redefinitionMustKeepExistingOverridesValid() {
        Snapshot s = commit(base(), set(BLR1, BOOKING_CUTOFF, "PT90M"));
        assertThat(codes(s, new Op.DefineSetting(BOOKING_CUTOFF, "DURATION", Map.of("max", "PT60M"), "PT30M", "")))
                .containsExactly("INCOMPATIBLE_REDEFINITION");
        assertThat(codes(s, new Op.DefineSetting("Bad Key", "NUMBER", Map.of(), "1", ""))).containsExactly("INVALID_DEFINITION");
        assertThat(codes(s, new Op.DefineConstraint("c", "TIME_WINDOW_PAIR", Map.of("startKey", BOOKING_CUTOFF, "endKey", NIGHT_END))))
                .containsExactly("INVALID_CONSTRAINT");
    }
}
