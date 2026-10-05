package com.wontlost.web3.monitor;

import java.time.Instant;

/** Hosted payment intent state returned by the monitor service. */
public record MonitoredPayment(String id, String orderId, long chainId, Token token, String recipient,
        String amount, String payer, int minConfirmations, MonitoredStatus status, String txHash,
        String paidAmount, long confirmations, Instant notBefore, Instant expiresAt,
        Instant createdAt, Instant updatedAt) {
    /** Token metadata associated with the payment intent. */
    public record Token(String symbol, String address, int decimals) { }
}
