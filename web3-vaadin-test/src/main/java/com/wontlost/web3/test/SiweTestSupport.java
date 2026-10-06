package com.wontlost.web3.test;

import java.time.Instant;
import java.util.Objects;

import com.wontlost.web3.siwe.NonceStore;
import com.wontlost.web3.siwe.SiweMessage;

/** Builds signed SIWE messages for verifier tests without bypassing message parsing or signature checks. */
public final class SiweTestSupport {
    private final String domain;
    private final String uri;
    private final long chainId;
    private final NonceStore nonces;
    private final TestWallet wallet;

    /** Creates a fixture builder using the supplied expected SIWE values and nonce store. */
    public SiweTestSupport(String domain, String uri, long chainId, NonceStore nonces) {
        this(domain, uri, chainId, nonces, TestWallet.random());
    }

    /** Creates a fixture builder with an explicit signing wallet. */
    public SiweTestSupport(String domain, String uri, long chainId, NonceStore nonces, TestWallet wallet) {
        this.domain = Objects.requireNonNull(domain);
        this.uri = Objects.requireNonNull(uri);
        this.chainId = chainId;
        this.nonces = Objects.requireNonNull(nonces);
        this.wallet = Objects.requireNonNull(wallet);
    }

    /** Builds and signs a currently valid message for the configured domain. */
    public SignedMessage valid() { return create(domain, Instant.now(), Instant.now().plusSeconds(600)); }

    /** Builds and signs a message that is expired at the current time. */
    public SignedMessage expired() { return create(domain, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)); }

    /** Builds and signs a message with a domain different from the configured expectation. */
    public SignedMessage wrongDomain(String value) {
        if (domain.equals(value)) throw new IllegalArgumentException("The failure domain must differ from the configured domain");
        return create(value, Instant.now(), Instant.now().plusSeconds(600));
    }

    /** Returns the signer wallet. */
    public TestWallet wallet() { return wallet; }

    private SignedMessage create(String messageDomain, Instant issuedAt, Instant expiresAt) {
        String nonce = nonces.issue();
        String message = SiweMessage.builder().domain(messageDomain).address(wallet.address())
                .statement("Sign in to the test application").uri(uri).chainId(chainId).nonce(nonce)
                .issuedAt(issuedAt).expirationTime(expiresAt).build().toMessage();
        return new SignedMessage(message, wallet.signPersonalMessage(message));
    }

    /** A canonical SIWE message and its personal-sign signature. */
    public record SignedMessage(String message, String signature) { }
}
