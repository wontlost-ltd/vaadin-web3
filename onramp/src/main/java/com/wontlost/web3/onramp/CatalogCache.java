package com.wontlost.web3.onramp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

final class CatalogCache<T> {
    private final Clock clock;
    private Duration ttl;
    private List<T> values;
    private Instant expiresAt = Instant.MIN;
    private boolean refreshing;
    /** 失效代次：invalidate/setTtl 时递增，在途的后台刷新若代次已变则丢弃结果（例如 Coinbase 切换国家后）。 */
    private long generation;

    CatalogCache(Clock clock, Duration ttl) { this.clock = clock; this.ttl = ttl; }

    synchronized List<T> get(Supplier<List<T>> loader) {
        if (values != null && clock.instant().isBefore(expiresAt)) return values;
        List<T> loaded = List.copyOf(loader.get());
        values = loaded;
        expiresAt = clock.instant().plus(ttl);
        return loaded;
    }

    /**
     * 非阻塞读取：立即返回当前缓存（过期的也返回，即"先用旧数据"），缺失或过期时在后台刷新一次。
     * 用于界面构建等不能等待网络的路径；刷新失败只记录日志，下次调用会再次尝试。
     */
    synchronized java.util.Optional<List<T>> peekAndRefresh(Supplier<List<T>> loader, java.util.concurrent.Executor executor) {
        boolean stale = values == null || !clock.instant().isBefore(expiresAt);
        if (stale && !refreshing) {
            refreshing = true;
            long started = generation;
            try {
                executor.execute(() -> {
                    try {
                        List<T> loaded = List.copyOf(loader.get());
                        synchronized (this) {
                            if (started == generation) {
                                values = loaded;
                                expiresAt = clock.instant().plus(ttl);
                            }
                        }
                    } catch (RuntimeException failure) {
                        org.slf4j.LoggerFactory.getLogger(CatalogCache.class).warn("On-ramp catalog refresh failed", failure);
                    } finally {
                        synchronized (this) { refreshing = false; }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                refreshing = false;
            }
        }
        return java.util.Optional.ofNullable(values);
    }

    synchronized void invalidate() {
        generation++;
        values = null;
        expiresAt = Instant.MIN;
    }

    synchronized void setTtl(Duration value) {
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("Cache duration must be positive");
        ttl = value;
        generation++;
        values = null;
        expiresAt = Instant.MIN;
    }
}
