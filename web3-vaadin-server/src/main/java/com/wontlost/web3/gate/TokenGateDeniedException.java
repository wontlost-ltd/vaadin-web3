package com.wontlost.web3.gate;

/** Thrown when a signed-in wallet does not meet a view's token requirement. */
public class TokenGateDeniedException extends RuntimeException {
    public TokenGateDeniedException() { }
    public TokenGateDeniedException(String message) { super(message); }
}
