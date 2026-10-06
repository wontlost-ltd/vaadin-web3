package com.wontlost.web3.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.wontlost.web3.chain.EthRpcClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "ANVIL_RPC", matches = "https?://.+")
class DevWalletBatchAnvilIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECIPIENT = "0x000000000000000000000000000000000000dEaD";
    private static EthRpcClient client;
    private static DevWallet wallet;

    @BeforeAll static void connect() {
        client = new EthRpcClient(System.getenv("ANVIL_RPC"));
        wallet = DevWallet.anvilDefault(client.chainId(), client);
    }

    @Test void sendsTwoCallsAndReportsBothMinedReceipts() throws Exception {
        List<Map<String, String>> calls = List.of(
                Map.of("to", RECIPIENT, "value", "0x1", "data", "0x"),
                Map.of("to", RECIPIENT, "value", "0x2", "data", "0x"));
        Map<String, Object> request = Map.of("version", "2.0.0", "chainId", wallet.chainId(),
                "atomicRequired", false, "calls", calls);
        String submission = wallet.request("wallet_sendCalls", MAPPER.writeValueAsString(List.of(request))).join();
        String id = MAPPER.readTree(submission).path("id").asString();

        JsonNode status = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            status = MAPPER.readTree(wallet.request("wallet_getCallsStatus", MAPPER.writeValueAsString(List.of(id))).join());
            if (status.path("status").asInt() == 200) break;
            Thread.sleep(100);
        }

        assertEquals(200, status.path("status").asInt());
        assertFalse(status.path("atomic").asBoolean());
        assertEquals(2, status.path("receipts").size());
    }
}
