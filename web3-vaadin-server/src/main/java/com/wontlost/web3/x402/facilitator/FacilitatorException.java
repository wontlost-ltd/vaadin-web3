package com.wontlost.web3.x402.facilitator;

public final class FacilitatorException extends RuntimeException {
    private final FacilitatorFailure failure;
    private final int statusCode;
    public FacilitatorException(FacilitatorFailure failure, String message) { this(failure, 0, message, null); }
    public FacilitatorException(FacilitatorFailure failure, int statusCode, String message, Throwable cause) {
        super(message, cause); this.failure = failure; this.statusCode = statusCode;
    }
    public FacilitatorFailure failure() { return failure; }
    public int statusCode() { return statusCode; }
}
