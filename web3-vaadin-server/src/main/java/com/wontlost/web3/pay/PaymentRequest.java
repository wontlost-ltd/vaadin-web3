package com.wontlost.web3.pay;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;

/**
 * Describes the token payment a checkout accepts.
 * <p>
 * {@code notBefore} is the earliest acceptable block time: a transaction mined in an earlier block is rejected with
 * {@link PaymentStatus#PREDATES_ORDER}. Set it to when the order was created, minus a small clock tolerance
 * ({@link StablecoinCheckout} uses the Pay click minus two minutes by default). Without it, any earlier, still unclaimed
 * transfer to the recipient can be presented as payment for a new order &mdash; for example a third party's historical
 * payment when the payer is client-reported, or the buyer's own earlier payment after an in-memory ledger was reset.
 * <p>
 * This bounds replay to transfers mined inside the tolerance window; it cannot prove that a transfer was made for this
 * particular order. Require a SIWE-verified payer for checkouts where that matters.
 */
public record PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount,
        String payer, int minConfirmations, Instant notBefore, Finality finality) {
    public PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount,
            String payer, int minConfirmations, Instant notBefore) {
        this(chainId, token, recipient, minAmount, payer, minConfirmations, notBefore,
                Finality.confirmations(minConfirmations));
    }
    /** Creates a payment request with no payer restriction, one confirmation and no time restriction. */
    public PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount) {
        this(chainId, token, recipient, minAmount, null, 1, null, Finality.confirmations(1));
    }
    /** Creates a payment request with one confirmation and no time restriction. */
    public PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount, String payer) {
        this(chainId, token, recipient, minAmount, payer, 1, null, Finality.confirmations(1));
    }
    /** Creates a payment request with no time restriction. */
    public PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount, String payer,
            int minConfirmations) {
        this(chainId, token, recipient, minAmount, payer, minConfirmations, null, Finality.confirmations(minConfirmations));
    }
    /** Creates a payment request using the specified finality condition. */
    public PaymentRequest(long chainId, String token, String recipient, BigInteger minAmount,
            String payer, Finality finality) {
        this(chainId, token, recipient, minAmount, payer,
                finality.kind() == Finality.Kind.CONFIRMATIONS ? finality.confirmations() : 1,
                null, finality);
    }
    /** Returns a copy requiring the supplied finality condition. */
    public PaymentRequest withFinality(Finality value) {
        return new PaymentRequest(chainId, token, recipient, minAmount, payer, minConfirmations, notBefore,
                java.util.Objects.requireNonNull(value));
    }
    /**
     * {@code finality} is the single source of truth: for a confirmation-count finality, {@code minConfirmations} is
     * always set to {@link Finality#confirmations()}, so the two components can never disagree.
     */
    public PaymentRequest {
        java.util.Objects.requireNonNull(finality, "finality");
        // 两个字段表达同一件事时以 finality 为准，避免 withFinality 之后 minConfirmations 残留旧值
        if (finality.kind() == Finality.Kind.CONFIRMATIONS) minConfirmations = finality.confirmations();
        token = token.toLowerCase(Locale.ROOT);
        recipient = recipient.toLowerCase(Locale.ROOT);
        payer = payer == null ? null : payer.toLowerCase(Locale.ROOT);
        if (minAmount.signum() < 0 || minConfirmations < 1) throw new IllegalArgumentException("Invalid payment requirement");
    }
}
