package com.moveinsync.config.core.change;

import java.util.List;

/**
 * A committed change set as distributed to clients: the version it produced, when it was committed and
 * its operations. Applied atomically: a reader sees all of {@code ops} or none of them.
 */
public record ChangeEvent(long version, long committedAtMillis, ChangeKind kind, List<Op> ops) {

    public ChangeEvent {
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        ops = List.copyOf(ops);
    }
}
