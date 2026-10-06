package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.wontlost.web3.pay.InMemoryPaymentLedger;
import com.wontlost.web3.pay.PaymentRequest;
import com.wontlost.web3.pay.PaymentStatus;
import com.wontlost.web3.pay.PaymentVerifier;

class FailoverAnvilIT {
    @Test
    @EnabledIfEnvironmentVariable(named = "ANVIL_RPC", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "ANVIL_RPC_2", matches = ".+")
    void switchesFromUnavailableAnvilToHealthyAnvilForCallsAndPaymentReads() throws Exception {
        FailoverJsonRpcTransport transport = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", new HttpJsonRpcTransport(System.getenv("ANVIL_RPC"))),
                new FailoverJsonRpcTransport.Endpoint("backup", new HttpJsonRpcTransport(System.getenv("ANVIL_RPC_2")))),
                new FailoverJsonRpcTransport.Config(1, java.time.Duration.ofSeconds(1), 3));
        EthRpcClient rpc = new EthRpcClient(transport);
        assertEquals("0x", rpc.call("0x0000000000000000000000000000000000000001", "0x", "latest"));

        ChainRegistry registry = new ChainRegistry();
        registry.register(31337, rpc);
        PaymentVerifier verifier = new PaymentVerifier(registry, new InMemoryPaymentLedger());
        PaymentStatus status = verifier.verify("anvil-read-path", "0x" + "11".repeat(32),
                new PaymentRequest(31337, "0x0000000000000000000000000000000000000001",
                        "0x0000000000000000000000000000000000000002", BigInteger.ONE, null)).status();
        assertEquals(PaymentStatus.PENDING, status);
        assertEquals("backup", transport.healthSnapshot().get(1).id());
        org.junit.jupiter.api.Assertions.assertTrue(transport.healthSnapshot().get(1).lastSuccessAt() != null);
        assertEquals(FailoverJsonRpcTransport.State.OPEN, transport.healthSnapshot().getFirst().state());
    }
}
