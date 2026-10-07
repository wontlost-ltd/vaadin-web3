package com.wontlost.web3.x402.payment;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.wontlost.web3.x402.protocol.TransferAuthorization;
import com.wontlost.web3.x402.protocol.X402Validation;

import tools.jackson.databind.ObjectMapper;

public final class Eip3009TypedDataFactory {
    private static final String TYPE = "TransferWithAuthorization";
    private static final Duration DEFAULT_VALID_AFTER_SKEW = Duration.ofSeconds(600);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Clock clock;
    private final Duration validAfterSkew;
    private final Supplier<byte[]> nonceSource;

    public Eip3009TypedDataFactory(Clock clock) { this(clock, DEFAULT_VALID_AFTER_SKEW); }
    public Eip3009TypedDataFactory(Clock clock, Duration validAfterSkew) {
        this(clock, validAfterSkew, randomNonceSource());
    }
    public Eip3009TypedDataFactory(Clock clock, Supplier<byte[]> nonceSource) {
        this(clock, DEFAULT_VALID_AFTER_SKEW, nonceSource);
    }
    public Eip3009TypedDataFactory(Clock clock, Duration validAfterSkew, Supplier<byte[]> nonceSource) {
        this.clock = Objects.requireNonNull(clock);
        this.validAfterSkew = Objects.requireNonNull(validAfterSkew);
        if (validAfterSkew.isNegative()) throw new IllegalArgumentException("validAfterSkew must not be negative");
        this.nonceSource = Objects.requireNonNull(nonceSource);
    }

    private static Supplier<byte[]> randomNonceSource() {
        return () -> { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return bytes; };
    }

    public GeneratedAuthorization create(ResourcePolicy policy, String walletAddress) {
        return create(policy, walletAddress, clock.instant());
    }

    public GeneratedAuthorization create(ResourcePolicy policy, String walletAddress, Instant now) {
        String from = X402Validation.address(walletAddress);
        long chainId = X402Validation.chainId(policy.network(), java.util.Set.of());
        Objects.requireNonNull(now);
        long validAfter;
        long validBefore;
        try {
            long skewSeconds = Math.addExact(validAfterSkew.getSeconds(), validAfterSkew.getNano() == 0 ? 0 : 1);
            validAfter = Math.subtractExact(now.getEpochSecond(), skewSeconds);
            validBefore = Math.addExact(now.getEpochSecond(), policy.maxTimeoutSeconds());
            if (validAfter < 0) throw new ArithmeticException("validAfter is negative");
        }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("authorization time overflow", exception); }
        byte[] nonceBytes = nonceSource.get();
        if (nonceBytes == null || nonceBytes.length != 32) throw new IllegalArgumentException("nonce source must return 32 bytes");
        String nonce = "0x" + HexFormat.of().formatHex(nonceBytes);
        var auth = new TransferAuthorization(from, X402Validation.address(policy.payTo()), policy.amount().toString(),
                Long.toString(validAfter), Long.toString(validBefore), nonce);
        try {
            return new GeneratedAuthorization(auth, typedDataJson(policy, auth, chainId), Instant.ofEpochSecond(validBefore));
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException("authorization time overflow", exception);
        }
    }

    long validAfterSkewSeconds() {
        try {
            return Math.addExact(validAfterSkew.getSeconds(), validAfterSkew.getNano() == 0 ? 0 : 1);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("validAfterSkew is too large", exception);
        }
    }

    public static String typedDataJson(ResourcePolicy policy, TransferAuthorization auth, long chainId) {
        Map<String, Object> typedData = new LinkedHashMap<>();
        typedData.put("domain", Map.of("name", policy.tokenName(), "version", policy.tokenVersion(),
                "chainId", Long.toString(chainId), "verifyingContract", X402Validation.address(policy.asset())));
        typedData.put("primaryType", TYPE);
        typedData.put("types", Map.of("EIP712Domain", java.util.List.of(
                        Map.of("name", "name", "type", "string"), Map.of("name", "version", "type", "string"),
                        Map.of("name", "chainId", "type", "uint256"), Map.of("name", "verifyingContract", "type", "address")),
                TYPE, java.util.List.of(Map.of("name", "from", "type", "address"), Map.of("name", "to", "type", "address"),
                        Map.of("name", "value", "type", "uint256"), Map.of("name", "validAfter", "type", "uint256"),
                        Map.of("name", "validBefore", "type", "uint256"), Map.of("name", "nonce", "type", "bytes32"))));
        typedData.put("message", Map.of("from", auth.from(), "to", auth.to(), "value", auth.value(),
                "validAfter", auth.validAfter(), "validBefore", auth.validBefore(), "nonce", auth.nonce()));
        try { return new ObjectMapper().writeValueAsString(typedData); }
        catch (Exception exception) { throw new IllegalStateException("could not encode typed data", exception); }
    }

    public record GeneratedAuthorization(TransferAuthorization authorization, String typedDataJson, Instant expiresAt) { }
}
