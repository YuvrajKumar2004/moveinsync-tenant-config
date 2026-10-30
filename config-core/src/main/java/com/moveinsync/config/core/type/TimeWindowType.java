package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

/**
 * A window "HH:mm-HH:mm". Rules: allowCrossMidnight (default false), minLength, maxLength.
 * <ul>
 *   <li>start == end is always rejected (empty or ambiguous 24h window).</li>
 *   <li>end before start is rejected ("ends before it starts") unless allowCrossMidnight=true, in which
 *       case it means the window runs past midnight, e.g. a night window 22:00-06:00.</li>
 * </ul>
 */
public final class TimeWindowType extends SettingType<TimeWindow> {

    private final boolean allowCrossMidnight;
    private final Duration minLength;
    private final Duration maxLength;

    public TimeWindowType(Map<String, String> rules) {
        super(rules, Set.of("allowCrossMidnight", "minLength", "maxLength"));
        this.allowCrossMidnight = ruleBool("allowCrossMidnight", false);
        this.minLength = ruleDuration("minLength");
        this.maxLength = ruleDuration("maxLength");
    }

    @Override
    public String name() {
        return "TIME_WINDOW";
    }

    @Override
    public Class<TimeWindow> valueClass() {
        return TimeWindow.class;
    }

    @Override
    protected TimeWindow doParse(String raw) {
        String[] parts = raw.split("-");
        if (parts.length != 2) {
            throw new InvalidValueException("time window must look like HH:mm-HH:mm");
        }
        return new TimeWindow(LocalTime.parse(parts[0].trim(), TimeOfDayType.HH_MM),
                LocalTime.parse(parts[1].trim(), TimeOfDayType.HH_MM));
    }

    @Override
    protected void check(TimeWindow w) {
        checkWindow(w, allowCrossMidnight, minLength, maxLength);
    }

    /** Shared by the single-value type and the two-key pair constraint. */
    public static void checkWindow(TimeWindow w, boolean allowCrossMidnight, Duration minLength, Duration maxLength) {
        if (w.start().equals(w.end())) {
            throw new InvalidValueException("window start and end must differ");
        }
        if (w.crossesMidnight() && !allowCrossMidnight) {
            throw new InvalidValueException("window ends (" + w.end() + ") before it starts (" + w.start() + ")");
        }
        if (minLength != null && w.length().compareTo(minLength) < 0) {
            throw new InvalidValueException("window " + w + " is shorter than " + minLength);
        }
        if (maxLength != null && w.length().compareTo(maxLength) > 0) {
            throw new InvalidValueException("window " + w + " is longer than " + maxLength);
        }
    }

    @Override
    protected String format(TimeWindow value) {
        return value.toString();
    }
}
