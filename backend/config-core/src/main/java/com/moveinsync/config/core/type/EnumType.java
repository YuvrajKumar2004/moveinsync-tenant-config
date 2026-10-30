package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One of a fixed set of values. Rule: values (required, comma or pipe separated). */
public final class EnumType extends SettingType<String> {

    private final Set<String> values;

    public EnumType(Map<String, String> rules) {
        super(rules, Set.of("values"));
        List<String> list = ruleList("values");
        if (list == null || list.isEmpty()) {
            throw new InvalidValueException("ENUM needs a non-empty 'values' rule");
        }
        this.values = new LinkedHashSet<>(list);
    }

    @Override
    public String name() {
        return "ENUM";
    }

    @Override
    public Class<String> valueClass() {
        return String.class;
    }

    @Override
    protected String doParse(String raw) {
        return raw;
    }

    @Override
    protected void check(String value) {
        if (!values.contains(value)) {
            throw new InvalidValueException("must be one of " + values);
        }
    }

    @Override
    protected String format(String value) {
        return value;
    }
}
