package com.wontlost.web3.x402.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import com.wontlost.web3.x402.payment.PaymentRecord;
import com.wontlost.web3.x402.payment.PaymentStatus;

public final class InMemoryPaidResourceStore implements PaidResourceStore {
    private final ConcurrentHashMap<String, PaymentRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idempotency = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AuthorizationNonceKey, String> authorizationNonces = new ConcurrentHashMap<>();
    private final Map<LookupKey, ConcurrentSkipListMap<IndexOrder, String>> recordsByIdentity = new ConcurrentHashMap<>();
    private final Map<LookupKey, ConcurrentSkipListMap<IndexOrder, String>> settledByIdentity = new ConcurrentHashMap<>();
    private final int maxRecords;

    public InMemoryPaidResourceStore() { this(100_000); }
    public InMemoryPaidResourceStore(int maxRecords) {
        if (maxRecords < 1) throw new IllegalArgumentException("maxRecords must be positive");
        this.maxRecords = maxRecords;
    }

    @Override public Optional<PaymentRecord> findByIdempotencyKey(String key) {
        String id = idempotency.get(key);
        return id == null ? Optional.empty() : findByPaymentId(id);
    }
    @Override public Optional<PaymentRecord> findByAuthorizationNonce(String network, String asset,
            String walletAddress, String nonce) {
        String id = authorizationNonces.get(nonceKey(network, asset, walletAddress, nonce));
        return id == null ? Optional.empty() : findByPaymentId(id);
    }
    @Override public Optional<PaymentRecord> findByPaymentId(String paymentId) { return Optional.ofNullable(records.get(paymentId)); }
    @Override public Optional<PaymentRecord> findSettled(String resourceId, String walletAddress) {
        return findLatest(settledByIdentity, new LookupKey(resourceId, normalize(walletAddress), null));
    }
    @Override public Optional<PaymentRecord> findSettled(String resourceId, String walletAddress, String policyVersion) {
        return findLatest(settledByIdentity, key(resourceId, walletAddress, policyVersion));
    }
    @Override public Optional<PaymentRecord> findLatest(String resourceId, String walletAddress, String policyVersion) {
        return findLatest(recordsByIdentity, key(resourceId, walletAddress, policyVersion));
    }

    @Override public PaymentRecord createReady(PaymentRecord record) {
        if (record.status() != PaymentStatus.READY) throw new IllegalArgumentException("initial status must be READY");
        synchronized (idempotency) {
            String existingId = idempotency.get(record.idempotencyKey());
            if (existingId != null) {
                PaymentRecord existing = records.get(existingId);
                if (!PaymentRecord.sameIntent(existing, record)) throw new IllegalStateException("idempotency conflict");
                return existing;
            }
            AuthorizationNonceKey nonceKey = nonceKey(record.network(), record.asset(),
                    record.walletAddress(), record.nonce());
            String nonceOwner = authorizationNonces.get(nonceKey);
            if (nonceOwner != null) {
                throw new IllegalStateException("authorization nonce conflict");
            }
            if (records.size() >= maxRecords) throw new IllegalStateException("payment store capacity reached");
            if (records.putIfAbsent(record.paymentId(), record) != null) throw new IllegalStateException("payment id conflict");
            idempotency.put(record.idempotencyKey(), record.paymentId());
            authorizationNonces.put(nonceKey, record.paymentId());
            add(recordsByIdentity, record);
            return record;
        }
    }

    @Override public PaymentRecord transition(String paymentId, PaymentStatus expected, PaymentRecord update) {
        if (!paymentId.equals(update.paymentId()) || !expected.canTransitionTo(update.status()))
            throw new IllegalArgumentException("invalid payment transition");
        PaymentRecord changed = records.compute(paymentId, (id, current) -> {
            if (current == null || current.status() != expected) throw new PaymentStateChangedException();
            if (!PaymentRecord.sameIntent(current, update)) throw new IllegalArgumentException("payment intent is immutable");
            if (update.status() == PaymentStatus.SETTLED) add(settledByIdentity, update);
            return update;
        });
        return changed;
    }

