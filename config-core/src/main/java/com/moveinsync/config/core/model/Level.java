package com.moveinsync.config.core.model;

/**
 * Hierarchy levels, ordered from least to most specific. Resolution walks from {@link #SHIFT} up to
 * {@link #GLOBAL}; the first level holding a value wins.
 */
public enum Level {
    GLOBAL,
    CLIENT,
    OFFICE,
    SHIFT
}
