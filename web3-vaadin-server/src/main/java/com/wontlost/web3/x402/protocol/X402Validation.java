package com.wontlost.web3.x402.protocol;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.web3j.crypto.Keys;

public final class X402Validation {
    private static final Pattern UINT = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern NETWORK = Pattern.compile("eip155:([1-9][0-9]*)");
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern NONCE = Pattern.compile("0x[0-9a-fA-F]{64}");
    private static final Pattern SIGNATURE = Pattern.compile("0x[0-9a-fA-F]{130}");

    private X402Validation() { }

    public static BigInteger amount(String value) {
        if (value == null || !UINT.matcher(value).matches()) throw new IllegalArgumentException("invalid amount");
        BigInteger result = new BigInteger(value);
        if (result.signum() <= 0 || result.bitLength() > 256) throw new IllegalArgumentException("invalid amount");
        return result;
    }

    public static BigInteger uint(String value) {
        if (value == null || !UINT.matcher(value).matches()) throw new IllegalArgumentException("invalid integer");
        BigInteger result = new BigInteger(value);
        if (result.bitLength() > 256) throw new IllegalArgumentException("integer too large");
        return result;
    }

    public static long chainId(String network, Set<Long> allowed) {
        var matcher = NETWORK.matcher(network == null ? "" : network);
        if (!matcher.matches()) throw new IllegalArgumentException("unsupported network");
        long id;
        try { id = Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("unsupported network"); }
        if (!allowed.isEmpty() && !allowed.contains(id)) throw new IllegalArgumentException("network not allowed");
        return id;
    }

    public static String address(String value) {
        if (value == null || !ADDRESS.matcher(value).matches()) throw new IllegalArgumentException("invalid address");
        String body = value.substring(2);
        boolean lower = body.equals(body.toLowerCase(Locale.ROOT));
        boolean upper = body.equals(body.toUpperCase(Locale.ROOT));
        String checksum = Keys.toChecksumAddress(value);
        if (!lower && !upper && !checksum.equals(value)) throw new IllegalArgumentException("invalid address checksum");
        if (body.matches("0{40}")) throw new IllegalArgumentException("zero address");
        return checksum;
    }

    public static String normalizedAddress(String value) { return address(value).toLowerCase(Locale.ROOT); }
    public static void nonce(String value) {
        if (value == null || !NONCE.matcher(value).matches()) throw new IllegalArgumentException("invalid nonce");
    }
    public static void signature(String value) {
        if (value == null || !SIGNATURE.matcher(value).matches()) throw new IllegalArgumentException("invalid signature");
        int recovery = Integer.parseInt(value.substring(130), 16);
        if (recovery != 0 && recovery != 1 && recovery != 27 && recovery != 28)
            throw new IllegalArgumentException("invalid signature recovery id");
    }
}
