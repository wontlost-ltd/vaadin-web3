package com.wontlost.web3.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookSignaturesTest {
    private static final String SECRET = "whsec_example";
    private static final String BODY = "{\"status\":\"CONFIRMED\"}";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_800_000_000), ZoneOffset.UTC);

    @Test void verifiesSignatureAndRejectsBodyTampering() {
        String signature = WebhookSignatures.sign(SECRET, CLOCK.instant().getEpochSecond(), BODY);
        assertTrue(WebhookSignatures.verify(SECRET, header(signature), BODY, Duration.ofMinutes(5), CLOCK));
        assertFalse(WebhookSignatures.verify(SECRET, header(signature), BODY + " ", Duration.ofMinutes(5), CLOCK));
    }

    @Test void rejectsExpiredTimestampAndMalformedHeader() {
        String old = WebhookSignatures.sign(SECRET, CLOCK.instant().minusSeconds(301).getEpochSecond(), BODY);
        assertFalse(WebhookSignatures.verify(SECRET, header(old), BODY, Duration.ofMinutes(5), CLOCK));
        assertFalse(WebhookSignatures.verify(SECRET, "bad", BODY, Duration.ofMinutes(5), CLOCK));
    }

    @Test void acceptsAnyMatchingV1Value() {
        String signature = WebhookSignatures.sign(SECRET, CLOCK.instant().getEpochSecond(), BODY);
        assertTrue(WebhookSignatures.verify(SECRET, "t=" + CLOCK.instant().getEpochSecond()
                + ",v1=" + "00".repeat(32) + ",v1=" + signature, BODY, Duration.ofMinutes(5), CLOCK));
    }

    private static String header(String signature) {
        return "t=" + CLOCK.instant().getEpochSecond() + ",v1=" + signature;
    }
}
