package com.wontlost.web3.pay;

/** Selects the chain-finality condition required before a payment is confirmed. */
public record Finality(Kind kind, int confirmations) {
    public enum Kind { CONFIRMATIONS, FINALIZED }

    public Finality {
        if (kind == null) throw new NullPointerException("kind");
        if (kind == Kind.CONFIRMATIONS && confirmations < 1)
            throw new IllegalArgumentException("confirmations must be positive");
    }

    /** Requires the given number of blocks including the transaction block. */
    public static Finality confirmations(int count) { return new Finality(Kind.CONFIRMATIONS, count); }

    /** Requires the transaction block to be at or below the node's finalized head. */
    public static Finality finalized() { return new Finality(Kind.FINALIZED, 0); }
}
