package com.moveinsync.config.core.type;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * Time of day, "HH:mm". Used for the separate start/end keys of a window whose two ends must change
 * together (see {@code TimeWindowPairConstraint}).
 */
public final class TimeOfDayType extends SettingType<LocalTime> {

    static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public TimeOfDayType(Map<String, String> rules) {
        super(rules, Set.of());
    }

    @Override
    public String name() {
        return "TIME";
    }

    @Override
    public Class<LocalTime> valueClass() {
        return LocalTime.class;
    }

    @Override
    protected LocalTime doParse(String raw) {
        return LocalTime.parse(raw, HH_MM);
    }

    @Override
    protected void check(LocalTime value) {
        // any time of day is valid on its own; pairs are checked by constraints
    }

    @Override
    protected String format(LocalTime value) {
        return value.format(HH_MM);
    }
}
