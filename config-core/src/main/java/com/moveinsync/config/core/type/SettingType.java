package com.moveinsync.config.core.type;

import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.validation.InvalidValueException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A setting's type together with its validation rules. Each concrete type knows how to parse a raw
 * string, check it against its rules and print it in canonical form. Values are parsed once when a
 * change is applied, so reads never parse.
 *
 * @param <T> the Java type a parsed value has
 */
public abstract class SettingType<T> {

    private final Map<String, String> rules;

    protected SettingType(Map<String, String> rules, Set<String> supportedRules) {
        this.rules = Map.copyOf(rules == null ? Map.of() : new LinkedHashMap<>(rules));
        for (String name : this.rules.keySet()) {
            if (!supportedRules.contains(name)) {
                throw new InvalidValueException("rule '" + name + "' is not supported by type " + name()
                        + " (supported: " + supportedRules + ")");
            }
        }
    }

    /** Type name as used in the API: NUMBER, DURATION, TIME, TIME_WINDOW, ENUM, LIST. */
    public abstract String name();

    /** Java class of parsed values, used by typed getters in the client. */
    public abstract Class<T> valueClass();

    protected abstract T doParse(String raw);

    /** Throws {@link InvalidValueException} when the value breaks a rule. */
    protected abstract void check(T value);

    protected abstract String format(T value);

    /** Parses, validates and canonicalises a raw value. */
    public final SettingValue parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidValueException("value must not be empty");
        }
        T value;
        try {
            value = doParse(raw.trim());
        } catch (InvalidValueException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidValueException("'" + abbreviate(raw) + "' is not a valid " + name());
        }
        check(value);
        return new SettingValue(format(value), value);
    }

    public Map<String, String> rules() {
        return rules;
    }

    protected String rule(String name) {
        return rules.get(name);
    }

    protected Duration ruleDuration(String name) {
        String v = rules.get(name);
        return v == null ? null : Durations.parse(v);
    }

    protected BigDecimal ruleDecimal(String name) {
        String v = rules.get(name);
        try {
            return v == null ? null : new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            throw new InvalidValueException("rule '" + name + "' must be a number");
        }
    }

    protected Integer ruleInt(String name) {
        String v = rules.get(name);
        try {
            return v == null ? null : Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw new InvalidValueException("rule '" + name + "' must be an integer");
        }
    }

    protected boolean ruleBool(String name, boolean fallback) {
        String v = rules.get(name);
        if (v == null) {
            return fallback;
        }
        if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
            throw new InvalidValueException("rule '" + name + "' must be true or false");
        }
        return Boolean.parseBoolean(v);
    }

    protected List<String> ruleList(String name) {
        String v = rules.get(name);
        if (v == null) {
            return null;
        }
        return Arrays.stream(v.split("[,|]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static String abbreviate(String raw) {
        return raw.length() <= 40 ? raw : raw.substring(0, 40) + "...";
    }

    @Override
    public String toString() {
        return name() + rules;
    }
}
