package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class SiweInteropTest {

    private static final String ADDRESS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23";
    private static final Pattern CASE = Pattern.compile("\"text\":\"((?:\\\\.|[^\"\\\\])*)\",\"signature\":\"(0x[0-9a-f]+)\"");

    @Test
    void parsesRoundTripsAndVerifiesOfficialSiweCases() throws Exception {
        String fixture = new String(getClass().getResourceAsStream("/siwe/reference-cases.json").readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher cases = CASE.matcher(fixture);
        int count = 0;
        while (cases.find()) {
            String text = cases.group(1).replace("\\n", "\n");
            String signature = cases.group(2);
            SiweMessage parsed = SiweMessage.parse(text);
            assertEquals(text, parsed.toMessage());
            assertEquals(ADDRESS, verifier().verify(text, signature,
                    SiweExpectations.forDomain("app.example.com")
                            .withUri("https://app.example.com/login")).address());
            count++;
        }
        assertEquals(3, count);
    }

    @Test
    void parsesOffsetTimestampAndPreservesItsText() {
        String text = "app.example.com wants you to sign in with your Ethereum account:\n"
                + ADDRESS + "\n\nURI: https://app.example.com/login\nVersion: 1\nChain ID: 1\n"
                + "Nonce: Abc123XYZ789qwe45\nIssued At: 2026-10-05T03:02:03+02:00";
        SiweMessage parsed = SiweMessage.parse(text);
        assertEquals(Instant.parse("2026-10-05T01:02:03Z"), parsed.getIssuedAt());
        assertEquals(text, parsed.toMessage());
    }

    private static SiweVerifier verifier() {
        NonceStore store = new NonceStore() {
            @Override
            public String issue() {
                return "Abc123XYZ789qwe45";
            }

            @Override
            public boolean consume(String nonce) {
                return "Abc123XYZ789qwe45".equals(nonce);
            }
        };
        return new SiweVerifier(store, Clock.fixed(Instant.parse("2026-10-05T01:05:00Z"), ZoneOffset.UTC));
    }
}
