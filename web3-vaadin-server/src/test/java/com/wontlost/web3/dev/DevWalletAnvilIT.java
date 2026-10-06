package com.wontlost.web3.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.EthLog;
import com.wontlost.web3.chain.LogFilter;
import com.wontlost.web3.chain.TransactionReceipt;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 在真实 anvil 节点上验证开发钱包的发送路径：服务端签名、广播、上链成功，连续发送 nonce 递增。
 * <p>
 * 默认跳过；本地运行：{@code anvil --port 8547} 后执行
 * {@code ANVIL_RPC=http://127.0.0.1:8547 mvn -pl web3-vaadin-server test -Dtest=DevWalletAnvilIT}。
 */
@EnabledIfEnvironmentVariable(named = "ANVIL_RPC", matches = "https?://.+")
class DevWalletAnvilIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECIPIENT = "0x000000000000000000000000000000000000dEaD";

    private static EthRpcClient client;
    private static DevWallet wallet;

    @BeforeAll static void connect() {
        client = new EthRpcClient(System.getenv("ANVIL_RPC"));
        wallet = DevWallet.anvilDefault(client.chainId(), client);
    }

    @Test void sendsSignedTransactionsThatAreMinedWithIncreasingNonces() throws Exception {
        String account = wallet.accounts().getFirst();
        BigInteger before = client.getTransactionCount(account, "latest");
        String first = send("0x1234");
        String second = send("0x5678");
        assertNotEquals(first, second);

        TransactionReceipt firstReceipt = client.getTransactionReceipt(first).orElseThrow();
        TransactionReceipt secondReceipt = client.getTransactionReceipt(second).orElseThrow();
        assertTrue(firstReceipt.status(), "first transfer must succeed on chain");
        assertTrue(secondReceipt.status(), "second transfer must succeed on chain");
        assertEquals(wallet.accounts().getFirst().toLowerCase(), firstReceipt.from());
        assertEquals(RECIPIENT.toLowerCase(), firstReceipt.to());
        assertEquals(before.add(BigInteger.TWO), client.getTransactionCount(account, "latest"),
                "two sends must consume two consecutive nonces");
    }

    @Test void walletChainMatchesNode() {
        assertEquals("0x" + Long.toHexString(client.chainId()), wallet.chainId());
    }

    @Test void readsTransferLogFromDeployedEmitterContract() throws Exception {
        String sender = wallet.accounts().getFirst();
        String signature = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        String runtime = "7f" + "00".repeat(31) + "01" + "600052"
                + "7f" + "00".repeat(12) + RECIPIENT.substring(2).toLowerCase()
                + "7f" + "00".repeat(12) + sender.substring(2).toLowerCase()
                + "7f" + signature.substring(2) + "60206000a300";
        String runtimeLength = Integer.toHexString(runtime.length() / 2);
        String initCode = "60" + runtimeLength + "600c60003960" + runtimeLength + "6000f3" + runtime;

        String deployment = sendTo(null, initCode, "0x0");
        JsonNode deploymentReceipt = receiptNode(deployment);
        String token = deploymentReceipt.path("contractAddress").asString();
        long fromBlock = Long.decode(deploymentReceipt.path("blockNumber").asString());

        String transfer = sendTo(token, "0x", "0x0");
        JsonNode transferReceipt = receiptNode(transfer);
        long toBlock = Long.decode(transferReceipt.path("blockNumber").asString());
        List<EthLog> logs = client.pinned().getLogs(LogFilter.erc20Transfers(fromBlock, toBlock, token, RECIPIENT));

        assertTrue(logs.stream().anyMatch(log -> log.transactionHash().equalsIgnoreCase(transfer)
                && log.topics().getFirst().equals(signature)));
    }

    private static JsonNode receiptNode(String hash) {
        return client.request("eth_getTransactionReceipt", java.util.List.of(hash));
    }

    private static String send(String value) throws Exception {
        return sendTo(RECIPIENT, "0x", value);
    }

    private static String sendTo(String to, String data, String value) throws Exception {
        java.util.Map<String, String> transaction = new java.util.HashMap<>();
        transaction.put("from", wallet.accounts().getFirst());
        if (to != null) transaction.put("to", to);
        transaction.put("value", value);
        transaction.put("data", data);
        String params = MAPPER.writeValueAsString(List.of(transaction));
        return MAPPER.readTree(wallet.request("eth_sendTransaction", params).join()).asString();
    }
}
