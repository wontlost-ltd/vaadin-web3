package com.wontlost.web3.demo;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.wontlost.web3.ServerWallet;
import com.wontlost.web3.autoconfigure.NftCollections;
import com.wontlost.web3.autoconfigure.Web3Properties;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 为本地演示配置部署仅供展示的 NFT 测试合约。 */
@Configuration
@Profile("demo")
public class NftDemoConfiguration {
    private static final long CHAIN_ID = 31337;
    private static final String RASTER_IMAGE = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=640&auto=format&fit=crop";
    private static final String MISSING_ADDRESS = "0x0000000000000000000000000000000000000001";

    @Bean
    NftDemoFixture nftDemoFixture(ChainRegistry chains, ServerWallet wallet, Web3Properties properties,
            @Value("${web3.nft.demo.deploy-on-startup:true}") boolean deployOnStartup) {
        if (!properties.getNft().isEnabled()) {
            return NftDemoFixture.unavailable("NFT gallery is disabled.");
        }
        if (!deployOnStartup) {
            return NftDemoFixture.unavailable("NFT fixture deployment is disabled.");
        }
        return deploy(chains, wallet);
    }

    @Bean
    NftCollections nftDemoCollections(NftDemoFixture fixture, Web3Properties properties) {
        String erc721 = fixture.available() ? fixture.erc721Address() : MISSING_ADDRESS;
        String erc1155 = fixture.available() ? fixture.erc1155Address() : "0x0000000000000000000000000000000000000002";
        configureCollectionProperties(properties, erc721, erc1155);
        return new NftCollections(List.of(
                new com.wontlost.web3.nft.NftCollection(CHAIN_ID, erc721,
                        com.wontlost.web3.nft.NftStandard.ERC721, true, List.of()),
                new com.wontlost.web3.nft.NftCollection(CHAIN_ID, erc1155,
                        com.wontlost.web3.nft.NftStandard.ERC1155, false,
                        List.of(BigInteger.valueOf(101), BigInteger.valueOf(102), BigInteger.valueOf(103),
                                BigInteger.valueOf(104)))));
    }

    private void configureCollectionProperties(Web3Properties properties, String erc721, String erc1155) {
        Web3Properties.NftCollection first = new Web3Properties.NftCollection();
        first.setChainId(CHAIN_ID);
        first.setContract(erc721);
        first.setStandard("ERC721");
        first.setEnumerable(true);
        Web3Properties.NftCollection second = new Web3Properties.NftCollection();
        second.setChainId(CHAIN_ID);
        second.setContract(erc1155);
        second.setStandard("ERC1155");
        second.setTokenIds(List.of("101", "102", "103", "104"));
        properties.getNft().setCollections(List.of(first, second));
    }

    private NftDemoFixture deploy(ChainRegistry chains, ServerWallet wallet) {
        try {
            EthRpcClient rpc = chains.get(CHAIN_ID).orElseThrow();
            if (rpc.chainId() != CHAIN_ID) {
                throw new IllegalStateException("Local RPC must use chain 31337");
            }
            String erc721Hash = send(wallet, Map.of("from", wallet.accounts().getFirst(),
                    "data", bytecode("nft721Mock-bytecode.json") + uintWord(BigInteger.ONE)));
            waitReceipt(rpc, erc721Hash);
            String erc721Address = receipt(rpc, erc721Hash).path("contractAddress").asString();
            String erc1155Hash = send(wallet, Map.of("from", wallet.accounts().getFirst(),
                    "data", bytecode("nft1155Mock-bytecode.json")));
            waitReceipt(rpc, erc1155Hash);
            String erc1155Address = receipt(rpc, erc1155Hash).path("contractAddress").asString();
            mint721(rpc, wallet, erc721Address);
            mint1155(rpc, wallet, erc1155Address);
            return new NftDemoFixture(true, null, erc721Address, erc1155Address, rpc, wallet);
        } catch (Exception exception) {
            return NftDemoFixture.unavailable("NFT gallery is unavailable: "
                    + exception.getClass().getSimpleName());
        }
    }

