package com.wontlost.web3.x402.payment;

import java.time.Instant;

public record PaymentRecord(String paymentId, String idempotencyKey, String resourceId, String walletAddress,
        String network, String asset, String amount, String policyVersion, PaymentStatus status, String txHash,
        String facilitator, Instant createdAt, Instant updatedAt, String failureCode, String nonce, String validAfter,
        String validBefore, String to, String value) {
    public boolean sameIntent(PaymentRecord other) {
        return sameIntent(this, other);
    }

    public static boolean sameIntent(PaymentRecord first, PaymentRecord second) {
        return first != null && second != null
                && first.idempotencyKey().equals(second.idempotencyKey())
                && first.resourceId().equals(second.resourceId()) && first.walletAddress().equals(second.walletAddress())
                && first.network().equals(second.network()) && first.asset().equalsIgnoreCase(second.asset())
                && first.amount().equals(second.amount()) && first.policyVersion().equals(second.policyVersion())
                && java.util.Objects.equals(first.nonce(), second.nonce())
                && java.util.Objects.equals(first.validAfter(), second.validAfter())
                && java.util.Objects.equals(first.validBefore(), second.validBefore())
                && java.util.Objects.equals(first.to(), second.to()) && java.util.Objects.equals(first.value(), second.value());
    }
}
