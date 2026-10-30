package com.moveinsync.config.client;

import com.moveinsync.config.core.model.ResolutionContext;
import com.moveinsync.config.core.snapshot.Resolution;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.type.TimeWindow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * Reads pinned to one snapshot and one context. Every value read through the same view comes from the
 * same version, so related keys (a window's start and end) are always mutually consistent even if a
 * change lands between the two reads. Create one per request; it costs one volatile read.
 */
public final class ConfigView {

    private final Snapshot snapshot;
    private final ResolutionContext context;

    ConfigView(Snapshot snapshot, ResolutionContext context) {
        this.snapshot = snapshot;
        this.context = context;
    }

    public long version() {
        return snapshot.version();
    }

    public ResolutionContext context() {
        return context;
    }

    public String raw(String key) {
        return snapshot.resolve(context, key).raw();
    }

    public Duration duration(String key) {
        return (Duration) snapshot.resolve(context, key).value();
    }

    public BigDecimal number(String key) {
        return (BigDecimal) snapshot.resolve(context, key).value();
    }

    public int intValue(String key) {
        return number(key).intValueExact();
    }

    public LocalTime time(String key) {
        return (LocalTime) snapshot.resolve(context, key).value();
    }

    public TimeWindow window(String key) {
        return (TimeWindow) snapshot.resolve(context, key).value();
    }

    public String enumValue(String key) {
        return (String) snapshot.resolve(context, key).value();
    }

    @SuppressWarnings("unchecked")
    public List<String> list(String key) {
        return (List<String>) snapshot.resolve(context, key).value();
    }

    /** Which level supplied the value, answered locally. */
    public Resolution explain(String key) {
        return snapshot.explain(context, key);
    }
}
