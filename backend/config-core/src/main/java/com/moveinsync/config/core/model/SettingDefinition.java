package com.moveinsync.config.core.model;

import com.moveinsync.config.core.type.SettingType;
import com.moveinsync.config.core.type.SettingTypes;
import com.moveinsync.config.core.validation.InvalidValueException;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A setting's key, type, rules and global default. Immutable; construction validates everything. */
public final class SettingDefinition {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private final String key;
    private final SettingType<?> type;
    private final SettingValue defaultValue;
    private final String description;

    private SettingDefinition(String key, SettingType<?> type, SettingValue defaultValue, String description) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    /** @throws InvalidValueException when the key, type, rules or default are invalid */
    public static SettingDefinition of(String key, String typeName, Map<String, String> rules,
                                       String defaultRaw, String description) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidValueException("key must match " + KEY.pattern());
        }
        SettingType<?> type = SettingTypes.create(typeName, rules);
        SettingValue def;
        try {
            def = type.parse(defaultRaw);
        } catch (InvalidValueException e) {
            throw new InvalidValueException("default value: " + e.getMessage());
        }
        return new SettingDefinition(key, type, def, description == null ? "" : description);
    }

    public static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public String key() {
        return key;
    }

    public SettingType<?> type() {
        return type;
    }

    public String typeName() {
        return type.name();
    }

    public Map<String, String> rules() {
        return type.rules();
    }

    public SettingValue defaultValue() {
        return defaultValue;
    }

    public String description() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SettingDefinition d && key.equals(d.key) && typeName().equals(d.typeName())
                && rules().equals(d.rules()) && defaultValue.equals(d.defaultValue)
                && description.equals(d.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, typeName(), rules(), defaultValue);
    }

    @Override
    public String toString() {
        return key + ":" + type + "=" + defaultValue;
    }
}
