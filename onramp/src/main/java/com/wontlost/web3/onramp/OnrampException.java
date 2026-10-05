package com.wontlost.web3.onramp;

/** Reports a provider request failure with its HTTP status when available. */
public class OnrampException extends RuntimeException {
    private final String provider;
    private final int statusCode;

    /** Creates a provider error. */
    public OnrampException(String provider, int statusCode, String message) {
        super(message);
        this.provider = provider;
        this.statusCode = statusCode;
    }

    /** Creates a provider error caused by another exception. */
    public OnrampException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.statusCode = -1;
    }

    /** Returns the provider name. */
    public String getProvider() { return provider; }
    /** Returns the HTTP status, or -1 when no HTTP response was received. */
    public int getStatusCode() { return statusCode; }
}
