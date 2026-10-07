package com.wontlost.web3.x402.payment;

import java.time.Instant;

import com.wontlost.web3.x402.protocol.PaymentRequirements;
import com.wontlost.web3.x402.protocol.TransferAuthorization;

public record PaymentAttempt(String paymentId, PaymentRequirements requirements, String typedDataJson,
        TransferAuthorization authorization, Instant expiresAt) { }
