package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

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
