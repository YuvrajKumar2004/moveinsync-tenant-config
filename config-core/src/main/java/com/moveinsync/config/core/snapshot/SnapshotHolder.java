package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.change.ChangeEvent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the current snapshot and enforces "versions only move forward".
 *
 * <ul>
 *   <li>Readers call {@link #current()}: one volatile read, no lock.</li>
 *   <li>Writers are serialised; each builds a new immutable snapshot and publishes it with a single
 *       reference write, so a change set becomes visible all at once.</li>
 *   <li>An event at or below the current version is a duplicate or arrived out of order: dropped.</li>
 *   <li>An event more than one ahead means something was missed: not applied; the caller fetches the
 *       delta since {@link #version()} instead.</li>
 * </ul>
 */
public final class SnapshotHolder {

    public enum Outcome { APPLIED, STALE, GAP }

    private final AtomicReference<Snapshot> ref;

    public SnapshotHolder(Snapshot initial) {
        this.ref = new AtomicReference<>(initial);
    }

    public Snapshot current() {
        return ref.get();
    }

    public long version() {
        return ref.get().version();
    }

    public synchronized Outcome offer(ChangeEvent event) {
        Snapshot cur = ref.get();
        if (event.version() <= cur.version()) {
            return Outcome.STALE;
        }
        if (event.version() != cur.version() + 1) {
            return Outcome.GAP;
        }
        ref.set(cur.apply(event));
        return Outcome.APPLIED;
    }

    /** Replaces the snapshot only if {@code next} is newer. Returns whether it was installed. */
    public synchronized boolean replaceIfNewer(Snapshot next) {
        if (next.version() <= ref.get().version()) {
            return false;
        }
        ref.set(next);
        return true;
    }

    /**
     * Installs {@code next} unconditionally. Only for switching to a different server history (the
     * server's change log was reset or replaced): version numbers of two histories are not comparable,
     * so "never go backwards" applies within a history, not across histories.
     */
    public synchronized void reset(Snapshot next) {
        ref.set(next);
    }
}
