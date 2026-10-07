package com.wontlost.web3.x402.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.x402.payment.PaymentRecord;
import com.wontlost.web3.x402.payment.PaymentStatus;

class InMemoryPaidResourceStoreTest {
    private static final String WALLET = "0x0000000000000000000000000000000000000001";
    private static final String RECIPIENT = "0x0000000000000000000000000000000000000002";
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

    @Test void latestUsesCompositeIdentityIndexAndSettledEntitlementRemainsAvailable() {
        var store = new InMemoryPaidResourceStore();
        var settled = record("settled", "settled-key", "v1", PaymentStatus.READY, NOW, 1_700_000_100);
        store.createReady(settled);
        store.transition("settled", PaymentStatus.READY, withStatus(settled, PaymentStatus.VERIFIED));
        store.transition("settled", PaymentStatus.VERIFIED, withStatus(settled, PaymentStatus.SETTLING));
        store.transition("settled", PaymentStatus.SETTLING, withStatus(settled, PaymentStatus.SETTLED));
        var newer = record("newer", "newer-key", "v1", PaymentStatus.READY, NOW.plusSeconds(1), 1_700_000_200);
        store.createReady(newer);

        assertEquals("newer", store.findLatest("article", WALLET, "v1").orElseThrow().paymentId());
        assertEquals("settled", store.findSettled("article", WALLET, "v1").orElseThrow().paymentId());
        assertTrue(store.findLatest("article", WALLET, "v2").isEmpty());
    }

    @Test void purgeExpiredRemovesOnlyExpiredReadyRecordsAndReclaimsCapacity() {
        var store = new InMemoryPaidResourceStore(3);
        var expired = record("expired", "expired-key", "v1", PaymentStatus.READY, NOW, NOW.getEpochSecond() - 1);
        var active = record("active", "active-key", "v1", PaymentStatus.READY, NOW, NOW.getEpochSecond() + 60);
        var settled = record("settled", "settled-key", "v1", PaymentStatus.READY, NOW.minusSeconds(1), NOW.getEpochSecond() - 1);
        store.createReady(expired);
        store.createReady(active);
        store.createReady(settled);
        store.transition("settled", PaymentStatus.READY, withStatus(settled, PaymentStatus.VERIFIED));
        store.transition("settled", PaymentStatus.VERIFIED, withStatus(settled, PaymentStatus.SETTLING));
        store.transition("settled", PaymentStatus.SETTLING, withStatus(settled, PaymentStatus.SETTLED));

        assertEquals(1, store.purgeExpired(NOW));
        assertTrue(store.findByPaymentId("expired").isEmpty());
        assertTrue(store.findByIdempotencyKey("expired-key").isEmpty());
        assertEquals("active", store.findLatest("article", WALLET, "v1").orElseThrow().paymentId());
        assertFalse(store.findByPaymentId("active").isEmpty());
        assertEquals(PaymentStatus.SETTLED, store.findByPaymentId("settled").orElseThrow().status());
        store.createReady(record("replacement", "replacement-key", "v1", PaymentStatus.READY,
                NOW.plusSeconds(1), NOW.getEpochSecond() + 120));
        assertEquals(2, store.purgeExpired(NOW.plusSeconds(121)));
        assertFalse(store.findByPaymentId("settled").isEmpty());
    }

    @Test void purgeRespectsInclusiveValidBeforeSecond() {
        var store = new InMemoryPaidResourceStore();
        store.createReady(record("boundary", "boundary-key", "v1", PaymentStatus.READY, NOW,
                NOW.getEpochSecond()));

        assertEquals(0, store.purgeExpired(NOW.plusNanos(999_000_000)));
        assertEquals(1, store.purgeExpired(NOW.plusSeconds(1)));
    }

    @Test void pendingAndUnknownCanOnlyReconcileToSettledOrFailed() {
        var store = new InMemoryPaidResourceStore();
        var pending = record("pending", "pending-key", "v1", PaymentStatus.READY, NOW, 1_700_000_100);
        store.createReady(pending);
        store.transition("pending", PaymentStatus.READY, withStatus(pending, PaymentStatus.VERIFIED));
        store.transition("pending", PaymentStatus.VERIFIED, withStatus(pending, PaymentStatus.SETTLING));
        store.transition("pending", PaymentStatus.SETTLING, withStatus(pending, PaymentStatus.PENDING));
        assertThrows(IllegalArgumentException.class, () -> store.transition("pending", PaymentStatus.PENDING,
                withStatus(pending, PaymentStatus.UNKNOWN)));

        var unknown = record("unknown", "unknown-key", "v1", PaymentStatus.READY, NOW, 1_700_000_100);
        store.createReady(unknown);
        store.transition("unknown", PaymentStatus.READY, withStatus(unknown, PaymentStatus.VERIFIED));
        store.transition("unknown", PaymentStatus.VERIFIED, withStatus(unknown, PaymentStatus.SETTLING));
        unknown = withStatus(unknown, PaymentStatus.SETTLING);
        store.transition("unknown", PaymentStatus.SETTLING, withStatus(unknown, PaymentStatus.UNKNOWN));
        unknown = withStatus(unknown, PaymentStatus.UNKNOWN);
        assertEquals(PaymentStatus.SETTLED, store.transition("unknown", PaymentStatus.UNKNOWN,
                withStatus(unknown, PaymentStatus.SETTLED)).status());
    }

    private static PaymentRecord record(String id, String key, String version, PaymentStatus status, Instant createdAt,
            long validBefore) {
        return new PaymentRecord(id, key, "article", WALLET, "eip155:1", RECIPIENT, "100", version, status,
                null, null, createdAt, createdAt, null, "0x" + id.repeat(64).substring(0, 64),
                Long.toString(createdAt.getEpochSecond() - 600), Long.toString(validBefore), RECIPIENT, "100");
    }

    private static PaymentRecord withStatus(PaymentRecord record, PaymentStatus status) {
        return new PaymentRecord(record.paymentId(), record.idempotencyKey(), record.resourceId(), record.walletAddress(),
                record.network(), record.asset(), record.amount(), record.policyVersion(), status, record.txHash(),
                record.facilitator(), record.createdAt(), record.updatedAt(), record.failureCode(), record.nonce(),
                record.validAfter(), record.validBefore(), record.to(), record.value());
    }
}