    @Override public List<PaymentRecord> findPending(int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        List<PaymentRecord> result = new ArrayList<>();
        for (PaymentRecord record : records.values()) {
            if ((record.status() == PaymentStatus.PENDING || record.status() == PaymentStatus.UNKNOWN
                    || record.status() == PaymentStatus.SETTLING) && result.size() < limit) result.add(record);
        }
        return List.copyOf(result);
    }

    @Override public int purgeExpired(Instant now) {
        int removed = 0;
        synchronized (idempotency) {
            for (PaymentRecord record : records.values()) {
                if (record.status() != PaymentStatus.READY || record.validBefore() == null
                        || Long.parseLong(record.validBefore()) >= now.getEpochSecond()) continue;
                if (!records.remove(record.paymentId(), record)) continue;
                idempotency.remove(record.idempotencyKey(), record.paymentId());
                authorizationNonces.remove(nonceKey(record.network(), record.asset(), record.walletAddress(), record.nonce()),
                        record.paymentId());
                remove(recordsByIdentity, record);
                removed++;
            }
        }
        return removed;
    }

    private Optional<PaymentRecord> findLatest(Map<LookupKey, ConcurrentSkipListMap<IndexOrder, String>> index, LookupKey key) {
        var entries = index.get(key);
        if (entries == null) return Optional.empty();
        var latest = entries.lastEntry();
        Optional<PaymentRecord> record = latest == null ? Optional.empty() : findByPaymentId(latest.getValue());
        if (index == settledByIdentity && record.filter(value -> value.status() == PaymentStatus.SETTLED).isEmpty())
            return Optional.empty();
        return record;
    }

    private static LookupKey key(String resourceId, String walletAddress, String policyVersion) {
        return new LookupKey(resourceId, normalize(walletAddress), policyVersion);
    }
    private static AuthorizationNonceKey nonceKey(String network, String asset, String walletAddress, String nonce) {
        return new AuthorizationNonceKey(network.toLowerCase(Locale.ROOT), asset.toLowerCase(Locale.ROOT),
                normalize(walletAddress), nonce.toLowerCase(Locale.ROOT));
    }
    private static String normalize(String walletAddress) { return walletAddress.toLowerCase(Locale.ROOT); }
    private static IndexOrder order(PaymentRecord record) { return new IndexOrder(record.createdAt(), record.paymentId()); }

    private static void add(Map<LookupKey, ConcurrentSkipListMap<IndexOrder, String>> index, PaymentRecord record) {
        addOne(index, new LookupKey(record.resourceId(), record.walletAddress(), record.policyVersion()), record);
        addOne(index, new LookupKey(record.resourceId(), record.walletAddress(), null), record);
    }
    private static void addOne(Map<LookupKey, ConcurrentSkipListMap<IndexOrder, String>> index, LookupKey key,
            PaymentRecord record) {
        index.computeIfAbsent(key, ignored -> new ConcurrentSkipListMap<>()).put(order(record), record.paymentId());
    }
    private static void remove(Map<LookupKey, ConcurrentSkipListMap<IndexOrder, String>> index, PaymentRecord record) {
        removeOne(index, new LookupKey(record.resourceId(), record.walletAddress(), record.policyVersion()), record);
        removeOne(index, new LookupKey(record.resourceId(), record.walletAddress(), null), record);
    }
    private static void removeOne(Map<LookupKey, ConcurrentSkipListMap<IndexOrder, String>> index, LookupKey key,
            PaymentRecord record) {
        var entries = index.get(key);
        if (entries == null) return;
        entries.remove(order(record), record.paymentId());
        if (entries.isEmpty()) index.remove(key, entries);
    }

    private record LookupKey(String resourceId, String walletAddress, String policyVersion) { }
    private record AuthorizationNonceKey(String network, String asset, String walletAddress, String nonce) { }
    private record IndexOrder(Instant createdAt, String paymentId) implements Comparable<IndexOrder> {
        @Override public int compareTo(IndexOrder other) {
            int byCreatedAt = createdAt.compareTo(other.createdAt);
            return byCreatedAt == 0 ? paymentId.compareTo(other.paymentId) : byCreatedAt;
        }
    }
}
