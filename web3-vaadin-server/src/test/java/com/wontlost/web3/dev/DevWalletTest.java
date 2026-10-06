package com.wontlost.web3.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.crypto.transaction.type.Transaction1559;
import org.web3j.utils.Numeric;

import com.wontlost.web3.ServerWallet.ServerWalletException;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.siwe.InMemoryNonceStore;
import com.wontlost.web3.siwe.SiweExpectations;
import com.wontlost.web3.siwe.SiweMessage;
import com.wontlost.web3.siwe.SiweVerifier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DevWalletTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final String MAIL = "{\"types\":{\"EIP712Domain\":[{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"version\",\"type\":\"string\"},{\"name\":\"chainId\",\"type\":\"uint256\"},{\"name\":\"verifyingContract\",\"type\":\"address\"}],\"Person\":[{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"wallet\",\"type\":\"address\"}],\"Mail\":[{\"name\":\"from\",\"type\":\"Person\"},{\"name\":\"to\",\"type\":\"Person\"},{\"name\":\"contents\",\"type\":\"string\"}]},\"primaryType\":\"Mail\",\"domain\":{\"name\":\"Ether Mail\",\"version\":\"1\",\"chainId\":1,\"verifyingContract\":\"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC\"},\"message\":{\"from\":{\"name\":\"Cow\",\"wallet\":\"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826\"},\"to\":{\"name\":\"Bob\",\"wallet\":\"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB\"},\"contents\":\"Hello, Bob!\"}}";

    @Test void personalSignIsAcceptedBySiweVerifier() throws Exception {
        DevWallet wallet = new DevWallet(KEY, 31337, null);
        InMemoryNonceStore nonces = new InMemoryNonceStore();
        String message = SiweMessage.builder().scheme("http").domain("localhost").address(wallet.accounts().getFirst())
                .uri("http://localhost").chainId(31337).nonce(nonces.issue()).issuedAt(Instant.now()).build().toMessage();
        String signature = result(wallet.request("personal_sign", json(List.of(message, wallet.accounts().getFirst()))));
        assertEquals(wallet.accounts().getFirst(), new SiweVerifier(nonces, Clock.systemUTC())
                .verify(message, signature, SiweExpectations.forDomain("localhost")).address());
    }

    @Test void personalSignRejectsDifferentAccount() {
        DevWallet wallet = new DevWallet(KEY, 31337, null);
        ServerWalletException error = failure(wallet, "personal_sign", "[\"hello\",\"0x0000000000000000000000000000000000000001\"]");
        assertEquals(4100, error.getCode());
    }

    @Test void typedDataSignatureMatchesMailVectorAndRecoversWallet() throws Exception {
        DevWallet wallet = new DevWallet(KEY, 1, null);
        String hash = Numeric.toHexString(new StructuredDataEncoder(MAIL).hashStructuredData());
        assertEquals("0xbe609aee343fb3c4b28e1df9e632fca64fcfaede20f02e86244efddf30957bd2", hash);
        String signature = result(wallet.request("eth_signTypedData_v4", json(List.of(wallet.accounts().getFirst(), MAIL))));
        Sign.SignatureData data = new Sign.SignatureData(HexFormat.of().parseHex(signature.substring(130)),
                HexFormat.of().parseHex(signature.substring(2, 66)), HexFormat.of().parseHex(signature.substring(66, 130)));
        assertEquals(wallet.accounts().getFirst(), Keys.toChecksumAddress("0x" + Keys.getAddress(
                Sign.signedMessageHashToKey(new StructuredDataEncoder(MAIL).hashStructuredData(), data))));
    }

    @Test void typedDataRejectsDifferentChain() {
        DevWallet wallet = new DevWallet(KEY, 31337, null);
        assertEquals(4901, failure(wallet, "eth_signTypedData_v4", json(List.of(wallet.accounts().getFirst(), MAIL))).getCode());
    }

    @Test void transactionIsSignedForConfiguredChainAndBroadcast() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.baseFeeUnsupported = true;
        DevWallet wallet = new DevWallet(KEY, 31337, new EthRpcClient(transport));
        String tx = "[{\"from\":\"" + wallet.accounts().getFirst() + "\",\"to\":\"0x0000000000000000000000000000000000000001\",\"value\":\"0x2a\",\"data\":\"0x1234\",\"gas\":\"0x5208\",\"nonce\":\"0x7\",\"maxFeePerGas\":\"0x64\",\"maxPriorityFeePerGas\":\"0x2\"}]";
        assertEquals("0xfeed", result(wallet.request("eth_sendTransaction", tx)));
        RawTransaction decoded = TransactionDecoder.decode(transport.rawHex.substring(2));
        assertEquals(BigInteger.valueOf(7), decoded.getNonce());
        assertEquals("0x0000000000000000000000000000000000000001", decoded.getTo());
        assertEquals(BigInteger.valueOf(42), decoded.getValue());
        assertEquals("1234", decoded.getData());
        assertEquals(BigInteger.valueOf(21000), decoded.getGasLimit());
        // 原始交易必须是 EIP-1559（type 0x2）；Transaction7702 是 Transaction1559 的子类，强转不足以排除 type 0x4
        assertEquals("02", transport.rawHex.substring(2, 4), "EIP-1559 transactions start with type byte 0x02");
        assertEquals(Transaction1559.class, decoded.getTransaction().getClass());
        Transaction1559 typed = (Transaction1559) decoded.getTransaction();
        assertEquals(31337, typed.getChainId());
        assertEquals(BigInteger.valueOf(100), typed.getMaxFeePerGas());
        assertEquals(BigInteger.valueOf(2), typed.getMaxPriorityFeePerGas());
        assertEquals(1, transport.broadcasts.get());
    }

    @Test void missingRpcAndSecretStringHandling() {
        DevWallet wallet = new DevWallet(KEY, 31337, null);
        assertEquals(4200, failure(wallet, "eth_sendTransaction", "[{\"from\":\"" + wallet.accounts().getFirst() + "\"}]").getCode());
        assertFalse(wallet.toString().contains(KEY));
    }

    @Test void concurrentSendsUseDistinctPendingNonces() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        DevWallet wallet = new DevWallet(KEY, 31337, new EthRpcClient(transport));
        String tx = "[{\"from\":\"" + wallet.accounts().getFirst() + "\",\"to\":\"0x0000000000000000000000000000000000000001\",\"gas\":\"0x5208\",\"gasPrice\":\"0x3\"}]";
        Thread first = Thread.ofPlatform().start(() -> wallet.request("eth_sendTransaction", tx).join());
        Thread second = Thread.ofPlatform().start(() -> wallet.request("eth_sendTransaction", tx).join());
        first.join();
        second.join();
        assertEquals(List.of(BigInteger.valueOf(4), BigInteger.valueOf(5)), transport.nonces);
    }

    @Test void unsupportedPriorityFeeFallsBackToLegacyGasPrice() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.priorityUnsupported = true;
        DevWallet wallet = new DevWallet(KEY, 31337, new EthRpcClient(transport));
        String tx = "[{\"from\":\"" + wallet.accounts().getFirst() + "\",\"to\":\"0x0000000000000000000000000000000000000001\"}]";
        result(wallet.request("eth_sendTransaction", tx));
        RawTransaction decoded = TransactionDecoder.decode(transport.rawHex.substring(2));
        assertEquals(BigInteger.valueOf(3), decoded.getGasPrice());
        assertEquals(BigInteger.valueOf(21000), decoded.getGasLimit());
    }

    @Test void rpcTransactionErrorCodeIsPreserved() {
        RecordingTransport transport = new RecordingTransport();
        transport.broadcastError = true;
        DevWallet wallet = new DevWallet(KEY, 31337, new EthRpcClient(transport));
        String tx = "[{\"from\":\"" + wallet.accounts().getFirst() + "\",\"to\":\"0x0000000000000000000000000000000000000001\",\"gas\":\"0x5208\",\"gasPrice\":\"0x3\"}]";
        assertEquals(-32000, failure(wallet, "eth_sendTransaction", tx).getCode());
    }

    private static ServerWalletException failure(DevWallet wallet, String method, String params) {
        java.util.concurrent.CompletionException exception = assertThrows(java.util.concurrent.CompletionException.class,
                () -> wallet.request(method, params).join());
        return (ServerWalletException) exception.getCause();
    }

    private static String result(java.util.concurrent.CompletableFuture<String> future) throws Exception {
        return MAPPER.readTree(future.get()).asString();
    }

    private static String json(Object value) { return MAPPER.writeValueAsString(value); }


    @Test void forwardsOnlyAllowListedReadOnlyMethodsToTheNode() {
        java.util.List<String> seen = new CopyOnWriteArrayList<>();
        com.wontlost.web3.chain.JsonRpcTransport node = request -> {
            JsonNode json = MAPPER.readTree(request);
            seen.add(json.path("method").asString());
            return "{\"jsonrpc\":\"2.0\",\"id\":" + json.path("id").asString() + ",\"result\":\"0xde0b6b3a7640000\"}";
        };
        DevWallet wallet = DevWallet.anvilDefault(31337, new EthRpcClient(node));
        String params = "[\"" + wallet.accounts().getFirst() + "\",\"latest\"]";

        assertEquals("\"0xde0b6b3a7640000\"", wallet.request("eth_getBalance", params).join());
        assertEquals(java.util.List.of("eth_getBalance"), seen);

        var signing = assertThrows(java.util.concurrent.CompletionException.class,
                () -> wallet.request("eth_sign", params).join());
        assertEquals(4200, ((ServerWalletException) signing.getCause()).getCode());
        assertEquals(java.util.List.of("eth_getBalance"), seen, "non-allow-listed methods must never reach the node");

        DevWallet offline = DevWallet.anvilDefault(31337, null);
        var noRpc = assertThrows(java.util.concurrent.CompletionException.class,
                () -> offline.request("eth_getBalance", params).join());
        assertEquals(4200, ((ServerWalletException) noRpc.getCause()).getCode());
    }

    private static final class RecordingTransport implements JsonRpcTransport {
        private final AtomicInteger nonce = new AtomicInteger(4);
        private final AtomicInteger broadcasts = new AtomicInteger();
        private final List<BigInteger> nonces = new CopyOnWriteArrayList<>();
        private volatile String rawHex;
        private volatile boolean priorityUnsupported;
        private volatile boolean baseFeeUnsupported;
        private volatile boolean broadcastError;

        @Override public String send(String requestJson) throws IOException {
            JsonNode request = MAPPER.readTree(requestJson);
            String method = request.path("method").asString();
            if ("eth_maxPriorityFeePerGas".equals(method) && priorityUnsupported) {
                return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asString()
                        + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
            }
            if ("eth_sendRawTransaction".equals(method) && broadcastError) {
                rawHex = request.path("params").get(0).asString();
                broadcasts.incrementAndGet();
                return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asString()
                        + ",\"error\":{\"code\":-32000,\"message\":\"rejected\"}}";
            }
            if ("eth_getBlockByNumber".equals(method) && baseFeeUnsupported) {
                return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asString()
                        + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
            }
            String result = switch (method) {
                case "eth_getTransactionCount" -> {
                    BigInteger value = BigInteger.valueOf(nonce.getAndIncrement());
                    nonces.add(value);
                    yield Numeric.encodeQuantity(value);
                }
                case "eth_estimateGas" -> "0x5208";
                case "eth_maxPriorityFeePerGas" -> "0x2";
                case "eth_getBlockByNumber" -> "{\"number\":\"0x1\",\"baseFeePerGas\":\"0x10\"}";
                case "eth_gasPrice" -> "0x3";
                case "eth_sendRawTransaction" -> {
                    rawHex = request.path("params").get(0).asString();
                    broadcasts.incrementAndGet();
                    yield "0xfeed";
                }
                default -> throw new IllegalArgumentException("unexpected method " + method);
            };
            String encodedResult = method.equals("eth_getBlockByNumber") ? result : MAPPER.writeValueAsString(result);
            return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asString() + ",\"result\":" + encodedResult + "}";
        }
    }
}
