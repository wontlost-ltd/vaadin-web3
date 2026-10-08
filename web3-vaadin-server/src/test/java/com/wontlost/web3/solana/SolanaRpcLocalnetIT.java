package com.wontlost.web3.solana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.wontlost.web3.chain.FailoverJsonRpcTransport;
import com.wontlost.web3.chain.HttpJsonRpcTransport;
import com.wontlost.web3.chain.JsonRpcDialect;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.siws.Base58;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 针对真实 {@code solana-test-validator} 的集成测试（{@code SOLANA_RPC=http://127.0.0.1:8899}）。
 * <p>
 * SPL 部分另需预先用 CLI 准备代币，并导出 {@code SOLANA_SPL_MINT}、{@code SOLANA_SPL_OWNER}、
 * {@code SOLANA_SPL_AMOUNT}（最小单位）；可选 {@code SOLANA_SPL_2022_MINT} 为同一所有者、同样数额与小数位数的
 * Token-2022 代币（CLI 加 {@code --program-id TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb}）。
 * 转账用例另需 {@code SOLANA_SPL_OWNER_KEYPAIR}：所有者的 solana-keygen JSON 密钥文件路径（仅本机测试夹具）：
 * <pre>
 * spl-token -u $SOLANA_RPC create-token --decimals 6
 * spl-token -u $SOLANA_RPC create-account &lt;mint&gt; --owner &lt;owner&gt; --fee-payer &lt;payer.json&gt;
 * spl-token -u $SOLANA_RPC mint &lt;mint&gt; 1234.5 --recipient-owner &lt;owner&gt;
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "SOLANA_RPC", matches = ".+")
class SolanaRpcLocalnetIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    @Test void failoverWithSolanaDialectSkipsADeadPrimaryAndReturnsAfterARealGetHealthProbe() throws Exception {
        String rpc = System.getenv("SOLANA_RPC");
        AtomicInteger primaryFailures = new AtomicInteger(1);
        List<String> primaryMethods = new CopyOnWriteArrayList<>();
        HttpJsonRpcTransport primaryNode = new HttpJsonRpcTransport(rpc);
        // 主端点：真实节点，首个请求模拟超时；之后的恢复探测与请求都打到真实验证器
        JsonRpcTransport primary = new JsonRpcTransport() {
            @Override public String send(String request) throws IOException {
                primaryMethods.add(MAPPER.readTree(request).path("method").asString());
                if (primaryFailures.getAndDecrement() > 0) throw new IOException("timeout");
                return primaryNode.send(request);
            }

            @Override public void close() {
                primaryNode.close();
            }
        };
        FailoverJsonRpcTransport failover = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", primary),
                new FailoverJsonRpcTransport.Endpoint("backup", new HttpJsonRpcTransport(rpc))),
                new FailoverJsonRpcTransport.Config(1, Duration.ZERO, 3), JsonRpcDialect.SOLANA);
        try (SolanaRpcClient client = new SolanaRpcClient(failover, SolanaCommitment.CONFIRMED)) {
            String genesis = client.getGenesisHash();
            assertEquals(List.of("getGenesisHash", "getHealth"), primaryMethods);
            assertNotNull(failover.healthSnapshot().get(1).lastSuccessAt());
            assertEquals(FailoverJsonRpcTransport.State.CLOSED, failover.healthSnapshot().getFirst().state());

            assertEquals(genesis, client.getGenesisHash());
            assertEquals("getGenesisHash", primaryMethods.getLast(), "the recovered primary serves the next request");
        }
    }

    @Test void solTransferBuiltInJavaLandsAndFinalizes() throws Exception {
        try (SolanaRpcClient client = new SolanaRpcClient(System.getenv("SOLANA_RPC"))) {
            Ed25519PrivateKeyParameters sender = randomKey();
            String from = address(sender);
            String to = randomAddress();
            client.request("requestAirdrop", List.of(from, AIRDROP, Map.of("commitment", "confirmed")));
            awaitBalance(client, from);
            BigInteger amount = BigInteger.valueOf(500_000_000L);

            SolanaTransaction transfer = SolanaTransfers.sol(from, to, amount, client.getLatestBlockhash().blockhash());
            String signature = client.sendTransaction(transfer.wire(Map.of(from, sign(sender, transfer.message()))));
            SignatureStatus status = awaitStatus(client, signature, SolanaCommitment.FINALIZED);

            assertTrue(status.succeededAt(SolanaCommitment.FINALIZED));
            assertNull(status.confirmations(), "finalized transactions report no confirmation count");
            assertEquals(amount, client.getBalance(to).lamports());
            assertEquals(AIRDROP.subtract(amount).subtract(BigInteger.valueOf(5_000)), client.getBalance(from).lamports(),
                    "the sender paid the amount plus the 5000-lamport signature fee");
        }
    }

    @Test void preflightRejectsATransferLargerThanTheBalance() throws Exception {
        try (SolanaRpcClient client = new SolanaRpcClient(System.getenv("SOLANA_RPC"))) {
            Ed25519PrivateKeyParameters sender = randomKey();
            String from = address(sender);
            client.request("requestAirdrop", List.of(from, AIRDROP, Map.of("commitment", "confirmed")));
            awaitBalance(client, from);

            SolanaTransaction transfer = SolanaTransfers.sol(from, randomAddress(), AIRDROP.multiply(BigInteger.TWO),
                    client.getLatestBlockhash().blockhash());
            SolanaRpcException rejected = assertThrows(SolanaRpcException.class,
                    () -> client.sendTransaction(transfer.wire(Map.of(from, sign(sender, transfer.message())))));

            assertEquals(-32002, rejected.getCode());
            assertEquals(AIRDROP, client.getBalance(from).lamports(), "nothing was charged");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SOLANA_SPL_OWNER_KEYPAIR", matches = ".+")
    void splTransfersCreateTheRecipientAccountForBothTokenPrograms() throws Exception {
        Ed25519PrivateKeyParameters owner = keypairFile(System.getenv("SOLANA_SPL_OWNER_KEYPAIR"));
        String from = address(owner);
        assertEquals(System.getenv("SOLANA_SPL_OWNER"), from);
        List<String> mints = new java.util.ArrayList<>(List.of(System.getenv("SOLANA_SPL_MINT")));
        if (System.getenv("SOLANA_SPL_2022_MINT") != null) mints.add(System.getenv("SOLANA_SPL_2022_MINT"));
        try (SolanaRpcClient client = new SolanaRpcClient(System.getenv("SOLANA_RPC"))) {
            // 所有者支付手续费与接收方账户租金：等空投到账后再发送，否则预检因余额不足失败
            BigInteger funded = client.getBalance(from).lamports().add(AIRDROP);
            client.request("requestAirdrop", List.of(from, AIRDROP, Map.of("commitment", "confirmed")));
            awaitLamports(client, from, funded);
            for (String mint : mints) {
                String program = client.getAccountOwner(mint).orElseThrow();
                Ed25519PrivateKeyParameters recipientKey = randomKey();
                String recipient = address(recipientKey);
                SplTokenBalance before = client.getTokenBalance(from, mint);
                BigInteger amount = BigInteger.valueOf(1_250_000L);

                SolanaTransaction transfer = SolanaTransfers.spl(from, mint, recipient, amount, before.decimals(), program,
                        client.getLatestBlockhash().blockhash(), true);
                String signature = client.sendTransaction(transfer.wire(Map.of(from, sign(owner, transfer.message()))));
                assertTrue(awaitStatus(client, signature, SolanaCommitment.CONFIRMED).succeededAt(SolanaCommitment.CONFIRMED));

                assertEquals(amount, client.getTokenBalance(recipient, mint).amount(), mint);
                assertEquals(before.amount().subtract(amount), client.getTokenBalance(from, mint).amount(), mint);
                assertEquals(java.util.Optional.of(program), client.getAccountOwner(
                        SolanaAddresses.associatedTokenAddress(recipient, mint, program)), "recipient ATA created");

                // 退回给所有者（其关联账户已存在，不再创建），保持夹具余额不变，其他用例不受影响
                BigInteger recipientFunds = AIRDROP;
                client.request("requestAirdrop", List.of(recipient, recipientFunds, Map.of("commitment", "confirmed")));
                awaitLamports(client, recipient, recipientFunds);
                SolanaTransaction refund = SolanaTransfers.spl(recipient, mint, from, amount, before.decimals(), program,
                        client.getLatestBlockhash().blockhash(), false);
                String refundSignature = client.sendTransaction(
                        refund.wire(Map.of(recipient, sign(recipientKey, refund.message()))));
                assertTrue(awaitStatus(client, refundSignature, SolanaCommitment.CONFIRMED)
                        .succeededAt(SolanaCommitment.CONFIRMED));
                assertEquals(before.amount(), client.getTokenBalance(from, mint).amount(), "fixture balance restored");
            }
        }
    }

    private static SignatureStatus awaitStatus(SolanaRpcClient client, String signature, SolanaCommitment commitment)
            throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            var status = client.getSignatureStatuses(List.of(signature), false).getFirst();
            if (status.isPresent() && (status.get().failed() || status.get().succeededAt(commitment))) return status.get();
            Thread.sleep(300);
        }
        throw new AssertionError("transaction did not reach " + commitment + " in time");
    }

    private static Ed25519PrivateKeyParameters randomKey() {
        return new Ed25519PrivateKeyParameters(new SecureRandom());
    }

    // solana-keygen 的 JSON 文件：64 个字节（32 字节种子 + 32 字节公钥）
    private static Ed25519PrivateKeyParameters keypairFile(String path) throws java.io.IOException {
        JsonNode bytes = MAPPER.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(path)));
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) bytes.get(i).asInt();
        return new Ed25519PrivateKeyParameters(seed, 0);
    }

    private static String address(Ed25519PrivateKeyParameters key) {
        return Base58.encode(key.generatePublicKey().getEncoded());
    }

    private static byte[] sign(Ed25519PrivateKeyParameters key, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    private static void awaitLamports(SolanaRpcClient client, String address, BigInteger atLeast)
            throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (client.getBalance(address).lamports().compareTo(atLeast) < 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("airdrop did not arrive in time");
            Thread.sleep(250);
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

    /** 随机钱包地址：由新密钥派生的公钥（随机 32 字节约一半不在曲线上，不能作为钱包地址）。 */
    private static String randomAddress() {
        return address(randomKey());
    }
}
