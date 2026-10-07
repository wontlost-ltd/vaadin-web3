package com.wontlost.web3.test.nft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.dev.DevWallet;
import com.wontlost.web3.nft.NftCollection;
import com.wontlost.web3.nft.NftHolding;
import com.wontlost.web3.nft.NftOwnershipPage;
import com.wontlost.web3.nft.NftStandard;
import com.wontlost.web3.nft.RpcNftOwnershipSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "ANVIL_RPC", matches = "https?://.+")
class NftOwnershipAnvilTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static EthRpcClient rpc;
    private static DevWallet wallet;
    private static ChainRegistry chains;
    private static long chainId;
    private static String owner;
    private static String other;
    private static String enumerable721;
    private static String explicit721;
    private static String multi1155;

    @BeforeAll
    static void deployFixtures() throws Exception {
        rpc = new EthRpcClient(System.getenv("ANVIL_RPC"));
        chainId = rpc.chainId();
        wallet = DevWallet.anvilDefault(chainId, rpc);
        owner = wallet.accounts().getFirst();
        other = "0x000000000000000000000000000000000000dEaD";
        chains = new ChainRegistry();
        chains.register(chainId, rpc);
        enumerable721 = deploy("nft721Mock-bytecode.json", word(BigInteger.ONE));
        explicit721 = deploy("nft721Mock-bytecode.json", word(BigInteger.ZERO));
        multi1155 = deploy("nft1155Mock-bytecode.json", "");
    }

    @Test
    void readsEnumerableExplicitAndMultiTokenOwnershipAtStableSnapshots() throws Exception {
        send(enumerable721, "mint(address,uint256)", wordAddress(owner) + word(BigInteger.ONE));
        send(enumerable721, "mint(address,uint256)", wordAddress(owner) + word(BigInteger.TWO));
        send(enumerable721, "mint(address,uint256)", wordAddress(owner) + word(BigInteger.valueOf(3)));
        send(enumerable721, "transferFrom(address,address,uint256)",
                wordAddress(owner) + wordAddress(other) + word(BigInteger.TWO));
        send(enumerable721, "burn(uint256)", word(BigInteger.ONE));

        send(explicit721, "mint(address,uint256)", wordAddress(owner) + word(BigInteger.valueOf(7)));
        send(explicit721, "transferFrom(address,address,uint256)",
                wordAddress(owner) + wordAddress(other) + word(BigInteger.valueOf(7)));
        send(explicit721, "mint(address,uint256)", wordAddress(owner) + word(BigInteger.valueOf(9)));

        send(multi1155, "mintBatch(address,uint256[],uint256[])",
                wordAddress(owner) + word(BigInteger.valueOf(96)) + word(BigInteger.valueOf(192))
                        + word(BigInteger.TWO) + word(BigInteger.ONE) + word(BigInteger.TWO)
                        + word(BigInteger.TWO) + word(BigInteger.valueOf(5)) + word(BigInteger.valueOf(9)));

        List<NftCollection> collections = List.of(
                new NftCollection(chainId, enumerable721, NftStandard.ERC721, true, List.of()),
                new NftCollection(chainId, explicit721, NftStandard.ERC721, false,
                        List.of(BigInteger.valueOf(7), BigInteger.valueOf(9), BigInteger.TEN)),
                new NftCollection(chainId, multi1155, NftStandard.ERC1155, false,
                        List.of(BigInteger.ONE, BigInteger.TWO)));
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(chains, 3, 20, 1, 100)) {
            NftOwnershipPage page = source.find(chainId, owner, collections, null, 20);
            assertTrue(page.failures().isEmpty(), page.failures().toString());
            assertEquals(4, page.holdings().size());
            assertEquals(List.of(BigInteger.valueOf(3), BigInteger.valueOf(9), BigInteger.ONE, BigInteger.TWO),
                    page.holdings().stream().map(NftHolding::tokenId).toList());
            assertEquals(List.of(BigInteger.valueOf(5), BigInteger.valueOf(9)), page.holdings().stream()
                    .filter(holding -> holding.standard() == NftStandard.ERC1155)
                    .map(NftHolding::amount).toList());

            NftCollection paged = new NftCollection(chainId, explicit721, NftStandard.ERC721, false,
                    List.of(BigInteger.valueOf(9), BigInteger.TEN, BigInteger.valueOf(11)));
            NftOwnershipPage first = source.find(chainId, owner, List.of(paged), null, 1);
            NftOwnershipPage second = source.find(chainId, owner, List.of(paged), first.nextCursor(), 1);
            assertEquals(first.snapshotBlock(), second.snapshotBlock());

            send(multi1155, "safeTransferFrom(address,address,uint256,uint256)",
                    wordAddress(owner) + wordAddress(other) + word(BigInteger.ONE) + word(BigInteger.TWO));
            send(multi1155, "burn(address,uint256,uint256)",
                    wordAddress(owner) + word(BigInteger.TWO) + word(BigInteger.valueOf(9)));
            NftOwnershipPage afterUpdates = source.find(chainId, owner, collections, null, 20);
            assertEquals(List.of(BigInteger.valueOf(3)), afterUpdates.holdings().stream()
                    .filter(holding -> holding.standard() == NftStandard.ERC1155)
                    .map(NftHolding::amount).toList());
        }
    }

    private static String deploy(String resource, String constructorWord) throws Exception {
        JsonNode artifact = JSON.readTree(NftOwnershipAnvilTest.class.getResourceAsStream("/contracts/" + resource));
        String data = artifact.path("bytecode").asString() + constructorWord;
        String transactionHash = wallet.request("eth_sendTransaction", JSON.writeValueAsString(List.of(
                Map.of("from", owner, "data", data, "value", "0x0")))).join();
        String hash = JSON.readTree(transactionHash).asString();
        for (int attempt = 0; attempt < 50; attempt++) {
            JsonNode receipt = rpc.request("eth_getTransactionReceipt", List.of(hash));
            if (!receipt.isNull()) {
                assertEquals("0x1", receipt.path("status").asString());
                return receipt.path("contractAddress").asString();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Anvil did not mine the NFT fixture deployment");
    }

    private static void send(String to, String signature, String arguments) throws Exception {
        String selector = Numeric.toHexString(Hash.sha3(signature.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
        String transactionHash = wallet.request("eth_sendTransaction", JSON.writeValueAsString(List.of(
                Map.of("from", owner, "to", to, "data", selector + arguments, "value", "0x0")))).join();
        String hash = JSON.readTree(transactionHash).asString();
        for (int attempt = 0; attempt < 50; attempt++) {
            var receipt = rpc.getTransactionReceipt(hash);
            if (receipt.isPresent()) {
                assertTrue(receipt.get().status());
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Anvil did not mine NFT fixture transaction");
    }

    private static String word(BigInteger value) {
        return String.format("%064x", value);
    }

    private static String wordAddress(String address) {
        return "0".repeat(24) + Numeric.cleanHexPrefix(address).toLowerCase(java.util.Locale.ROOT);
    }
}
