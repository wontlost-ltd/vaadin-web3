package com.wontlost.web3.chain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Small thread-safe Ethereum JSON-RPC client. */
public final class EthRpcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonRpcTransport transport;
    private final AtomicLong ids = new AtomicLong();

    /** Creates a client using a custom JSON-RPC transport. */
    public EthRpcClient(JsonRpcTransport transport) { this.transport = Objects.requireNonNull(transport); }
    /** Creates a client backed by the default HTTP transport. */
    public EthRpcClient(String endpoint) { this(new HttpJsonRpcTransport(endpoint)); }

    /** Returns this endpoint's EVM chain id. */
    public long chainId() { return hexLong(result("eth_chainId", List.of()).asString()); }
    /** Returns the latest block number. */
    public long blockNumber() { return hexLong(result("eth_blockNumber", List.of()).asString()); }
    /** Executes an {@code eth_call} against the requested block tag; a {@code null} target runs creation code. */
    public String call(String to, String dataHex, String blockTag) {
        ObjectNode call = MAPPER.createObjectNode();
        if (to != null) call.put("to", to);
        call.put("data", dataHex);
        return result("eth_call", List.of(call, blockTag)).asString();
    }
    /** Returns the receipt when the transaction has been mined. */
    public Optional<TransactionReceipt> getTransactionReceipt(String hash) {
        JsonNode node = result("eth_getTransactionReceipt", List.of(hash));
        if (node.isNull()) return Optional.empty();
        List<LogEntry> logs = new ArrayList<>();
        for (JsonNode log : node.path("logs")) {
            List<String> topics = new ArrayList<>();
            for (JsonNode topic : log.path("topics")) topics.add(topic.asString());
            logs.add(new LogEntry(log.path("address").asString(), topics,
                    log.path("data").asString(), hexLong(log.path("logIndex").asString())));
        }
        return Optional.of(new TransactionReceipt(node.path("transactionHash").asString(),
                hexLong(node.path("blockNumber").asString()), "0x1".equalsIgnoreCase(node.path("status").asString()),
                nullableString(node.path("from")), nullableString(node.path("to")), logs));
    }
    /** Returns the transaction when it is known to this endpoint. */
    public Optional<EthTransaction> getTransactionByHash(String hash) {
        JsonNode node = result("eth_getTransactionByHash", List.of(hash));
        if (node.isNull()) return Optional.empty();
        JsonNode block = node.path("blockNumber");
        return Optional.of(new EthTransaction(node.path("hash").asString(),
                nullableString(node.path("from")), nullableString(node.path("to")),
                node.path("input").asString(), hexBigInteger(node.path("value").asString()),
                block.isNull() ? null : hexLong(block.asString())));
    }

    private JsonNode result(String method, List<?> params) {
        long id = ids.incrementAndGet();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", MAPPER.valueToTree(params));
        try {
            JsonNode response = MAPPER.readTree(transport.send(request.toString()));
            JsonNode error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new EthRpcException(error.path("code").asInt(), error.path("message").asString("JSON-RPC error"));
            }
            JsonNode result = response.path("result");
            if (result.isMissingNode()) throw new IllegalStateException("JSON-RPC response has no result");
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("JSON-RPC transport failed", exception);
        }
    }
    private static String nullableString(JsonNode value) { return value.isNull() ? null : value.asString(); }
    static long hexLong(String value) { return hexBigInteger(value).longValueExact(); }
    static BigInteger hexBigInteger(String value) {
        if (value == null || value.isBlank() || "0x".equals(value)) return BigInteger.ZERO;
        return new BigInteger(value.startsWith("0x") ? value.substring(2) : value, 16);
    }
}
