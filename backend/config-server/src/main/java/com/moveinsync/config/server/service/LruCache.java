package com.moveinsync.config.server.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Small thread-safe least-recently-used cache. */
public final class LruCache<K, V> {

    private final Map<K, V> map;

    public LruCache(int maxEntries) {
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    /** Value is computed outside the lock; concurrent misses may compute twice (harmless, values are equal). */
    public V computeIfAbsent(K key, Function<K, V> loader) {
        V v = get(key);
        if (v == null) {
            v = loader.apply(key);
            put(key, v);
        }
        return v;
    }

    public synchronized int size() {
        return map.size();
    }
}
