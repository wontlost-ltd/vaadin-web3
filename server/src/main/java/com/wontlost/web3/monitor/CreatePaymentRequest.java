package com.wontlost.web3.monitor;

/** Request parameters for creating a hosted payment intent. */
public record CreatePaymentRequest(String orderId, long chainId, String token, String recipient,
        String amount, String payer, Integer minConfirmations, Long expiresInSeconds) { }
