package com.wontlost.web3.pro.payments;

import java.time.Instant;

/** Immutable operational record of a checkout payment. */
public record PaymentRecord(String orderId, long chainId, String tokenSymbol, String tokenAddress,
        String amount, String payer, String txHash, String status, Instant createdAt, Instant updatedAt) { }
