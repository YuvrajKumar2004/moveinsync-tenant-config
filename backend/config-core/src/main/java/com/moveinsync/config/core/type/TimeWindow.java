package com.moveinsync.config.core.type;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;

/** A daily window [start, end). If end is before start the window crosses midnight. */
public record TimeWindow(LocalTime start, LocalTime end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    public boolean crossesMidnight() {
        return end.isBefore(start);
    }

    public Duration length() {
        Duration d = Duration.between(start, end);
        return d.isNegative() ? d.plusDays(1) : d;
    }

    public boolean contains(LocalTime t) {
        if (crossesMidnight()) {
            return !t.isBefore(start) || t.isBefore(end);
        }
        return !t.isBefore(start) && t.isBefore(end);
    }

    @Override
    public String toString() {
        return start.format(TimeOfDayType.HH_MM) + "-" + end.format(TimeOfDayType.HH_MM);
    }
}
