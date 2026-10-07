package com.wontlost.web3.siwe;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;


import com.wontlost.web3.chain.ChainRegistry;

/**
 * Verifies EIP-4361 personal_sign messages.
 * <p>
 * Signatures of externally owned accounts are verified locally with ECDSA recovery. When a {@link ChainRegistry} is
 * supplied and the message's chain has an RPC client, signatures that do not recover to the message address are
 * also checked on chain with {@link SignatureValidator}, which accepts smart-contract wallets
 * (<a href="https://eips.ethereum.org/EIPS/eip-1271">ERC-1271</a>), including wallets that are not deployed yet
 * (<a href="https://eips.ethereum.org/EIPS/eip-6492">ERC-6492</a>). That check performs one blocking
 * {@code eth_call}, bounded by the transport timeout.
 */
public final class SiweVerifier {

    private final NonceStore nonces;
    private final Clock clock;
    private final ChainRegistry chains;

    /** Creates a verifier for externally owned accounts only, using the supplied nonce store and clock. */
    public SiweVerifier(NonceStore nonces, Clock clock) {
        this(nonces, clock, null);
    }

    /**
     * Creates a verifier that also accepts smart-contract wallet signatures on the chains registered in
     * {@code chains}; a {@code null} registry verifies externally owned accounts only.
     */
    public SiweVerifier(NonceStore nonces, Clock clock, ChainRegistry chains) {
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.chains = chains;
    }

    /** Verifies a SIWE message and consumes its nonce only after all checks succeed. */
    public VerifiedSignIn verify(String message, String signatureHex, SiweExpectations expected) {
        SiweMessage parsed = SiweMessage.parse(message);
        if (!parsed.getDomain().equals(expected.domain())) {
            throw failure(SiweException.Reason.DOMAIN_MISMATCH, "SIWE domain did not match");
        }
        if (expected.uri() != null && !expected.uri().equals(parsed.getUri())) {
            throw failure(SiweException.Reason.URI_MISMATCH, "SIWE URI did not match");
        }
        if (!expected.allowedChainIds().isEmpty() && !expected.allowedChainIds().contains(parsed.getChainId())) {
            throw failure(SiweException.Reason.CHAIN_NOT_ALLOWED, "SIWE chain is not allowed");
        }

        Instant now = clock.instant();
        if (parsed.getIssuedAt().isAfter(now.plus(Duration.ofMinutes(1)))) {
            throw failure(SiweException.Reason.NOT_YET_VALID, "SIWE issuedAt is in the future");
        }
        if (parsed.getExpirationTime() != null && !parsed.getExpirationTime().isAfter(now)) {
            throw failure(SiweException.Reason.EXPIRED, "SIWE message has expired");
        }
        if (parsed.getNotBefore() != null && parsed.getNotBefore().isAfter(now)) {
            throw failure(SiweException.Reason.NOT_YET_VALID, "SIWE message is not yet valid");
        }
        if (expected.maxAge() != null && !parsed.getIssuedAt().isAfter(now.minus(expected.maxAge()))) {
            throw failure(SiweException.Reason.TOO_OLD, "SIWE message is too old");
        }

        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        if (!nonces.isActive(parsed.getNonce())) {
            throw failure(SiweException.Reason.NONCE_INVALID, "SIWE nonce is invalid or already used");
        }
        if (!EvmPersonalSignatureVerifier.verify(parsed.getAddress(), parsed.getChainId(), messageBytes,
                signatureHex, chains)) {
            throw failure(SiweException.Reason.ADDRESS_MISMATCH, "Signature does not match SIWE address");
        }
        if (!nonces.consume(parsed.getNonce())) {
            throw failure(SiweException.Reason.NONCE_INVALID, "SIWE nonce is invalid or already used");
        }
        return new VerifiedSignIn(parsed.getAddress(), parsed.getChainId(), parsed, now);
    }

    private static SiweException failure(SiweException.Reason reason, String message) {
        return new SiweException(reason, message);
    }
}
