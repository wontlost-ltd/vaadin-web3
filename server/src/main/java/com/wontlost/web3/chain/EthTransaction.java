package com.wontlost.web3.chain;

import java.math.BigInteger;
import java.util.Locale;

/** An EVM transaction returned by JSON-RPC. */
public record EthTransaction(String hash, String from, String to, String input,
        BigInteger value, Long blockNumber) {
    public EthTransaction {
        from = from == null ? null : from.toLowerCase(Locale.ROOT);
        to = to == null ? null : to.toLowerCase(Locale.ROOT);
    }
}
