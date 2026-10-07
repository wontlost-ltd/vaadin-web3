package com.wontlost.web3.x402.siwx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import com.wontlost.web3.siwe.SiweMessage;

import tools.jackson.databind.ObjectMapper;

class SiwxVerifierTest {
    private static final URI ORIGIN = URI.create("https://merchant.example");
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final ECKeyPair KEY = ECKeyPair.create(java.math.BigInteger.ONE);
    private static final String ADDRESS = "0x" + Keys.getAddress(KEY.getPublicKey());
    private static final String RESOURCE_URL = "https://merchant.example/api/x402/quote";

    @Test
    void acceptsBoundProofOnceAndRejectsReplay() throws Exception {
        Fixture fixture = new Fixture();
        SiwxChallenge challenge = fixture.verifier.issue(ORIGIN, java.util.List.of(31337L), "quote");
        String proof = fixture.proof(challenge, ORIGIN, 31337, challenge.issuedAt(), challenge.expirationTime());

        VerifiedWallet wallet = fixture.verifier.verify(proof, ORIGIN, "quote");

        assertEquals(Keys.toChecksumAddress(ADDRESS), wallet.address());
        assertEquals(31337, wallet.chainId());
        assertEquals(IdentitySource.SIWX_PROOF, wallet.source());
        SiwxVerificationException replay = assertThrows(SiwxVerificationException.class,
                () -> fixture.verifier.verify(proof, ORIGIN, "quote"));
        assertEquals("siwx_nonce_invalid", replay.failureCode());
    }

    @Test
    void rejectsWrongOriginExpiredNotBeforeAndWrongChain() {
        assertCode("siwx_origin_mismatch", (fixture, challenge) -> fixture.proof(challenge,
                URI.create("https://attacker.example"), 31337, NOW, NOW.plusSeconds(60)));
        assertCode("siwx_expired", (fixture, challenge) -> fixture.proof(challenge,
                ORIGIN, 31337, NOW.minusSeconds(120), NOW.minusSeconds(1)));
        assertCode("siwx_not_yet_valid", (fixture, challenge) -> fixture.proofWithNotBefore(challenge,
                NOW.plusSeconds(30)));
        assertCode("siwx_chain_mismatch", (fixture, challenge) -> fixture.proof(challenge,
                ORIGIN, 1, challenge.issuedAt(), challenge.expirationTime()));
    }

    @Test
    void rejectsCrossResourceNonceUse() throws Exception {
        Fixture fixture = new Fixture();
        SiwxChallenge challenge = fixture.verifier.issue(ORIGIN, java.util.List.of(31337L), "quote");
        String proof = fixture.proof(challenge, ORIGIN, 31337, NOW, NOW.plusSeconds(60));

        SiwxVerificationException error = assertThrows(SiwxVerificationException.class,
                () -> fixture.verifier.verify(proof, ORIGIN, "other"));

        assertEquals("siwx_nonce_invalid", error.failureCode());
    }

    @Test
    void rejectsMissingOrMismatchedSignedResources() {
        Fixture fixture = new Fixture();
        SiwxChallenge challenge = fixture.verifier.issue(ORIGIN, List.of(31337L), "quote", RESOURCE_URL);
        String wrongResource = fixture.proofWithResources(challenge, List.of("https://other.example/data"));
        String missingResource = fixture.proofWithResources(challenge, List.of());

        assertEquals("siwx_resource_mismatch", assertThrows(SiwxVerificationException.class,
                () -> fixture.verifier.verify(wrongResource, ORIGIN, "quote", RESOURCE_URL)).failureCode());
        assertEquals("siwx_resource_mismatch", assertThrows(SiwxVerificationException.class,
                () -> fixture.verifier.verify(missingResource, ORIGIN, "quote", RESOURCE_URL)).failureCode());
    }

