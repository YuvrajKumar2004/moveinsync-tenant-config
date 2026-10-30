package com.moveinsync.config.core.model;

/** A read asked for a key that has no definition. */
public class UnknownSettingException extends RuntimeException {

    public UnknownSettingException(String key) {
        super("unknown setting '" + (key == null ? "null" : key.length() > 100 ? key.substring(0, 100) : key) + "'");
    }
}
