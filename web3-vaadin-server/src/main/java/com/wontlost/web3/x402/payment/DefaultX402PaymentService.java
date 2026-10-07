package com.wontlost.web3.x402.payment;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;

import com.wontlost.web3.x402.facilitator.FacilitatorException;
import com.wontlost.web3.x402.facilitator.FacilitatorFailure;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;
import com.wontlost.web3.x402.protocol.TransferAuthorization;
import com.wontlost.web3.x402.protocol.X402Resource;
import com.wontlost.web3.x402.protocol.X402Validation;
import com.wontlost.web3.x402.store.PaidResourceStore;
import com.wontlost.web3.x402.store.PaymentStateChangedException;

public final class DefaultX402PaymentService implements X402PaymentService {
    private final Map<String, ResourcePolicy> policies;
    private final PaidResourceStore store;
    private final FacilitatorClient facilitator;
    private final Eip3009TypedDataFactory typedDataFactory;
    private final Clock clock;
    private final ChainRegistry chains;
    private final Duration settlingRecoveryTimeout;
    private final int reconcileConfirmations;

    public DefaultX402PaymentService(Map<String, ResourcePolicy> policies, PaidResourceStore store,
            FacilitatorClient facilitator, Eip3009TypedDataFactory typedDataFactory, Clock clock) {
        this(policies, store, facilitator, typedDataFactory, clock, null, Duration.ofSeconds(15), 3);
    }

    public DefaultX402PaymentService(Map<String, ResourcePolicy> policies, PaidResourceStore store,
            FacilitatorClient facilitator, Eip3009TypedDataFactory typedDataFactory, Clock clock, ChainRegistry chains) {
        this(policies, store, facilitator, typedDataFactory, clock, chains, Duration.ofSeconds(15), 3);
    }

    public DefaultX402PaymentService(Map<String, ResourcePolicy> policies, PaidResourceStore store,
            FacilitatorClient facilitator, Eip3009TypedDataFactory typedDataFactory, Clock clock, ChainRegistry chains,
            Duration settlingRecoveryTimeout) {
        this(policies, store, facilitator, typedDataFactory, clock, chains, settlingRecoveryTimeout, 3);
    }

    public DefaultX402PaymentService(Map<String, ResourcePolicy> policies, PaidResourceStore store,
            FacilitatorClient facilitator, Eip3009TypedDataFactory typedDataFactory, Clock clock, ChainRegistry chains,
            Duration settlingRecoveryTimeout, int reconcileConfirmations) {
        if (settlingRecoveryTimeout == null || settlingRecoveryTimeout.isZero() || settlingRecoveryTimeout.isNegative())
            throw new IllegalArgumentException("settling recovery timeout must be positive");
        if (reconcileConfirmations < 0) throw new IllegalArgumentException("reconcile confirmations must not be negative");
        this.policies = Map.copyOf(policies); this.store = store; this.facilitator = facilitator;
        this.typedDataFactory = typedDataFactory; this.clock = clock; this.chains = chains;
        this.settlingRecoveryTimeout = settlingRecoveryTimeout;
        this.reconcileConfirmations = reconcileConfirmations;
    }

    @Override public PaymentRequired createChallenge(String resourceId, URI canonicalUri) {
        ResourcePolicy policy = policy(resourceId);
        if (canonicalUri == null || !policy.resource().url().equals(canonicalUri.toString()))
            throw new IllegalArgumentException("resource URI does not match configured policy");
        return new PaymentRequired(2, null,
                new X402Resource(canonicalUri.toString(), policy.resource().description(), policy.resource().mimeType()),
                java.util.List.of(policy.requirements()), Map.of());
    }

    @Override public PaymentAttempt prepare(String resourceId, String walletAddress) {
        ResourcePolicy policy = policy(resourceId);
        String address = X402Validation.address(walletAddress);
        Instant now = clock.instant();
        store.purgeExpired(now);
        var generated = typedDataFactory.create(policy, address);
        var authorization = generated.authorization();
        String idempotencyKey = idempotencyKey(resourceId, address, policy, authorization.nonce());
        String paymentId = UUID.randomUUID().toString();
        PaymentRecord record = new PaymentRecord(paymentId, idempotencyKey, resourceId,
                address.toLowerCase(java.util.Locale.ROOT), policy.network(), policy.asset().toLowerCase(java.util.Locale.ROOT),
                policy.amount().toString(), policy.version(), PaymentStatus.READY, null, null, now, now, null,
                authorization.nonce(), authorization.validAfter(), authorization.validBefore(), authorization.to(),
                authorization.value());
        return attempt(store.createReady(record), policy);
    }

