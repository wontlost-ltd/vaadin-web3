package com.wontlost.web3.x402.payment;

public record PaymentOutcome(String paymentId, PaymentStatus status, String txHash, String failureCode) { }
