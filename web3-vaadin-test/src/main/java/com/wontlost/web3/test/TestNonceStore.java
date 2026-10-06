package com.wontlost.web3.test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.wontlost.web3.siwe.NonceStore;

/** A nonce store whose entries can be inserted, expired, inspected, and consumed by a test. */
public final class TestNonceStore implements NonceStore {
    private final ConcurrentMap<String, Instant> entries = new ConcurrentHashMap<>();

    /** Issues a unique nonce with no expiry. */
    @Override
    public String issue() {
        String nonce;
        do { nonce = UUID.randomUUID().toString().replace("-", ""); }
        while (entries.putIfAbsent(nonce, Instant.MAX) != null);
        return nonce;
    }

    /** Stores and returns a caller-selected nonce with no expiry. */
    public String issue(String nonce) {
        if (nonce == null || !nonce.matches("[A-Za-z0-9]{8,}")) throw new IllegalArgumentException("Invalid nonce");
        if (entries.putIfAbsent(nonce, Instant.MAX) != null) throw new IllegalStateException("Nonce is already active");
        return nonce;
    }

    /** Sets the expiry instant of an issued nonce. */
    public void setExpiry(String nonce, Instant expiresAt) {
        if (!entries.containsKey(nonce)) throw new IllegalArgumentException("Nonce was not issued by this store");
        entries.put(nonce, expiresAt);
    }

    /** Expires an issued nonce immediately. */
    public void expire(String nonce) { setExpiry(nonce, Instant.MIN); }

    /** Returns whether the nonce is stored and has not expired. */
    @Override
    public boolean isActive(String nonce) {
        Instant expiry = entries.get(nonce);
        return expiry != null && Instant.now().isBefore(expiry);
    }

    /** Removes a nonce and returns whether it was still active. */
    @Override
    public boolean consume(String nonce) {
        Instant expiry = entries.remove(nonce);
        return expiry != null && Instant.now().isBefore(expiry);
    }
}
