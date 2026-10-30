package com.moveinsync.config.core.validation;

/** A single raw value could not be parsed or broke a type rule. Converted into a {@link Violation}. */
public class InvalidValueException extends RuntimeException {

    public InvalidValueException(String message) {
        super(message);
    }
}
