package com.wontlost.web3.solana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.wontlost.web3.siws.Base58;

/**
 * 针对真实 {@code solana-test-validator} 的集成测试（{@code SOLANA_RPC=http://127.0.0.1:8899}）。
 * <p>
 * SPL 部分另需预先用 CLI 准备代币，并导出 {@code SOLANA_SPL_MINT}、{@code SOLANA_SPL_OWNER}、
 * {@code SOLANA_SPL_AMOUNT}（最小单位）；可选 {@code SOLANA_SPL_2022_MINT} 为同一所有者、同样数额与小数位数的
 * Token-2022 代币（CLI 加 {@code --program-id TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb}）：
 * <pre>
 * spl-token -u $SOLANA_RPC create-token --decimals 6
 * spl-token -u $SOLANA_RPC create-account &lt;mint&gt; --owner &lt;owner&gt; --fee-payer &lt;payer.json&gt;
 * spl-token -u $SOLANA_RPC mint &lt;mint&gt; 1234.5 --recipient-owner &lt;owner&gt;
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SOLANA_RPC", matches = ".+")
class SolanaRpcLocalnetIT {
    private static final BigInteger AIRDROP = BigInteger.valueOf(2_000_000_000L);

    @Test void airdroppedLamportsAreVisibleThroughGetBalance() throws InterruptedException {
        try (SolanaRpcClient client = new SolanaRpcClient(System.getenv("SOLANA_RPC"))) {
            String fresh = randomAddress();
            assertEquals(BigInteger.ZERO, client.getBalance(fresh).lamports());

            client.request("requestAirdrop", List.of(fresh, AIRDROP, Map.of("commitment", "confirmed")));
            SolanaBalance balance = awaitBalance(client, fresh);

            assertEquals(AIRDROP, balance.lamports());
            assertEquals(new BigDecimal("2.000000000"), balance.sol());
            assertTrue(balance.slot() > 0);
        }
    }

    @Test void clusterReferenceIsTheGenesisHashPrefix() {
        try (SolanaRpcClient client = new SolanaRpcClient(System.getenv("SOLANA_RPC"))) {
            String genesis = client.getGenesisHash();
            assertEquals(32, Base58.decode(genesis, 32).length);
            assertEquals(genesis.substring(0, 32), client.clusterReference());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SOLANA_SPL_MINT", matches = ".+")
    void splBalanceMatchesTheCliMintedAmount() {
        String mint = System.getenv("SOLANA_SPL_MINT");
        try (SolanaRpcClient client = new SolanaRpcClient(System.getenv("SOLANA_RPC"))) {
            SplTokenBalance held = client.getTokenBalance(System.getenv("SOLANA_SPL_OWNER"), mint);
            assertEquals(new BigInteger(System.getenv("SOLANA_SPL_AMOUNT")), held.amount());
            assertEquals(6, held.decimals());

            SplTokenBalance none = client.getTokenBalance(randomAddress(), mint);
            assertEquals(BigInteger.ZERO, none.amount());
            assertEquals(6, none.decimals());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SOLANA_SPL_2022_MINT", matches = ".+")
    void token2022BalanceMatchesTheCliMintedAmount() {
        try (SolanaRpcClient client = new SolanaRpcClient(System.getenv("SOLANA_RPC"))) {
            SplTokenBalance held = client.getTokenBalance(System.getenv("SOLANA_SPL_OWNER"),
                    System.getenv("SOLANA_SPL_2022_MINT"));
            assertEquals(new BigInteger(System.getenv("SOLANA_SPL_AMOUNT")), held.amount());
            assertEquals(6, held.decimals());
        }
    }

    private static SolanaBalance awaitBalance(SolanaRpcClient client, String address) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        SolanaBalance balance = client.getBalance(address);
        while (balance.lamports().signum() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(250);
            balance = client.getBalance(address);
        }
        return balance;
    }

    private static String randomAddress() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base58.encode(key);
    }
}
