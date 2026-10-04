package com.wontlost.web3.pay;

/** State of a submitted stablecoin payment; {@link #NO_MATCHING_TRANSFER} means no requested token transfer matched. */
public enum PaymentStatus { PENDING, FAILED, NO_MATCHING_TRANSFER, UNDERPAID, CONFIRMING, ALREADY_CLAIMED, CONFIRMED }
