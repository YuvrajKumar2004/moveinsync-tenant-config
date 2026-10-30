package com.moveinsync.config.core;

import com.moveinsync.config.core.change.Op;

import java.util.List;
import java.util.Map;

/**
 * The settings named in the case study, used to bootstrap an empty server and in tests. One of each
 * type, plus the night window as two TIME keys tied by a constraint (the atomic change-set case).
 */
public final class DemoCatalog {

    public static final String BOOKING_CUTOFF = "booking.cutoff";
    public static final String MAX_RIDE_TIME = "ride.max-duration";
    public static final String NIGHT_START = "night.window.start";
    public static final String NIGHT_END = "night.window.end";
    public static final String ESCORT_WINDOW = "escort.window";
    public static final String ESCORT_RULE = "escort.rule";
    public static final String CAB_TYPES = "cab.types-allowed";
    public static final String MAX_POOL = "ride.max-pool-size";

    private DemoCatalog() {
    }

    public static List<Op> bootstrapOps() {
        return List.of(
                new Op.DefineSetting(BOOKING_CUTOFF, "DURATION", Map.of("min", "PT0M", "max", "PT24H"), "PT60M",
                        "How long before departure a booking closes"),
                new Op.DefineSetting(MAX_RIDE_TIME, "DURATION", Map.of("min", "PT10M", "max", "PT4H"), "PT90M",
                        "Maximum time an employee may spend in a cab"),
                new Op.DefineSetting(NIGHT_START, "TIME", Map.of(), "20:00", "Night-safety window start"),
                new Op.DefineSetting(NIGHT_END, "TIME", Map.of(), "06:00", "Night-safety window end"),
                new Op.DefineConstraint("night.window", "TIME_WINDOW_PAIR",
                        Map.of("startKey", NIGHT_START, "endKey", NIGHT_END, "allowCrossMidnight", "true",
                                "minLength", "PT1H", "maxLength", "PT14H")),
                new Op.DefineSetting(ESCORT_WINDOW, "TIME_WINDOW",
                        Map.of("allowCrossMidnight", "true", "maxLength", "PT12H"), "21:00-05:00",
                        "Hours during which an escort rule applies"),
                new Op.DefineSetting(ESCORT_RULE, "ENUM", Map.of("values", "ALWAYS,FEMALE_FIRST_OR_LAST,NEVER"),
                        "FEMALE_FIRST_OR_LAST", "When an escort must ride along"),
                new Op.DefineSetting(CAB_TYPES, "LIST", Map.of("allowed", "HATCHBACK,SEDAN,SUV,TEMPO", "minSize", "1"),
                        "SEDAN,SUV", "Cab types the client allows"),
                new Op.DefineSetting(MAX_POOL, "NUMBER", Map.of("min", "1", "max", "6", "integer", "true"), "4",
                        "Maximum riders pooled in one cab"));
    }
}
