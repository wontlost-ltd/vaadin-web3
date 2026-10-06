package com.wontlost.web3.test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.pay.InMemoryPaymentLedger;
import com.wontlost.web3.pay.PaymentVerifier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Creates a JSON-RPC test transport with a configurable ERC-20 receipt and canonical block. */
public final class PaymentTestSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PaymentTestSupport() { }

    /**
     * Creates a fake RPC transport for one successful ERC-20 transfer receipt. Set {@code canonicalHash} different from
     * {@code blockHash} to simulate a reorg.
     */
    public static ConfiguredPayment rpc(String txHash, String token, String from, String to, BigInteger amount,
            long blockNumber, long currentHeight, String blockHash, String canonicalHash) {
        return new ConfiguredPayment(new FakeTransport(txHash, token, from, to, amount,
                blockNumber, currentHeight, blockHash, canonicalHash));
    }

    /** A reusable transport, registry, verifier, and transaction identifier for payment assertions. */
    public static final class ConfiguredPayment {
        private final FakeTransport transport;
        private final ChainRegistry chains = new ChainRegistry();
        private final String txHash;

        private ConfiguredPayment(FakeTransport transport) {
            this.transport = transport;
            this.txHash = transport.txHash;
            chains.register(transport.chainId, new EthRpcClient(transport));
        }

        /** Sets the canonical hash returned for the receipt block. */
        public ConfiguredPayment canonicalHash(String value) { transport.canonicalHash = value; return this; }
        /** Sets the current chain height. */
        public ConfiguredPayment currentHeight(long value) { transport.currentHeight = value; return this; }
        /** Returns the JSON-RPC test transport. */
        public JsonRpcTransport transport() { return transport; }
        /** Returns the RPC-backed chain registry. */
        public ChainRegistry chains() { return chains; }
        /** Creates a PaymentVerifier backed by a fresh in-memory claim ledger. */
        public PaymentVerifier verifier() { return new PaymentVerifier(chains, new InMemoryPaymentLedger()); }
        /** Returns the transaction hash described by this fixture. */
        public String txHash() { return txHash; }
    }

    private static final class FakeTransport implements JsonRpcTransport {
        private final String txHash;
        private final String token;
        private final String from;
        private final String to;
        private final BigInteger amount;
        private final long blockNumber;
        private long currentHeight;
        private final String blockHash;
        private String canonicalHash;
        private final long chainId = 1;

        private FakeTransport(String txHash, String token, String from, String to, BigInteger amount,
                long blockNumber, long currentHeight, String blockHash, String canonicalHash) {
            this.txHash = Objects.requireNonNull(txHash);
            this.token = Objects.requireNonNull(token);
            this.from = Objects.requireNonNull(from);
            this.to = Objects.requireNonNull(to);
            this.amount = Objects.requireNonNull(amount);
            this.blockNumber = blockNumber;
            this.currentHeight = currentHeight;
            this.blockHash = Objects.requireNonNull(blockHash);
            this.canonicalHash = Objects.requireNonNull(canonicalHash);
        }

        @Override
        public String send(String requestJson) throws IOException {
            JsonNode request = MAPPER.readTree(requestJson);
            String method = request.path("method").asString();
            JsonNode params = request.path("params");
            JsonNode result = switch (method) {
                case "eth_chainId" -> MAPPER.valueToTree("0x" + Long.toHexString(chainId));
                case "eth_blockNumber" -> MAPPER.valueToTree(hex(currentHeight));
                case "eth_getTransactionReceipt" -> params.get(0).asString().equalsIgnoreCase(txHash) ? receipt() : MAPPER.nullNode();
                case "eth_getBlockByNumber" -> block(params.get(0).asString());
                default -> MAPPER.nullNode();
            };
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.set("result", result);
            return response.toString();
        }

        private JsonNode receipt() {
            ObjectNode receipt = MAPPER.createObjectNode();
            receipt.put("transactionHash", txHash);
            receipt.put("blockNumber", hex(blockNumber));
            receipt.put("blockHash", blockHash);
            receipt.put("status", "0x1");
            receipt.put("from", from);
            receipt.put("to", token);
            ObjectNode log = MAPPER.createObjectNode();
            log.put("address", token);
            log.set("topics", MAPPER.valueToTree(List.of(
                    "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                    topic(from), topic(to))));
            log.put("data", "0x" + amount.toString(16));
            log.put("logIndex", "0x0");
            receipt.set("logs", MAPPER.valueToTree(List.of(log)));
            return receipt;
        }

        private JsonNode block(String tag) {
            String hash = "finalized".equals(tag) ? canonicalHash : canonicalHash;
            String number = "finalized".equals(tag) ? hex(currentHeight) : tag;
            return MAPPER.createObjectNode().put("number", number).put("timestamp", "0x65a00000").put("hash", hash);
        }

        private static String topic(String address) {
            String value = address.startsWith("0x") ? address.substring(2) : address;
            return "0x" + "0".repeat(64 - value.length()) + value.toLowerCase();
        }

        private static String hex(long value) { return "0x" + Long.toHexString(value); }
    }
}
