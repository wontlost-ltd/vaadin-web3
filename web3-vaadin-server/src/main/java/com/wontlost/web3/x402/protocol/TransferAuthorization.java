package com.wontlost.web3.x402.protocol;

public record TransferAuthorization(String from, String to, String value, String validAfter,
        String validBefore, String nonce) { }
