package com.wontlost.web3.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.OnChainSupportTest;
import com.wontlost.web3.chain.Tokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PaymentVerifierTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonNode fixture;
    private OnChainSupportTest.FixtureTransport transport;
    private PaymentVerifier verifier;
    private String recipient;
    private String payer;
    private BigInteger amount;
    private String hash;

    @BeforeEach void setup() throws Exception {
        fixture = MAPPER.readTree(getClass().getResourceAsStream("/fixtures/usdc-mainnet.json").readAllBytes());
        transport = new OnChainSupportTest.FixtureTransport(fixture);
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(transport));
        verifier = new PaymentVerifier(registry, new InMemoryPaymentLedger());
        JsonNode receipt = fixture.path("simpleTransfer").path("receipt");
        JsonNode transfer = receipt.path("logs").get(0);
        recipient = "0x" + transfer.path("topics").get(2).asString().substring(26);
        payer = "0x" + transfer.path("topics").get(1).asString().substring(26);
        amount = new BigInteger(transfer.path("data").asString().substring(2), 16);
        hash = receipt.path("transactionHash").asString();
    }

    @Test void finalityIsTheSingleSourceOfConfirmationCount() {
        PaymentRequest base = new PaymentRequest(1, "0x00000000000000000000000000000000000000aa",
                "0x00000000000000000000000000000000000000bb", BigInteger.ONE, null, 3, null);
        assertEquals(Finality.confirmations(3), base.finality());
        PaymentRequest five = base.withFinality(Finality.confirmations(5));
        assertEquals(5, five.minConfirmations());
        PaymentRequest conflicting = new PaymentRequest(1, base.token(), base.recipient(), BigInteger.ONE, null, 9, null,
                Finality.confirmations(2));
        assertEquals(2, conflicting.minConfirmations());
        assertEquals(Finality.Kind.FINALIZED, base.withFinality(Finality.finalized()).finality().kind());
    }

    @Test void confirmsOnlyCorrectAndSufficientPaymentAndClaimsIdempotently() {
        PaymentRequest request = new PaymentRequest(1, Tokens.usdc(1).orElseThrow().address(), recipient, amount, payer);
        assertEquals(PaymentStatus.CONFIRMED, verifier.verify("order-a", hash, request).status());
        assertEquals(PaymentStatus.CONFIRMED, verifier.verify("order-a", hash, request).status());
        assertEquals(PaymentStatus.ALREADY_CLAIMED, verifier.verify("order-b", hash, request).status());
    }

    @Test void classifiesRecipientAmountReceiptConfirmationsAndFailedStatus() {
        String token = Tokens.usdc(1).orElseThrow().address();
        assertEquals(PaymentStatus.NO_MATCHING_TRANSFER, verifier.verify("wrong", hash,
                new PaymentRequest(1, token, "0x0000000000000000000000000000000000000001", amount, null)).status());
        assertEquals(PaymentStatus.UNDERPAID, verifier.verify("under", hash,
                new PaymentRequest(1, token, recipient, amount.add(BigInteger.ONE), null)).status());
        transport.missingReceipt = true;
        assertEquals(PaymentStatus.PENDING, verifier.verify("pending", hash,
                new PaymentRequest(1, token, recipient, amount, null)).status());
        transport.missingReceipt = false;
        assertEquals(PaymentStatus.CONFIRMING, verifier.verify("confirming", hash,
                new PaymentRequest(1, token, recipient, amount, null, 2)).status());
        var failed = ((tools.jackson.databind.node.ObjectNode) fixture.path("simpleTransfer").path("receipt")).deepCopy();
        failed.put("status", "0x0");
        transport.receipt = failed;
        assertEquals(PaymentStatus.FAILED, verifier.verify("failed", hash,
                new PaymentRequest(1, token, recipient, amount, null)).status());
    }

    @Test void verifiesRealUsdtMainnetFixture() throws Exception {
        JsonNode usdt = MAPPER.readTree(getClass().getResourceAsStream("/fixtures/usdt-mainnet.json").readAllBytes());
        JsonNode simple = usdt.path("simpleTransfer");
        JsonNode tx = simple.path("transaction");
        JsonNode log = simple.path("receipt").path("logs").get(0);
        String recipient = "0x" + log.path("topics").get(2).asString().substring(26);
        String payer = "0x" + log.path("topics").get(1).asString().substring(26);
        BigInteger amount = new BigInteger(log.path("data").asString().substring(2), 16);
        assertEquals(tx.path("input").asString(), com.wontlost.web3.chain.Erc20.transferData(recipient, amount));
        OnChainSupportTest.FixtureTransport usdtTransport = new OnChainSupportTest.FixtureTransport(usdt);
        usdtTransport.blockNumber = "0x18e97c7";
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(usdtTransport));
        PaymentVerifier usdtVerifier = new PaymentVerifier(registry, new InMemoryPaymentLedger());
        String hash = tx.path("hash").asString();
        var receipt = registry.get(1).orElseThrow().getTransactionReceipt(hash).orElseThrow();
        var parsedTransfers = com.wontlost.web3.chain.Erc20.transfers(receipt, Tokens.usdt(1).orElseThrow().address());
        assertEquals(1, parsedTransfers.size());
        assertEquals(recipient, parsedTransfers.getFirst().to());
        assertEquals(amount, parsedTransfers.getFirst().amount());
        assertEquals(com.wontlost.web3.pay.PaymentStatus.CONFIRMED, usdtVerifier.verify("usdt", hash,
                new PaymentRequest(1, Tokens.usdt(1).orElseThrow().address(), recipient, amount, payer)).status());
        assertEquals(com.wontlost.web3.pay.PaymentStatus.UNDERPAID, usdtVerifier.verify("usdt-short", hash,
                new PaymentRequest(1, Tokens.usdt(1).orElseThrow().address(), recipient, amount.add(BigInteger.ONE), payer)).status());
        assertEquals(com.wontlost.web3.pay.PaymentStatus.NO_MATCHING_TRANSFER, usdtVerifier.verify("usdc", hash,
                new PaymentRequest(1, Tokens.usdc(1).orElseThrow().address(), recipient, amount, payer)).status());
    }

    @Test void waitsForCanonicalHashAndFinalizedBlock() {
        String token = Tokens.usdc(1).orElseThrow().address();
        var request = new PaymentRequest(1, token, recipient, amount, payer, Finality.finalized());
        transport.finalizedBlockNumber = 0;
        assertEquals(PaymentStatus.CONFIRMING, verifier.verify("finality-wait", hash, request).status());
        transport.finalizedBlockNumber = Long.parseLong(transport.receiptBlockNumber().substring(2), 16);
        assertEquals(PaymentStatus.CONFIRMED, verifier.verify("finality-done", hash, request).status());
        transport.reorg = true;
        assertEquals(PaymentStatus.PENDING, verifier.verify("reorg", hash, request).status());
    }

    @Test void finalizedRpcErrorsAreNotTreatedAsConfirmation() {
        String token = Tokens.usdc(1).orElseThrow().address();
        transport.finalizedError = true;
        org.junit.jupiter.api.Assertions.assertThrows(com.wontlost.web3.chain.EthRpcException.class,
                () -> verifier.verify("finalized-error", hash,
                        new PaymentRequest(1, token, recipient, amount, payer, Finality.finalized())));
    }

    @Test void pinnedReadFailurePropagatesAndDoesNotClaimPayment() throws Exception {
        String token = Tokens.usdc(1).orElseThrow().address();
        var ledger = new InMemoryPaymentLedger();
        var primaryFixture = new OnChainSupportTest.FixtureTransport(fixture);
        com.wontlost.web3.chain.JsonRpcTransport primary = request -> {
            JsonNode parsed = MAPPER.readTree(request);
            if ("eth_getBlockByNumber".equals(parsed.path("method").asString())) throw new java.io.IOException("pinned node lost");
            return primaryFixture.send(request);
        };
        var failover = new com.wontlost.web3.chain.FailoverJsonRpcTransport(java.util.List.of(
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint("primary", primary),
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint("backup", transport)),
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Config(1, java.time.Duration.ofSeconds(30), 3));
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(failover));
        PaymentVerifier pinnedVerifier = new PaymentVerifier(registry, ledger);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> pinnedVerifier.verify("pinned-failure", hash,
                        new PaymentRequest(1, token, recipient, amount, payer)));

        PaymentVerifier recovered = new PaymentVerifier(registryFor(transport), ledger);
        assertEquals(PaymentStatus.CONFIRMED, recovered.verify("pinned-failure", hash,
                new PaymentRequest(1, token, recipient, amount, payer)).status());
    }

    @Test void rejectsTransactionsMinedBeforeTheOrderWasCreated() {
        String token = Tokens.usdc(1).orElseThrow().address();
        java.time.Instant mined = java.time.Instant.parse("2026-10-04T11:35:23Z");
        // 下单早于出块：正常确认
        assertEquals(PaymentStatus.CONFIRMED, verifier.verify("fresh", hash,
                new PaymentRequest(1, token, recipient, amount, payer, 1, mined.minusSeconds(60))).status());
        // 下单晚于出块：旧交易不能付新订单（即使账本里没有认领记录）
        assertEquals(PaymentStatus.PREDATES_ORDER, new PaymentVerifier(registryFor(transport), new InMemoryPaymentLedger())
                .verify("replay", hash, new PaymentRequest(1, token, recipient, amount, payer, 1, mined.plusSeconds(1))).status());
        // 未设置 notBefore：不查询区块（保持旧行为与 RPC 开销）
        int before = transport.blockRequests;
        new PaymentVerifier(registryFor(transport), new InMemoryPaymentLedger())
                .verify("legacy", hash, new PaymentRequest(1, token, recipient, amount, payer));
        assertEquals(before + 1, transport.blockRequests);
    }

    private static ChainRegistry registryFor(OnChainSupportTest.FixtureTransport transport) {
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(transport));
        return registry;
    }
}
