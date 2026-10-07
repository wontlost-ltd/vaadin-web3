package com.wontlost.web3.demo;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.wontlost.web3.ServerWallet;
import com.wontlost.web3.autoconfigure.Web3Properties;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.test.x402.LocalFacilitator;
import com.wontlost.web3.x402.payment.FacilitatorClient;
import com.wontlost.web3.x402.payment.ResourcePolicy;
import com.wontlost.web3.x402.payment.SettlementResult;
import com.wontlost.web3.x402.payment.SettlementState;
import com.wontlost.web3.x402.payment.SupportedResponse;
import com.wontlost.web3.x402.payment.VerifyResult;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequirements;
import com.wontlost.web3.x402.protocol.X402Resource;

import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("demo")
public class X402DemoConfiguration {
    private static final long CHAIN_ID = 31337;
    private static final BigInteger MINT_AMOUNT = BigInteger.valueOf(100_000_000);

    @Bean
    X402DemoFixture x402DemoFixture(ChainRegistry chains, ServerWallet wallet, Web3Properties properties,
            @Value("${web3.x402.demo.deploy-on-startup:true}") boolean deployOnStartup) {
        if (!properties.getX402().isEnabled()) {
            return X402DemoFixture.unavailable("x402 is disabled");
        }
        if (!properties.getX402().getLocalFacilitator().isEnabled()) {
            return X402DemoFixture.unavailable("Local facilitator is disabled.");
        }
        if (!deployOnStartup) {
            return X402DemoFixture.unavailable("Local token deployment is disabled.");
        }
        return deployIfConfigured(chains, wallet, properties);
    }

    private X402DemoFixture deployIfConfigured(ChainRegistry chains, ServerWallet wallet,
            Web3Properties properties) {
        try {
            EthRpcClient rpc = chains.get(CHAIN_ID)
                    .orElseThrow(() -> new IllegalStateException("Anvil RPC for chain 31337 is not configured"));
            if (rpc.chainId() != CHAIN_ID) {
                throw new IllegalStateException("Local demo RPC must use chain 31337");
            }
            String deploymentHash = sendWalletTransaction(wallet, Map.of(
                    "from", wallet.accounts().getFirst(),
                    "data", bytecode(),
                    "value", "0x0"));
            waitReceipt(rpc, deploymentHash);
            String tokenAddress = transactionReceipt(rpc, deploymentHash).path("contractAddress").asString();
            String mintData = "0x" + selector("mint(address,uint256)")
                    + addressWord(wallet.accounts().getFirst()) + uintWord(MINT_AMOUNT);
            String mintHash = sendWalletTransaction(wallet, Map.of(
                    "from", wallet.accounts().getFirst(),
                    "to", tokenAddress,
                    "data", mintData,
                    "value", "0x0"));
            if (!waitReceipt(rpc, mintHash).status()) {
                throw new IllegalStateException("Token mint transaction failed");
            }
            return new X402DemoFixture(true, null, tokenAddress, rpc, wallet, CHAIN_ID);
        } catch (Exception failure) {
            String detail = failure.getMessage() == null ? "" : " — " + failure.getMessage();
            return X402DemoFixture.unavailable("Local payment demo is unavailable: "
                    + failure.getClass().getSimpleName() + detail);
        }
    }

    @Bean
    ResourcePolicy x402DemoArticlePolicy(X402DemoFixture fixture, Web3Properties properties, ServerWallet wallet) {
        String asset = fixture.available()
                ? fixture.tokenAddress()
                : "0x0000000000000000000000000000000000000001";
        String url = properties.getX402().getOrigin().replaceAll("/$", "") + "/paid-article";
        return new ResourcePolicy("demo-article", "1",
                new X402Resource(url, "A paid article from the Vaadin Web3 demo", "text/html"),
                "eip155:" + CHAIN_ID, BigInteger.valueOf(1_000_000), asset,
                wallet.accounts().getFirst(), 300, "X402 Test Token", "1");
    }

    @Bean
    ResourcePolicy x402DemoQuotePolicy(X402DemoFixture fixture, Web3Properties properties, ServerWallet wallet) {
        String asset = fixture.available()
                ? fixture.tokenAddress()
                : "0x0000000000000000000000000000000000000001";
        String url = properties.getX402().getOrigin().replaceAll("/$", "") + "/api/x402/quote";
        return new ResourcePolicy("demo-quote", "1",
                new X402Resource(url, "A protected demo quote", "application/json"),
                "eip155:" + CHAIN_ID, BigInteger.valueOf(1_000_000), asset,
                wallet.accounts().getFirst(), 300, "X402 Test Token", "1");
    }

    @Bean
    FacilitatorClient x402DemoFacilitator(X402DemoFixture fixture) {
        if (fixture.available()) {
            return new LocalFacilitator(fixture.rpc(), fixture.wallet(), fixture.chainId(),
                    fixture.tokenAddress(), fixture.wallet().accounts().getFirst());
        }
        return new UnavailableDemoFacilitator(fixture.error());
    }

    private static String sendWalletTransaction(ServerWallet wallet, Map<String, String> transaction)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String params = mapper.writeValueAsString(List.of(transaction));
        String result = wallet.request("eth_sendTransaction", params).join();
        return mapper.readTree(result).asString();
    }

    private static String bytecode() throws Exception {
        try (var input = X402DemoConfiguration.class
                .getResourceAsStream("/contracts/x402-eip3009-token-bytecode.json")) {
            if (input == null) {
                throw new IllegalStateException("X402Eip3009Token bytecode is missing from web3-vaadin-test");
            }
            return new ObjectMapper().readTree(input).path("bytecode").asString();
        }
    }

    private static com.wontlost.web3.chain.TransactionReceipt waitReceipt(EthRpcClient rpc, String hash)
            throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            Optional<com.wontlost.web3.chain.TransactionReceipt> receipt = rpc.getTransactionReceipt(hash);
            if (receipt.isPresent()) {
                if (!receipt.get().status()) {
                    throw new IllegalStateException("Local token transaction failed");
                }
                return receipt.get();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for local token transaction");
    }

    private static tools.jackson.databind.JsonNode transactionReceipt(EthRpcClient rpc, String hash) {
        return rpc.request("eth_getTransactionReceipt", List.of(hash));
    }

    private static String selector(String signature) {
        byte[] digest = org.web3j.crypto.Hash.sha3(signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return org.web3j.utils.Numeric.toHexStringNoPrefix(digest).substring(0, 8);
    }

    private static String addressWord(String address) {
        return "0".repeat(24) + address.substring(2).toLowerCase(java.util.Locale.ROOT);
    }

    private static String uintWord(BigInteger value) {
        return String.format("%064x", value);
    }

    public record X402DemoFixture(boolean available, String error, String tokenAddress,
            EthRpcClient rpc, ServerWallet wallet, long chainId) {
        static X402DemoFixture unavailable(String message) {
            return new X402DemoFixture(false, message, null, null, null, CHAIN_ID);
        }
    }

    private record UnavailableDemoFacilitator(String reason) implements FacilitatorClient {
        @Override
        public SupportedResponse supported() {
            return new SupportedResponse(2, List.of());
        }

        @Override
        public VerifyResult verify(PaymentPayload payload, PaymentRequirements requirements) {
            return new VerifyResult(false, reason, null);
        }

        @Override
        public SettlementResult settle(PaymentPayload payload, PaymentRequirements requirements) {
            return new SettlementResult(SettlementState.REJECTED, null, reason, null);
        }
    }
}
