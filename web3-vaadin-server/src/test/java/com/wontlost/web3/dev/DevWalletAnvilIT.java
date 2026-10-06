package com.wontlost.web3.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.TransactionReceipt;

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

    private static String send(String value) throws Exception {
        String params = MAPPER.writeValueAsString(java.util.List.of(java.util.Map.of(
                "from", wallet.accounts().getFirst(), "to", RECIPIENT, "value", value)));
        return MAPPER.readTree(wallet.request("eth_sendTransaction", params).join()).asString();
    }
}
