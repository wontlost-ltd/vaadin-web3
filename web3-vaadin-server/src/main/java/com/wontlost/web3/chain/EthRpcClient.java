package com.wontlost.web3.chain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Small thread-safe Ethereum JSON-RPC client. */
public final class EthRpcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonRpcTransport transport;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicReference<Long> maxObservedHead;
    private final Integer lagTolerance;
    private final FailoverJsonRpcTransport failoverOwner;
    private final boolean pinnedView;

    /** Creates a client using a custom JSON-RPC transport. */
    public EthRpcClient(JsonRpcTransport transport) {
        this.transport = Objects.requireNonNull(transport);
        this.maxObservedHead = new AtomicReference<>(-1L);
        this.lagTolerance = transport instanceof FailoverJsonRpcTransport failover
                ? failover.config().lagTolerance() : null;
        this.failoverOwner = transport instanceof FailoverJsonRpcTransport failover ? failover : null;
        this.pinnedView = false;
    }
    private EthRpcClient(JsonRpcTransport transport, AtomicReference<Long> maxObservedHead, Integer lagTolerance,
                         FailoverJsonRpcTransport failoverOwner, boolean pinnedView) {
        this.transport = Objects.requireNonNull(transport);
        this.maxObservedHead = maxObservedHead;
        this.lagTolerance = lagTolerance;
        this.failoverOwner = failoverOwner;
        this.pinnedView = pinnedView;
    }
    /** Creates a client backed by the default HTTP transport. */
    public EthRpcClient(String endpoint) { this(new HttpJsonRpcTransport(endpoint)); }

    /** Returns this endpoint's EVM chain id. */
    public long chainId() { return hexLong(result("eth_chainId", List.of()).asString()); }
    /** Returns the latest block number. */
    public long blockNumber() {
        long observed = hexLong(result("eth_blockNumber", List.of()).asString());
        if (lagTolerance != null) {
            long maximum = maxObservedHead.get();
            if (maximum >= 0 && observed < maximum - lagTolerance) {
                if (failoverOwner != null && !pinnedView)
                    failoverOwner.rejectActiveForLag("Endpoint head " + observed + " trails observed head " + maximum);
                throw new EthRpcException(-32000, "unknown block: endpoint head " + observed + " trails observed head " + maximum);
            }
        }
        maxObservedHead.accumulateAndGet(observed, Math::max);
        return observed;
    }
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
        String expectedHash = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(hex)));
        try { return result("eth_sendRawTransaction", List.of(hex)).asString(); }
        catch (EthRpcException exception) {
            if (isKnownTransaction(exception)) {
                if (getTransactionByHash(expectedHash).isPresent()) return expectedHash;
            }
            throw exception;
        }
    }

    /**
     * Returns this client when ordinary requests already have endpoint affinity, otherwise a fixed endpoint view.
     * Reads made through a failover view stay on one endpoint; a failure is propagated to the caller.
     */
    public EthRpcClient pinned() {
        if (!(transport instanceof FailoverJsonRpcTransport failover)) return this;
        return new EthRpcClient(failover.pinned(), maxObservedHead, lagTolerance, failover, true);
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
    /** Returns logs matching the filter on this client's current endpoint view. */
    public List<EthLog> getLogs(LogFilter filter) {
        Objects.requireNonNull(filter, "filter");
        ObjectNode criteria = MAPPER.createObjectNode();
        criteria.put("fromBlock", "0x" + Long.toHexString(filter.fromBlock()));
        criteria.put("toBlock", "0x" + Long.toHexString(filter.toBlock()));
        if (filter.addresses() != null && !filter.addresses().isEmpty()) {
            ArrayNode addresses = criteria.putArray("address");
            filter.addresses().forEach(addresses::add);
        }
        if (filter.topics() != null && !filter.topics().isEmpty()) {
            ArrayNode topics = criteria.putArray("topics");
            for (List<String> position : filter.topics()) {
                if (position == null) {
                    topics.addNull();
                } else {
                    ArrayNode alternatives = topics.addArray();
                    position.forEach(alternatives::add);
                }
            }
        }
        JsonNode result = result("eth_getLogs", List.of(criteria));
        if (!result.isArray()) throw new IllegalStateException("eth_getLogs result is not an array");
        List<EthLog> logs = new ArrayList<>();
        for (JsonNode log : result) logs.add(parseLog(log));
        return List.copyOf(logs);
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
            String requestJson = request.toString();
            String responseJson = transport instanceof FailoverJsonRpcTransport failover
                    && "eth_sendRawTransaction".equals(method)
                    ? failover.sendRawTransactionRequest(requestJson) : transport.send(requestJson);
            JsonNode response = MAPPER.readTree(responseJson);
            JsonNode error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                JsonNode data = error.path("data");
                throw new EthRpcException(error.path("code").asInt(), error.path("message").asString("JSON-RPC error"),
                        data.isMissingNode() || data.isNull() ? null : data.toString());
            }
            JsonNode result = response.path("result");
            if (result.isMissingNode()) throw new IllegalStateException("JSON-RPC response has no result");
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("JSON-RPC transport failed", exception);
        }
    }
    private static EthLog parseLog(JsonNode log) {
        if (!log.isObject()) throw new IllegalStateException("eth_getLogs entry is not an object");
        String address = requiredString(log, "address");
        if (!address.matches("0x[0-9a-fA-F]{40}")) throw invalidLog("address");
        JsonNode topicNodes = log.path("topics");
        if (!topicNodes.isArray()) throw invalidLog("topics");
        List<String> topics = new ArrayList<>();
        for (JsonNode topic : topicNodes) {
            if (!topic.isString() || !topic.asString().matches("0x[0-9a-fA-F]{64}")) throw invalidLog("topics");
            topics.add(topic.asString().toLowerCase(java.util.Locale.ROOT));
        }
        String data = requiredString(log, "data");
        if (!data.matches("0x(?:[0-9a-fA-F]{2})*")) throw invalidLog("data");
        String transactionHash = requiredHash(log, "transactionHash");
        long transactionIndex = requiredQuantity(log, "transactionIndex");
        long blockNumber = requiredQuantity(log, "blockNumber");
        String blockHash = requiredHash(log, "blockHash");
        long logIndex = requiredQuantity(log, "logIndex");
        // 部分节点省略 removed 字段，缺省视为 false
        JsonNode removedNode = log.path("removed");
        if (!removedNode.isMissingNode() && !removedNode.isNull() && !removedNode.isBoolean()) throw invalidLog("removed");
        return new EthLog(address.toLowerCase(java.util.Locale.ROOT), List.copyOf(topics), data.toLowerCase(java.util.Locale.ROOT),
                transactionHash.toLowerCase(java.util.Locale.ROOT), transactionIndex, blockNumber,
                blockHash.toLowerCase(java.util.Locale.ROOT), logIndex, removedNode.asBoolean());
    }
    private static String requiredHash(JsonNode log, String field) {
        String value = requiredString(log, field);
        if (!value.matches("0x[0-9a-fA-F]{64}")) throw invalidLog(field);
        return value;
    }
    private static String requiredString(JsonNode log, String field) {
        JsonNode value = log.path(field);
        if (!value.isString() || value.asString().isBlank()) throw invalidLog(field);
        return value.asString();
    }
    private static long requiredQuantity(JsonNode log, String field) {
        String value = requiredString(log, field);
        if (!value.matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)")) throw invalidLog(field);
        try { return hexLong(value); }
        catch (ArithmeticException | NumberFormatException exception) { throw invalidLog(field); }
    }
    private static IllegalStateException invalidLog(String field) {
        return new IllegalStateException("Invalid or missing eth_getLogs field: " + field);
    }
    private static boolean isKnownTransaction(EthRpcException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("already known") || message.contains("known transaction") || message.contains("nonce too low");
    }
    private static String nullableString(JsonNode value) { return value.isNull() ? null : value.asString(); }
    static long hexLong(String value) { return hexBigInteger(value).longValueExact(); }
    static BigInteger hexBigInteger(String value) {
        if (value == null || value.isBlank() || "0x".equals(value)) return BigInteger.ZERO;
        return new BigInteger(value.startsWith("0x") ? value.substring(2) : value, 16);
    }
}