    @Test
    void concurrentVerificationConsumesTheChallengeOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        SiwxChallenge challenge = fixture.verifier.issue(ORIGIN, List.of(31337L), "quote", RESOURCE_URL);
        String proof = fixture.proof(challenge, ORIGIN, 31337, challenge.issuedAt(), challenge.expirationTime());
        var task = (java.util.concurrent.Callable<Boolean>) () -> {
            try {
                fixture.verifier.verify(proof, ORIGIN, "quote", RESOURCE_URL);
                return true;
            } catch (SiwxVerificationException exception) {
                return false;
            }
        };

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(task, task));
            long successes = results.stream().filter(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).count();
            assertEquals(1, successes);
        }
    }

    @Test
    void rejectsChallengeWhenCapacityIsFullWithoutEvictingLiveEntry() {
        Fixture fixture = new Fixture(new InMemorySiwxChallengeStore(1));
        SiwxChallenge first = fixture.verifier.issue(ORIGIN, List.of(31337L), "first", RESOURCE_URL);

        assertThrows(SiwxChallengeCapacityException.class,
                () -> fixture.verifier.issue(ORIGIN, List.of(31337L), "second", RESOURCE_URL));
        assertTrue(fixture.store.find(first.nonce(), "first").isPresent());
    }

    @Test
    void prunesExpiredChallengesUsingInjectedClock() {
        // 注入时钟位于远未来，与系统时钟无关；第二个挑战的签发时间故意早于注入时钟，确认清理不依赖挑战自带时间
        Instant first = Instant.parse("2099-01-01T00:00:00Z");
        Clock clock = Clock.fixed(first.plus(Duration.ofHours(1)), ZoneOffset.UTC);
        InMemorySiwxChallengeStore store = new InMemorySiwxChallengeStore(1, clock);
        store.issue(challenge("expired-nonce", first, first.plus(Duration.ofMinutes(5))), "quote");

        Instant earlierIssue = first.plus(Duration.ofMinutes(1));
        store.issue(challenge("live-nonce", earlierIssue, first.plus(Duration.ofHours(2))), "quote");

        assertTrue(store.find("expired-nonce", "quote").isEmpty());
        assertTrue(store.find("live-nonce", "quote").isPresent());
    }

    @Test
    void rejectsChallengeThatDoesNotExpireAfterIssue() {
        InMemorySiwxChallengeStore store = new InMemorySiwxChallengeStore(1, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> store.issue(challenge("bad", NOW, NOW), "quote"));
    }

    private static SiwxChallenge challenge(String nonce, Instant issuedAt, Instant expirationTime) {
        return new SiwxChallenge(ORIGIN.getHost(), ORIGIN, nonce, issuedAt, expirationTime, "statement", "1",
                List.of(), List.of(RESOURCE_URL));
    }

    private static void assertCode(String code, InvalidProof proofFactory) {
        Fixture fixture = new Fixture();
        SiwxChallenge challenge = fixture.verifier.issue(ORIGIN, java.util.List.of(31337L), "quote");
        String proof = proofFactory.create(fixture, challenge);
        SiwxVerificationException error = assertThrows(SiwxVerificationException.class,
                () -> fixture.verifier.verify(proof, ORIGIN, "quote"));
        assertEquals(code, error.failureCode());
    }

    @FunctionalInterface
    private interface InvalidProof {
        String create(Fixture fixture, SiwxChallenge challenge);
    }

    private static final class Fixture {
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final InMemorySiwxChallengeStore store;
        private final SiwxVerifier verifier;
        private final ObjectMapper mapper = new ObjectMapper();

        private Fixture() {
            this(new InMemorySiwxChallengeStore());
        }

        private Fixture(InMemorySiwxChallengeStore store) {
            this.store = store;
            verifier = new SiwxVerifier(store, clock, Duration.ofMinutes(5), null);
        }

        private String proof(SiwxChallenge challenge, URI origin, long chainId, Instant issuedAt, Instant expiration) {
            SiweMessage message = SiweMessage.builder().scheme(origin.getScheme())
                    .domain(origin.getRawAuthority()).address(ADDRESS).statement(challenge.statement())
                    .uri(origin.toString()).chainId(chainId).nonce(challenge.nonce()).issuedAt(issuedAt)
                    .expirationTime(expiration).resource(challenge.resources().getFirst()).build();
            return encode(message.toMessage(), sign(message.toMessage()), "eip155:" + chainId);
        }

        private String proofWithNotBefore(SiwxChallenge challenge, Instant notBefore) {
            SiweMessage message = SiweMessage.builder().scheme(ORIGIN.getScheme())
                    .domain(ORIGIN.getRawAuthority()).address(ADDRESS).statement(challenge.statement())
                    .uri(ORIGIN.toString()).chainId(31337).nonce(challenge.nonce()).issuedAt(NOW)
                    .expirationTime(NOW.plusSeconds(60)).notBefore(notBefore)
                    .resource(challenge.resources().getFirst()).build();
            return encode(message.toMessage(), sign(message.toMessage()), "eip155:31337");
        }

        private String proofWithResources(SiwxChallenge challenge, List<String> resources) {
            SiweMessage message = SiweMessage.builder().scheme(ORIGIN.getScheme())
                    .domain(ORIGIN.getRawAuthority()).address(ADDRESS).statement(challenge.statement())
                    .uri(ORIGIN.toString()).chainId(31337).nonce(challenge.nonce()).issuedAt(NOW)
                    .expirationTime(challenge.expirationTime()).resources(resources).build();
            return encode(message.toMessage(), sign(message.toMessage()), "eip155:31337");
        }

        private String encode(String message, String signature, String chainId) {
            try {
                byte[] bytes = mapper.writeValueAsBytes(java.util.Map.of(
                        "message", message, "signature", signature, "chainId", chainId));
                return Base64.getEncoder().encodeToString(bytes);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static String sign(String message) {
        Sign.SignatureData signature = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), KEY);
        return "0x" + HexFormat.of().formatHex(signature.getR()) + HexFormat.of().formatHex(signature.getS())
                + HexFormat.of().formatHex(signature.getV());
    }
}
