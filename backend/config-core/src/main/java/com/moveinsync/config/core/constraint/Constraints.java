package com.moveinsync.config.core.constraint;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.util.Map;
import java.util.regex.Pattern;

/** Factory for constraint implementations. */
public final class Constraints {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private Constraints() {
    }

    public static Constraint create(String name, String type, Map<String, String> params) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new InvalidValueException("constraint name must match " + NAME.pattern());
        }
        if (TimeWindowPairConstraint.TYPE.equalsIgnoreCase(type)) {
            return new TimeWindowPairConstraint(name, params == null ? Map.of() : params);
        }
        throw new InvalidValueException("unknown constraint type (supported: " + TimeWindowPairConstraint.TYPE + ")");
    }
}
