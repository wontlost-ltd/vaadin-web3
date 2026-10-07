package com.wontlost.web3.x402.siwx;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.security.SecureRandom;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.siwe.EvmPersonalSignatureVerifier;
import com.wontlost.web3.siwe.SiweMessage;
import com.wontlost.web3.x402.protocol.X402Validation;

public final class SiwxVerifier {
    public static final String EXTENSION = "sign-in-with-x";
    private static final int MAX_HEADER_LENGTH = 65_536;
    private static final int MAX_JSON_BYTES = 49_152;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SiwxChallengeStore challenges;
    private final Clock clock;
    private final Duration ttl;
    private final ChainRegistry chains;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public SiwxVerifier(SiwxChallengeStore challenges, Clock clock,
            Duration ttl, ChainRegistry chains) {
        this.challenges = java.util.Objects.requireNonNull(challenges);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.ttl = java.util.Objects.requireNonNull(ttl);
        this.chains = chains;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("SIWX challenge TTL must be positive");
        }
    }

    public SiwxChallenge issue(URI configuredOrigin, List<Long> allowedChainIds) {
        return issue(configuredOrigin, allowedChainIds, "", configuredOrigin.toString());
    }

    public SiwxChallenge issue(URI configuredOrigin, List<Long> allowedChainIds, String resourceId) {
        return issue(configuredOrigin, allowedChainIds, resourceId, configuredOrigin.toString());
    }

    public SiwxChallenge issue(URI configuredOrigin, List<Long> allowedChainIds, String resourceId,
            String resourceUrl) {
        validateOrigin(configuredOrigin);
        if (allowedChainIds == null || allowedChainIds.isEmpty() || resourceUrl == null || resourceUrl.isBlank()) {
            throw new IllegalArgumentException("SIWX requires at least one allowed chain");
        }
        Instant issuedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expirationTime = issuedAt.plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        byte[] nonceBytes = new byte[16];
        RANDOM.nextBytes(nonceBytes);
        SiwxChallenge challenge = new SiwxChallenge(configuredOrigin.getRawAuthority(), configuredOrigin,
                HexFormat.of().formatHex(nonceBytes), issuedAt, expirationTime,
                "Sign in to access this resource", "1",
                allowedChainIds.stream().distinct()
                        .map(chainId -> new SupportedChain("eip155:" + chainId, "eip191")).toList(),
                List.of(resourceUrl));
        challenges.issue(challenge, resourceId);
        return challenge;
    }

    public VerifiedWallet verify(String proofHeader, URI configuredOrigin) {
        return verify(proofHeader, configuredOrigin, "");
    }

    public VerifiedWallet verify(String proofHeader, URI configuredOrigin, String resourceId) {
        SiwxChallenge issued = challengeFromProof(proofHeader, resourceId);
        return verify(proofHeader, configuredOrigin, resourceId, issued.resources().getFirst());
    }

    public VerifiedWallet verify(String proofHeader, URI configuredOrigin, String resourceId, String resourceUrl) {
        validateOrigin(configuredOrigin);
        SiwxProof proof = decode(proofHeader);
        SiweMessage message = parseMessage(proof.message());
        SiwxChallenge challenge = validateMessage(message, proof, configuredOrigin, resourceId, resourceUrl);
        boolean valid;
        try {
            valid = EvmPersonalSignatureVerifier.verify(message.getAddress(), message.getChainId(),
                    proof.message().getBytes(StandardCharsets.UTF_8), proof.signature(), chains);
        } catch (com.wontlost.web3.siwe.SiweException exception) {
            String code = exception.getReason() == com.wontlost.web3.siwe.SiweException.Reason.SIGNATURE_UNVERIFIABLE
                    ? "siwx_signature_unverifiable" : "siwx_signature_invalid";
            throw new SiwxVerificationException(code);
        } catch (RuntimeException exception) {
            throw new SiwxVerificationException("siwx_signature_unverifiable");
        }
        if (!valid) {
            throw new SiwxVerificationException("siwx_signature_invalid");
        }
        if (challenges.consume(challenge.nonce(), resourceId).isEmpty()) {
            throw new SiwxVerificationException("siwx_nonce_invalid");
        }
        return new VerifiedWallet(message.getAddress(), message.getChainId(), IdentitySource.SIWX_PROOF);
    }

    private SiwxChallenge challengeFromProof(String proofHeader, String resourceId) {
        SiweMessage message = parseMessage(decode(proofHeader).message());
        return challenges.find(message.getNonce(), resourceId)
                .orElseThrow(() -> new SiwxVerificationException("siwx_nonce_invalid"));
    }

    private SiwxChallenge validateMessage(SiweMessage message, SiwxProof proof,
            URI origin, String resourceId, String resourceUrl) {
        if (!proof.chainId().equals("eip155:" + message.getChainId())) {
            throw new SiwxVerificationException("siwx_chain_mismatch");
        }
        SiwxChallenge challenge = challenges.find(message.getNonce(), resourceId)
                .orElseThrow(() -> new SiwxVerificationException("siwx_nonce_invalid"));
        if (challenge.supportedChains().stream()
                .noneMatch(chain -> chain.chainId().equals(proof.chainId()))) {
            throw new SiwxVerificationException("siwx_chain_mismatch");
        }
        if (!message.getDomain().equals(origin.getRawAuthority()) || !message.getUri().equals(origin.toString())) {
            throw new SiwxVerificationException("siwx_origin_mismatch");
        }
        if (message.getIssuedAt().isAfter(clock.instant().plus(Duration.ofMinutes(1)))) {
            throw new SiwxVerificationException("siwx_not_yet_valid");
        }
        if (message.getExpirationTime() != null && !message.getExpirationTime().isAfter(clock.instant())) {
            throw new SiwxVerificationException("siwx_expired");
        }
        if (message.getNotBefore() != null && message.getNotBefore().isAfter(clock.instant())) {
            throw new SiwxVerificationException("siwx_not_yet_valid");
        }
        if (!challenge.domain().equals(message.getDomain()) || !challenge.uri().equals(URI.create(message.getUri()))) {
            throw new SiwxVerificationException("siwx_origin_mismatch");
        }
        if (!challenge.issuedAt().equals(message.getIssuedAt())
                || !challenge.expirationTime().equals(message.getExpirationTime())) {
            throw new SiwxVerificationException("siwx_challenge_mismatch");
        }
        if (resourceUrl == null || !challenge.resources().contains(resourceUrl)
                || !message.getResources().contains(resourceUrl)) {
            throw new SiwxVerificationException("siwx_resource_mismatch");
        }
        if (clock.instant().isBefore(challenge.issuedAt()) || !clock.instant().isBefore(challenge.expirationTime())) {
            throw new SiwxVerificationException("siwx_expired");
        }
        if (challenge.supportedChains().stream().noneMatch(chain -> chain.chainId().equals(proof.chainId()))) {
            throw new SiwxVerificationException("siwx_chain_mismatch");
        }
        return challenge;
    }

    private SiweMessage parseMessage(String value) {
        try {
            return SiweMessage.parse(value);
        } catch (RuntimeException exception) {
            throw new SiwxVerificationException("siwx_message_invalid");
        }
    }

    private SiwxProof decode(String header) {
        if (header == null || header.isBlank() || header.length() > MAX_HEADER_LENGTH
                || !header.matches("[A-Za-z0-9+/]+={0,2}")) {
            throw new SiwxVerificationException("siwx_proof_invalid");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(header.replaceFirst("=+$", ""));
            if (bytes.length > MAX_JSON_BYTES) {
                throw new SiwxVerificationException("siwx_proof_too_large");
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            var value = mapper.readTree(json);
            if (!value.isObject() || !value.path("message").isString()
                    || !value.path("signature").isString() || !value.path("chainId").isString()) {
                throw new SiwxVerificationException("siwx_proof_invalid");
            }
            return new SiwxProof(value.path("message").asString(), value.path("signature").asString(),
                    value.path("chainId").asString());
        } catch (SiwxVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SiwxVerificationException("siwx_proof_invalid");
        }
    }

    private static void validateOrigin(URI origin) {
        if (origin == null || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || origin.getPath() != null && !origin.getPath().isEmpty()) {
            throw new IllegalArgumentException("SIWX origin must be an origin URI");
        }
    }
}
