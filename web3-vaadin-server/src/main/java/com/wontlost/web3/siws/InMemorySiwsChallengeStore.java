package com.wontlost.web3.siws;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 单节点内存挑战存储：容量有界，过期挑战在签发新挑战时清理；满时拒绝签发而不淘汰有效挑战。 */
public final class InMemorySiwsChallengeStore implements SiwsChallengeStore {
    public static final int DEFAULT_MAXIMUM_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, SiwsChallenge> entries = new ConcurrentHashMap<>();
    private final int maximumEntries;
    private final Clock clock;

    public InMemorySiwsChallengeStore(Clock clock) {
        this(DEFAULT_MAXIMUM_ENTRIES, clock);
    }

    // clock 应与 SiwsVerifier 使用同一实例，避免两者时间基准不一致
    public InMemorySiwsChallengeStore(int maximumEntries, Clock clock) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximum SIWS challenge entries must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized void save(SiwsChallenge challenge) {
        Objects.requireNonNull(challenge);
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !Instant.parse(entry.expirationTime()).isAfter(now));
        if (entries.size() >= maximumEntries) {
            throw new IllegalStateException("SIWS challenge capacity reached");
        }
        if (entries.putIfAbsent(challenge.nonce(), challenge) != null) {
            throw new IllegalStateException("SIWS nonce collision");
        }
    }

    @Override
    public Optional<SiwsChallenge> find(String nonce) {
        return nonce == null ? Optional.empty() : Optional.ofNullable(entries.get(nonce));
    }

    @Override
    public boolean consume(String nonce) {
        return nonce != null && entries.remove(nonce) != null;
    }
}
