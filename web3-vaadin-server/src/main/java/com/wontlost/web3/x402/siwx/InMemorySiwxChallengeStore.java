package com.wontlost.web3.x402.siwx;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class InMemorySiwxChallengeStore implements SiwxChallengeStore {
    public static final int DEFAULT_MAXIMUM_ENTRIES = 10_000;

    private record Entry(SiwxChallenge challenge, String resourceId) { }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final int maximumEntries;
    private final Clock clock;

    public InMemorySiwxChallengeStore() {
        this(DEFAULT_MAXIMUM_ENTRIES);
    }

    public InMemorySiwxChallengeStore(int maximumEntries) {
        this(maximumEntries, Clock.systemUTC());
    }

    // clock 应与 SiwxVerifier 使用同一实例，避免两者时间基准不一致导致提前清理或过期项占用容量
    public InMemorySiwxChallengeStore(int maximumEntries, Clock clock) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximum SIWX challenge entries must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized void issue(SiwxChallenge challenge, String resourceId) {
        Objects.requireNonNull(challenge);
        Objects.requireNonNull(resourceId);
        if (!challenge.expirationTime().isAfter(challenge.issuedAt())) {
            throw new IllegalArgumentException("SIWX challenge must expire after it is issued");
        }
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !entry.getValue().challenge().expirationTime().isAfter(now));
        if (entries.size() >= maximumEntries) {
            throw new SiwxChallengeCapacityException();
        }
        if (entries.putIfAbsent(challenge.nonce(), new Entry(challenge, resourceId)) != null) {
            throw new IllegalStateException("SIWX nonce collision");
        }
    }

    @Override
    public Optional<SiwxChallenge> find(String nonce, String resourceId) {
        Entry entry = entries.get(nonce);
        if (entry == null || !entry.resourceId().equals(resourceId)) {
            return Optional.empty();
        }
        return Optional.of(entry.challenge());
    }

    @Override
    public Optional<SiwxChallenge> consume(String nonce, String resourceId) {
        AtomicReference<SiwxChallenge> consumed = new AtomicReference<>();
        entries.computeIfPresent(nonce, (key, entry) -> {
            if (entry.resourceId().equals(resourceId)) {
                consumed.set(entry.challenge());
                return null;
            }
            return entry;
        });
        return Optional.ofNullable(consumed.get());
    }
}
