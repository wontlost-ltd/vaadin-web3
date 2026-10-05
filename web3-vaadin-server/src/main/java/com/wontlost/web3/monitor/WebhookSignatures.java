package com.wontlost.web3.monitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs and verifies timestamped HMAC-SHA256 webhook payloads. */
public final class WebhookSignatures {
    private WebhookSignatures() { }

    /** Verifies a signature header against the exact HTTP request body. */
    public static boolean verify(String secret, String signatureHeader, String body, Duration tolerance, Clock clock) {
        if (secret == null || signatureHeader == null || body == null || tolerance == null || clock == null) return false;
        try {
            String timestamp = null;
            java.util.List<String> signatures = new java.util.ArrayList<>();
            for (String part : signatureHeader.split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2) return false;
                if (pair[0].equals("t")) timestamp = pair[1];
                else if (pair[0].equals("v1")) signatures.add(pair[1]);
            }
            if (timestamp == null || signatures.isEmpty()) return false;
            long epoch = Long.parseLong(timestamp);
            if (tolerance.isNegative()) return false;
            java.math.BigInteger delta = java.math.BigInteger.valueOf(clock.instant().getEpochSecond())
                    .subtract(java.math.BigInteger.valueOf(epoch)).abs();
            if (delta.compareTo(java.math.BigInteger.valueOf(tolerance.toSeconds())) > 0) return false;
            byte[] expected = HexFormat.of().parseHex(sign(secret, epoch, body));
            for (String signature : signatures) {
                byte[] supplied = HexFormat.of().parseHex(signature);
                if (MessageDigest.isEqual(expected, supplied)) return true;
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
