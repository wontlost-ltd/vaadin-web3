package com.wontlost.web3.x402.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentSharedContractTest {
    private static final Map<PaymentStatus, java.util.Set<PaymentStatus>> LEGAL = legalMatrix();

    @Test void transitionContractMatchesEveryPairInOriginalMatrix() {
        for (PaymentStatus from : PaymentStatus.values()) for (PaymentStatus to : PaymentStatus.values())
            assertEquals(LEGAL.get(from).contains(to), from.canTransitionTo(to), from + " -> " + to);
    }

    @Test void intentEqualityIgnoresMutableSettlementFieldsAndComparesAuthorizationFields() {
        PaymentRecord base = record("0xABC", "100");
        PaymentRecord statusChanged = new PaymentRecord(base.paymentId(), base.idempotencyKey(), base.resourceId(),
                base.walletAddress(), base.network(), base.asset(), base.amount(), base.policyVersion(), PaymentStatus.PENDING,
                "0xtx", "facilitator", base.createdAt(), base.updatedAt().plusSeconds(1), "failure", base.nonce(),
                base.validAfter(), base.validBefore(), base.to(), base.value());
        assertTrue(base.sameIntent(statusChanged));
        assertFalse(base.sameIntent(record("0xDEF", "100")));
        assertFalse(base.sameIntent(record("0xABC", "101")));
    }

    private static PaymentRecord record(String nonce, String value) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PaymentRecord("id", "key", "resource", "0xabc", "eip155:31337", "0x0000000000000000000000000000000000000001",
                "100", "v1", PaymentStatus.READY, null, null, now, now, null, nonce, "1", "2",
                "0x0000000000000000000000000000000000000002", value);
    }

    private static Map<PaymentStatus, java.util.Set<PaymentStatus>> legalMatrix() {
        Map<PaymentStatus, java.util.Set<PaymentStatus>> result = new EnumMap<>(PaymentStatus.class);
        result.put(PaymentStatus.READY, java.util.Set.of(PaymentStatus.VERIFIED, PaymentStatus.FAILED));
        result.put(PaymentStatus.VERIFIED, java.util.Set.of(PaymentStatus.SETTLING, PaymentStatus.FAILED));
        result.put(PaymentStatus.SETTLING, java.util.Set.of(PaymentStatus.SETTLED, PaymentStatus.PENDING, PaymentStatus.FAILED, PaymentStatus.UNKNOWN));
        result.put(PaymentStatus.PENDING, java.util.Set.of(PaymentStatus.SETTLED, PaymentStatus.FAILED));
        result.put(PaymentStatus.UNKNOWN, java.util.Set.of(PaymentStatus.SETTLED, PaymentStatus.FAILED));
        result.put(PaymentStatus.SETTLED, java.util.Set.of());
        result.put(PaymentStatus.FAILED, java.util.Set.of());
        return result;
    }
}
