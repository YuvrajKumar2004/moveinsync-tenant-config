package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigView;
import com.moveinsync.config.core.snapshot.Resolution;

import java.time.Duration;

/**
 * The case-study rule: a booking is accepted only if it is made at least {@code booking.cutoff} before
 * departure. Plain class, testable without Spring or a server.
 */
public final class BookingPolicy {

    public record Decision(boolean accepted, Duration timeBeforeDeparture, Duration cutoff, String cutoffLevel,
                           String cutoffSource, long configVersion) {
    }

    public Decision decide(ConfigView config, Duration timeBeforeDeparture) {
        Resolution cutoff = config.explain("booking.cutoff");
        Duration limit = (Duration) cutoff.value().value();
        boolean accepted = timeBeforeDeparture.compareTo(limit) >= 0;
        return new Decision(accepted, timeBeforeDeparture, limit, cutoff.level().name(), cutoff.source().name(), config.version());
    }
}
