package com.moveinsync.config.sim;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

/** Percentile summary of a sample of measurements. */
public record Stats(int count, double min, double p50, double p90, double p99, double max, double mean) {

    public static Stats of(Collection<? extends Number> values) {
        double[] a = values.stream().mapToDouble(Number::doubleValue).toArray();
        return of(a);
    }

    public static Stats of(double[] a) {
        if (a.length == 0) {
            return new Stats(0, 0, 0, 0, 0, 0, 0);
        }
        double[] s = a.clone();
        Arrays.sort(s);
        return new Stats(s.length, s[0], pct(s, 50), pct(s, 90), pct(s, 99), s[s.length - 1], Arrays.stream(s).average().orElse(0));
    }

    /** Nearest-rank percentile. */
    static double pct(double[] sorted, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    public String format(String unit) {
        return String.format(Locale.ROOT, "n=%d min=%.2f%s p50=%.2f%s p90=%.2f%s p99=%.2f%s max=%.2f%s mean=%.2f%s",
                count, min, unit, p50, unit, p90, unit, p99, unit, max, unit, mean, unit);
    }
}
