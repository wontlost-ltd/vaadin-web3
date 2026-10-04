package com.wontlost.web3.siwe;

/** Exception raised when a Sign-In with Ethereum message cannot be accepted. */
public class SiweException extends RuntimeException {

    private final Reason reason;

    /** Creates an SIWE exception with its rejection reason. */
    public SiweException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Creates an SIWE exception with its rejection reason and underlying cause. */
    public SiweException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** Returns the stable rejection category. */
    public Reason getReason() {
        return reason;
    }

    /** Reasons a SIWE message or signature can be rejected. */
    public enum Reason {
        MALFORMED,
        DOMAIN_MISMATCH,
        URI_MISMATCH,
        CHAIN_NOT_ALLOWED,
        NOT_YET_VALID,
        EXPIRED,
        TOO_OLD,
        SIGNATURE_INVALID,
        ADDRESS_MISMATCH,
        NONCE_INVALID
    }
}
