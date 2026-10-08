package com.wontlost.web3.solana;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.wontlost.web3.chain.HttpJsonRpcTransport;
import com.wontlost.web3.chain.HttpJsonRpcTransport.JsonRpcHttpException;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.siws.Base58;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Solana JSON-RPC 客户端，复用链无关的 {@link JsonRpcTransport}（可接入自定义传输与装饰器）。
 * <p>
 * 方法依据 Solana RPC 文档：{@code getBalance}、{@code getTokenAccountsByOwner}（{@code jsonParsed}）、
 * {@code getTokenSupply}、{@code getGenesisHash}。余额查询按给定确认级别（默认 {@code confirmed}）进行。
 * 错误只携带 RPC 错误码、HTTP 状态码与方法名，不包含节点地址（地址中可能含 API key）。
 * <p>
 * 多端点故障转移需用 {@link com.wontlost.web3.chain.JsonRpcDialect#SOLANA} 构造
 * {@link com.wontlost.web3.chain.FailoverJsonRpcTransport}（{@code getHealth} 探测、Solana 错误码分类）。
 */
public final class SolanaRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonRpcTransport transport;
    private final SolanaCommitment commitment;
    private final AtomicLong ids = new AtomicLong();

    public SolanaRpcClient(String endpoint) {
        this(new HttpJsonRpcTransport(endpoint), SolanaCommitment.CONFIRMED);
    }

    public SolanaRpcClient(JsonRpcTransport transport, SolanaCommitment commitment) {
        this.transport = Objects.requireNonNull(transport);
        this.commitment = Objects.requireNonNull(commitment);
    }

    /** 账户的 SOL 余额（lamports）及其对应的 slot。 */
    public SolanaBalance getBalance(String address) {
        requireAddress(address, "address");
        JsonNode result = request("getBalance", List.of(address, Map.of("commitment", commitment.value())));
        return new SolanaBalance(unsigned(result.path("value"), "getBalance"), slot(result, "getBalance"));
    }

    /**
     * 账户持有某 SPL 代币（含 Token-2022）的总余额：汇总该 mint 下该所有者的全部代币账户。
     * 没有代币账户时余额为 0，小数位数取自 {@code getTokenSupply}。
     */
    public SplTokenBalance getTokenBalance(String owner, String mint) {
        requireAddress(owner, "owner");
        requireAddress(mint, "mint");
        JsonNode result = request("getTokenAccountsByOwner", List.of(owner, Map.of("mint", mint),
                Map.of("encoding", "jsonParsed", "commitment", commitment.value())));
        JsonNode accounts = result.path("value");
        if (!accounts.isArray()) {
            throw new SolanaRpcException(0, "getTokenAccountsByOwner returned an invalid response");
        }
        BigInteger total = BigInteger.ZERO;
        int decimals = -1;
        for (JsonNode account : accounts) {
            JsonNode amount = tokenAmount(account, mint);
            int accountDecimals = decimals(amount, "getTokenAccountsByOwner");
            if (decimals >= 0 && decimals != accountDecimals) {
                throw new SolanaRpcException(0, "getTokenAccountsByOwner returned inconsistent decimals");
            }
            decimals = accountDecimals;
            total = total.add(decimalString(amount.path("amount"), "getTokenAccountsByOwner"));
        }
        if (decimals < 0) {
            JsonNode supply = request("getTokenSupply", List.of(mint, Map.of("commitment", commitment.value())));
            decimals = decimals(supply.path("value"), "getTokenSupply");
        }
        return new SplTokenBalance(mint, total, decimals, slot(result, "getTokenAccountsByOwner"));
    }

    /** The most recent blockhash at this client's commitment, for binding a new transaction. */
    public LatestBlockhash getLatestBlockhash() {
        JsonNode result = request("getLatestBlockhash", List.of(Map.of("commitment", commitment.value())));
        JsonNode value = result.path("value");
        String blockhash = value.path("blockhash").asString("");
        long lastValid = value.path("lastValidBlockHeight").asLong(-1);
        if (lastValid < 0) throw new SolanaRpcException(0, "getLatestBlockhash returned an invalid response");
        try {
            SolanaAddresses.key(blockhash);
        } catch (IllegalArgumentException exception) {
            throw new SolanaRpcException(0, "getLatestBlockhash returned an invalid response");
        }
        return new LatestBlockhash(blockhash, lastValid, slot(result, "getLatestBlockhash"));
    }

    /** The current block height at this client's commitment, to compare with {@link LatestBlockhash#lastValidBlockHeight()}. */
    public long getBlockHeight() {
        JsonNode result = request("getBlockHeight", List.of(Map.of("commitment", commitment.value())));
        if (!result.isIntegralNumber() || result.asLong() < 0) {
            throw new SolanaRpcException(0, "getBlockHeight returned an invalid response");
        }
        return result.asLong();
    }

    /**
     * Statuses for up to 256 transaction signatures, in the same order; an empty entry means the cluster does not
     * know the signature (not processed yet, or older than the status cache when {@code searchHistory} is false).
     */
    public List<java.util.Optional<SignatureStatus>> getSignatureStatuses(List<String> signatures, boolean searchHistory) {
        if (signatures.isEmpty() || signatures.size() > 256) {
            throw new IllegalArgumentException("between 1 and 256 signatures are required");
        }
        signatures.forEach(signature -> requireSignature(signature));
        JsonNode result = request("getSignatureStatuses",
                List.of(signatures, Map.of("searchTransactionHistory", searchHistory)));
        JsonNode values = result.path("value");
        if (!values.isArray() || values.size() != signatures.size()) {
            throw new SolanaRpcException(0, "getSignatureStatuses returned an invalid response");
        }
        List<java.util.Optional<SignatureStatus>> statuses = new java.util.ArrayList<>();
        for (JsonNode value : values) {
            statuses.add(value.isNull() ? java.util.Optional.empty() : java.util.Optional.of(signatureStatus(value)));
        }
        return statuses;
    }

    /**
     * Submits a signed wire transaction and returns its signature. The node first simulates it (preflight) at this
     * client's commitment, so most failures are reported here as an RPC error instead of a failed transaction.
     */
    public String sendTransaction(byte[] signedTransaction) {
        String expected = SolanaTransaction.signatureOf(signedTransaction);
        JsonNode result = request("sendTransaction", List.of(java.util.Base64.getEncoder().encodeToString(signedTransaction),
                Map.of("encoding", "base64", "preflightCommitment", commitment.value())));
        if (!result.isString() || !expected.equals(result.asString())) {
            throw new SolanaRpcException(0, "sendTransaction returned an unexpected signature");
        }
        return expected;
    }

    /** The program that owns {@code address} (for a mint: Token or Token-2022), or empty when the account does not exist. */
    public java.util.Optional<String> getAccountOwner(String address) {
        requireAddress(address, "address");
        JsonNode result = request("getAccountInfo", List.of(address, Map.of("encoding", "base64",
                "dataSlice", Map.of("offset", 0, "length", 0), "commitment", commitment.value())));
        JsonNode value = result.path("value");
        if (value.isNull()) return java.util.Optional.empty();
        String owner = value.path("owner").asString("");
        try {
            SolanaAddresses.key(owner);
        } catch (IllegalArgumentException exception) {
            throw new SolanaRpcException(0, "getAccountInfo returned an invalid response");
        }
        return java.util.Optional.of(owner);
    }

    /** 创世哈希（base58）。 */
    public String getGenesisHash() {
        JsonNode result = request("getGenesisHash", List.of());
        if (!result.isString()) {
            throw new SolanaRpcException(0, "getGenesisHash returned an invalid response");
        }
        return result.asString();
    }

    /** 本节点所在集群的 CAIP-2 引用（创世哈希前 32 个字符）。 */
    public String clusterReference() {
        String genesis = getGenesisHash();
        try {
            Base58.decode(genesis, 32);
        } catch (IllegalArgumentException exception) {
            throw new SolanaRpcException(0, "getGenesisHash returned an invalid hash");
        }
        return genesis.substring(0, 32);
    }

    /** 发送任意 JSON-RPC 请求并返回 {@code result}；RPC 错误抛出 {@link SolanaRpcException}。 */
    public JsonNode request(String method, List<?> params) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", ids.incrementAndGet());
        request.put("method", method);
        request.set("params", MAPPER.valueToTree(params));
        JsonNode response = parse(send(method, request.toString()), method);
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new SolanaRpcException(error.path("code").asInt(SolanaRpcException.INTERNAL_ERROR), method + " failed");
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode()) {
            throw new SolanaRpcException(0, method + " returned no result");
        }
        return result;
    }

    /** 关闭底层传输；传输关闭失败时抛出 {@link SolanaRpcException}。 */
    @Override
    public void close() {
        try {
            transport.close();
        } catch (Exception exception) {
            throw new SolanaRpcException(0, "transport close failed");
        }
    }

    // 传输层异常只保留 HTTP 状态码；异常消息可能含节点地址，故不透传
    private String send(String method, String request) {
        try {
            return transport.send(request);
        } catch (JsonRpcHttpException exception) {
            throw new SolanaRpcException(0, exception.status(), method + " transport failed");
        } catch (IOException exception) {
            throw new SolanaRpcException(0, method + " transport failed");
        }
    }

    private static JsonNode parse(String body, String method) {
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new SolanaRpcException(0, method + " returned an invalid response");
        }
    }

    // jsonParsed 下节点无法解析的账户会回退为 base64 数组，info 不是对象
    private static JsonNode tokenAmount(JsonNode account, String mint) {
        JsonNode info = account.path("account").path("data").path("parsed").path("info");
        if (!info.isObject()) {
            throw new SolanaRpcException(0, "getTokenAccountsByOwner returned an unparsed token account");
        }
        if (!mint.equals(info.path("mint").asString(""))) {
            throw new SolanaRpcException(0, "getTokenAccountsByOwner returned an account for another mint");
        }
        return info.path("tokenAmount");
    }

    private static int decimals(JsonNode node, String method) {
        JsonNode decimals = node.path("decimals");
        if (!decimals.isIntegralNumber() || decimals.asInt() < 0 || decimals.asInt() > 255) {
            throw new SolanaRpcException(0, method + " returned invalid decimals");
        }
        return decimals.asInt();
    }

    private static long slot(JsonNode result, String method) {
        long slot = result.path("context").path("slot").asLong(-1);
        if (slot < 0) {
            throw new SolanaRpcException(0, method + " returned no slot");
        }
        return slot;
    }

    private static BigInteger unsigned(JsonNode node, String method) {
        if (!node.isIntegralNumber() || node.bigIntegerValue().signum() < 0) {
            throw new SolanaRpcException(0, method + " returned an invalid amount");
        }
        return node.bigIntegerValue();
    }

    private static BigInteger decimalString(JsonNode node, String method) {
        try {
            BigInteger value = new BigInteger(node.asString(""));
            if (value.signum() < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new SolanaRpcException(0, method + " returned an invalid amount");
        }
    }

    private static SignatureStatus signatureStatus(JsonNode value) {
        long slot = value.path("slot").asLong(-1);
        JsonNode confirmations = value.path("confirmations");
        SolanaCommitment status = switch (value.path("confirmationStatus").asString("")) {
            case "processed" -> SolanaCommitment.PROCESSED;
            case "confirmed" -> SolanaCommitment.CONFIRMED;
            case "finalized" -> SolanaCommitment.FINALIZED;
            // 旧节点可能不返回 confirmationStatus：confirmations 为 null 表示已 rooted，即 finalized
            case "" -> confirmations.isNull() ? SolanaCommitment.FINALIZED : null;
            default -> null;
        };
        if (slot < 0 || status == null || !(confirmations.isNull() || confirmations.isIntegralNumber())) {
            throw new SolanaRpcException(0, "getSignatureStatuses returned an invalid response");
        }
        JsonNode error = value.path("err");
        return new SignatureStatus(slot, confirmations.isNull() ? null : confirmations.asLong(), status,
                error.isNull() || error.isMissingNode() ? null : error.toString());
    }

    private static void requireSignature(String signature) {
        try {
            com.wontlost.web3.siws.Base58.decode(signature, 64);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Solana signatures must be base58 64-byte values", exception);
        }
    }

    private static void requireAddress(String address, String name) {
        try {
            Base58.decode(address, 32);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Solana " + name + " must be a base58 32-byte address", exception);
        }
    }
}
