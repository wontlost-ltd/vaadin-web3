package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

class SiweVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final ECKeyPair OWNER = ECKeyPair.create(BigInteger.ONE);
    private static final ECKeyPair OTHER = ECKeyPair.create(BigInteger.TWO);
    private static final String ADDRESS = Keys.toChecksumAddress("0x" + Keys.getAddress(OWNER.getPublicKey()));

    @Test
    void verifiesRealPersonalSignSignatureAndConsumesNonce() {
        InMemoryNonceStore nonces = new InMemoryNonceStore(Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        String nonce = nonces.issue();
        String message = message(nonce, NOW, null, null, null, null).toMessage();
        VerifiedSignIn result = verifier(nonces, NOW).verify(message, sign(message, OWNER), expectations());
        assertEquals(ADDRESS, result.address());
        assertEquals(1, result.chainId());
        assertEquals(NOW, result.verifiedAt());
        assertReason(SiweException.Reason.NONCE_INVALID,
                () -> verifier(nonces, NOW).verify(message, sign(message, OWNER), expectations()));
    }

    @Test
    void acceptsRecoveryVZeroAndOne() {
        for (int recovery = 0; recovery <= 1; recovery++) {
            InMemoryNonceStore nonces = new InMemoryNonceStore(Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
            String nonce = nonces.issue();
            String message = message(nonce, NOW, null, null, null, null).toMessage();
            String signature = sign(message, OWNER);
            String normalizedV = signature.substring(0, signature.length() - 2)
                    + String.format("%02x", (Integer.parseInt(signature.substring(signature.length() - 2), 16) - 27));
            assertEquals(ADDRESS, verifier(nonces, NOW).verify(message, normalizedV, expectations()).address());
        }
    }

    @Test
    void rejectsTamperedMessageAndSignatureFromAnotherAddress() {
        InMemoryNonceStore firstStore = store();
        String first = message(firstStore.issue(), NOW, null, null, null, null).toMessage();
        assertReason(SiweException.Reason.ADDRESS_MISMATCH,
                () -> verifier(firstStore, NOW).verify(first.replace("Chain ID: 1", "Chain ID: 2"),
                        sign(first, OWNER), expectations()));

        InMemoryNonceStore secondStore = store();
        String second = message(secondStore.issue(), NOW, null, null, null, null).toMessage();
        assertReason(SiweException.Reason.ADDRESS_MISMATCH,
                () -> verifier(secondStore, NOW).verify(second, sign(second, OTHER), expectations()));
    }

    @Test
    void rejectsDomainUriAndChainMismatches() {
        InMemoryNonceStore store = store();
        String message = message(store.issue(), NOW, null, null, null, null).toMessage();
        String signature = sign(message, OWNER);
        assertReason(SiweException.Reason.DOMAIN_MISMATCH, () -> verifier(store, NOW)
                .verify(message, signature, SiweExpectations.forDomain("other.example")));
        assertReason(SiweException.Reason.URI_MISMATCH, () -> verifier(store, NOW)
                .verify(message, signature, expectations().withUri("https://other.example")));
        assertReason(SiweException.Reason.CHAIN_NOT_ALLOWED, () -> verifier(store, NOW)
                .verify(message, signature, expectations().withAllowedChainIds(Set.of(5L))));
    }

    @Test
    void rejectsForgedNonce() {
        InMemoryNonceStore store = store();
        String message = message("forgedNonce12345", NOW, null, null, null, null).toMessage();
        assertReason(SiweException.Reason.NONCE_INVALID,
                () -> verifier(store, NOW).verify(message, sign(message, OWNER), expectations()));
    }

    @Test
    void rejectsExpiredNotYetValidAndTooOldMessages() {
        InMemoryNonceStore expiredStore = store();
        String expired = message(expiredStore.issue(), NOW.minusSeconds(60), NOW, null, null, null).toMessage();
        assertReason(SiweException.Reason.EXPIRED,
                () -> verifier(expiredStore, NOW).verify(expired, sign(expired, OWNER), expectations()));

        InMemoryNonceStore futureStore = store();
        String future = message(futureStore.issue(), NOW.minusSeconds(1), null, NOW.plusSeconds(1), null, null).toMessage();
        assertReason(SiweException.Reason.NOT_YET_VALID,
                () -> verifier(futureStore, NOW).verify(future, sign(future, OWNER), expectations()));

        InMemoryNonceStore oldStore = store();
        String old = message(oldStore.issue(), NOW.minusSeconds(120), null, null, null, null).toMessage();
        assertReason(SiweException.Reason.TOO_OLD,
                () -> verifier(oldStore, NOW).verify(old, sign(old, OWNER), expectations().withMaxAge(Duration.ofSeconds(60))));

        InMemoryNonceStore farFutureStore = store();
        String farFuture = message(farFutureStore.issue(), NOW.plusSeconds(61), null, null, null, null).toMessage();
        assertReason(SiweException.Reason.NOT_YET_VALID,
                () -> verifier(farFutureStore, NOW).verify(farFuture, sign(farFuture, OWNER), expectations()));
    }

    @Test
    void parsesAndSerializesOptionalFieldsAndAbsentStatement() {
        SiweMessage complete = message("nonce123456789", NOW, NOW.plusSeconds(600), NOW.minusSeconds(5),
                "request-7", "Sign in");
        SiweMessage parsed = SiweMessage.parse(complete.toMessage());
        assertEquals(complete, parsed);
        assertEquals(2, parsed.getResources().size());

        SiweMessage withoutStatement = message("nonce123456789", NOW, null, null, null, null);
        String text = withoutStatement.toMessage();
        assertTrue(text.contains(ADDRESS + "\n\nURI: https://example.com/login"));
        assertEquals(withoutStatement, SiweMessage.parse(text));

        SiweMessage statementStartingWithUri = SiweMessage.builder().scheme("https").domain("example.com")
                .address(ADDRESS).statement("URI: this is part of the statement")
                .uri("https://example.com/login").chainId(1).nonce("nonce123456789")
                .issuedAt(NOW).build();
        assertEquals(statementStartingWithUri, SiweMessage.parse(statementStartingWithUri.toMessage()));
    }

    @Test
    void emitsGoldenEip4361Message() {
        String expected = "https://example.com wants you to sign in with your Ethereum account:\n"
                + ADDRESS + "\n\nSign in to Example.\n\n"
                + "URI: https://example.com/login\nVersion: 1\nChain ID: 1\n"
                + "Nonce: abcd1234efgh5678\nIssued At: 2026-10-05T00:00:00Z\n"
                + "Expiration Time: 2026-10-05T00:10:00Z\nNot Before: 2026-10-04T23:59:55Z\n"
                + "Request ID: request-7\nResources:\n- https://example.com/policy\n- ipfs://bafyexample";
        SiweMessage actual = SiweMessage.builder().scheme("https").domain("example.com").address(ADDRESS)
                .statement("Sign in to Example.").uri("https://example.com/login").chainId(1)
                .nonce("abcd1234efgh5678").issuedAt(NOW).expirationTime(NOW.plusSeconds(600))
                .notBefore(NOW.minusSeconds(5)).requestId("request-7")
                .resource("https://example.com/policy").resource("ipfs://bafyexample").build();
        assertEquals(expected, actual.toMessage());
    }

    @Test
    void rejectsMalformedTextAndInvalidBuilderValues() {
        assertReason(SiweException.Reason.MALFORMED, () -> SiweMessage.parse("not a SIWE message"));
        assertThrows(IllegalArgumentException.class, () -> SiweMessage.builder().domain("example.com")
                .address("invalid-address").uri("https://example.com").chainId(1)
                .nonce("nonce12345678").issuedAt(NOW).build());
        assertThrows(IllegalArgumentException.class, () -> SiweMessage.builder().domain("example.com")
                .address(ADDRESS).uri("https://example.com").chainId(1).nonce("short").issuedAt(NOW).build());
        assertThrows(IllegalArgumentException.class, () -> SiweMessage.builder().domain("example.com")
                .address(ADDRESS).statement("line one\nline two").uri("https://example.com")
                .chainId(1).nonce("nonce12345678").issuedAt(NOW).build());
    }

    @Test
    void nonceStoreUsesTtlAndOneTimeConsumption() {
        MutableClock clock = new MutableClock(NOW);
        InMemoryNonceStore store = new InMemoryNonceStore(Duration.ofSeconds(5), clock);
        String nonce = store.issue();
        assertTrue(nonce.matches("[A-Za-z0-9]{17}"));
        assertTrue(store.consume(nonce));
        assertFalse(store.consume(nonce));
        String expired = store.issue();
        clock.advance(Duration.ofSeconds(5));
        assertFalse(store.consume(expired));
    }

    private static SiweMessage message(String nonce, Instant issuedAt, Instant expiration,
            Instant notBefore, String requestId, String statement) {
        SiweMessage.Builder builder = SiweMessage.builder().scheme("https").domain("example.com")
                .address(ADDRESS).statement(statement).uri("https://example.com/login").chainId(1)
                .nonce(nonce).issuedAt(issuedAt).expirationTime(expiration).notBefore(notBefore).requestId(requestId);
        if (statement != null) {
            builder.resource("https://example.com/policy").resource("ipfs://bafyexample");
        }
        return builder.build();
    }

    private static InMemoryNonceStore store() {
        return new InMemoryNonceStore(Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SiweVerifier verifier(NonceStore store, Instant now) {
        return new SiweVerifier(store, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static SiweExpectations expectations() {
        return SiweExpectations.forDomain("example.com").withUri("https://example.com/login");
    }

    private static String sign(String message, ECKeyPair keyPair) {
        Sign.SignatureData signature = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keyPair);
        return "0x" + HexFormat.of().formatHex(signature.getR())
                + HexFormat.of().formatHex(signature.getS()) + HexFormat.of().formatHex(signature.getV());
    }

    private static void assertReason(SiweException.Reason reason, Runnable action) {
        assertEquals(reason, assertThrows(SiweException.class, action::run).getReason());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
