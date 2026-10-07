package com.wontlost.web3.test.x402;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.dev.DevWallet;
import com.wontlost.web3.test.TestWallet;
import com.wontlost.web3.x402.payment.Eip3009TypedDataFactory;
import com.wontlost.web3.x402.payment.DefaultX402PaymentService;
import com.wontlost.web3.x402.payment.FacilitatorClient;
import com.wontlost.web3.x402.payment.PaymentRecord;
import com.wontlost.web3.x402.payment.PaymentStatus;
import com.wontlost.web3.x402.payment.ResourcePolicy;
import com.wontlost.web3.x402.payment.SettlementResult;
import com.wontlost.web3.x402.payment.SettlementState;
import com.wontlost.web3.x402.payment.SupportedResponse;
import com.wontlost.web3.x402.payment.VerifyResult;
import com.wontlost.web3.x402.protocol.Eip3009Payload;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.TransferAuthorization;
import com.wontlost.web3.x402.protocol.X402Resource;
import com.wontlost.web3.x402.protocol.X402Validation;
import com.wontlost.web3.x402.store.InMemoryPaidResourceStore;

import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "ANVIL_RPC", matches = "https?://.+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class X402LocalFacilitatorAnvilIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigInteger AMOUNT = BigInteger.valueOf(1_000_000);
    private static EthRpcClient rpc;
    private static DevWallet relay;
    private static TestWallet payer;
    private static String token;
    private static ResourcePolicy policy;
    private static LocalFacilitator facilitator;

    @BeforeAll static void setUp() throws Exception {
        rpc = new EthRpcClient(System.getenv("ANVIL_RPC"));
        long chainId = rpc.chainId();
        relay = DevWallet.anvilDefault(chainId, rpc);
        payer = TestWallet.anvil(1);
        token = deployToken();
        policy = new ResourcePolicy("article:one", "1", new X402Resource("http://localhost/article", "Article", "text/plain"),
                "eip155:" + chainId, AMOUNT, token, relay.accounts().getFirst(), 86_400, "X402 Test Token", "1");
        facilitator = new LocalFacilitator(rpc, relay, chainId, token, relay.accounts().getFirst());
        send(token, "mint(address,uint256)", wordAddress(payer.address()) + wordUint(AMOUNT.multiply(BigInteger.valueOf(3))));
    }

    @Test @Order(1) void settlesOnceAndRejectsReplay() {
        var generated = new Eip3009TypedDataFactory(Clock.systemUTC(), Duration.ofSeconds(600)).create(policy, payer.address());
        PaymentPayload payload = new PaymentPayload(2, policy.resource(), policy.requirements(),
                new Eip3009Payload(payer.signTypedData(generated.typedDataJson()), generated.authorization()));
        BigInteger before = balance(payer.address());
        var verification = facilitator.verify(payload, policy.requirements());
        assertTrue(verification.valid(), verification.invalidReason());
        assertEquals(before, balance(payer.address()));
        var settled = facilitator.settle(payload, policy.requirements());
        assertEquals(SettlementState.SETTLED, settled.state());
        assertNotNull(settled.txHash());
        assertTrue(rpc.getTransactionReceipt(settled.txHash()).orElseThrow().logs().stream()
                .anyMatch(log -> log.address().equalsIgnoreCase(token)
                        && !log.topics().isEmpty() && log.topics().getFirst().equalsIgnoreCase(
                                "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")));
        assertEquals(before.subtract(AMOUNT), balance(payer.address()));
        assertFalse(facilitator.verify(payload, policy.requirements()).valid());
    }

    @Test @Order(2) void rejectsExpiredWrongRecipientAmountChainAssetAndInsufficientBalance() {
        long now = chainTimestamp();
        var boundaryFacilitator = new LocalFacilitator(rpc, relay, rpc.chainId(), token, policy.payTo(),
                Clock.fixed(Instant.ofEpochSecond(now + 1), ZoneOffset.UTC));
        var boundary = boundaryFacilitator.verify(
                payload(payer, policy, now, now + 300, AMOUNT, policy.payTo(), policy.network(), token), policy.requirements());
        assertFalse(boundary.valid());
        assertEquals("authorization_not_yet_valid", boundary.invalidReason());
        assertFalse(facilitator.verify(payload(payer, policy, now - 400, now - 1, AMOUNT, policy.payTo(), policy.network(), token), policy.requirements()).valid());
        assertFalse(facilitator.verify(payload(payer, policy, now - 1, now + 300, AMOUNT, TestWallet.anvil(1).address(), policy.network(), token), policy.requirements()).valid());
        assertFalse(facilitator.verify(payload(payer, policy, now - 1, now + 300, AMOUNT.add(BigInteger.ONE), policy.payTo(), policy.network(), token), policy.requirements()).valid());
        assertFalse(facilitator.verify(payload(payer, policy, now - 1, now + 300, AMOUNT, policy.payTo(), "eip155:1", token), policy.requirements()).valid());
        String otherToken = "0x0000000000000000000000000000000000000001";
        assertFalse(facilitator.verify(payload(payer, policy, now - 1, now + 300, AMOUNT, policy.payTo(), policy.network(), otherToken), policy.requirements()).valid());
        TestWallet empty = TestWallet.random();
        assertFalse(facilitator.verify(payload(empty, policy, now - 1, now + 300, AMOUNT, policy.payTo(), policy.network(), token), policy.requirements()).valid());
    }

    @Test @Order(3) void reconcilesUnknownByAuthorizationStateAndLeavesUnusedAuthorizationPending() {
        var store = new InMemoryPaidResourceStore();
        var forbiddenFacilitator = new FacilitatorClient() {
            @Override public SupportedResponse supported() { return new SupportedResponse(2, List.of()); }
            @Override public VerifyResult verify(PaymentPayload payload, com.wontlost.web3.x402.protocol.PaymentRequirements requirements) {
                return new VerifyResult(false, "not used", null);
            }
            @Override public SettlementResult settle(PaymentPayload payload, com.wontlost.web3.x402.protocol.PaymentRequirements requirements) {
                throw new AssertionError("reconcile must never settle");
            }
        };
        ChainRegistry registry = new ChainRegistry(); registry.register(rpc.chainId(), rpc);
        var service = new DefaultX402PaymentService(Map.of(policy.resourceId(), policy), store, forbiddenFacilitator,
                new Eip3009TypedDataFactory(Clock.systemUTC(), Duration.ofSeconds(600)), Clock.systemUTC(), registry);
        var settledAttempt = service.prepare(policy.resourceId(), payer.address());
        PaymentPayload settledPayload = new PaymentPayload(2, policy.resource(), settledAttempt.requirements(),
                new Eip3009Payload(payer.signTypedData(settledAttempt.typedDataJson()), settledAttempt.authorization()));
        assertEquals(SettlementState.SETTLED, facilitator.settle(settledPayload, policy.requirements()).state());
        markUnknown(store, settledAttempt.paymentId());
        assertEquals(PaymentStatus.UNKNOWN, service.reconcile(settledAttempt.paymentId()).status());
        for (int i = 0; i < 3; i++) rpc.request("evm_mine", List.of());
        var reconciled = service.reconcile(settledAttempt.paymentId());
        assertEquals(PaymentStatus.SETTLED, reconciled.status());
        assertEquals("reconciled_by_authorization_state", reconciled.failureCode());
        assertEquals(com.wontlost.web3.x402.payment.AccessDecision.ALLOW,
                service.hasAccess(policy.resourceId(), payer.address()));

        var unusedAttempt = service.prepare(policy.resourceId(), payer.address());
        markUnknown(store, unusedAttempt.paymentId());
        assertEquals(PaymentStatus.UNKNOWN, service.reconcile(unusedAttempt.paymentId()).status());

    }

    @Test @Order(4) void reconcilesExpiryAgainstChainTimeRatherThanExpiredServerClock() {
        var store = new InMemoryPaidResourceStore();
        var forbiddenFacilitator = new FacilitatorClient() {
            @Override public SupportedResponse supported() { return new SupportedResponse(2, List.of()); }
            @Override public VerifyResult verify(PaymentPayload payload, com.wontlost.web3.x402.protocol.PaymentRequirements requirements) {
                return new VerifyResult(false, "not used", null);
            }
            @Override public SettlementResult settle(PaymentPayload payload, com.wontlost.web3.x402.protocol.PaymentRequirements requirements) {
                throw new AssertionError("reconcile must never settle");
            }
        };
        ChainRegistry registry = new ChainRegistry(); registry.register(rpc.chainId(), rpc);
        Clock liveClock = Clock.systemUTC();
        long chainNow = chainTimestamp();
        long validBefore = chainNow + 60;
        String paymentId = java.util.UUID.randomUUID().toString();
        String nonce = "0x" + HexFormat.of().formatHex(java.security.SecureRandom.getSeed(32));
        Instant createdAt = liveClock.instant();
        var record = new PaymentRecord(paymentId, java.util.UUID.randomUUID().toString(), policy.resourceId(),
                payer.address().toLowerCase(java.util.Locale.ROOT), policy.network(), policy.asset().toLowerCase(java.util.Locale.ROOT),
                policy.amount().toString(), policy.version(), PaymentStatus.READY, null, null, createdAt, createdAt, null,
                nonce, Long.toString(chainNow - 600), Long.toString(validBefore), policy.payTo(), policy.amount().toString());
        store.createReady(record);
        markUnknown(store, paymentId);
        assertTrue(chainTimestamp() < validBefore);
        Clock serverPastExpiry = Clock.fixed(Instant.ofEpochSecond(validBefore + 1), ZoneOffset.UTC);
        var reconciliationService = new DefaultX402PaymentService(Map.of(policy.resourceId(), policy), store,
                forbiddenFacilitator, new Eip3009TypedDataFactory(serverPastExpiry, Duration.ZERO), serverPastExpiry, registry);
        assertEquals(PaymentStatus.UNKNOWN, reconciliationService.reconcile(paymentId).status());

        long increaseBy = validBefore - chainTimestamp() + 1;
        rpc.request("evm_increaseTime", List.of(increaseBy));
        rpc.request("evm_mine", List.of());
        assertTrue(chainTimestamp() >= validBefore);
        assertEquals(PaymentStatus.UNKNOWN, reconciliationService.reconcile(paymentId).status());
        for (int i = 0; i < 3; i++) rpc.request("evm_mine", List.of());
        var expired = reconciliationService.reconcile(paymentId);
        assertEquals(PaymentStatus.FAILED, expired.status());
        assertEquals("authorization_expired", expired.failureCode());
    }

    @Test @Order(5) void paymentServiceSettlementUnlocksResourceAccess() {
        var store = new InMemoryPaidResourceStore();
        ChainRegistry registry = new ChainRegistry();
        registry.register(rpc.chainId(), rpc);
        var service = new DefaultX402PaymentService(Map.of(policy.resourceId(), policy), store, facilitator,
                new Eip3009TypedDataFactory(Clock.systemUTC(), Duration.ofSeconds(600)), Clock.systemUTC(), registry);
        var attempt = service.prepare(policy.resourceId(), payer.address());
        PaymentPayload payload = new PaymentPayload(2, policy.resource(), attempt.requirements(),
                new Eip3009Payload(payer.signTypedData(attempt.typedDataJson()), attempt.authorization()));
        var outcome = service.verifyAndSettle(policy.resourceId(), payload);
        assertEquals(PaymentStatus.SETTLED, outcome.status());
        assertNotNull(outcome.txHash());
        assertEquals(com.wontlost.web3.x402.payment.AccessDecision.ALLOW,
                service.hasAccess(policy.resourceId(), payer.address()));
    }

    @Test @Order(6) void devWalletTypedDataRpcSignaturePaysFromItsOwnAccount() throws Exception {
        String devAddress = relay.accounts().getFirst();
        send(token, "mint(address,uint256)", wordAddress(devAddress) + wordUint(AMOUNT));
        ResourcePolicy selfPaymentPolicy = new ResourcePolicy("devwallet-self-payment", "1",
                new X402Resource("http://localhost/devwallet-self-payment", "DevWallet payment", "text/plain"),
                "eip155:" + rpc.chainId(), AMOUNT, token, devAddress, 300, "X402 Test Token", "1");
        var store = new InMemoryPaidResourceStore();
        ChainRegistry registry = new ChainRegistry();
        registry.register(rpc.chainId(), rpc);
        var service = new DefaultX402PaymentService(Map.of(selfPaymentPolicy.resourceId(), selfPaymentPolicy),
                store, facilitator, new Eip3009TypedDataFactory(Clock.systemUTC(), Duration.ofSeconds(600)),
                Clock.systemUTC(), registry);

        var attempt = service.prepare(selfPaymentPolicy.resourceId(), devAddress);
        String params = MAPPER.writeValueAsString(List.of(devAddress, attempt.typedDataJson()));
        String encodedSignature = relay.request("eth_signTypedData_v4", params).join();
        String signature = MAPPER.readTree(encodedSignature).asString();
        PaymentPayload payload = new PaymentPayload(2, selfPaymentPolicy.resource(), attempt.requirements(),
                new Eip3009Payload(signature, attempt.authorization()));
        var outcome = service.verifyAndSettle(selfPaymentPolicy.resourceId(), payload);

        assertEquals(PaymentStatus.SETTLED, outcome.status(), "DevWallet verify failure: " + outcome.failureCode()
                + ", authorization=" + attempt.authorization() + ", chainTime=" + chainTimestamp()
                + ", hostTime=" + Instant.now().getEpochSecond());
        assertNotNull(outcome.txHash());
        assertEquals(com.wontlost.web3.x402.payment.AccessDecision.ALLOW,
                service.hasAccess(selfPaymentPolicy.resourceId(), devAddress));
    }

    private static void markUnknown(InMemoryPaidResourceStore store, String paymentId) {
        PaymentRecord record = store.findByPaymentId(paymentId).orElseThrow();
        for (PaymentStatus next : List.of(PaymentStatus.VERIFIED, PaymentStatus.SETTLING, PaymentStatus.UNKNOWN)) {
            PaymentRecord update = new PaymentRecord(record.paymentId(), record.idempotencyKey(), record.resourceId(),
                    record.walletAddress(), record.network(), record.asset(), record.amount(), record.policyVersion(), next,
                    null, record.facilitator(), record.createdAt(), record.updatedAt(), "simulated_unknown", record.nonce(),
                    record.validAfter(), record.validBefore(), record.to(), record.value());
            record = store.transition(paymentId, record.status(), update);
        }
    }

    private static PaymentPayload payload(TestWallet wallet, ResourcePolicy policy, long validAfter, long validBefore,
            BigInteger value, String to, String network, String verifyingContract) {
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        TransferAuthorization authorization = new TransferAuthorization(wallet.address(), to, value.toString(),
                Long.toString(validAfter), Long.toString(validBefore), "0x" + HexFormat.of().formatHex(random));
        ResourcePolicy typedPolicy = new ResourcePolicy(policy.resourceId(), policy.version(), policy.resource(), network,
                policy.amount(), verifyingContract, policy.payTo(), 300, "X402 Test Token", "1");
        String typedData = Eip3009TypedDataFactory.typedDataJson(typedPolicy, authorization, X402Validation.chainId(network, java.util.Set.of()));
        return new PaymentPayload(2, policy.resource(), policy.requirements(), new Eip3009Payload(wallet.signTypedData(typedData), authorization));
    }

    private static String deployToken() throws Exception {
        var artifact = MAPPER.readTree(X402LocalFacilitatorAnvilIT.class.getResourceAsStream("/contracts/x402-eip3009-token-bytecode.json"));
        String bytecode = artifact.path("bytecode").asString();
        String result = relay.request("eth_sendTransaction", MAPPER.writeValueAsString(List.of(Map.of("from", relay.accounts().getFirst(), "data", bytecode, "value", "0x0")))).join();
        String hash = MAPPER.readTree(result).asString();
        var receipt = waitReceipt(hash);
        var receiptJson = rpc.request("eth_getTransactionReceipt", List.of(hash));
        assertTrue(receipt.status());
        return receiptJson.path("contractAddress").asString();
    }
    private static void send(String to, String signature, String args) throws Exception {
        String data = "0x" + selector(signature) + args;
        String json = MAPPER.writeValueAsString(List.of(Map.of("from", relay.accounts().getFirst(), "to", to, "data", data, "value", "0x0")));
        String result = relay.request("eth_sendTransaction", json).join();
        String hash = MAPPER.readTree(result).asString();
        assertTrue(waitReceipt(hash).status());
    }
    private static com.wontlost.web3.chain.TransactionReceipt waitReceipt(String hash) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            var receipt = rpc.getTransactionReceipt(hash);
            if (receipt.isPresent()) return receipt.get();
            Thread.sleep(100);
        }
        throw new IllegalStateException("Anvil transaction was not mined");
    }
    private static BigInteger balance(String address) { return new BigInteger(Numeric.cleanHexPrefix(rpc.call(token, selector("balanceOf(address)") + wordAddress(address), "latest")), 16); }
    private static long chainTimestamp() { return Long.parseLong(Numeric.cleanHexPrefix(rpc.request("eth_getBlockByNumber", List.of("latest", false)).path("timestamp").asString()), 16); }
    private static String selector(String signature) { return HexFormat.of().formatHex(Hash.sha3(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 4); }
    private static String wordAddress(String address) { return "0".repeat(24) + Numeric.cleanHexPrefix(address).toLowerCase(java.util.Locale.ROOT); }
    private static String wordUint(BigInteger value) { return String.format("%064x", value); }
}
