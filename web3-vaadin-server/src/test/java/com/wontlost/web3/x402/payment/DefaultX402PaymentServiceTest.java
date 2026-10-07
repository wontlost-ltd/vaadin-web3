package com.wontlost.web3.x402.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.x402.facilitator.FacilitatorException;
import com.wontlost.web3.x402.facilitator.FacilitatorFailure;
import com.wontlost.web3.x402.protocol.Eip3009Payload;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.TransferAuthorization;
import com.wontlost.web3.x402.protocol.X402Resource;
import com.wontlost.web3.x402.store.InMemoryPaidResourceStore;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.JsonRpcTransport;
import tools.jackson.databind.ObjectMapper;

class DefaultX402PaymentServiceTest {
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);
    private final ResourcePolicy policy = policy("v1");

    @Test void onlySettledPaymentsGrantAccessAndRepeatedNonceDoesNotSettleAgain() {
        var facilitator = new FakeFacilitator();
        var store = new InMemoryPaidResourceStore();
        var service = service(store, facilitator, policy);
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        assertEquals(attempt.paymentId(), service.prepare(policy.resourceId(), ADDRESS).paymentId());
        facilitator.settlement = new SettlementResult(SettlementState.PENDING, "0xpending", "settlement_pending", null);
        var first = service.verifyAndSettle(policy.resourceId(), payload(attempt));
        assertEquals(PaymentStatus.PENDING, first.status());
        assertEquals(AccessDecision.PAYMENT_PENDING, service.hasAccess(policy.resourceId(), ADDRESS));
        assertEquals(PaymentStatus.PENDING, service.verifyAndSettle(policy.resourceId(), payload(attempt)).status());
        var restarted = service(store, facilitator, policy);
        assertEquals(PaymentStatus.PENDING, restarted.verifyAndSettle(policy.resourceId(), payload(attempt)).status());
        assertEquals(1, facilitator.settles.get());

        var settledStore = new InMemoryPaidResourceStore();
        var settledService = service(settledStore, facilitator, policy);
        var settledAttempt = settledService.prepare(policy.resourceId(), ADDRESS);
        facilitator.settlement = new SettlementResult(SettlementState.SETTLED, "0xsettled", null, null);
        assertEquals(PaymentStatus.SETTLED, settledService.verifyAndSettle(policy.resourceId(), payload(settledAttempt)).status());
        assertEquals(AccessDecision.ALLOW, settledService.hasAccess(policy.resourceId(), ADDRESS));
        var nextPolicyService = service(settledStore, facilitator, policy("v2"));
        assertEquals(AccessDecision.PAYMENT_REQUIRED, nextPolicyService.hasAccess(policy.resourceId(), ADDRESS));
        assertEquals(PaymentStatus.SETTLED, settledService.verifyAndSettle(policy.resourceId(), payload(settledAttempt)).status());
        assertEquals(2, facilitator.settles.get());
    }

    @Test void concurrentCallsWithSameNonceEnterSettleExactlyOnce() throws Exception {
        var facilitator = new FakeFacilitator(); facilitator.settleWait = new CountDownLatch(1);
        var store = new InMemoryPaidResourceStore();
        var firstService = service(store, facilitator, policy);
        var secondService = service(store, facilitator, policy);
        var attempt = firstService.prepare(policy.resourceId(), ADDRESS);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> {
                start.await();
                var service = i % 2 == 0 ? firstService : secondService;
                return service.verifyAndSettle(policy.resourceId(), payload(attempt));
            })).toList();
            start.countDown();
            assertTrue(facilitator.entered.await(3, TimeUnit.SECONDS));
            facilitator.settleWait.countDown();
            for (var future : futures) assertTrue(java.util.Set.of(PaymentStatus.VERIFIED, PaymentStatus.SETTLING,
                    PaymentStatus.SETTLED).contains(future.get(3, TimeUnit.SECONDS).status()));
        }
        assertEquals(PaymentStatus.SETTLED, firstService.verifyAndSettle(policy.resourceId(), payload(attempt)).status());
        assertEquals(1, facilitator.settles.get());
    }

    @Test void anotherServiceInstanceCanSettleFromTheSharedStore() {
        var facilitator = new FakeFacilitator();
        var store = new InMemoryPaidResourceStore();
        var preparingService = service(store, facilitator, policy);
        var settlingService = service(store, facilitator, policy);
        var attempt = preparingService.prepare(policy.resourceId(), ADDRESS);

        var outcome = settlingService.verifyAndSettle(policy.resourceId(), payload(attempt));

        assertEquals(PaymentStatus.SETTLED, outcome.status());
        assertEquals(1, facilitator.settles.get());
    }

    @Test void preparePurgesExpiredReadyRecordsBeforeCreatingAnotherAttempt() {
        var facilitator = new FakeFacilitator();
        var store = new InMemoryPaidResourceStore(1);
        var firstService = service(store, facilitator, policy);
        var first = firstService.prepare(policy.resourceId(), ADDRESS);
        Clock later = Clock.offset(clock, java.time.Duration.ofSeconds(301));
        var laterService = new DefaultX402PaymentService(Map.of(policy.resourceId(), policy), store, facilitator,
                new Eip3009TypedDataFactory(later, () -> HexFormat.of().parseHex("43".repeat(32))), later);

        var second = laterService.prepare(policy.resourceId(), ADDRESS);

        assertFalse(first.paymentId().equals(second.paymentId()));
        assertTrue(store.findByPaymentId(first.paymentId()).isEmpty());
        assertEquals(second.paymentId(), store.findByPaymentId(second.paymentId()).orElseThrow().paymentId());
    }

    @Test void verifyRejectionDoesNotSettleAndSettleTimeoutBecomesUnknown() {
        var rejected = new FakeFacilitator(); rejected.verify = new VerifyResult(false, "bad_signature", ADDRESS);
        var service = service(new InMemoryPaidResourceStore(), rejected, policy);
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        assertEquals(PaymentStatus.FAILED, service.verifyAndSettle(policy.resourceId(), payload(attempt)).status());
        assertEquals(0, rejected.settles.get());

        var unknown = new FakeFacilitator(); unknown.failure = new FacilitatorException(FacilitatorFailure.TIMEOUT, "timeout");
        var uncertain = service(new InMemoryPaidResourceStore(), unknown, policy);
        var uncertainAttempt = uncertain.prepare(policy.resourceId(), ADDRESS);
        assertEquals(PaymentStatus.UNKNOWN, uncertain.verifyAndSettle(policy.resourceId(), payload(uncertainAttempt)).status());
        assertEquals(PaymentStatus.UNKNOWN, uncertain.verifyAndSettle(policy.resourceId(), payload(uncertainAttempt)).status());
        assertEquals(1, unknown.settles.get());
        assertEquals(AccessDecision.PAYMENT_PENDING, uncertain.hasAccess(policy.resourceId(), ADDRESS));
    }

    @Test void reconcileUsesAuthorizationStateAndNeverBroadcastsAgain() {
        var facilitator = new FakeFacilitator();
        var store = new InMemoryPaidResourceStore();
        var chainRegistry = registry("0x" + "0".repeat(63) + "1");
        var service = service(store, facilitator, policy, chainRegistry);
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        PaymentRecord record = store.findByPaymentId(attempt.paymentId()).orElseThrow();
        record = transition(store, record, PaymentStatus.VERIFIED);
        record = transition(store, record, PaymentStatus.SETTLING);
        record = transition(store, record, PaymentStatus.UNKNOWN);

        var outcome = service.reconcile(attempt.paymentId());

        assertEquals(PaymentStatus.SETTLED, outcome.status());
        assertEquals("reconciled_by_authorization_state", outcome.failureCode());
        assertEquals(0, facilitator.settles.get());
        assertEquals(AccessDecision.ALLOW, service.hasAccess(policy.resourceId(), ADDRESS));
    }

    @Test void reconcileExpiresUnusedAuthorizationAndLeavesUnexpiredOneAlone() {
        var facilitator = new FakeFacilitator();
        var store = new InMemoryPaidResourceStore();
        var service = service(store, facilitator, policy, registry("0x" + "0".repeat(64), 1_700_000_000L));
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        PaymentRecord record = store.findByPaymentId(attempt.paymentId()).orElseThrow();
        record = transition(store, record, PaymentStatus.VERIFIED);
        record = transition(store, record, PaymentStatus.SETTLING);
        transition(store, record, PaymentStatus.UNKNOWN);

        assertEquals(PaymentStatus.UNKNOWN, service.reconcile(attempt.paymentId()).status());
        assertEquals(0, facilitator.settles.get());

        var expiredPolicy = new ResourcePolicy("expired", "1", policy.resource(), policy.network(), policy.amount(),
                policy.asset(), policy.payTo(), 300, "Test Token", "1");
        var expiredStore = new InMemoryPaidResourceStore();
        var prepareExpiredService = new DefaultX402PaymentService(Map.of("expired", expiredPolicy), expiredStore, facilitator,
                new Eip3009TypedDataFactory(clock, () -> HexFormat.of().parseHex("44".repeat(32))), clock,
                registry("0x" + "0".repeat(64)));
        var expired = prepareExpiredService.prepare("expired", ADDRESS);
        Clock expiredClock = Clock.offset(clock, java.time.Duration.ofSeconds(301));
        var expiredService = new DefaultX402PaymentService(Map.of("expired", expiredPolicy), expiredStore, facilitator,
                new Eip3009TypedDataFactory(expiredClock, () -> HexFormat.of().parseHex("45".repeat(32))), expiredClock,
                registry("0x" + "0".repeat(64), 1_700_000_299L));
        record = expiredStore.findByPaymentId(expired.paymentId()).orElseThrow();
        record = transition(expiredStore, record, PaymentStatus.VERIFIED);
        record = transition(expiredStore, record, PaymentStatus.SETTLING);
        transition(expiredStore, record, PaymentStatus.UNKNOWN);
        assertEquals(PaymentStatus.UNKNOWN, expiredService.reconcile(expired.paymentId()).status());
        var chainExpiredService = new DefaultX402PaymentService(Map.of("expired", expiredPolicy), expiredStore, facilitator,
                new Eip3009TypedDataFactory(expiredClock, () -> HexFormat.of().parseHex("46".repeat(32))), expiredClock,
                registry("0x" + "0".repeat(64), 1_700_000_300L));
        assertEquals(PaymentStatus.FAILED, chainExpiredService.reconcile(expired.paymentId()).status());
        assertEquals("authorization_expired", chainExpiredService.reconcile(expired.paymentId()).failureCode());
        assertEquals(0, facilitator.settles.get());
    }

    @Test void reconcileUsesOneConfirmedBlockSnapshotForAuthorizationAndExpiry() {
        var facilitator = new FakeFacilitator();
        var store = new InMemoryPaidResourceStore();
        var rpcCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var stateTags = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var blockTags = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var registry = new ChainRegistry();
        JsonRpcTransport transport = request -> {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(request);
            rpcCalls.add(root.path("method").asString() + ":" + root.path("params").toString());
            var response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0"); response.set("id", root.get("id"));
            if (root.path("method").asString().equals("eth_getBlockByNumber")) {
                String tag = root.path("params").get(0).asString();
                blockTags.add(tag);
                var block = response.putObject("result");
                if (tag.equals("latest")) {
                    block.put("number", "0xc");
                    block.put("timestamp", "0x" + Long.toHexString(1_700_000_400L));
                } else {
                    block.put("number", "0xa");
                    block.put("timestamp", "0x" + Long.toHexString(1_700_000_299L));
                }
            } else if (root.path("method").asString().equals("eth_call")) {
                stateTags.add(root.path("params").get(1).asString());
                response.put("result", "0x" + "0".repeat(64));
            } else response.putNull("result");
            return mapper.writeValueAsString(response);
        };
        registry.register(1, new EthRpcClient(transport));
        var service = service(store, facilitator, policy, registry, clock, java.time.Duration.ofSeconds(15), 2);
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        PaymentRecord record = store.findByPaymentId(attempt.paymentId()).orElseThrow();
        record = transition(store, record, PaymentStatus.VERIFIED);
        record = transition(store, record, PaymentStatus.SETTLING);
        transition(store, record, PaymentStatus.UNKNOWN);

        var result = service.reconcile(attempt.paymentId());

        assertEquals(PaymentStatus.UNKNOWN, result.status());
        assertTrue(blockTags.containsAll(java.util.List.of("latest", "0xa")));
        assertEquals(java.util.List.of("0xa"), stateTags);
        assertTrue(rpcCalls.stream().anyMatch(call -> call.startsWith("eth_call:")));
        assertEquals(0, facilitator.settles.get());
    }

    @Test void reconcileClampsInsufficientHistoryToGenesisAndZeroConfirmationsUsesLatest() {
        var stateTags = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var registry = new ChainRegistry();
        JsonRpcTransport transport = request -> {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(request);
            var response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0"); response.set("id", root.get("id"));
            if (root.path("method").asString().equals("eth_getBlockByNumber")) {
                String tag = root.path("params").get(0).asString();
                var block = response.putObject("result");
                long number = tag.equals("latest") ? 1 : 0;
                block.put("number", "0x" + Long.toHexString(number));
                block.put("timestamp", "0x" + Long.toHexString(1_700_000_000L));
            } else {
                stateTags.add(root.path("params").get(1).asString());
                response.put("result", "0x" + "0".repeat(64));
            }
            return mapper.writeValueAsString(response);
        };
        registry.register(1, new EthRpcClient(transport));
        var store = new InMemoryPaidResourceStore();
        var facilitator = new FakeFacilitator();
        var service = service(store, facilitator, policy, registry, clock, java.time.Duration.ofSeconds(15), 3);
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        PaymentRecord record = store.findByPaymentId(attempt.paymentId()).orElseThrow();
        record = transition(store, record, PaymentStatus.VERIFIED);
        record = transition(store, record, PaymentStatus.SETTLING);
        transition(store, record, PaymentStatus.UNKNOWN);

        assertEquals(PaymentStatus.UNKNOWN, service.reconcile(attempt.paymentId()).status());
        assertEquals(java.util.List.of("0x0"), stateTags);

        var zeroConfirmationStore = new InMemoryPaidResourceStore();
        var zeroConfirmationService = service(zeroConfirmationStore, facilitator, policy, registry, clock,
                java.time.Duration.ofSeconds(15), 0);
        var zeroConfirmationAttempt = zeroConfirmationService.prepare(policy.resourceId(), ADDRESS);
        record = zeroConfirmationStore.findByPaymentId(zeroConfirmationAttempt.paymentId()).orElseThrow();
        record = transition(zeroConfirmationStore, record, PaymentStatus.VERIFIED);
        record = transition(zeroConfirmationStore, record, PaymentStatus.SETTLING);
        transition(zeroConfirmationStore, record, PaymentStatus.UNKNOWN);
        assertEquals(PaymentStatus.UNKNOWN, zeroConfirmationService.reconcile(zeroConfirmationAttempt.paymentId()).status());
        assertEquals(java.util.List.of("0x0", "0x1"), stateTags);
    }

    @Test void reconcileRecoversOnlyStaleSettlingRecords() {
        var facilitator = new FakeFacilitator();
        var store = new InMemoryPaidResourceStore();
        var preparedBy = service(store, facilitator, policy);
        var attempt = preparedBy.prepare(policy.resourceId(), ADDRESS);
        PaymentRecord settling = store.findByPaymentId(attempt.paymentId()).orElseThrow();
        settling = transition(store, settling, PaymentStatus.VERIFIED);
        transition(store, settling, PaymentStatus.SETTLING);

        var recentRpcCalls = new AtomicInteger();
        var recentService = service(store, facilitator, policy,
                registry("0x" + "0".repeat(64), 1_700_000_000L, recentRpcCalls), clock,
                java.time.Duration.ofSeconds(15));
        assertEquals(PaymentStatus.SETTLING, recentService.reconcile(attempt.paymentId()).status());
        assertEquals(0, recentRpcCalls.get());

        Clock staleClock = Clock.offset(clock, java.time.Duration.ofSeconds(16));
        var staleService = service(store, facilitator, policy,
                registry("0x" + "0".repeat(64), 1_700_000_000L), staleClock, java.time.Duration.ofSeconds(15));
        var recovered = staleService.reconcile(attempt.paymentId());
        assertEquals(PaymentStatus.UNKNOWN, recovered.status());
        assertEquals("settlement_recovery_timeout", recovered.failureCode());
        assertEquals(0, facilitator.settles.get());
    }

    @Test void reconcileUsesTransactionReceiptAndWaitsWhenReceiptIsMissing() {
        for (String[] testCase : new String[][] {
                {"{\"transactionHash\":\"0xabc\",\"blockNumber\":\"0x1\",\"status\":\"0x1\",\"logs\":[]}", "SETTLED"},
                {"{\"transactionHash\":\"0xabc\",\"blockNumber\":\"0x1\",\"status\":\"0x0\",\"logs\":[]}", "FAILED"},
                {"{\"transactionHash\":\"0xabc\",\"blockNumber\":\"0x1\",\"status\":\"0x1\",\"logs\":[]}", "FAILED"}}) {
            String receipt = testCase[0];
            var store = new InMemoryPaidResourceStore();
            var facilitator = new FakeFacilitator();
            String authorizationState = testCase[1].equals("SETTLED") ? "0x" + "0".repeat(63) + "1" : "0x" + "0".repeat(64);
            var service = service(store, facilitator, policy, receiptRegistry(receipt, authorizationState));
            var attempt = service.prepare(policy.resourceId(), ADDRESS);
            markPending(store, attempt.paymentId(), "0xabc");
            var result = service.reconcile(attempt.paymentId());
            assertEquals(PaymentStatus.valueOf(testCase[1]), result.status());
        }

        var store = new InMemoryPaidResourceStore();
        var service = service(store, new FakeFacilitator(), policy, receiptRegistry("null", "0x" + "0".repeat(64)));
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        markPending(store, attempt.paymentId(), "0xabc");
        assertEquals(PaymentStatus.PENDING, service.reconcile(attempt.paymentId()).status());

        var mismatchedStore = new InMemoryPaidResourceStore();
        var mismatchedService = service(mismatchedStore, new FakeFacilitator(), policy,
                receiptRegistry("{\"transactionHash\":\"0xdef\",\"blockNumber\":\"0x1\",\"status\":\"0x1\",\"logs\":[]}",
                        "0x" + "0".repeat(63) + "1"));
        var mismatched = mismatchedService.prepare(policy.resourceId(), ADDRESS);
        markPending(mismatchedStore, mismatched.paymentId(), "0xabc");
        assertEquals(PaymentStatus.PENDING, mismatchedService.reconcile(mismatched.paymentId()).status());
    }

    @Test void reconcileWaitsForReceiptConfirmations() {
        var store = new InMemoryPaidResourceStore();
        var service = service(store, new FakeFacilitator(), policy,
                receiptRegistry("{\"transactionHash\":\"0xabc\",\"blockNumber\":\"0x62\",\"status\":\"0x1\",\"logs\":[]}",
                        "0x" + "0".repeat(63) + "1"));
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        markPending(store, attempt.paymentId(), "0xabc");

        assertEquals(PaymentStatus.PENDING, service.reconcile(attempt.paymentId()).status());
    }

    @Test void reconcileChecksReceiptAuthorizationAtTheSameConfirmedBlock() {
        var store = new InMemoryPaidResourceStore();
        var service = service(store, new FakeFacilitator(), policy,
                receiptRegistry("{\"transactionHash\":\"0xabc\",\"blockNumber\":\"0x1\",\"status\":\"0x1\",\"logs\":[]}",
                        "0x" + "0".repeat(63) + "1", "0x" + "0".repeat(64)));
        var attempt = service.prepare(policy.resourceId(), ADDRESS);
        markPending(store, attempt.paymentId(), "0xabc");

        var result = service.reconcile(attempt.paymentId());

        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals("authorization_not_used", result.failureCode());
    }

    @Test void storeEnforcesCasAndPolicyVersionIsolation() {
        var store = new InMemoryPaidResourceStore();
        var record = paymentRecord(PaymentStatus.READY, "100");
        store.createReady(record);
        var verified = paymentRecord(PaymentStatus.VERIFIED, "100");
        store.transition("p", PaymentStatus.READY, verified);
        assertThrows(IllegalStateException.class, () -> store.transition("p", PaymentStatus.READY, verified));
        assertFalse(store.findSettled("article", ADDRESS, "v2").isPresent());
        assertThrows(IllegalArgumentException.class, () -> store.transition("p", PaymentStatus.VERIFIED,
                paymentRecord(PaymentStatus.SETTLING, "101")));
    }

    private PaymentRecord paymentRecord(PaymentStatus status, String amount) {
        return new PaymentRecord("p", "key", "article", ADDRESS.toLowerCase(), "eip155:1", policy.asset().toLowerCase(),
                amount, "v1", status, null, null, clock.instant(), clock.instant(), null, "0x" + "ab".repeat(32),
                "1699999400", "1700000300", policy.payTo(), "100");
    }

    private DefaultX402PaymentService service(InMemoryPaidResourceStore store, FakeFacilitator facilitator, ResourcePolicy resourcePolicy) {
        return new DefaultX402PaymentService(Map.of(resourcePolicy.resourceId(), resourcePolicy), store, facilitator,
                new Eip3009TypedDataFactory(clock, () -> HexFormat.of().parseHex("42".repeat(32))), clock);
    }
    private DefaultX402PaymentService service(InMemoryPaidResourceStore store, FakeFacilitator facilitator,
            ResourcePolicy resourcePolicy, ChainRegistry registry) {
        return new DefaultX402PaymentService(Map.of(resourcePolicy.resourceId(), resourcePolicy), store, facilitator,
                new Eip3009TypedDataFactory(clock, () -> HexFormat.of().parseHex("42".repeat(32))), clock, registry);
    }
    private DefaultX402PaymentService service(InMemoryPaidResourceStore store, FakeFacilitator facilitator,
            ResourcePolicy resourcePolicy, ChainRegistry registry, Clock serviceClock, java.time.Duration recoveryTimeout) {
        return new DefaultX402PaymentService(Map.of(resourcePolicy.resourceId(), resourcePolicy), store, facilitator,
                new Eip3009TypedDataFactory(serviceClock, () -> HexFormat.of().parseHex("47".repeat(32))),
                serviceClock, registry, recoveryTimeout);
    }
    private DefaultX402PaymentService service(InMemoryPaidResourceStore store, FakeFacilitator facilitator,
            ResourcePolicy resourcePolicy, ChainRegistry registry, Clock serviceClock,
            java.time.Duration recoveryTimeout, int confirmations) {
        return new DefaultX402PaymentService(Map.of(resourcePolicy.resourceId(), resourcePolicy), store, facilitator,
                new Eip3009TypedDataFactory(serviceClock, () -> HexFormat.of().parseHex("48".repeat(32))),
                serviceClock, registry, recoveryTimeout, confirmations);
    }
    private static PaymentRecord transition(InMemoryPaidResourceStore store, PaymentRecord record, PaymentStatus next) {
        return transition(store, record, next, record.txHash());
    }
    private static PaymentRecord transition(InMemoryPaidResourceStore store, PaymentRecord record, PaymentStatus next,
            String txHash) {
        PaymentRecord update = new PaymentRecord(record.paymentId(), record.idempotencyKey(), record.resourceId(),
                record.walletAddress(), record.network(), record.asset(), record.amount(), record.policyVersion(), next,
                txHash, record.facilitator(), record.createdAt(), record.updatedAt(), null, record.nonce(),
                record.validAfter(), record.validBefore(), record.to(), record.value());
        return store.transition(record.paymentId(), record.status(), update);
    }
    private static void markPending(InMemoryPaidResourceStore store, String paymentId, String txHash) {
        PaymentRecord record = store.findByPaymentId(paymentId).orElseThrow();
        record = transition(store, record, PaymentStatus.VERIFIED);
        record = transition(store, record, PaymentStatus.SETTLING);
        transition(store, record, PaymentStatus.PENDING, txHash);
    }
    private static ChainRegistry registry(String callResult) {
        return registry(callResult, 1_700_000_000L);
    }
    private static ChainRegistry registry(String callResult, long blockTimestamp) {
        return registry(callResult, blockTimestamp, new AtomicInteger());
    }
    private static ChainRegistry registry(String callResult, long blockTimestamp, AtomicInteger calls) {
        ChainRegistry registry = new ChainRegistry();
        JsonRpcTransport transport = request -> {
            calls.incrementAndGet();
            var mapper = new ObjectMapper();
            var root = mapper.readTree(request);
            var response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0"); response.set("id", root.get("id"));
            if (root.path("method").asString().equals("eth_call")) response.put("result", callResult);
            else if (root.path("method").asString().equals("eth_getBlockByNumber")) {
                String tag = root.path("params").get(0).asString();
                long number = tag.equals("latest") ? 100 : Long.parseLong(tag.substring(2), 16);
                var block = response.putObject("result");
                block.put("number", "0x" + Long.toHexString(number));
                block.put("timestamp", "0x" + Long.toHexString(blockTimestamp));
            } else response.putNull("result");
            return mapper.writeValueAsString(response);
        };
        registry.register(1, new EthRpcClient(transport));
        return registry;
    }
    private static ChainRegistry receiptRegistry(String receiptJson, String authorizationState) {
        return receiptRegistry(receiptJson, authorizationState, authorizationState);
    }
    private static ChainRegistry receiptRegistry(String receiptJson, String latestAuthorizationState,
            String confirmedAuthorizationState) {
        ChainRegistry registry = new ChainRegistry();
        JsonRpcTransport transport = request -> {
            var mapper = new ObjectMapper(); var root = mapper.readTree(request);
            var response = mapper.createObjectNode(); response.put("jsonrpc", "2.0"); response.set("id", root.get("id"));
            if (root.path("method").asString().equals("eth_call")) {
                response.put("result", root.path("params").get(1).asString().equals("latest")
                        ? latestAuthorizationState : confirmedAuthorizationState);
            }
            else if (root.path("method").asString().equals("eth_getBlockByNumber")) {
                String tag = root.path("params").get(0).asString();
                long number = tag.equals("latest") ? 100 : Long.parseLong(tag.substring(2), 16);
                var block = response.putObject("result"); block.put("number", "0x" + Long.toHexString(number)); block.put("timestamp", "0x6553f100");
            }
            else response.set("result", mapper.readTree(receiptJson));
            return mapper.writeValueAsString(response);
        };
        registry.register(1, new EthRpcClient(transport)); return registry;
    }
    private PaymentPayload payload(PaymentAttempt attempt) {
        return new PaymentPayload(2, policy.resource(), attempt.requirements(),
                new Eip3009Payload("0x" + "11".repeat(64) + "1b", attempt.authorization()));
    }
    private static ResourcePolicy policy(String version) {
        return new ResourcePolicy("article", version, new X402Resource("https://example.test/article", "Paid", "text/plain"),
                "eip155:1", BigInteger.valueOf(100), "0x0000000000000000000000000000000000000001",
                "0x0000000000000000000000000000000000000002", 300, "Test Token", "1");
    }
    private static final String ADDRESS = "0x0000000000000000000000000000000000000003";

    private static final class FakeFacilitator implements FacilitatorClient {
        private final AtomicInteger verifies = new AtomicInteger();
        private final AtomicInteger settles = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch settleWait;
        private volatile RuntimeException failure;
        private volatile VerifyResult verify = new VerifyResult(true, null, ADDRESS);
        private volatile SettlementResult settlement = new SettlementResult(SettlementState.SETTLED, "0xabc", null, null);
        @Override public SupportedResponse supported() { return new SupportedResponse(2, java.util.List.of()); }
        @Override public VerifyResult verify(PaymentPayload payload, com.wontlost.web3.x402.protocol.PaymentRequirements requirements) {
            verifies.incrementAndGet(); return verify;
        }
        @Override public SettlementResult settle(PaymentPayload payload, com.wontlost.web3.x402.protocol.PaymentRequirements requirements) {
            settles.incrementAndGet(); entered.countDown();
            if (failure != null) throw failure;
            if (settleWait != null) try { settleWait.await(3, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return settlement;
        }
    }
}
