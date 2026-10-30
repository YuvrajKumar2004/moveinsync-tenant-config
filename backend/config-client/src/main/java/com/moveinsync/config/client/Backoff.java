package com.moveinsync.config.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with "full jitter": the delay is uniform in [0, min(cap, base * 2^attempt)].
 * Spreading retries uniformly is what stops hundreds of clients from reconnecting in lock-step after a
 * server restart.
 */
public final class Backoff {

    private final long baseMillis;
    private final long capMillis;

    public Backoff(Duration base, Duration cap) {
        this.baseMillis = Math.max(1, base.toMillis());
        this.capMillis = Math.max(baseMillis, cap.toMillis());
    }

    public long delayMillis(int attempt) {
        long ceiling = attempt >= 30 ? capMillis : Math.min(capMillis, baseMillis << Math.max(0, attempt));
        return ThreadLocalRandom.current().nextLong(ceiling + 1);
    }

    public long capMillis() {
        return capMillis;
    }

    /** Uniform in [d * (1 - spread), d * (1 + spread)], used to de-synchronise periodic polls. */
    public static long jittered(Duration d, double spread) {
        long ms = d.toMillis();
        long delta = (long) (ms * spread);
        return ms - delta + ThreadLocalRandom.current().nextLong(2 * delta + 1);
    }
}
