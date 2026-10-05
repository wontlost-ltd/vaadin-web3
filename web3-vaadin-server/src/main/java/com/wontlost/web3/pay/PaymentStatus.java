package com.wontlost.web3.pay;

/**
 * State of a submitted stablecoin payment; {@link #NO_MATCHING_TRANSFER} means no requested token transfer matched and
 * {@link #PREDATES_ORDER} means the transaction was mined before the request's {@code notBefore} time.
 */
public enum PaymentStatus { PENDING, FAILED, NO_MATCHING_TRANSFER, UNDERPAID, CONFIRMING, ALREADY_CLAIMED, CONFIRMED,
    PREDATES_ORDER }