    private void mint721(EthRpcClient rpc, ServerWallet wallet, String contract) throws Exception {
        String owner = wallet.accounts().getFirst();
        for (long tokenId = 1; tokenId <= 4; tokenId++) {
            sendAndWait(rpc, wallet, Map.of("from", owner, "to", contract,
                    "data", selector("mint(address,uint256)") + addressWord(owner) + uintWord(BigInteger.valueOf(tokenId))));
        }
        String[] uris = {
                dataJson("{\"name\":\"Demo landscape\",\"description\":\"Plain text metadata\",\"image\":\"" + RASTER_IMAGE + "\",\"external_url\":\"https://example.com/nft-demo\"}"),
                "javascript:alert(1)",
                dataJson("{\"name\":\"SVG artwork\",\"image\":\"https://example.com/nft-demo.svg\"}"),
                dataJson("{\"name\":\"Blocked data image\",\"image\":\"data:image/png;base64,iVBORw0KGgo=\"}")
        };
        for (int index = 0; index < uris.length; index++) {
            BigInteger id = BigInteger.valueOf(index + 1L);
            String call = selector("setTokenURI(uint256,string)") + uintWord(id)
                    + uintWord(BigInteger.valueOf(64)) + dynamicString(uris[index]);
            sendAndWait(rpc, wallet, Map.of("from", owner, "to", contract, "data", call));
        }
    }

    private void mint1155(EthRpcClient rpc, ServerWallet wallet, String contract) throws Exception {
        String owner = wallet.accounts().getFirst();
        long[] ids = {101, 102, 103, 104};
        long[] amounts = {5, 2, 1, 1};
        String[] names = {"Multi edition", "SVG edition", "Blocked data image", "Bad metadata"};
        String[] images = {RASTER_IMAGE, "https://example.com/nft-demo.svg",
                "data:image/png;base64,iVBORw0KGgo=", null};
        for (int index = 0; index < ids.length; index++) {
            String json = "{\"name\":\"" + names[index] + "\",\"description\":\"Quantity "
                    + amounts[index] + "\"" + (images[index] == null ? "" : ",\"image\":\"" + images[index] + "\"") + "}";
            String uri = index == 3 ? "javascript:alert(1)" : dataJson(json);
            BigInteger id = BigInteger.valueOf(ids[index]);
            String setUri = selector("setURI(uint256,string)") + uintWord(id)
                    + uintWord(BigInteger.valueOf(64)) + dynamicString(uri);
            sendAndWait(rpc, wallet, Map.of("from", owner, "to", contract, "data", setUri));
            String mint = selector("mint(address,uint256,uint256)") + addressWord(owner)
                    + uintWord(id) + uintWord(BigInteger.valueOf(amounts[index]));
            sendAndWait(rpc, wallet, Map.of("from", owner, "to", contract, "data", mint));
        }
    }

    private void sendAndWait(EthRpcClient rpc, ServerWallet wallet, Map<String, String> transaction)
            throws Exception {
        waitReceipt(rpc, send(wallet, transaction));
    }

    private String send(ServerWallet wallet, Map<String, String> transaction) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String result = wallet.request("eth_sendTransaction", mapper.writeValueAsString(List.of(transaction))).join();
        return mapper.readTree(result).asString();
    }

    private String bytecode(String resource) throws Exception {
        try (var input = NftDemoConfiguration.class.getResourceAsStream("/contracts/" + resource)) {
            if (input == null) {
                throw new IllegalStateException("NFT fixture bytecode is missing");
            }
            JsonNode root = new ObjectMapper().readTree(input);
            return root.path("bytecode").asString();
        }
    }

    private com.wontlost.web3.chain.TransactionReceipt waitReceipt(EthRpcClient rpc, String hash)
            throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            Optional<com.wontlost.web3.chain.TransactionReceipt> result = rpc.getTransactionReceipt(hash);
            if (result.isPresent()) {
                if (!result.get().status()) {
                    throw new IllegalStateException("NFT fixture transaction failed");
                }
                return result.get();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for NFT fixture transaction");
    }

    private JsonNode receipt(EthRpcClient rpc, String hash) {
        return rpc.request("eth_getTransactionReceipt", List.of(hash));
    }

    private String dataJson(String json) {
        return "data:application/json," + java.net.URLEncoder.encode(json, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String dynamicString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String hex = HexFormat.of().formatHex(bytes);
        int paddedLength = ((bytes.length + 31) / 32) * 64;
        return uintWord(BigInteger.valueOf(bytes.length)) + hex + "0".repeat(paddedLength - hex.length());
    }

    private String selector(String signature) {
        return HexFormat.of().formatHex(Hash.sha3(signature.getBytes(StandardCharsets.UTF_8)), 0, 4);
    }

    private String addressWord(String address) {
        return "0".repeat(24) + Numeric.cleanHexPrefix(address).toLowerCase(java.util.Locale.ROOT);
    }

    private String uintWord(BigInteger value) {
        return String.format("%064x", value);
    }

    public record NftDemoFixture(boolean available, String error, String erc721Address,
            String erc1155Address, EthRpcClient rpc, ServerWallet wallet) {
        private static NftDemoFixture unavailable(String reason) {
            return new NftDemoFixture(false, reason, null, null, null, null);
        }
    }
}
