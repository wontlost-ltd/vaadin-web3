package com.wontlost.web3.siwe;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/** Verifies EIP-4361 personal_sign messages. EIP-1271 smart-contract wallet signatures are not supported yet. */
public final class SiweVerifier {

    private final NonceStore nonces;
    private final Clock clock;

    /** Creates a verifier using the supplied nonce store and clock. */
    public SiweVerifier(NonceStore nonces, Clock clock) {
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.clock = Objects.requireNonNull(clock, "clock");
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

        BigInteger publicKey;
        try {
            byte[] signature = Numeric.hexStringToByteArray(signatureHex);
            if (signature.length != 65) {
                throw new IllegalArgumentException("Signature must be 65 bytes");
            }
            byte v = signature[64];
            if (v == 0 || v == 1) {
                signature[64] = (byte) (v + 27);
            }
            Sign.SignatureData signatureData = new Sign.SignatureData(signature[64],
                    Arrays.copyOfRange(signature, 0, 32), Arrays.copyOfRange(signature, 32, 64));
            publicKey = Sign.signedPrefixedMessageToKey(message.getBytes(StandardCharsets.UTF_8), signatureData);
        } catch (Exception exception) {
            throw new SiweException(SiweException.Reason.SIGNATURE_INVALID, "SIWE signature is invalid", exception);
        }

        String recoveredAddress = Keys.toChecksumAddress("0x" + Keys.getAddress(publicKey));
        if (!recoveredAddress.equalsIgnoreCase(parsed.getAddress())) {
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
