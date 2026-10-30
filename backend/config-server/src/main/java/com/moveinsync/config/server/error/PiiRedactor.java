package com.moveinsync.config.server.error;

import java.util.regex.Pattern;

/**
 * Masks personal data (emails, phone numbers) in free text before it reaches a log line or an error
 * response. Configuration data itself carries no personal data, but free-text fields such as a change
 * reason might ("as asked by priya@acme.com").
 */
public final class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)\\+?\\d[\\d\\s().-]{7,}\\d(?!\\w)");

    private PiiRedactor() {
    }

    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String out = EMAIL.matcher(text).replaceAll("[email]");
        return PHONE.matcher(out).replaceAll("[phone]");
    }
}
