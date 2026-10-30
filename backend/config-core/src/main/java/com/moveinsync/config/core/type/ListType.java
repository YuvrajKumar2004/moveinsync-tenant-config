package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Comma-separated list of distinct tokens ([A-Za-z0-9_.-]). Rules: allowed (optional token whitelist),
 * minSize, maxSize.
 */
public final class ListType extends SettingType<List<String>> {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final Set<String> allowed;
    private final Integer minSize;
    private final Integer maxSize;

    public ListType(Map<String, String> rules) {
        super(rules, Set.of("allowed", "minSize", "maxSize"));
        List<String> a = ruleList("allowed");
        this.allowed = a == null ? null : Set.copyOf(a);
        this.minSize = ruleInt("minSize");
        this.maxSize = ruleInt("maxSize");
    }

    @Override
    public String name() {
        return "LIST";
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Class<List<String>> valueClass() {
        return (Class) List.class;
    }

    @Override
    protected List<String> doParse(String raw) {
        return Arrays.stream(raw.split(",")).map(String::trim).toList();
    }

    @Override
    protected void check(List<String> items) {
        Set<String> seen = new HashSet<>();
        for (String item : items) {
            if (!TOKEN.matcher(item).matches()) {
                throw new InvalidValueException("list item '" + abbreviate(item) + "' is not a valid token");
            }
            if (!seen.add(item)) {
                throw new InvalidValueException("list item '" + item + "' appears twice");
            }
            if (allowed != null && !allowed.contains(item)) {
                throw new InvalidValueException("list item '" + item + "' is not allowed (allowed: " + allowed + ")");
            }
        }
        if (minSize != null && items.size() < minSize) {
            throw new InvalidValueException("list needs at least " + minSize + " items");
        }
        if (maxSize != null && items.size() > maxSize) {
            throw new InvalidValueException("list allows at most " + maxSize + " items");
        }
    }

    @Override
    protected String format(List<String> value) {
        return String.join(",", value);
    }
}
