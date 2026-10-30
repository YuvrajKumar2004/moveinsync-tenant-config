package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Non-negative duration. Rules: min, max (each a duration). A negative cut-off is always rejected. */
public final class DurationType extends SettingType<Duration> {

    private final Duration min;
    private final Duration max;

    public DurationType(Map<String, String> rules) {
        super(rules, Set.of("min", "max"));
        this.min = ruleDuration("min");
        this.max = ruleDuration("max");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new InvalidValueException("rule min must not exceed max");
        }
    }

    @Override
    public String name() {
        return "DURATION";
    }

    @Override
    public Class<Duration> valueClass() {
        return Duration.class;
    }

    @Override
    protected Duration doParse(String raw) {
        return Durations.parse(raw);
    }

    @Override
    protected void check(Duration value) {
        if (value.isNegative()) {
            throw new InvalidValueException("must not be negative");
        }
        if (min != null && value.compareTo(min) < 0) {
            throw new InvalidValueException("must be at least " + min);
        }
        if (max != null && value.compareTo(max) > 0) {
            throw new InvalidValueException("must be at most " + max);
        }
    }

    @Override
    protected String format(Duration value) {
        return value.toString();
    }
}
