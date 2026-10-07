package com.wontlost.web3.x402.payment;

public enum PaymentStatus {
    READY, VERIFIED, SETTLING, SETTLED, PENDING, FAILED, UNKNOWN;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case READY -> next == VERIFIED || next == FAILED;
            case VERIFIED -> next == SETTLING || next == FAILED;
            case SETTLING -> next == SETTLED || next == PENDING || next == FAILED || next == UNKNOWN;
            case PENDING, UNKNOWN -> next == SETTLED || next == FAILED;
            case SETTLED, FAILED -> false;
        };
    }
}
