package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.model.Level;
import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.model.SettingValue;

/**
 * "Explain" output: the resolved value and where it came from.
 *
 * @param source          OVERRIDE (a stored value at {@code scope}), STAGED (a staged rollout value for
 *                        this office) or DEFAULT (the definition's global default)
 * @param rolloutVersion  the staged rollout's version when source is STAGED, otherwise null
 * @param snapshotVersion version of the snapshot that answered
 */
public record Resolution(String key, SettingValue value, Level level, Scope scope, Source source,
                         Long rolloutVersion, long snapshotVersion) {

    public enum Source { OVERRIDE, STAGED, DEFAULT }
}
