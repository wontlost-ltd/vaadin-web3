package com.wontlost.web3.chain;

/** Reports an error returned by an Ethereum JSON-RPC endpoint. */
public class EthRpcException extends RuntimeException {
    private final int code;
    public EthRpcException(int code, String message) { super(message); this.code = code; }
    public int getCode() { return code; }
}
