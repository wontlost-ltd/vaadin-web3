package com.wontlost.web3.calls;

/** An EIP-1193 or EIP-5792 wallet error preserving its numeric code. */
public class CallsException extends RuntimeException {
    private final int code;

    public CallsException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** Returns the wallet error code. */
    public int code() { return code; }
}
