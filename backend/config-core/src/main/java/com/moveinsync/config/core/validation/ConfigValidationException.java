package com.moveinsync.config.core.validation;

import java.util.List;

/** Thrown when a change set (or a single value) fails validation. Nothing is published. */
public class ConfigValidationException extends RuntimeException {

    private final List<Violation> violations;

    public ConfigValidationException(List<Violation> violations) {
        super(violations.size() == 1 ? violations.get(0).toString() : violations.size() + " violations: " + violations);
        this.violations = List.copyOf(violations);
    }

    public ConfigValidationException(String code, String target, String message) {
        this(List.of(new Violation(code, target, message)));
    }

    public List<Violation> violations() {
        return violations;
    }
}
