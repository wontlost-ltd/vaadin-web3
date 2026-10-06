package com.wontlost.web3.chain;

/** Reports an error returned by an Ethereum JSON-RPC endpoint. */
public class EthRpcException extends RuntimeException {
    private final int code;
    private final String data;
    private final Category category;
    /** Creates an exception without structured error data. */
    public EthRpcException(int code, String message) { this(code, message, null); }
    /** Creates an exception preserving the original JSON-RPC error data. */
    public EthRpcException(int code, String message, String data) {
        super(message);
        this.code = code;
        this.data = data;
        this.category = EthRpcErrorClassifier.classify(code, message, data);
    }
    /** Returns the JSON-RPC error code. */
    public int getCode() { return code; }
    /** Returns the serialized JSON-RPC error data, or {@code null} when absent. */
    public String getData() { return data; }
    /** Returns the conservative category assigned to this error. */
    public Category getCategory() { return category; }

    /** Classification of the JSON-RPC error. */
    public enum Category { TRANSIENT_NODE, RATE_LIMITED, DETERMINISTIC, INVALID_REQUEST, UNKNOWN }
}
