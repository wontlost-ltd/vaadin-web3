package com.wontlost.web3.x402.siwx;

public final class SiwxVerificationException extends RuntimeException {
    private final String failureCode;

    public SiwxVerificationException(String failureCode) {
        super(failureCode);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
