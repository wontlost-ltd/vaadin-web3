package com.wontlost.web3.gate;

/** Indicates that a token balance could not be verified temporarily. */
public class TokenGateUnavailableException extends RuntimeException {
    public TokenGateUnavailableException() { }
    public TokenGateUnavailableException(String message) { super(message); }
}
