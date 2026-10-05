package com.wontlost.web3.pro.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.h2.jdbcx.JdbcDataSource;

import com.wontlost.web3.pro.NonAutoCommitDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcStoresTest {
    private JdbcDataSource source;
    private Clock clock;
    @BeforeEach void setup() {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:pro" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ProSchema.create(source);
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test void nonceExpiresAndCanBeConsumedOnlyOnceAcrossInstances() {
        JdbcNonceStore first = new JdbcNonceStore(source, Duration.ofMinutes(5), clock);
        JdbcNonceStore second = new JdbcNonceStore(source, Duration.ofMinutes(5), clock);
        String nonce = first.issue();
        assertEquals(17, nonce.length());
        assertTrue(first.isActive(nonce));
        assertTrue(second.consume(nonce));
        assertFalse(first.consume(nonce));
        String expired = first.issue();
        JdbcNonceStore later = new JdbcNonceStore(source, Duration.ofMinutes(5), Clock.offset(clock, Duration.ofMinutes(5)));
        assertFalse(later.isActive(expired));
        assertFalse(later.consume(expired));
    }

    @Test void writesCommitWhenDataSourceReturnsNonAutoCommitConnections() {
        NonAutoCommitDataSource transactionalSource = new NonAutoCommitDataSource(source);
        JdbcNonceStore nonceStore = new JdbcNonceStore(transactionalSource, Duration.ofMinutes(5), clock);
        String nonce = nonceStore.issue();
        assertTrue(nonceStore.isActive(nonce));
        assertTrue(nonceStore.consume(nonce));
        assertFalse(nonceStore.isActive(nonce));

        JdbcPaymentLedger ledger = new JdbcPaymentLedger(transactionalSource);
        assertTrue(ledger.claim("claim", "order-a"));
        assertTrue(ledger.claim("claim", "order-a"));
        assertFalse(ledger.claim("claim", "order-b"));
    }

    @Test void concurrentNonceConsumeHasOneWinner() throws Exception {
        JdbcNonceStore first = new JdbcNonceStore(source, Duration.ofMinutes(5), clock);
        JdbcNonceStore second = new JdbcNonceStore(source, Duration.ofMinutes(5), clock);
        String nonce = first.issue();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return first.consume(nonce); });
            var b = pool.submit(() -> { start.await(); return second.consume(nonce); });
            start.countDown();
            assertNotEquals(a.get(), b.get());
        }
    }

    @Test void paymentClaimIsIdempotentForSameOrderAndUniqueAcrossOrders() throws Exception {
        JdbcPaymentLedger ledger = new JdbcPaymentLedger(source);
        assertTrue(ledger.claim("1:0xabc", "order-a"));
        assertTrue(ledger.claim("1:0xabc", "order-a"));
        assertFalse(ledger.claim("1:0xabc", "order-b"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return ledger.claim("1:0xdef", "order-a"); });
            var b = pool.submit(() -> { start.await(); return ledger.claim("1:0xdef", "order-b"); });
            start.countDown();
            assertNotEquals(a.get(), b.get());
        }
    }

    @Test void schemaCreationCommitsOnNonAutoCommitConnectionsAndAvoidsTheClasspathRoot() {
        // H2 的 DDL 会隐式提交，无法复现 PostgreSQL 的"关闭连接即回滚建表"；因此断言显式 commit 确实发生
        NonAutoCommitDataSource transactional = new NonAutoCommitDataSource(source);
        ProSchema.create(transactional);
        org.junit.jupiter.api.Assertions.assertTrue(transactional.commits() >= 1, "DDL must be committed explicitly");
        // 根目录的 schema.sql 会被 Spring Boot 对嵌入式数据库自动执行并与应用自己的 schema.sql 冲突
        org.junit.jupiter.api.Assertions.assertNull(ProSchema.class.getResource("/schema.sql"));
        org.junit.jupiter.api.Assertions.assertNotNull(ProSchema.class.getResource("/com/wontlost/web3/pro/schema.sql"));
    }
}
