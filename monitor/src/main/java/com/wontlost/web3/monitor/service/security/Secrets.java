package com.wontlost.web3.monitor.service.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class Secrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Secrets() { }
    public static String create(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static boolean same(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(java.nio.charset.StandardCharsets.UTF_8), right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
