package com.wontlost.web3.siwe;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory one-time nonce store intended to be held as an application singleton. */
public final class InMemoryNonceStore implements NonceStore {

    private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> entries = new ConcurrentHashMap<>();

    /** Creates a store with a five-minute TTL and the system UTC clock. */
    public InMemoryNonceStore() {
        this(Duration.ofMinutes(5), Clock.systemUTC());
    }

    /** Creates a store with the given TTL and the system UTC clock. */
    public InMemoryNonceStore(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    /** Creates a store with an explicit TTL and clock. */
    public InMemoryNonceStore(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.ttl = ttl;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Issues a random 17-character alphanumeric nonce. */
    @Override
    public String issue() {
        cleanupExpired();
        String nonce;
        do {
            nonce = randomNonce();
        } while (entries.putIfAbsent(nonce, clock.instant().plus(ttl)) != null);
        return nonce;
    }

    /** Consumes a live nonce once and removes expired entries. */
    @Override
    public boolean consume(String nonce) {
        if (nonce == null) {
            return false;
        }
        cleanupExpired();
        Instant expiresAt = entries.remove(nonce);
        return expiresAt != null && clock.instant().isBefore(expiresAt);
    }

    /** Returns whether the nonce was issued by this store and has not expired or been consumed. */
    @Override
    public boolean isActive(String nonce) {
        Instant expiresAt = nonce == null ? null : entries.get(nonce);
        return expiresAt != null && clock.instant().isBefore(expiresAt);
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    }

    private static String randomNonce() {
        StringBuilder value = new StringBuilder(17);
        for (int i = 0; i < 17; i++) {
            value.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        }
        return value.toString();
    }
}
