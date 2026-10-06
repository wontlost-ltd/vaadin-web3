package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EthRpcClientLogsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADDRESS = "0x" + "ab".repeat(20);
    private static final String TOPIC = "0x" + "cd".repeat(32);

    @Test void sendsHexRangeAndPositionalWildcardAndOrTopics() throws IOException {
        AtomicReference<String> sent = new AtomicReference<>();
        EthRpcClient client = new EthRpcClient(request -> {
            sent.set(request);
            return response(request, "[]");
        });
        List<List<String>> topics = new java.util.ArrayList<>();
        topics.add(List.of(TOPIC, "0x" + "ef".repeat(32)));
        topics.add(null);
        topics.add(List.of(TOPIC));

        assertTrue(client.getLogs(new LogFilter(16, 31, List.of(ADDRESS), topics)).isEmpty());

        JsonNode request = JSON.readTree(sent.get());
        assertEquals("eth_getLogs", request.path("method").asString());
        JsonNode filter = request.path("params").get(0);
        assertEquals("0x10", filter.path("fromBlock").asString());
        assertEquals("0x1f", filter.path("toBlock").asString());
        assertEquals(ADDRESS, filter.path("address").get(0).asString());
        assertEquals(2, filter.path("topics").get(0).size());
        assertTrue(filter.path("topics").get(1).isNull());
        assertEquals(TOPIC, filter.path("topics").get(2).get(0).asString());
    }

    @Test void parsesMultipleLogsAndEmptyResult() {
        EthRpcClient client = new EthRpcClient(request -> response(request, "[{\"address\":\"" + ADDRESS
                + "\",\"topics\":[\"" + TOPIC + "\"],\"data\":\"0x01\",\"transactionHash\":\"0x"
                + "11".repeat(32) + "\",\"transactionIndex\":\"0x2\",\"blockNumber\":\"0xa\",\"blockHash\":\"0x"
                + "22".repeat(32) + "\",\"logIndex\":\"0x3\",\"removed\":true},{\"address\":\"" + ADDRESS
                + "\",\"topics\":[],\"data\":\"0x\",\"transactionHash\":\"0x" + "33".repeat(32)
                + "\",\"transactionIndex\":\"0x0\",\"blockNumber\":\"0xb\",\"blockHash\":\"0x"
                + "44".repeat(32) + "\",\"logIndex\":\"0x4\",\"removed\":false}]"));

        List<EthLog> logs = client.getLogs(new LogFilter(10, 11, null, List.of()));
        assertEquals(2, logs.size());
        assertEquals(2, logs.get(0).transactionIndex());
        assertEquals(10, logs.get(0).blockNumber());
        assertEquals(3, logs.get(0).logIndex());
        assertTrue(logs.get(0).removed());
        assertFalse(logs.get(1).removed());

        assertTrue(new EthRpcClient(request -> response(request, "[]"))
                .getLogs(new LogFilter(10, 11, null, List.of())).isEmpty());
    }

    @Test void exposesOriginalRpcLimitError() {
        EthRpcException error = assertThrows(EthRpcException.class, () -> new EthRpcClient(request ->
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"query returned more than 10000 results\"}}")
                .getLogs(new LogFilter(0, 100, null, List.of())));
        assertEquals(-32005, error.getCode());
        assertTrue(error.getMessage().contains("query returned more than 10000 results"));
    }

    @Test void rejectsMissingOrMalformedLogFields() {
        EthRpcClient missing = new EthRpcClient(request -> response(request, "[{\"address\":\"" + ADDRESS + "\"}]"));
        assertThrows(IllegalStateException.class,
                () -> missing.getLogs(new LogFilter(0, 0, null, List.of())));
        EthRpcClient malformed = new EthRpcClient(request -> response(request, "[{\"address\":\"" + ADDRESS
                + "\",\"topics\":[],\"data\":\"0x\",\"transactionHash\":\"0x\",\"transactionIndex\":\"0x0\""
                + ",\"blockNumber\":\"0x0\",\"blockHash\":\"0x" + "22".repeat(32)
                + "\",\"logIndex\":\"0x0\",\"removed\":false}]"));
        assertThrows(IllegalStateException.class,
                () -> malformed.getLogs(new LogFilter(0, 0, null, List.of())));
    }

    @Test void validatesFilterAndEncodesErc20RecipientTopic() {
        assertThrows(IllegalArgumentException.class, () -> new LogFilter(-1, 1, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LogFilter(2, 1, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LogFilter(0, 1, List.of("0x12"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LogFilter(0, 1, null, List.of(List.of("0x12"))));

        String token = "0x" + "AB".repeat(20);
        String recipient = "0x" + "12".repeat(20);
        LogFilter filter = LogFilter.erc20Transfers(1, 2, token, recipient);
        assertEquals(token.toLowerCase(java.util.Locale.ROOT), filter.addresses().getFirst());
        assertEquals("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", filter.topics().get(0).getFirst());
        assertEquals("0x" + "0".repeat(24) + "12".repeat(20), filter.topics().get(2).getFirst());
    }

    @Test void pinnedViewSupportsGetLogs() {
        FailoverJsonRpcTransport transport = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("node", request -> response(request, "[]"))));
        assertTrue(new EthRpcClient(transport).pinned()
                .getLogs(new LogFilter(0, 0, null, List.of())).isEmpty());
    }

    private static String response(String request, String result) throws IOException {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.readTree(request).path("id").asString()
                + ",\"result\":" + result + "}";
    }

    @Test
    void missingRemovedFieldMeansNotRemoved() {
        String log = "{\"address\":\"0x" + "11".repeat(20) + "\",\"topics\":[],\"data\":\"0x\",\"transactionHash\":\"0x"
                + "22".repeat(32) + "\",\"transactionIndex\":\"0x0\",\"blockNumber\":\"0x1\",\"blockHash\":\"0x"
                + "33".repeat(32) + "\",\"logIndex\":\"0x0\"}";
        EthRpcClient client = new EthRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[" + log + "]}");
        assertFalse(client.getLogs(new LogFilter(0, 1, null, null)).getFirst().removed());
    }
}