    @Override public PaymentOutcome verifyAndSettle(String resourceId, PaymentPayload payload) {
        if (payload == null || payload.payload() == null || payload.payload().authorization() == null)
            throw new IllegalArgumentException("payment payload is incomplete");
        ResourcePolicy policy = policy(resourceId);
        TransferAuthorization authorization = payload.payload().authorization();
        String idempotencyKey = idempotencyKey(resourceId, authorization.from(), policy, authorization.nonce());
        PaymentRecord current = store.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException("unknown payment nonce"));
        verifyIntent(payload, policy, resourceId, current);

        while (true) {
            switch (current.status()) {
                case READY -> {
                    if (clock.instant().getEpochSecond() > Long.parseLong(current.validBefore())) {
                        return outcome(transitionOrReload(current, PaymentStatus.FAILED, null, "authorization_expired"));
                    }
                    VerifyResult verification;
                    try { verification = facilitator.verify(payload, policy.requirements()); }
                    catch (FacilitatorException exception) {
                        if (exception.failure() != FacilitatorFailure.REJECTED) throw exception;
                        return outcome(transitionOrReload(current, PaymentStatus.FAILED, null, "payment_rejected"));
                    }
                    if (!verification.valid() || verification.payer() != null
                            && !verification.payer().equalsIgnoreCase(authorization.from())) {
                        String reason = verification.invalidReason() == null
                                ? "payment_rejected" : stableCode(verification.invalidReason());
                        return outcome(transitionOrReload(current, PaymentStatus.FAILED, null, reason));
                    }
                    current = transitionOrReload(current, PaymentStatus.VERIFIED, null, null);
                }
                case VERIFIED -> {
                    PaymentRecord claimed = transitionIfCurrent(current, PaymentStatus.SETTLING, null, null);
                    if (claimed == null) {
                        current = latest(current.paymentId());
                        continue;
                    }
                    return settle(payload, policy, claimed);
                }
                default -> { return outcome(current); }
            }
        }
    }

    @Override public PaymentOutcome reconcile(String paymentId) {
        PaymentRecord current = latest(paymentId);
        if (current.status() == PaymentStatus.SETTLING) {
            if (current.updatedAt().plus(settlingRecoveryTimeout).isAfter(clock.instant())) return outcome(current);
            try { current = transition(current, PaymentStatus.UNKNOWN, null, "settlement_recovery_timeout"); }
            catch (PaymentStateChangedException exception) { current = latest(current.paymentId()); }
        }
        if (current.status() != PaymentStatus.PENDING && current.status() != PaymentStatus.UNKNOWN)
            return outcome(current);
        ResourcePolicy policy = policy(current.resourceId());
        verifyStoredPolicy(current, policy);
        PaymentRecord recovered = current.txHash() == null ? reconcileAuthorization(current)
                : reconcileReceipt(current);
        return outcome(recovered);
    }

    private PaymentRecord reconcileReceipt(PaymentRecord current) {
        long chainId = X402Validation.chainId(current.network(), java.util.Set.of());
        if (chains == null) throw new IllegalStateException("ChainRegistry is required to reconcile a transaction receipt");
        var client = chains.get(chainId).orElseThrow(() -> new IllegalStateException("no RPC client registered for payment network"));
        var receipt = client.getTransactionReceipt(current.txHash());
        if (receipt.isEmpty()) return current;
        if (!current.txHash().equalsIgnoreCase(receipt.get().transactionHash())) return current;
        ReconciliationBlock block = confirmedBlock(client);
        if (receipt.get().blockNumber() > block.number()) return current;
        if (!receipt.get().status()) return reconcileTransition(current, PaymentStatus.FAILED, "transaction_reverted");
        if (authorizationUsed(client, current, "0x" + Long.toHexString(block.number())))
            return reconcileTransition(current, PaymentStatus.SETTLED, "reconciled_by_authorization_state");
        return reconcileTransition(current, PaymentStatus.FAILED, "authorization_not_used");
    }

    private PaymentRecord reconcileAuthorization(PaymentRecord current) {
        long chainId = X402Validation.chainId(current.network(), java.util.Set.of());
        if (chains == null) throw new IllegalStateException("ChainRegistry is required to reconcile authorization state");
        var client = chains.get(chainId).orElseThrow(() -> new IllegalStateException("no RPC client registered for payment network"));
        ReconciliationBlock block = confirmedBlock(client);
        String blockTag = "0x" + Long.toHexString(block.number());
        boolean used = authorizationUsed(client, current, blockTag);
        if (used) return reconcileTransition(current, PaymentStatus.SETTLED, "reconciled_by_authorization_state");
        if (block.timestamp() >= Long.parseLong(current.validBefore()))
            return reconcileTransition(current, PaymentStatus.FAILED, "authorization_expired");
        return current;
    }

    /**
     * 以确认深度对应的同一区块读取授权状态与时间。区块 B 之后的交易不能回到 B 生效；因此 B 已过期且未使用时，之后也不可能再成功使用。带交易哈希时也要求 receipt 与授权状态都达到 B，避免使用 B 之后尚未确认的授权状态。
     */
    private ReconciliationBlock confirmedBlock(EthRpcClient client) {
        var latest = client.request("eth_getBlockByNumber", java.util.List.of("latest", false));
        long latestNumber = parseHexLong(latest.path("number").asString(), "latest block number");
        long blockNumber = latestNumber - Math.min(latestNumber, reconcileConfirmations);
        var block = blockNumber == latestNumber ? latest : client.request("eth_getBlockByNumber",
                java.util.List.of("0x" + Long.toHexString(blockNumber), false));
        if (block.isNull()) throw new IllegalStateException("reconciliation block is not available");
        long actualNumber = parseHexLong(block.path("number").asString(), "reconciliation block number");
        if (actualNumber != blockNumber)
            throw new IllegalStateException("reconciliation block is not available");
        long timestamp = parseHexLong(block.path("timestamp").asString(), "reconciliation block timestamp");
        return new ReconciliationBlock(actualNumber, timestamp);
    }

    private boolean authorizationUsed(EthRpcClient client, PaymentRecord current) {
        return authorizationUsed(client, current, "latest");
    }

    private boolean authorizationUsed(EthRpcClient client, PaymentRecord current, String blockTag) {
        String data = "0x" + selector("authorizationState(address,bytes32)")
                + "0".repeat(24) + Numeric.cleanHexPrefix(current.walletAddress()).toLowerCase(java.util.Locale.ROOT)
                + Numeric.cleanHexPrefix(current.nonce()).toLowerCase(java.util.Locale.ROOT);
        String result = client.call(current.asset(), data, blockTag);
        try { return new java.math.BigInteger(Numeric.cleanHexPrefix(result), 16).signum() != 0; }
        catch (RuntimeException exception) { throw new IllegalStateException("invalid authorizationState response", exception); }
    }

    private static long parseHexLong(String value, String field) {
        try { return new java.math.BigInteger(Numeric.cleanHexPrefix(value), 16).longValueExact(); }
        catch (RuntimeException exception) { throw new IllegalStateException("invalid " + field, exception); }
    }

    private record ReconciliationBlock(long number, long timestamp) { }

    private PaymentRecord reconcileTransition(PaymentRecord current, PaymentStatus next, String marker) {
        try { return transition(current, next, current.txHash(), marker); }
        catch (PaymentStateChangedException exception) { return latest(current.paymentId()); }
    }

    private void verifyStoredPolicy(PaymentRecord record, ResourcePolicy policy) {
        if (!record.policyVersion().equals(policy.version()) || !record.network().equals(policy.network())
                || !record.asset().equalsIgnoreCase(policy.asset()) || !record.amount().equals(policy.amount().toString())
                || !record.to().equalsIgnoreCase(policy.payTo()) || !record.value().equals(policy.amount().toString()))
            throw new IllegalStateException("payment policy changed");
    }

    private static String selector(String signature) {
        return java.util.HexFormat.of().formatHex(Hash.sha3(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 4);
    }

    @Override public AccessDecision hasAccess(String resourceId, String walletAddress) {
        ResourcePolicy policy = policy(resourceId);
        String address = X402Validation.normalizedAddress(walletAddress);
        if (store.findSettled(resourceId, address, policy.version()).isPresent()) return AccessDecision.ALLOW;
        PaymentRecord latest = store.findLatest(resourceId, address, policy.version()).orElse(null);
        if (latest != null && (latest.status() == PaymentStatus.PENDING || latest.status() == PaymentStatus.UNKNOWN
                || latest.status() == PaymentStatus.SETTLING)) return AccessDecision.PAYMENT_PENDING;
        return AccessDecision.PAYMENT_REQUIRED;
    }

    private PaymentOutcome settle(PaymentPayload payload, ResourcePolicy policy, PaymentRecord settling) {
        SettlementResult result;
        try { result = facilitator.settle(payload, policy.requirements()); }
        catch (FacilitatorException exception) {
            PaymentStatus status = isUnknown(exception.failure()) ? PaymentStatus.UNKNOWN : PaymentStatus.FAILED;
            return outcome(transition(settling, status, null, failureCode(exception.failure())));
        } catch (RuntimeException exception) {
            return outcome(transition(settling, PaymentStatus.UNKNOWN, null, "settlement_unknown"));
        }
        return switch (result.state()) {
            case SETTLED -> outcome(transition(settling, PaymentStatus.SETTLED, result.txHash(), null));
            case PENDING -> outcome(transition(settling, PaymentStatus.PENDING, result.txHash(), "settlement_pending"));
            case REJECTED -> outcome(transition(settling, PaymentStatus.FAILED, result.txHash(), stableCode(result.errorCode())));
            case UNKNOWN -> outcome(transition(settling, PaymentStatus.UNKNOWN, result.txHash(), "settlement_unknown"));
        };
    }

    private void verifyIntent(PaymentPayload payload, ResourcePolicy policy, String resourceId, PaymentRecord record) {
        if (!record.resourceId().equals(resourceId) || payload.x402Version() != 2 || payload.accepted() == null
                || !policy.requirements().equals(payload.accepted())) throw new IllegalStateException("payment intent conflict");
        if (!record.policyVersion().equals(policy.version()) || !record.network().equals(policy.network())
                || !record.asset().equalsIgnoreCase(policy.asset()) || !record.amount().equals(policy.amount().toString())
                || !record.to().equalsIgnoreCase(policy.payTo()) || !record.value().equals(policy.amount().toString()))
            throw new IllegalStateException("payment policy changed");
        TransferAuthorization actual = payload.payload().authorization();
        if (!record.walletAddress().equals(X402Validation.normalizedAddress(actual.from()))
                || !record.nonce().equalsIgnoreCase(actual.nonce()) || !record.validAfter().equals(actual.validAfter())
                || !record.validBefore().equals(actual.validBefore()) || !record.to().equalsIgnoreCase(actual.to())
                || !record.value().equals(actual.value())) throw new IllegalStateException("payment intent conflict");
        if (payload.resource() != null && !policy.resource().equals(payload.resource()))
            throw new IllegalStateException("payment resource conflict");
        X402Validation.signature(payload.payload().signature());
    }

    private PaymentAttempt attempt(PaymentRecord record, ResourcePolicy policy) {
        var authorization = new TransferAuthorization(record.walletAddress(), record.to(), record.value(),
                record.validAfter(), record.validBefore(), record.nonce());
        String typedData = Eip3009TypedDataFactory.typedDataJson(policy, authorization,
                X402Validation.chainId(policy.network(), java.util.Set.of()));
        return new PaymentAttempt(record.paymentId(), policy.requirements(), typedData, authorization,
                Instant.ofEpochSecond(Long.parseLong(record.validBefore())));
    }

    private PaymentRecord transitionOrReload(PaymentRecord current, PaymentStatus status, String txHash, String failure) {
        try { return transition(current, status, txHash, failure); }
        catch (PaymentStateChangedException exception) { return latest(current.paymentId()); }
    }
    private PaymentRecord transitionIfCurrent(PaymentRecord current, PaymentStatus status, String txHash, String failure) {
        try { return transition(current, status, txHash, failure); }
        catch (PaymentStateChangedException exception) { return null; }
    }
    private PaymentRecord latest(String paymentId) {
        return store.findByPaymentId(paymentId).orElseThrow(() -> new IllegalStateException("payment record disappeared"));
    }
    private PaymentRecord transition(PaymentRecord current, PaymentStatus status, String txHash, String failure) {
        PaymentRecord update = new PaymentRecord(current.paymentId(), current.idempotencyKey(), current.resourceId(),
                current.walletAddress(), current.network(), current.asset(), current.amount(), current.policyVersion(),
                status, txHash == null ? current.txHash() : txHash, current.facilitator(), current.createdAt(),
                clock.instant(), failure, current.nonce(), current.validAfter(), current.validBefore(), current.to(),
                current.value());
        return store.transition(current.paymentId(), current.status(), update);
    }
    private ResourcePolicy policy(String resourceId) {
        ResourcePolicy value = policies.get(resourceId);
        if (value == null) throw new IllegalArgumentException("unknown resource policy");
        return value;
    }
    private PaymentOutcome outcome(PaymentRecord record) {
        return new PaymentOutcome(record.paymentId(), record.status(), record.txHash(), record.failureCode());
    }
    private String idempotencyKey(String resourceId, String from, ResourcePolicy policy, String nonce) {
        try {
            String value = resourceId + from.toLowerCase(java.util.Locale.ROOT) + policy.network()
                    + policy.asset().toLowerCase(java.util.Locale.ROOT) + nonce.toLowerCase(java.util.Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static boolean isUnknown(FacilitatorFailure failure) {
        return failure == FacilitatorFailure.TIMEOUT || failure == FacilitatorFailure.UNAVAILABLE
                || failure == FacilitatorFailure.SETTLEMENT_UNKNOWN;
    }
    private static String failureCode(FacilitatorFailure failure) { return isUnknown(failure) ? "settlement_unknown" : failure.name().toLowerCase(java.util.Locale.ROOT); }
    private static String stableCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) return "payment_rejected";
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
