package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Keys;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class OnChainSupportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonNode fixture;

    @BeforeEach
    void loadFixture() throws IOException {
        fixture = MAPPER.readTree(getClass().getResourceAsStream("/fixtures/usdc-mainnet.json").readAllBytes());
    }

    @Test
    void transferCalldataMatchesMainnetTransaction() {
        JsonNode tx = fixture.path("simpleTransfer").path("transaction");
        JsonNode log = fixture.path("simpleTransfer").path("receipt").path("logs").get(0);
        String to = "0x" + log.path("topics").get(2).asString().substring(26);
        BigInteger amount = new BigInteger(log.path("data").asString().substring(2), 16);
        assertEquals(tx.path("input").asString(), Erc20.transferData(to, amount));
    }

    @Test
    void transferParsingFiltersByTokenAndMatchesFixtureLogs() {
        TransactionReceipt simple = parseReceipt(fixture.path("simpleTransfer").path("receipt"));
        assertEquals(1, Erc20.transfers(simple, Tokens.usdc(1).orElseThrow().address()).size());
        TransactionReceipt complex = parseReceipt(fixture.path("complexSwap").path("receipt"));
        List<Transfer> transfers = Erc20.transfers(complex, Tokens.usdc(1).orElseThrow().address());
        assertEquals(8, transfers.size());
        assertEquals(List.of(4, 5, 22, 23, 32, 33, 48, 49).stream().map(index -> fixture.path("complexSwap").path("receipt")
                .path("logs").get(index).path("data").asString())
                .map(value -> new BigInteger(value.substring(2), 16)).toList(),
                transfers.stream().map(Transfer::amount).toList());
    }

    @Test
    void tokensHaveValidChecksumsAndExactUnitConversion() {
        List<Long> chains = List.of(1L, 11155111L, 8453L, 84532L, 42161L, 421614L, 10L, 11155420L, 137L, 80002L, 43114L);
        for (long chain : chains) {
            TokenInfo token = Tokens.usdc(chain).orElseThrow();
            assertEquals(token.address(), Keys.toChecksumAddress(token.address()));
        }
        assertEquals(new BigInteger("1234567"), Tokens.toBaseUnits(new BigDecimal("1.234567"), 6));
        assertEquals(new BigDecimal("1.234567"), Tokens.fromBaseUnits(new BigInteger("1234567"), 6));
        assertThrows(IllegalArgumentException.class, () -> Tokens.toBaseUnits(new BigDecimal("0.0000001"), 6));
    }

    @Test
    void stablecoinRegistryHasExactAddressesAndLookupRules() {
        Map<String, Map<Long, String>> expected = Map.of(
                "USDC", Map.ofEntries(Map.entry(1L, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"), Map.entry(11155111L, "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"), Map.entry(8453L, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"), Map.entry(84532L, "0x036CbD53842c5426634e7929541eC2318f3dCF7e"), Map.entry(42161L, "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"), Map.entry(421614L, "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d"), Map.entry(10L, "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85"), Map.entry(11155420L, "0x5fd84259d66Cd46123540766Be93DFE6D43130D7"), Map.entry(137L, "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"), Map.entry(80002L, "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582"), Map.entry(43114L, "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E")),
                "USDT", Map.of(1L, "0xdAC17F958D2ee523a2206206994597C13D831ec7", 43114L, "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", 42161L, "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", 10L, "0x01bFF41798a0BcF287b996046Ca68b395DbC1071", 137L, "0xc2132D05D31c914a87C6611C10748AEb04B58e8F"),
                "EURC", Map.of(1L, "0x1aBaEA1f7C830bD89Acc67eC4af516284b1bC33c", 8453L, "0x60a3E35Cc302bFA44Cb288Bc5a4F316Fdb1adb42", 43114L, "0xC891EB4cbdEFf6e073e859e987815Ed1505c2ACD", 11155111L, "0x08210F9170F89Ab7658F0B5E3fF39b0E03C594D4", 84532L, "0x808456652fdb597867f38412077A9182bf77359F", 43113L, "0x5E44db7996c682E92a960b65AC713a54AD815c6B"),
                "PYUSD", Map.of(1L, "0x6c3ea9036406852006290770BEdFcAbA0e23A0e8", 42161L, "0x46850aD61C2B7d64d08c9C754F45254596696984", 137L, "0x99aF3EeA856556646C98c8B9b2548Fe815240750", 11155111L, "0xCaC524BcA292aaade2DF8A05cC58F0a65B1B3bB9", 421614L, "0x637A1259C6afd7E3AdF63993cA7E58BB438aB1B1", 80002L, "0x4549bb98c667aAb626627C118102c28065E8f54C"));
        expected.forEach((symbol, addresses) -> addresses.forEach((chain, address) -> {
            TokenInfo token = Tokens.find(symbol.toLowerCase(), chain).orElseThrow();
            assertEquals(symbol, token.symbol());
            assertEquals(address, token.address());
            assertEquals(address, Keys.toChecksumAddress(address));
            assertEquals(6, token.decimals());
        }));
        assertEquals(java.util.Set.of("USDC", "USDT", "EURC", "PYUSD"), Tokens.symbols());
        assertEquals(Tokens.find("USDT", 1), Tokens.usdt(1));
        assertEquals(Tokens.find("EURC", 43113), Tokens.eurc(43113));
        assertEquals(Tokens.find("PYUSD", 11155111), Tokens.pyusd(11155111));
        assertEquals("USD", Tokens.currency("USDC"));
        assertEquals("EUR", Tokens.currency("eurc"));
        assertEquals("USD", Tokens.currency("uSdT"));
        assertEquals("USD", Tokens.currency("pyusd"));
        assertEquals(expected.get("USDT").keySet(), Tokens.chains("USDT"));
        assertTrue(Tokens.find("unknown", 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Tokens.currency("unknown"));
    }

    @Test
    void usdtFixtureMatchesEncodingAndTransferLog() throws Exception {
        JsonNode usdt = MAPPER.readTree(getClass().getResourceAsStream("/fixtures/usdt-mainnet.json").readAllBytes());
        JsonNode simple = usdt.path("simpleTransfer");
        JsonNode tx = simple.path("transaction");
        JsonNode log = simple.path("receipt").path("logs").get(0);
        String recipient = "0x" + log.path("topics").get(2).asString().substring(26);
        BigInteger amount = new BigInteger(log.path("data").asString().substring(2), 16);
        assertEquals(tx.path("input").asString(), Erc20.transferData(recipient, amount));
        TransactionReceipt receipt = parseReceipt(simple.path("receipt"));
        List<Transfer> transfers = Erc20.transfers(receipt, Tokens.usdt(1).orElseThrow().address());
        assertEquals(1, transfers.size());
        assertEquals(recipient, transfers.getFirst().to());
        assertEquals(amount, transfers.getFirst().amount());

    }

    @Test
    void rpcErrorsAndNullResultsAreHandled() {
        EthRpcClient error = new EthRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"bad\"}}");
        EthRpcException exception = assertThrows(EthRpcException.class, error::blockNumber);
        assertEquals(-32000, exception.getCode());
        EthRpcClient empty = new EthRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}");
        assertTrue(empty.getTransactionReceipt("0x1").isEmpty());
        assertTrue(empty.getTransactionByHash("0x1").isEmpty());
    }

    @Test
    void fakeJsonRpcTransportServesFixtureMethods() {
        FixtureTransport transport = new FixtureTransport(fixture);
        EthRpcClient rpc = new EthRpcClient(transport);
        assertEquals(1L, rpc.chainId());
        assertEquals("0x" + fixture.path("simpleTransfer").path("transaction").path("hash").asString().substring(2),
                rpc.getTransactionByHash(fixture.path("simpleTransfer").path("transaction").path("hash").asString()).orElseThrow().hash());
        assertTrue(rpc.getTransactionReceipt("0xunknown").isEmpty());
    }

    static TransactionReceipt parseReceipt(JsonNode node) {
        List<LogEntry> logs = new java.util.ArrayList<>();
        for (JsonNode log : node.path("logs")) {
            List<String> topics = new java.util.ArrayList<>();
            for (JsonNode topic : log.path("topics")) topics.add(topic.asString());
            logs.add(new LogEntry(log.path("address").asString(), topics, log.path("data").asString(),
                    EthRpcClient.hexLong(log.path("logIndex").asString())));
        }
        return new TransactionReceipt(node.path("transactionHash").asString(),
                EthRpcClient.hexLong(node.path("blockNumber").asString()), "0x1".equals(node.path("status").asString()),
                node.path("from").asString(), node.path("to").asString(), logs);
    }

    public static final class FixtureTransport implements JsonRpcTransport {
        private final JsonNode fixture;
        public String blockNumber = "0x18e8a45";
        public JsonNode receipt;
        public boolean missingReceipt;
        public FixtureTransport(JsonNode fixture) { this.fixture = fixture; }
        @Override public String send(String requestJson) throws IOException {
            JsonNode request = MAPPER.readTree(requestJson);
            String method = request.path("method").asString();
            String requestedHash = request.path("params").isArray() && !request.path("params").isEmpty()
                    ? request.path("params").get(0).asString() : "";
            String fixtureHash = fixture.path("simpleTransfer").path("transaction").path("hash").asString();
            JsonNode value = switch (method) {
                case "eth_chainId" -> MAPPER.valueToTree("0x1");
                case "eth_blockNumber" -> MAPPER.valueToTree(blockNumber);
                case "eth_getTransactionReceipt" -> missingReceipt || !fixtureHash.equalsIgnoreCase(requestedHash) ? MAPPER.nullNode()
                        : receipt == null ? fixture.path("simpleTransfer").path("receipt") : receipt;
                case "eth_getTransactionByHash" -> fixtureHash.equalsIgnoreCase(requestedHash)
                        ? fixture.path("simpleTransfer").path("transaction") : MAPPER.nullNode();
                default -> MAPPER.nullNode();
            };
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.set("result", value);
            return response.toString();
        }
    }
}
