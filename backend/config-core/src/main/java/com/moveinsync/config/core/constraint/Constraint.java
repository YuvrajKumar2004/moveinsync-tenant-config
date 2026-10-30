package com.moveinsync.config.core.constraint;

import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.SettingValue;
import com.moveinsync.config.core.validation.InvalidValueException;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A rule spanning several keys. Checked against the resolved values at every point in the hierarchy a
 * change can affect, so a combination that is invalid anywhere is rejected before it is published.
 */
public interface Constraint {

    String name();

    String type();

    Map<String, String> params();

    /** Keys this constraint reads. A change touching none of them does not re-check it. */
    Set<String> keys();

    /** Verifies the referenced keys exist with suitable types. */
    void verifyDefinitions(Map<String, SettingDefinition> definitions);

    /** @throws InvalidValueException when the resolved combination is invalid */
    void check(Function<String, SettingValue> resolved);
}
