package com.wontlost.web3.monitor;

/** HTTP error returned by the hosted payment monitor. */
public final class PaymentMonitorException extends RuntimeException {
    private final int statusCode;
    private final String errorBody;

    public PaymentMonitorException(int statusCode, String errorBody) {
        super("Payment monitor request failed with HTTP " + statusCode);
        this.statusCode = statusCode;
        this.errorBody = errorBody;
    }

    /** Returns the HTTP response status. */
    public int statusCode() { return statusCode; }
    /** Returns the response body supplied by the service. */
    public String errorBody() { return errorBody; }
}
