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
    /** Returns the account transaction count at the supplied block tag. */
    public BigInteger getTransactionCount(String address, String blockTag) {
        return hexBigInteger(result("eth_getTransactionCount", List.of(address, blockTag)).asString());
    }
    /** Estimates gas for a JSON transaction object. */
    public BigInteger estimateGas(String txJson) {
        return hexBigInteger(result("eth_estimateGas", List.of(MAPPER.readTree(txJson))).asString());
    }
    /** Returns the legacy gas price. */
    public BigInteger gasPrice() { return hexBigInteger(result("eth_gasPrice", List.of()).asString()); }
    /** Returns the suggested EIP-1559 priority fee. */
    public BigInteger maxPriorityFeePerGas() {
        return hexBigInteger(result("eth_maxPriorityFeePerGas", List.of()).asString());
    }
    /** Returns the latest block base fee, or {@code null} when the node omits it. */
    public BigInteger latestBaseFee() {
        JsonNode block = getBlockByNumber("latest");
        JsonNode fee = block.path("baseFeePerGas");
        return fee.isMissingNode() || fee.isNull() ? null : hexBigInteger(fee.asString());
    }
    /** Broadcasts a signed raw transaction and returns its hash. */
    public String sendRawTransaction(String hex) {
        return result("eth_sendRawTransaction", List.of(hex)).asString();
    }
    /** Executes an {@code eth_call} against the requested block tag; a {@code null} target runs creation code. */
    public String call(String to, String dataHex, String blockTag) {
        ObjectNode call = MAPPER.createObjectNode();
        if (to != null) call.put("to", to);
        call.put("data", dataHex);
        return result("eth_call", List.of(call, blockTag)).asString();
    }
    /** Returns the timestamp of a mined block. */
    public java.time.Instant blockTimestamp(long blockNumber) {
        JsonNode block = getBlockByNumber("0x" + Long.toHexString(blockNumber));
        if (block.isNull()) throw new IllegalStateException("Block " + blockNumber + " is not available");
        return java.time.Instant.ofEpochSecond(hexLong(block.path("timestamp").asString()));
    }
    /** Returns a canonical block's number and hash for the supplied block tag or hex height. */
    public BlockReference getBlockReference(String blockTag) {
        JsonNode block = getBlockByNumber(blockTag);
        if (block.isNull()) return null;
        return new BlockReference(hexLong(block.path("number").asString()), nullableString(block.path("hash")));
    }
    private JsonNode getBlockByNumber(String blockTag) {
        return result("eth_getBlockByNumber", List.of(blockTag, false));
    }
    /** Canonical block identity returned by an Ethereum node. */
    public record BlockReference(long number, String hash) { }
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
                nullableString(node.path("from")), nullableString(node.path("to")), logs,
                nullableString(node.path("blockHash"))));
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

    /** Sends an arbitrary JSON-RPC request and returns its {@code result}; RPC errors throw {@link EthRpcException}. */
    public JsonNode request(String method, List<?> params) {
        return result(method, params);
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
