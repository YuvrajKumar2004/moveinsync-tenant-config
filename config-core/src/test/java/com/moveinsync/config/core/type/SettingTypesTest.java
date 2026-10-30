package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingTypesTest {

    @Test
    void durationAcceptsIsoAndShorthandAndCanonicalises() {
        SettingType<?> t = SettingTypes.create("DURATION", Map.of("min", "PT0M", "max", "PT24H"));
        assertThat(t.parse("30m").raw()).isEqualTo("PT30M");
        assertThat(t.parse("PT1H").value()).isEqualTo(Duration.ofHours(1));
        assertThat(t.parse("2h").raw()).isEqualTo("PT2H");
    }

    @Test
    void negativeCutoffIsRejected() {
        SettingType<?> t = SettingTypes.create("DURATION", Map.of());
        assertThatThrownBy(() -> t.parse("-30m")).isInstanceOf(InvalidValueException.class).hasMessageContaining("negative");
        assertThatThrownBy(() -> t.parse("PT-30M")).isInstanceOf(InvalidValueException.class);
    }

    @Test
    void durationBoundsAndGarbage() {
        SettingType<?> t = SettingTypes.create("DURATION", Map.of("max", "PT24H"));
        assertThatThrownBy(() -> t.parse("25h")).hasMessageContaining("at most");
        assertThatThrownBy(() -> t.parse("soon")).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> t.parse(" ")).hasMessageContaining("empty");
    }

    @Test
    void windowThatEndsBeforeItStartsIsRejectedUnlessCrossingMidnightIsAllowed() {
        SettingType<?> sameDay = SettingTypes.create("TIME_WINDOW", Map.of());
        assertThat(sameDay.parse("09:00-18:00").value()).isEqualTo(new TimeWindow(LocalTime.of(9, 0), LocalTime.of(18, 0)));
        assertThatThrownBy(() -> sameDay.parse("18:00-09:00")).hasMessageContaining("before it starts");

        SettingType<?> night = SettingTypes.create("TIME_WINDOW", Map.of("allowCrossMidnight", "true", "maxLength", "PT12H"));
        TimeWindow w = (TimeWindow) night.parse("22:00-06:00").value();
        assertThat(w.crossesMidnight()).isTrue();
        assertThat(w.length()).isEqualTo(Duration.ofHours(8));
        assertThat(w.contains(LocalTime.of(23, 30))).isTrue();
        assertThat(w.contains(LocalTime.of(5, 59))).isTrue();
        assertThat(w.contains(LocalTime.of(6, 0))).isFalse();
        assertThatThrownBy(() -> night.parse("06:00-22:00")).hasMessageContaining("longer than");
        assertThatThrownBy(() -> night.parse("22:00-22:00")).hasMessageContaining("must differ");
    }

    @Test
    void enumNumberListAndTime() {
        SettingType<?> e = SettingTypes.create("ENUM", Map.of("values", "A,B"));
        assertThat(e.parse("A").value()).isEqualTo("A");
        assertThatThrownBy(() -> e.parse("C")).hasMessageContaining("one of");

        SettingType<?> n = SettingTypes.create("NUMBER", Map.of("min", "1", "max", "6", "integer", "true"));
        assertThat(n.parse("4.0").raw()).isEqualTo("4");
        assertThat(n.parse("6").value()).isEqualTo(new BigDecimal("6"));
        assertThatThrownBy(() -> n.parse("2.5")).hasMessageContaining("whole");
        assertThatThrownBy(() -> n.parse("7")).hasMessageContaining("at most");

        SettingType<?> l = SettingTypes.create("LIST", Map.of("allowed", "SEDAN,SUV", "maxSize", "2"));
        assertThat(l.parse("SEDAN, SUV").value()).isEqualTo(List.of("SEDAN", "SUV"));
        assertThatThrownBy(() -> l.parse("SEDAN,SEDAN")).hasMessageContaining("twice");
        assertThatThrownBy(() -> l.parse("BUS")).hasMessageContaining("not allowed");

        SettingType<?> t = SettingTypes.create("TIME", Map.of());
        assertThat(t.parse("07:05").value()).isEqualTo(LocalTime.of(7, 5));
        assertThatThrownBy(() -> t.parse("25:00")).isInstanceOf(InvalidValueException.class);
    }

    @Test
    void unknownTypesAndRulesAreRejected() {
        assertThatThrownBy(() -> SettingTypes.create("COLOR", Map.of())).hasMessageContaining("unknown type");
        assertThatThrownBy(() -> SettingTypes.create("NUMBER", Map.of("maxx", "3"))).hasMessageContaining("not supported");
        assertThatThrownBy(() -> SettingTypes.create("ENUM", Map.of())).hasMessageContaining("values");
        assertThatThrownBy(() -> SettingTypes.create("NUMBER", Map.of("min", "5", "max", "1"))).hasMessageContaining("min");
    }
}
