package com.wontlost.web3.calls;

/** A single EIP-5792 call. */
public record Call(String to, String data, String value) {
}
