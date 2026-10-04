package com.wontlost.web3;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Small helpers for working with web3 values on the server side.
 */
public final class Web3Utils {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final BigDecimal WEI_PER_ETHER = new BigDecimal("1000000000000000000");

    private Web3Utils() {
    }

    /** Whether the string is a syntactically valid Ethereum address. */
    public static boolean isValidAddress(String address) {
        return address != null && ADDRESS.matcher(address).matches();
    }

    /** Abbreviates an address: {@code 0x1234…abcd}. */
    public static String abbreviate(String address) {
        if (address == null || address.length() <= 10) {
            return address == null ? "" : address;
        }
        return address.substring(0, 6) + "…" + address.substring(address.length() - 4);
    }

    /** Converts an ether amount to a hex-encoded wei string for transactions. */
    public static String etherToWeiHex(BigDecimal ether) {
        BigInteger wei = ether.multiply(WEI_PER_ETHER).toBigIntegerExact();
        if (wei.signum() < 0) {
            throw new IllegalArgumentException("Amount must be non-negative");
        }
        return "0x" + wei.toString(16);
    }

    /** Converts a hex-encoded wei string (e.g. a balance) to ether. */
    public static BigDecimal weiHexToEther(String weiHex) {
        String hex = weiHex.startsWith("0x") || weiHex.startsWith("0X")
                ? weiHex.substring(2) : weiHex;
        return new BigDecimal(new BigInteger(hex, 16)).divide(WEI_PER_ETHER);
    }
}
