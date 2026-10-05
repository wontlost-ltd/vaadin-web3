package com.wontlost.web3.chain;

import java.math.BigInteger;
import java.util.Locale;

/** An ERC-20 transfer event. */
public record Transfer(String from, String to, BigInteger amount) {
    public Transfer {
        from = from.toLowerCase(Locale.ROOT);
        to = to.toLowerCase(Locale.ROOT);
    }
}
