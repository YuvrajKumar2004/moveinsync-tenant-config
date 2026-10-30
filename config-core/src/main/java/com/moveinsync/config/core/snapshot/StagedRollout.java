package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.change.Op;

import java.util.List;
import java.util.Set;

/** An active staged rollout: overrides visible only to reads from the target offices. */
public record StagedRollout(long version, Set<String> targets, List<Op.SetOverride> items) {

    public StagedRollout {
        targets = Set.copyOf(targets);
        items = List.copyOf(items);
    }
}
