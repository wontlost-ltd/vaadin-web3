package com.wontlost.web3.siwe;

/** Issues server-side nonces and consumes each nonce at most once. */
public interface NonceStore {
    /** Creates and stores a new nonce for a pending sign-in. */
    String issue();

    /** Removes a nonce and returns whether it was present and still valid. */
    boolean consume(String nonce);

    /**
     * Returns whether a nonce is currently live, without consuming it. {@link SiweVerifier} calls this before an
     * on-chain smart-contract wallet check so that requests with made-up nonces cannot trigger RPC calls.
     * Custom stores should implement it; the default returns {@code true}, which skips that protection.
     */
    default boolean isActive(String nonce) {
        return true;
    }
}
