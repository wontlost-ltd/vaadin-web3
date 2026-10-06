package com.wontlost.web3.dev;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import com.wontlost.web3.ServerWallet;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.EthRpcException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A server-signed development wallet for local, valueless chains. */
public final class DevWallet implements ServerWallet {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BATCHES = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 只读方法透传到配置的节点，与浏览器钱包行为一致；任何签名类方法都不在此列。 */
    private static final java.util.Set<String> READ_ONLY_METHODS = java.util.Set.of(
            "eth_blockNumber",
            "eth_call",
            "eth_estimateGas",
            "eth_feeHistory",
            "eth_gasPrice",
            "eth_getBalance",
            "eth_getBlockByHash",
            "eth_getBlockByNumber",
            "eth_getCode",
            "eth_getLogs",
            "eth_getStorageAt",
            "eth_getTransactionByHash",
            "eth_getTransactionCount",
            "eth_getTransactionReceipt",
            "eth_maxPriorityFeePerGas",
            "net_version");
    private static final String ANVIL_KEY = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private final ECKeyPair keyPair;
    private final long chain;
    private final String address;
    private final EthRpcClient rpc;
    private final Map<String, CallsBatch> batches = new LinkedHashMap<>();

    /** Creates a wallet. The private key is retained only on this server. */
    public DevWallet(String privateKeyHex, long chainId, EthRpcClient rpcOrNull) {
        if (chainId < 0) throw new IllegalArgumentException("chainId must not be negative");
        String privateKey = Numeric.cleanHexPrefix(Objects.requireNonNull(privateKeyHex, "privateKeyHex"));
        this.keyPair = ECKeyPair.create(new BigInteger(privateKey, 16));
        this.chain = chainId;
        this.address = Keys.toChecksumAddress(Keys.getAddress(keyPair));
        this.rpc = rpcOrNull;
    }

    /**
     * Creates the public Anvil account #0 wallet. Its private key is public and must only be used on local chains
     * with no value; never use it for real assets or a publicly accessible network.
     */
    public static DevWallet anvilDefault(long chainId, EthRpcClient rpc) {
        return new DevWallet(ANVIL_KEY, chainId, rpc);
    }

    @Override public String name() { return "Development wallet"; }
    @Override public String rdns() { return "com.wontlost.development-wallet"; }
    @Override public List<String> accounts() { return List.of(address); }
    @Override public String chainId() { return "0x" + Long.toHexString(chain); }

