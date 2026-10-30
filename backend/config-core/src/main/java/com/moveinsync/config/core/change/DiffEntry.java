package com.moveinsync.config.core.change;

import com.moveinsync.config.core.model.Scope;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** One changed override. {@code from}/{@code to} are null when the override is absent on that side. */
public record DiffEntry(Scope scope, String key, String from, String to) {

    /** Differences going from {@code before} to {@code after}, in stable order. */
    public static List<DiffEntry> between(Map<Snapshot.ScopedKey, String> before, Map<Snapshot.ScopedKey, String> after) {
        TreeSet<Snapshot.ScopedKey> all = new TreeSet<>(Snapshot.ScopedKey.ORDER);
        all.addAll(before.keySet());
        all.addAll(after.keySet());
        List<DiffEntry> out = new ArrayList<>();
        for (Snapshot.ScopedKey k : all) {
            String a = before.get(k);
            String b = after.get(k);
            if (a == null ? b != null : !a.equals(b)) {
                out.add(new DiffEntry(k.scope(), k.key(), a, b));
            }
        }
        return out;
    }
}
