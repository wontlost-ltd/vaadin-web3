package com.wontlost.web3.x402.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.x402.protocol.X402Resource;

class Eip3009TypedDataFactoryTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);
    private static final ResourcePolicy POLICY = new ResourcePolicy("article", "v1",
            new X402Resource("https://example.test/article", "Paid", "text/plain"), "eip155:1",
            BigInteger.valueOf(100), "0x0000000000000000000000000000000000000001",
            "0x0000000000000000000000000000000000000002", 300, "Test Token", "1");

    @Test void defaultsToTenMinuteValidAfterSkewAndKeepsExpiryAtValidBefore() {
        var factory = new Eip3009TypedDataFactory(Clock.fixed(NOW, ZoneOffset.UTC));

        var generated = factory.create(POLICY, "0x0000000000000000000000000000000000000003");

        assertEquals(Long.toString(NOW.minusSeconds(600).getEpochSecond()), generated.authorization().validAfter());
        assertEquals(Long.toString(NOW.plusSeconds(300).getEpochSecond()), generated.authorization().validBefore());
        assertEquals(NOW.plusSeconds(300), generated.expiresAt());
    }

    @Test void honorsConfiguredValidAfterSkew() {
        var factory = new Eip3009TypedDataFactory(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(45));

        var generated = factory.create(POLICY, "0x0000000000000000000000000000000000000003");

        assertEquals(Long.toString(NOW.minusSeconds(45).getEpochSecond()), generated.authorization().validAfter());
        assertEquals(Long.toString(NOW.plusSeconds(300).getEpochSecond()), generated.authorization().validBefore());
        assertEquals(NOW.plusSeconds(300), generated.expiresAt());
    }

    @Test void rejectsNegativeValidAfterSkew() {
        assertThrows(IllegalArgumentException.class,
                () -> new Eip3009TypedDataFactory(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(-1)));
    }

    @Test void emitsTypedDataIntegersAsExactDecimalStringsForJsonWalletClients() throws Exception {
        var factory = new Eip3009TypedDataFactory(Clock.fixed(NOW, ZoneOffset.UTC),
                () -> java.util.HexFormat.of().parseHex("55".repeat(32)));
        var generated = factory.create(POLICY, "0x0000000000000000000000000000000000000003");
        var typedData = new tools.jackson.databind.ObjectMapper().readTree(generated.typedDataJson());

        assertTrue(typedData.path("domain").path("chainId").isTextual());
        assertEquals("1", typedData.path("domain").path("chainId").asString());
        assertTrue(typedData.path("message").path("value").isTextual());
        assertTrue(typedData.path("message").path("validAfter").isTextual());
        assertTrue(typedData.path("message").path("validBefore").isTextual());
        assertEquals("100", typedData.path("message").path("value").asString());
        assertEquals(generated.authorization().validAfter(), typedData.path("message").path("validAfter").asString());
        assertEquals(generated.authorization().validBefore(), typedData.path("message").path("validBefore").asString());
    }
}
