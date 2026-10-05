package com.wontlost.web3.onramp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.TokenInfo;

class CatalogCacheTest {
    @Test void peekNeverBlocksAndServesStaleDataWhileRefreshingInTheBackground() {
        MutableClock clock = new MutableClock();
        CatalogCache<String> cache = new CatalogCache<>(clock, Duration.ofMinutes(10));
        List<Runnable> background = new ArrayList<>();
        AtomicInteger loads = new AtomicInteger();

        // 首次：没有数据，立即返回 empty，并且只排队一次后台加载
        assertEquals(Optional.empty(), cache.peekAndRefresh(() -> List.of("v" + loads.incrementAndGet()), background::add));
        assertEquals(Optional.empty(), cache.peekAndRefresh(() -> List.of("v" + loads.incrementAndGet()), background::add));
        assertEquals(1, background.size());
        assertEquals(0, loads.get());

        background.removeFirst().run();
        assertEquals(List.of("v1"), cache.peekAndRefresh(() -> List.of("never"), background::add).orElseThrow());
        assertTrue(background.isEmpty(), "fresh data must not trigger a refresh");

        // 过期后：先返回旧数据，同时后台刷新
        clock.advance(Duration.ofMinutes(11));
        assertEquals(List.of("v1"), cache.peekAndRefresh(() -> List.of("v" + loads.incrementAndGet()), background::add).orElseThrow());
        background.removeFirst().run();
        assertEquals(List.of("v2"), cache.peekAndRefresh(() -> List.of("never"), background::add).orElseThrow());
    }

    @Test void failedBackgroundRefreshKeepsOldDataAndRetriesLater() {
        MutableClock clock = new MutableClock();
        CatalogCache<String> cache = new CatalogCache<>(clock, Duration.ofMinutes(10));
        cache.peekAndRefresh(() -> List.of("ok"), Runnable::run);
        clock.advance(Duration.ofMinutes(11));

        cache.peekAndRefresh(() -> { throw new OnrampException("P", 503, "down"); }, Runnable::run);

        // 刷新失败后旧数据仍在，且没有留下"刷新中"标记：下一次调用会重新排队刷新
        List<Runnable> queued = new ArrayList<>();
        assertEquals(List.of("ok"), cache.peekAndRefresh(() -> List.of("ok2"), queued::add).orElseThrow());
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(List.of("ok2"), cache.peekAndRefresh(() -> List.of("never"), queued::add).orElseThrow());
    }

    @Test void refreshStartedBeforeInvalidationIsDiscarded() {
        MutableClock clock = new MutableClock();
        CatalogCache<String> cache = new CatalogCache<>(clock, Duration.ofMinutes(10));
        List<Runnable> queued = new ArrayList<>();
        cache.peekAndRefresh(() -> List.of("US catalog"), queued::add);

        // 刷新在途时切换配置（如 Coinbase setCountry）：旧请求的结果不得写回
        cache.invalidate();
        queued.removeFirst().run();

        assertEquals(Optional.empty(), cache.peekAndRefresh(() -> List.of("CA catalog"), queued::add));
        queued.removeFirst().run();
        assertEquals(List.of("CA catalog"), cache.peekAndRefresh(() -> List.of("never"), queued::add).orElseThrow());
    }

    @Test void registeringADifferentProviderUnderTheSameNameFails() {
        var context = OnrampSerializationTestSupport.context();
        Named first = new Named();
        OnrampProviders.register(context, first);
        OnrampProviders.register(context, first);
        assertSame(first, OnrampProviders.find(context, "Same").orElseThrow());
        assertThrows(IllegalStateException.class, () -> OnrampProviders.register(context, new Named()));
        assertEquals(1, first.prefetches, "catalog prefetch runs once at first registration");
    }

    private static final class Named implements OnrampProvider {
        private static final long serialVersionUID = 1L;
        private int prefetches;
        public String name() { return "Same"; }
        public boolean supports(TokenInfo token) { return true; }
        public URI createSession(OnrampOrder order) { return URI.create("https://p.test"); }
        public boolean isTestEnvironment() { return true; }
        @Override public void prefetch() { prefetches++; }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
