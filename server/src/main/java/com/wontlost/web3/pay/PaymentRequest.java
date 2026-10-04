package com.wontlost.web3.pay;

import java.math.BigInteger;
import java.util.Locale;

/** Describes the token payment a checkout accepts. */
public record PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount,
        String payer, int minConfirmations) {
    /** Creates a payment request with no payer restriction and one confirmation. */
    public PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount) {
        this(chainId, token, recipient, minAmount, null, 1);
    }
    /** Creates a payment request with one confirmation. */
    public PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount, String payer) {
        this(chainId, token, recipient, minAmount, payer, 1);
    }
    public PaymentRequest {
        token = token.toLowerCase(Locale.ROOT);
        recipient = recipient.toLowerCase(Locale.ROOT);
        payer = payer == null ? null : payer.toLowerCase(Locale.ROOT);
        if (minAmount.signum() < 0 || minConfirmations < 1) throw new IllegalArgumentException("Invalid payment requirement");
    }
}
