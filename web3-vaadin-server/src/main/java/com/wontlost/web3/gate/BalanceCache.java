package com.wontlost.web3.gate;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 门禁共用的余额缓存：按 TTL 过期、容量有界（满时淘汰最旧的条目）；加载失败不缓存。 */
final class BalanceCache<K, V> {
    private final long ttlMillis;
    private final int capacity;
    private final Map<K, Cached<V>> entries = new ConcurrentHashMap<>();

    BalanceCache(Duration ttl, int capacity) {
        this.ttlMillis = Objects.requireNonNull(ttl).toMillis();
        if (capacity < 1) throw new IllegalArgumentException("cacheCapacity must be positive");
        this.capacity = capacity;
    }

    V get(K key, Supplier<V> loader) {
        long now = System.currentTimeMillis();
        Cached<V> cached = entries.get(key);
        if (cached != null && now - cached.fetchedAt < ttlMillis) return cached.value;
        V value = loader.get();
        synchronized (entries) {
            entries.entrySet().removeIf(entry -> now - entry.getValue().fetchedAt >= ttlMillis);
            entries.put(key, new Cached<>(value, now));
            while (entries.size() > capacity) {
                K oldest = entries.entrySet().stream()
                        .min(Comparator.comparingLong(entry -> entry.getValue().fetchedAt)).orElseThrow().getKey();
                entries.remove(oldest);
            }
        }
        return value;
    }

    int size() {
        return entries.size();
    }

    private record Cached<V>(V value, long fetchedAt) {
    }
}
