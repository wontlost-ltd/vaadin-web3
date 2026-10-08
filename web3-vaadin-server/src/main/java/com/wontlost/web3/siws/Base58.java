package com.wontlost.web3.siws;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Bitcoin 字母表的 Base58 编解码（Solana 地址与签名均使用该编码）。
 * 前导零字节编码为前导 '1'，解码时还原；拒绝字母表以外的字符。
 */
public final class Base58 {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);
    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int index = 0; index < ALPHABET.length(); index++) {
            INDEXES[ALPHABET.charAt(index)] = index;
        }
    }

    private Base58() {
    }

    public static String encode(byte[] input) {
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        StringBuilder encoded = new StringBuilder();
        BigInteger value = new BigInteger(1, input);
        while (value.signum() > 0) {
            BigInteger[] division = value.divideAndRemainder(BASE);
            encoded.append(ALPHABET.charAt(division[1].intValue()));
            value = division[0];
        }
        encoded.append("1".repeat(zeros));
        return encoded.reverse().toString();
    }

    public static byte[] decode(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Base58 input is required");
        }
        BigInteger value = BigInteger.ZERO;
        int zeros = 0;
        boolean leading = true;
        for (int position = 0; position < input.length(); position++) {
            char character = input.charAt(position);
            int digit = character < 128 ? INDEXES[character] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base58 character");
            }
            if (leading && digit == 0) {
                zeros++;
            } else {
                leading = false;
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] magnitude = value.signum() == 0 ? new byte[0] : value.toByteArray();
        int skip = magnitude.length > 1 && magnitude[0] == 0 ? 1 : 0;
        byte[] decoded = new byte[zeros + magnitude.length - skip];
        System.arraycopy(magnitude, skip, decoded, zeros, magnitude.length - skip);
        return decoded;
    }

    /** 解码并要求恰好为指定字节数（如 Solana 公钥 32 字节、签名 64 字节）。 */
    public static byte[] decode(String input, int expectedLength) {
        // n 字节最多编码为 ceil(n * log(256) / log(58)) 个字符；超长输入直接拒绝，避免对客户端输入做昂贵运算
        int maximumLength = (int) Math.ceil(expectedLength * 1.3658) + 1;
        if (input == null || input.length() > maximumLength) {
            throw new IllegalArgumentException("Base58 value is too long");
        }
        byte[] decoded = decode(input);
        if (decoded.length != expectedLength) {
            throw new IllegalArgumentException("Base58 value must decode to " + expectedLength + " bytes");
        }
        return decoded;
    }
}
