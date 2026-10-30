package com.moveinsync.config.core.wire;

import java.util.List;
import java.util.Map;

/**
 * Plain records forming the JSON wire/disk format shared by server and client. Kept free of any JSON
 * library so the core stays dependency-free; Jackson maps records natively.
 */
public final class Wire {

    private Wire() {
    }

    public record ScopeRef(String level, String clientId, String officeId, String shiftId) {
    }

    public record OverrideEntry(String level, String clientId, String officeId, String shiftId, String key, String value) {
    }

    public record Definition(String key, String type, Map<String, String> rules, String defaultValue, String description) {
    }

    public record ConstraintDef(String name, String type, Map<String, String> params) {
    }

    public record Rollout(long version, List<String> targets, List<OverrideEntry> items) {
    }

    /**
     * One operation. {@code type} is SET, REMOVE, DEFINE, DEFINE_CONSTRAINT, STAGE or UNSTAGE; only the
     * fields relevant to that type are set.
     */
    public record Op(String type, String level, String clientId, String officeId, String shiftId, String key,
                     String value, Definition definition, ConstraintDef constraint, List<String> targets,
                     List<OverrideEntry> items, Long rolloutVersion) {
    }

    public record Event(long version, long committedAtMillis, String kind, List<Op> ops) {
    }

    public record SnapshotData(long version, List<Definition> definitions, List<ConstraintDef> constraints,
                               List<OverrideEntry> overrides, List<Rollout> rollouts) {
    }

    /**
     * Answer to "what changed since version N". Either {@code events} (contiguous, starting at
     * since + 1) or a full {@code snapshot} when the server no longer retains those events.
     * {@code historyId} identifies the server's change log; version numbers are only comparable
     * within one history.
     */
    public record Delta(long fromVersion, long toVersion, boolean full, List<Event> events, SnapshotData snapshot,
                        String historyId) {
    }

    /** Current server version and the history it belongs to (version check and SSE heartbeat). */
    public record ServerVersion(long version, String historyId) {
    }

    /**
     * File written by the client: payload plus SHA-256 so a torn or corrupted file is detected.
     * Format 1 had no {@code historyId}; format 2 records it (covered by the checksum).
     */
    public record DiskFile(int format, String sha256, String historyId, SnapshotData snapshot) {
    }
}
