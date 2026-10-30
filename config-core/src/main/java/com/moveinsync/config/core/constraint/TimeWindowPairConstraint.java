package com.moveinsync.config.core.constraint;

import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.type.Durations;
import com.moveinsync.config.core.type.TimeWindow;
import com.moveinsync.config.core.type.TimeWindowType;
import com.moveinsync.config.core.validation.InvalidValueException;

import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Two TIME keys that together form a window (for example night.window.start / night.window.end).
 * Params: startKey, endKey, allowCrossMidnight (default true), minLength, maxLength.
 */
public final class TimeWindowPairConstraint implements Constraint {

    public static final String TYPE = "TIME_WINDOW_PAIR";
    private static final Set<String> PARAMS = Set.of("startKey", "endKey", "allowCrossMidnight", "minLength", "maxLength");

    private final String name;
    private final Map<String, String> params;
    private final String startKey;
    private final String endKey;
    private final boolean allowCrossMidnight;
    private final Duration minLength;
    private final Duration maxLength;

    public TimeWindowPairConstraint(String name, Map<String, String> params) {
        this.name = name;
        this.params = Map.copyOf(new LinkedHashMap<>(params));
        for (String p : this.params.keySet()) {
            if (!PARAMS.contains(p)) {
                throw new InvalidValueException("param '" + p + "' is not supported by " + TYPE);
            }
        }
        this.startKey = required("startKey");
        this.endKey = required("endKey");
        if (startKey.equals(endKey)) {
            throw new InvalidValueException("startKey and endKey must differ");
        }
        String cross = params.get("allowCrossMidnight");
        this.allowCrossMidnight = cross == null || Boolean.parseBoolean(cross);
        this.minLength = params.get("minLength") == null ? null : Durations.parse(params.get("minLength"));
        this.maxLength = params.get("maxLength") == null ? null : Durations.parse(params.get("maxLength"));
    }

    private String required(String p) {
        String v = params.get(p);
        if (v == null || v.isBlank()) {
            throw new InvalidValueException(TYPE + " needs param '" + p + "'");
        }
        return v;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Map<String, String> params() {
        return params;
    }

    @Override
    public Set<String> keys() {
        return Set.of(startKey, endKey);
    }

    @Override
    public void verifyDefinitions(Map<String, SettingDefinition> definitions) {
        for (String k : keys()) {
            SettingDefinition d = definitions.get(k);
            if (d == null) {
                throw new InvalidValueException("constraint references unknown key '" + k + "'");
            }
            if (!d.typeName().equals("TIME")) {
                throw new InvalidValueException("key '" + k + "' must be of type TIME for " + TYPE);
            }
        }
    }

    @Override
    public void check(Function<String, SettingValue> resolved) {
        LocalTime start = (LocalTime) resolved.apply(startKey).value();
        LocalTime end = (LocalTime) resolved.apply(endKey).value();
        TimeWindowType.checkWindow(new TimeWindow(start, end), allowCrossMidnight, minLength, maxLength);
    }
}
