package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.util.List;
import java.util.Map;

/** Factory mapping a type name plus rules to a concrete {@link SettingType}. */
public final class SettingTypes {

    public static final List<String> NAMES = List.of("NUMBER", "DURATION", "TIME", "TIME_WINDOW", "ENUM", "LIST");

    private SettingTypes() {
    }

    public static SettingType<?> create(String typeName, Map<String, String> rules) {
        if (typeName == null) {
            throw new InvalidValueException("type is required");
        }
        return switch (typeName.toUpperCase()) {
            case "NUMBER" -> new NumberType(rules);
            case "DURATION" -> new DurationType(rules);
            case "TIME" -> new TimeOfDayType(rules);
            case "TIME_WINDOW" -> new TimeWindowType(rules);
            case "ENUM" -> new EnumType(rules);
            case "LIST" -> new ListType(rules);
            default -> throw new InvalidValueException("unknown type '" + SettingType.abbreviate(typeName)
                    + "' (supported: " + NAMES + ")");
        };
    }
}
