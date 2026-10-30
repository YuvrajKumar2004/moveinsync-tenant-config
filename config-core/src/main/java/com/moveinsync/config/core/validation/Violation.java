package com.moveinsync.config.core.validation;

/**
 * One reason a change was rejected. {@code code} is stable and machine readable; {@code target}
 * names the offending key/scope; {@code message} is safe to show to API callers.
 */
public record Violation(String code, String target, String message) {

    @Override
    public String toString() {
        return code + " [" + target + "]: " + message;
    }
}