    /**
     * Processes wallet requests with all signing performed on this server. Typed-data domain chain mismatches use
     * error 4901; attempts to switch to another chain use error 4902.
     */
    @Override
    public CompletableFuture<String> request(String method, String paramsJson) {
        try {
            Object result = switch (method) {
                case "eth_accounts", "eth_requestAccounts" -> accounts();
                case "eth_chainId" -> chainId();
                case "personal_sign" -> personalSign(paramsJson);
                case "eth_signTypedData_v4" -> typedDataSign(paramsJson);
                case "eth_sendTransaction" -> sendTransaction(paramsJson);
                case "wallet_getCapabilities" -> getCapabilities(paramsJson);
                case "wallet_sendCalls" -> sendCalls(paramsJson);
                case "wallet_getCallsStatus" -> getCallsStatus(paramsJson);
                case "wallet_showCallsStatus" -> showCallsStatus(paramsJson);
                case "wallet_switchEthereumChain" -> switchChain(paramsJson);
                default -> readOnly(method, paramsJson);
            };
            return CompletableFuture.completedFuture(MAPPER.writeValueAsString(result));
        } catch (ServerWalletException exception) {
            return CompletableFuture.failedFuture(exception);
        } catch (EthRpcException exception) {
            return CompletableFuture.failedFuture(new ServerWalletException(exception.getCode(), exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(new ServerWalletException(-32602, "invalid request parameters"));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(new ServerWalletException(4900, "RPC request failed"));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(new ServerWalletException(-32602, "invalid request parameters"));
        }
    }

    private String personalSign(String paramsJson) throws Exception {
        JsonNode params = params(paramsJson, 2);
        requireAddress(params.get(1).asString());
        String message = params.get(0).asString();
        byte[] bytes = message.startsWith("0x") && message.length() % 2 == 0 && message.substring(2).matches("[0-9a-fA-F]*")
                ? HexFormat.of().parseHex(message.substring(2)) : message.getBytes(StandardCharsets.UTF_8);
        return signature(Sign.signPrefixedMessage(bytes, keyPair));
    }

    private String typedDataSign(String paramsJson) throws Exception {
        JsonNode params = params(paramsJson, 2);
        requireAddress(params.get(0).asString());
        String typedData = params.get(1).asString();
        JsonNode domain = MAPPER.readTree(typedData).path("domain");
        if (!domain.path("chainId").isMissingNode() && !domain.path("chainId").isNull()
                && quantity(domain.path("chainId").asString()).longValueExact() != chain) {
            throw new ServerWalletException(4901, "typed data domain chain does not match wallet chain");
        }
        return signature(Sign.signMessage(new StructuredDataEncoder(typedData).hashStructuredData(), keyPair, false));
    }

    private synchronized String sendTransaction(String paramsJson) throws Exception {
        JsonNode params = params(paramsJson, 1);
        JsonNode tx = params.get(0);
        requireAddress(tx.path("from").asString());
        if (rpc == null) throw new ServerWalletException(4200, "no RPC configured");
        String to = tx.path("to").asString("");
        String data = tx.path("data").asString("0x");
        BigInteger value = optionalQuantity(tx, "value", BigInteger.ZERO);
        BigInteger nonce = tx.has("nonce") ? quantity(tx.path("nonce").asString())
                : rpc.getTransactionCount(address, "pending");
        ObjectNode estimate = MAPPER.createObjectNode().put("from", address).put("data", data);
        if (!to.isEmpty()) estimate.put("to", to);
        estimate.put("value", Numeric.encodeQuantity(value));
        BigInteger gas = tx.has("gas") ? quantity(tx.path("gas").asString()) : rpc.estimateGas(estimate.toString());

        byte[] raw = signTransaction(tx, nonce, gas, to, value, data);
        return rpc.sendRawTransaction(Numeric.toHexString(raw));
    }

    private Object getCapabilities(String paramsJson) throws Exception {
        JsonNode params = MAPPER.readTree(paramsJson);
        if (!params.isArray() || params.size() < 1 || params.size() > 2) throw new IllegalArgumentException("invalid params");
        requireAddress(params.get(0).asString());
        JsonNode chainIds = params.get(1);
        if (chainIds != null && !chainIds.isNull() && !chainIds.isArray()) throw new IllegalArgumentException("invalid chainIds");
        if (chainIds != null && chainIds.isArray()) {
            boolean requested = false;
            for (JsonNode id : chainIds) {
                if (!id.isString()) throw new IllegalArgumentException("invalid chainId");
                if (chainId().equalsIgnoreCase(id.asString())) requested = true;
            }
            if (!requested) return MAPPER.createObjectNode();
        }
        ObjectNode atomic = MAPPER.createObjectNode().put("status", "unsupported");
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("atomic", atomic);
        ObjectNode result = MAPPER.createObjectNode();
        result.set(chainId(), capabilities);
        return result;
    }

    /** 批次只保存在内存中，服务重启后状态会丢失；此能力仅用于本地开发和测试。 */
    private synchronized Object sendCalls(String paramsJson) throws Exception {
        JsonNode params = params(paramsJson, 1);
        JsonNode request = params.get(0);
        if (!"2.0.0".equals(text(request, "version"))) throw new ServerWalletException(-32602, "unsupported calls version");
        String requestedChain = text(request, "chainId");
        if (requestedChain == null || !requestedChain.matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)")
                || !chainId().equalsIgnoreCase(requestedChain))
            throw new ServerWalletException(5710, "unsupported chain id");
        if (request.has("from")) requireAddress(text(request, "from"));
        JsonNode calls = request.path("calls");
        if (!calls.isArray() || calls.isEmpty()) throw new IllegalArgumentException("calls must not be empty");
        for (JsonNode call : calls) validateCall(call);
        rejectRequiredCapabilities(request.path("capabilities"));
        for (JsonNode call : calls) rejectRequiredCapabilities(call.path("capabilities"));
        if (!request.path("atomicRequired").isBoolean()) throw new IllegalArgumentException("atomicRequired must be boolean");
        if (request.path("atomicRequired").asBoolean()) throw new ServerWalletException(5760, "atomic calls are not supported");
        if (rpc == null) throw new ServerWalletException(4200, "no RPC configured");

        String id = request.has("id") ? text(request, "id") : newBatchId();
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("invalid calls id");
        if (request.has("id") && batches.containsKey(id)) throw new ServerWalletException(5720, "duplicate calls id");
        while (!request.has("id") && batches.containsKey(id)) id = newBatchId();
        CallsBatch batch = new CallsBatch(id, chainId());
        batches.put(id, batch);
        while (batches.size() > MAX_BATCHES) batches.remove(batches.keySet().iterator().next());

        for (JsonNode call : calls) {
            try {
                ObjectNode transaction = MAPPER.createObjectNode().put("from", address);
                if (call.has("to")) transaction.put("to", call.path("to").asString());
                if (call.has("data")) transaction.put("data", call.path("data").asString());
                if (call.has("value")) transaction.put("value", call.path("value").asString());
                batch.hashes.add(sendTransaction(MAPPER.writeValueAsString(List.of(transaction))));
            } catch (Exception exception) {
                batch.failure = exception.getMessage() == null ? "transaction send failed" : exception.getMessage();
                break;
            }
        }
        ObjectNode response = MAPPER.createObjectNode().put("id", id);
        return response;
    }

    private synchronized Object getCallsStatus(String paramsJson) throws Exception {
        JsonNode params = params(paramsJson, 1);
        CallsBatch batch = findBatch(params.get(0).asString());
        ArrayList<JsonNode> receipts = new ArrayList<>();
        int reverted = 0;
        for (String hash : batch.hashes) {
            JsonNode receipt = rpc.request("eth_getTransactionReceipt", List.of(hash));
            if (receipt.isNull()) continue;
            receipts.add(receipt);
            if (!"0x1".equalsIgnoreCase(receipt.path("status").asString())) reverted++;
        }
        boolean complete = receipts.size() == batch.hashes.size();
        int status;
        // 400 仅当一笔都未发出：已发出但尚未上链的交易仍可能上链，此时应为 100（pending），上链后为 600
        if (batch.hashes.isEmpty() && batch.failure != null) status = 400;
        else if (!complete) status = 100;
        else if (batch.failure != null) status = 600;
        else if (reverted == receipts.size()) status = 500;
        else if (reverted > 0) status = 600;
        else status = 200;

        ObjectNode result = MAPPER.createObjectNode().put("version", "2.0.0").put("id", batch.id)
                .put("chainId", batch.chainId).put("status", status).put("atomic", false);
        var receiptArray = result.putArray("receipts");
        for (JsonNode receipt : receipts) {
            ObjectNode output = MAPPER.createObjectNode();
            var logs = output.putArray("logs");
            for (JsonNode log : receipt.path("logs")) {
                ObjectNode entry = MAPPER.createObjectNode();
                entry.set("address", log.path("address"));
                entry.set("data", log.path("data"));
                entry.set("topics", log.path("topics"));
                logs.add(entry);
            }
            output.set("status", receipt.path("status"));
            output.set("blockHash", receipt.path("blockHash"));
            output.set("blockNumber", receipt.path("blockNumber"));
            output.set("gasUsed", receipt.path("gasUsed"));
            output.set("transactionHash", receipt.path("transactionHash"));
            receiptArray.add(output);
        }
        return result;
    }

    private synchronized Object showCallsStatus(String paramsJson) throws Exception {
        JsonNode params = params(paramsJson, 1);
        findBatch(params.get(0).asString());
        return null;
    }

    private CallsBatch findBatch(String id) {
        CallsBatch batch = batches.get(id);
        if (batch == null) throw new ServerWalletException(5730, "unknown calls id");
        return batch;
    }

    private static void validateCall(JsonNode call) {
        if (!call.isObject()) throw new IllegalArgumentException("invalid call");
        if (call.has("to") && (!call.path("to").isString() || !call.path("to").asString().matches("0x[0-9a-fA-F]{40}")))
            throw new IllegalArgumentException("invalid call recipient");
        if (call.has("data") && (!call.path("data").isString() || !call.path("data").asString().matches("0x(?:[0-9a-fA-F]{2})*")))
            throw new IllegalArgumentException("invalid call data");
        if (call.has("value") && (!call.path("value").isString() || !call.path("value").asString().matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)")))
            throw new IllegalArgumentException("invalid call value");
    }

    private static void rejectRequiredCapabilities(JsonNode capabilities) {
        if (!capabilities.isObject()) return;
        capabilities.properties().forEach(entry -> {
            if (!entry.getValue().path("optional").asBoolean(false))
                throw new ServerWalletException(5700, "unsupported capability: " + entry.getKey());
        });
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static String newBatchId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "0x" + HexFormat.of().formatHex(bytes);
    }

    private static final class CallsBatch {
        private final String id;
        private final String chainId;
        private final List<String> hashes = new ArrayList<>();
        private String failure;
        private CallsBatch(String id, String chainId) {
            this.id = id;
            this.chainId = chainId;
        }
    }

    private byte[] signTransaction(JsonNode tx, BigInteger nonce, BigInteger gas, String to, BigInteger value, String data) {
        if (tx.has("gasPrice")) return signLegacy(nonce, quantity(tx.path("gasPrice").asString()), gas, to, value, data);
        BigInteger priority;
        try {
            priority = tx.has("maxPriorityFeePerGas") ? quantity(tx.path("maxPriorityFeePerGas").asString())
                    : rpc.maxPriorityFeePerGas();
        } catch (EthRpcException exception) {
            return signLegacy(nonce, rpc.gasPrice(), gas, to, value, data);
        }
        BigInteger maxFee = tx.has("maxFeePerGas") ? quantity(tx.path("maxFeePerGas").asString()) : null;
        if (maxFee == null) {
            BigInteger baseFee;
            try {
                baseFee = rpc.latestBaseFee();
            } catch (EthRpcException exception) {
                return signLegacy(nonce, rpc.gasPrice(), gas, to, value, data);
            }
            if (baseFee != null) maxFee = baseFee.multiply(BigInteger.TWO).add(priority);
        }
        if (maxFee == null) return signLegacy(nonce, rpc.gasPrice(), gas, to, value, data);
        // 必须用 8 参数的 EIP-1559（type 0x2）构造器：带两个 List 的重载会生成 EIP-7702（type 0x4）交易，
        // 空授权列表的 type 0x4 交易会被节点接受后在出块时丢弃
        RawTransaction transaction = RawTransaction.createTransaction(chain, nonce, gas, to, value, data,
                priority, maxFee);
        return TransactionEncoder.signMessage(transaction, org.web3j.crypto.Credentials.create(keyPair));
    }

    private byte[] signLegacy(BigInteger nonce, BigInteger gasPrice, BigInteger gas, String to,
                              BigInteger value, String data) {
        RawTransaction transaction = RawTransaction.createTransaction(nonce, gasPrice, gas, to, value, data);
        return TransactionEncoder.signMessage(transaction, chain, org.web3j.crypto.Credentials.create(keyPair));
    }

    private Object readOnly(String method, String paramsJson) throws Exception {
        if (!READ_ONLY_METHODS.contains(method)) throw new ServerWalletException(4200, "unsupported method: " + method);
        if (rpc == null) throw new ServerWalletException(4200, "no RPC configured");
        JsonNode params = MAPPER.readTree(paramsJson);
        if (!params.isArray()) throw new IllegalArgumentException("invalid params");
        java.util.List<JsonNode> list = new java.util.ArrayList<>();
        params.forEach(list::add);
        return rpc.request(method, list);
    }

    private Object switchChain(String paramsJson) throws Exception {
        JsonNode params = params(paramsJson, 1);
        long target = quantity(params.get(0).path("chainId").asString()).longValueExact();
        if (target != chain) throw new ServerWalletException(4902, "wallet is not configured for requested chain");
        return null;
    }

    private JsonNode params(String json, int size) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        if (!node.isArray() || node.size() != size) throw new IllegalArgumentException("invalid params");
        return node;
    }

    private void requireAddress(String candidate) {
        if (!address.equalsIgnoreCase(candidate)) throw new ServerWalletException(4100, "account is not authorized");
    }

    private static String signature(Sign.SignatureData value) {
        return "0x" + HexFormat.of().formatHex(value.getR()) + HexFormat.of().formatHex(value.getS())
                + HexFormat.of().formatHex(value.getV());
    }

    private static BigInteger optionalQuantity(JsonNode object, String name, BigInteger fallback) {
        return object.has(name) ? quantity(object.path(name).asString()) : fallback;
    }

    private static BigInteger quantity(String value) {
        return value.startsWith("0x") ? new BigInteger(value.substring(2).isEmpty() ? "0" : value.substring(2), 16)
                : new BigInteger(value);
    }

    @Override public String toString() { return "DevWallet[address=" + address + ", chainId=" + chain + "]"; }
}
