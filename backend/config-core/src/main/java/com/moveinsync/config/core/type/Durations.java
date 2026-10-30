package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses ISO-8601 durations ("PT30M") and the shorthand "30m", "2h", "45s", "1d". */
public final class Durations {

    private static final Pattern SHORT = Pattern.compile("(-?\\d{1,9})\\s*([smhd])", Pattern.CASE_INSENSITIVE);

    private Durations() {
    }

    public static Duration parse(String raw) {
        String s = raw.trim();
        if (s.startsWith("P") || s.startsWith("p") || s.startsWith("-P") || s.startsWith("-p")) {
            try {
                return Duration.parse(s.toUpperCase());
            } catch (RuntimeException e) {
                throw new InvalidValueException("'" + SettingType.abbreviate(raw) + "' is not a valid duration");
            }
        }
        Matcher m = SHORT.matcher(s);
        if (!m.matches()) {
            throw new InvalidValueException("'" + SettingType.abbreviate(raw)
                    + "' is not a valid duration (use ISO-8601 like PT30M or shorthand like 30m)");
        }
        long n = Long.parseLong(m.group(1));
        return switch (Character.toLowerCase(m.group(2).charAt(0))) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }
}
