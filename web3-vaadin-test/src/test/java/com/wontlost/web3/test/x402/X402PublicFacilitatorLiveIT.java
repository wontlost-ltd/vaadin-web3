package com.wontlost.web3.test.x402;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.test.TestWallet;
import com.wontlost.web3.x402.facilitator.HttpFacilitatorClient;
import com.wontlost.web3.x402.payment.Eip3009TypedDataFactory;
import com.wontlost.web3.x402.payment.ResourcePolicy;
import com.wontlost.web3.x402.payment.SettlementState;
import com.wontlost.web3.x402.protocol.Eip3009Payload;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.X402Resource;

import org.web3j.utils.Numeric;

@EnabledIfEnvironmentVariable(named = "X402_FACILITATOR_URL", matches = "https://.+")
class X402PublicFacilitatorLiveIT {
    private static final String NETWORK = "eip155:84532";
    private static final String TOKEN = "0x036CbD53842c5426634e7929541eC2318f3dCF7e";
    private static final HttpFacilitatorClient FACILITATOR = new HttpFacilitatorClient(
            System.getenv("X402_FACILITATOR_URL"), System.getenv("X402_FACILITATOR_API_KEY"), null,
            Duration.ofSeconds(10), Duration.ofSeconds(20), 262_144);

    @Test void advertisesExactSchemeOnBaseSepolia() {
        var response = FACILITATOR.supported();
        assertFalse(response.kinds().isEmpty(), "facilitator advertises supported kinds");
        assertTrue(response.supports(2, "exact", NETWORK), "facilitator supports Base Sepolia exact payments");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "X402_TEST_PAYER_KEY", matches = "0x[0-9a-fA-F]{64}")
    void verifiesAndSettlesOneTestnetUsdcPayment() {
        TestWallet payer = TestWallet.fromPrivateKey(System.getenv("X402_TEST_PAYER_KEY"));
        EthRpcClient rpc = new EthRpcClient("https://sepolia.base.org");
        String name = readString(rpc.call(TOKEN, "0x06fdde03", "latest"));
        String version = readString(rpc.call(TOKEN, "0x54fd4d50", "latest"));
        ResourcePolicy policy = new ResourcePolicy("public-facilitator-live", "1",
                new X402Resource("https://example.test/x402-live", "Live facilitator interoperability check", "text/plain"),
                NETWORK, BigInteger.ONE, TOKEN, payer.address(), 600, name, version);
        var generated = new Eip3009TypedDataFactory(Clock.systemUTC(), Duration.ofSeconds(600)).create(policy, payer.address());
        PaymentPayload payload = new PaymentPayload(2, policy.resource(), policy.requirements(),
                new Eip3009Payload(payer.signTypedData(generated.typedDataJson()), generated.authorization()));
        var verified = FACILITATOR.verify(payload, policy.requirements());
        assertTrue(verified.valid(), "payment verifies for payer " + payer.address() + ": " + verified.invalidReason());
        var settled = FACILITATOR.settle(payload, policy.requirements());
        assertEquals(SettlementState.SETTLED, settled.state(), "payment settles for payer " + payer.address());
        assertNotNull(settled.txHash(), "settlement transaction hash is present");
        assertTrue(settled.txHash().matches("0x[0-9a-f]{64}"), "settlement transaction hash is valid");
        assertFalse(FACILITATOR.verify(payload, policy.requirements()).valid(), "settled authorization nonce is used");
    }

    private static String readString(String result) {
        String hex = Numeric.cleanHexPrefix(result);
        int offset = new BigInteger(hex.substring(0, 64), 16).intValueExact() * 2;
        int length = new BigInteger(hex.substring(offset, offset + 64), 16).intValueExact();
        byte[] bytes = HexFormat.of().parseHex(hex.substring(offset + 64, offset + 64 + length * 2));
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
