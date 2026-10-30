package com.moveinsync.config.core.type;

import com.moveinsync.config.core.validation.InvalidValueException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Decimal number. Rules: min, max, integer (true/false). */
public final class NumberType extends SettingType<BigDecimal> {

    private final BigDecimal min;
    private final BigDecimal max;
    private final boolean integer;

    public NumberType(Map<String, String> rules) {
        super(rules, Set.of("min", "max", "integer"));
        this.min = ruleDecimal("min");
        this.max = ruleDecimal("max");
        this.integer = ruleBool("integer", false);
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new InvalidValueException("rule min must not exceed max");
        }
    }

    @Override
    public String name() {
        return "NUMBER";
    }

    @Override
    public Class<BigDecimal> valueClass() {
        return BigDecimal.class;
    }

    @Override
    protected BigDecimal doParse(String raw) {
        return new BigDecimal(raw);
    }

    @Override
    protected void check(BigDecimal value) {
        if (integer && value.stripTrailingZeros().scale() > 0) {
            throw new InvalidValueException("must be a whole number");
        }
        if (min != null && value.compareTo(min) < 0) {
            throw new InvalidValueException("must be at least " + min.toPlainString());
        }
        if (max != null && value.compareTo(max) > 0) {
            throw new InvalidValueException("must be at most " + max.toPlainString());
        }
    }

    @Override
    protected String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
