package com.moveinsync.config.core.model;

/**
 * A validated value: its canonical raw text (what is stored and sent on the wire) and the parsed Java
 * object (Duration, BigDecimal, LocalTime, TimeWindow, String or List&lt;String&gt;).
 */
public record SettingValue(String raw, Object value) {

    @Override
    public boolean equals(Object o) {
        return o instanceof SettingValue other && raw.equals(other.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
