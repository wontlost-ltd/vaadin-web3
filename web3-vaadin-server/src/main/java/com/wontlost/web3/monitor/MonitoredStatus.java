package com.wontlost.web3.monitor;

/** Status values returned by a hosted payment monitor. */
public enum MonitoredStatus {
    AWAITING_TRANSACTION, PENDING, CONFIRMING, CONFIRMED, FAILED, UNDERPAID,
    NO_MATCHING_TRANSFER, ALREADY_CLAIMED, PREDATES_ORDER, EXPIRED
}
