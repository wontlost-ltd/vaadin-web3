package com.wontlost.web3.monitor.service.db;

import java.math.BigInteger;
import java.time.Instant;
import com.wontlost.web3.monitor.MonitoredStatus;

public record PaymentIntent(String id, String merchantId, String orderId, long chainId, String tokenSymbol,
        String tokenAddress, int tokenDecimals, String recipient, BigInteger amountUnits, String payer,
        int minConfirmations, Instant notBefore, Instant expiresAt, MonitoredStatus status,
        String txHash, BigInteger paidAmountUnits, long confirmations, Instant createdAt, Instant updatedAt,
        Instant leaseUntil, int attempts, Instant nextCheckAt) { }
