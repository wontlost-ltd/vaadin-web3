package com.wontlost.web3.pay;

import java.math.BigInteger;

/** Verification result and the payment details observed on chain. */
public record PaymentResult(PaymentStatus status, String txHash, String payer,
        BigInteger amountPaid, long confirmations) { }
