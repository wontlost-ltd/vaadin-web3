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
}
