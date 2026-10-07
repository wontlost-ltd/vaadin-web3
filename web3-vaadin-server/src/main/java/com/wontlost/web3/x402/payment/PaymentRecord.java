package com.wontlost.web3.x402.payment;

import java.time.Instant;

public record PaymentRecord(String paymentId, String idempotencyKey, String resourceId, String walletAddress,
        String network, String asset, String amount, String policyVersion, PaymentStatus status, String txHash,
        String facilitator, Instant createdAt, Instant updatedAt, String failureCode, String nonce, String validAfter,
        String validBefore, String to, String value) { }
