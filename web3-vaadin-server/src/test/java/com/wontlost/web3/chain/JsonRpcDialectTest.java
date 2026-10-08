package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class JsonRpcDialectTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void solanaDialectProbesWithGetHealthAndReturnsToTheRecoveredPrimary() throws Exception {
        List<String> primaryMethods = new CopyOnWriteArrayList<>();
        AtomicBoolean primaryDown = new AtomicBoolean(true);
        AtomicInteger backupCalls = new AtomicInteger();
        var transport = failover(solanaNode(primaryMethods, primaryDown), request -> {
            backupCalls.incrementAndGet();
            return result();
        }, JsonRpcDialect.SOLANA);

        assertEquals(result(), transport.send(request("getBalance")));
        assertEquals(1, backupCalls.get());
        primaryDown.set(false);
        assertEquals(result(), transport.send(request("getBalance")));
        assertEquals("getHealth", primaryMethods.getLast());
        assertEquals(FailoverJsonRpcTransport.State.CLOSED, transport.healthSnapshot().getFirst().state());

        assertEquals(result(), transport.send(request("getBalance")));
        assertEquals(2, backupCalls.get(), "the recovered primary serves the next request");
        assertEquals("getBalance", primaryMethods.getLast());
    }

    @Test void ethereumDialectNeverRecoversASolanaPrimary() throws Exception {
        List<String> primaryMethods = new CopyOnWriteArrayList<>();
        AtomicBoolean primaryDown = new AtomicBoolean(true);
        AtomicInteger backupCalls = new AtomicInteger();
        var transport = failover(solanaNode(primaryMethods, primaryDown), request -> {
            backupCalls.incrementAndGet();
            return result();
        }, JsonRpcDialect.ETHEREUM);

        transport.send(request("getBalance"));
        primaryDown.set(false);
        transport.send(request("getBalance"));
        transport.send(request("getBalance"));

        assertEquals("eth_chainId", primaryMethods.getLast());
        assertEquals(3, backupCalls.get());
        assertEquals("INVALID_REQUEST", transport.healthSnapshot().getFirst().lastErrorCategory());
    }

    @Test void unhealthyProbeKeepsThePrimaryFailedAsTransient() throws Exception {
        AtomicInteger backupCalls = new AtomicInteger();
        var transport = failover(request -> JSON.readTree(request).path("method").asString().equals("getHealth")
                ? error(-32005, "Node is behind by 42 slots") : error(-32004, "Block not available"), request -> {
                    backupCalls.incrementAndGet();
                    return result();
                }, JsonRpcDialect.SOLANA);

        transport.send(request("getBlock"));
        transport.send(request("getBlock"));

        assertEquals(2, backupCalls.get());
        assertEquals("TRANSIENT_NODE", transport.healthSnapshot().getFirst().lastErrorCategory());
        assertEquals("Node is behind by 42 slots", transport.healthSnapshot().getFirst().lastErrorMessage());
    }

    @Test void solanaTransientErrorsSwitchEndpointsButDeterministicOnesDoNot() throws Exception {
        for (int code : List.of(-32001, -32004, -32005, -32008, -32010, -32011, -32012, -32014, -32016, -32019, -32429)) {
            AtomicInteger backupCalls = new AtomicInteger();
            var transport = failover(request -> error(code, "server error"), request -> {
                backupCalls.incrementAndGet();
                return result();
            }, JsonRpcDialect.SOLANA);
            assertEquals(result(), transport.send(request("getBalance")), "code " + code);
            assertEquals(1, backupCalls.get(), "code " + code);
        }
        for (int code : List.of(-32002, -32003, -32006, -32007, -32009, -32013, -32015, -32018, -32020, -32601, -32017)) {
            AtomicInteger backupCalls = new AtomicInteger();
            var transport = failover(request -> error(code, "server error"), request -> {
                backupCalls.incrementAndGet();
                return result();
            }, JsonRpcDialect.SOLANA);
            assertEquals(error(code, "server error"), transport.send(request("sendTransaction")), "code " + code);
            assertEquals(0, backupCalls.get(), "code " + code);
        }
    }

    @Test void solanaClassificationTable() {
        JsonRpcDialect solana = JsonRpcDialect.SOLANA;
        assertEquals(EthRpcException.Category.TRANSIENT_NODE, solana.classify(-32005, "Node is unhealthy", null));
        assertEquals(EthRpcException.Category.TRANSIENT_NODE, solana.classify(-32014, null, null));
        for (int code : List.of(-32001, -32004, -32005, -32008, -32010, -32011, -32012, -32014, -32016, -32019)) {
            assertEquals(EthRpcException.Category.TRANSIENT_NODE, solana.classify(code, "server error", null), "code " + code);
        }
        for (int code : List.of(-32002, -32003, -32006, -32007, -32009, -32013, -32015, -32018, -32020)) {
            assertEquals(EthRpcException.Category.DETERMINISTIC, solana.classify(code, "server error", null), "code " + code);
        }
        assertEquals(EthRpcException.Category.INVALID_REQUEST, solana.classify(-32602, "Invalid params", null));
        assertEquals(EthRpcException.Category.RATE_LIMITED, solana.classify(-32000, "Too many requests", null));
        assertEquals(EthRpcException.Category.RATE_LIMITED, solana.classify(-32000, null, "{\"reason\":\"rate limit exceeded\"}"));
        assertEquals(EthRpcException.Category.RATE_LIMITED, solana.classify(429, "Too Many", null));
        assertEquals(EthRpcException.Category.RATE_LIMITED, solana.classify(-32429, null, null));
        assertEquals(EthRpcException.Category.UNKNOWN, solana.classify(-32017, "Epoch rewards period active", null));
        assertEquals(EthRpcException.Category.UNKNOWN,
                solana.classify(-32000, "Block 342942901 not available for slot 4290", "{\"slot\":4290}"),
                "slot numbers containing 429 are not rate limits");
        assertEquals(EthRpcException.Category.UNKNOWN, solana.classify(-32000, "header not found", null),
                "EVM node texts do not apply to Solana");
    }

    @Test void ethereumDialectKeepsTheExistingBehaviour() {
        assertEquals("eth_chainId", JsonRpcDialect.ETHEREUM.healthProbeMethod());
        assertEquals("getHealth", JsonRpcDialect.SOLANA.healthProbeMethod());
        assertEquals(EthRpcException.Category.TRANSIENT_NODE, JsonRpcDialect.ETHEREUM.classify(-32000, "header not found", null));
        assertEquals(EthRpcException.Category.DETERMINISTIC, JsonRpcDialect.ETHEREUM.classify(3, "execution reverted", null));
        assertEquals(EthRpcException.Category.UNKNOWN, JsonRpcDialect.ETHEREUM.classify(-32005, "Node is unhealthy", null));
    }

    @Test void customDialectAndNullChecks() throws Exception {
        List<String> methods = new CopyOnWriteArrayList<>();
        AtomicBoolean down = new AtomicBoolean(true);
        JsonRpcDialect custom = JsonRpcDialect.of("net_ping",
                (code, message, data) -> code == -1 ? EthRpcException.Category.TRANSIENT_NODE : EthRpcException.Category.UNKNOWN);
        var transport = failover(request -> {
            String method = JSON.readTree(request).path("method").asString();
            methods.add(method);
            return down.getAndSet(false) ? error(-1, "busy") : result();
        }, request -> result(), custom);

        transport.send(request("x"));
        transport.send(request("x"));

        // 第一次失败切到备用端点后立即用自定义方法探测主端点，恢复后第二次请求回到主端点
        assertEquals(List.of("x", "net_ping", "x"), methods);
        assertThrows(NullPointerException.class, () -> JsonRpcDialect.of(null, (c, m, d) -> EthRpcException.Category.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> JsonRpcDialect.of(" ", (c, m, d) -> EthRpcException.Category.UNKNOWN));
        assertThrows(NullPointerException.class, () -> JsonRpcDialect.of("getHealth", null));
        JsonRpcDialect nullClassifier = JsonRpcDialect.of("getHealth", (c, m, d) -> null);
        assertEquals("classifier returned null",
                assertThrows(NullPointerException.class, () -> nullClassifier.classify(1, null, null)).getMessage());
        assertThrows(NullPointerException.class, () -> new FailoverJsonRpcTransport(
                List.of(new FailoverJsonRpcTransport.Endpoint("a", request -> result())),
                FailoverJsonRpcTransport.Config.defaults(), null));
    }

    @Test void pinnedViewUsesTheSolanaClassification() throws Exception {
        var transient_ = failover(request -> error(-32005, "Node is unhealthy"), request -> result(), JsonRpcDialect.SOLANA);
        JsonRpcTransport pinned = transient_.pinned();
        assertThrows(IOException.class, () -> pinned.send(request("getBalance")));
        assertEquals("TRANSIENT_NODE", transient_.healthSnapshot().getFirst().lastErrorCategory());
        assertEquals(result(), transient_.pinned().send(request("getBalance")), "the next view moves to the backup");

        var deterministic = failover(request -> error(-32002, "Transaction simulation failed"), request -> result(),
                JsonRpcDialect.SOLANA);
        assertEquals(error(-32002, "Transaction simulation failed"), deterministic.pinned().send(request("sendTransaction")));
        assertEquals(FailoverJsonRpcTransport.State.CLOSED, deterministic.healthSnapshot().getFirst().state());
    }

    @Test void ethereumProbeErrorsKeepTheirCategoryAndMessage() throws Exception {
        AtomicBoolean down = new AtomicBoolean(true);
        var transport = failover(request -> {
            if (down.getAndSet(false)) throw new IOException("timeout");
            return error(-32000, "header not found");
        }, request -> result(), JsonRpcDialect.ETHEREUM);

        transport.send(request("eth_blockNumber"));

        assertEquals("TRANSIENT_NODE", transport.healthSnapshot().getFirst().lastErrorCategory());
        assertEquals("header not found", transport.healthSnapshot().getFirst().lastErrorMessage());
    }

    @Test void malformedProbeResponseKeepsThePrimaryFailed() throws Exception {
        AtomicBoolean down = new AtomicBoolean(true);
        AtomicInteger backupCalls = new AtomicInteger();
        var transport = failover(request -> {
            if (down.getAndSet(false)) throw new IOException("timeout");
            return "<html>bad gateway</html>";
        }, request -> {
            backupCalls.incrementAndGet();
            return result();
        }, JsonRpcDialect.SOLANA);

        transport.send(request("getBalance"));
        transport.send(request("getBalance"));

        assertEquals(2, backupCalls.get());
        assertEquals("IO", transport.healthSnapshot().getFirst().lastErrorCategory());
        assertEquals("Invalid JSON-RPC response", transport.healthSnapshot().getFirst().lastErrorMessage());
    }

    @Test void brokenCustomClassifierPropagatesWithoutFailingTheEndpoint() {
        AtomicInteger backupCalls = new AtomicInteger();
        var transport = failover(request -> error(-1, "busy"), request -> {
            backupCalls.incrementAndGet();
            return result();
        }, JsonRpcDialect.of("getHealth", (code, message, data) -> null));

        assertEquals("classifier returned null",
                assertThrows(NullPointerException.class, () -> transport.send(request("getBalance"))).getMessage());
        assertEquals(0, backupCalls.get());
        assertEquals(0, transport.healthSnapshot().getFirst().consecutiveFailures());
        assertEquals(FailoverJsonRpcTransport.State.CLOSED, transport.healthSnapshot().getFirst().state());
    }

    // 模拟 Solana 节点：不认识 eth_* 方法；down 时任何请求都超时
    private static JsonRpcTransport solanaNode(List<String> methods, AtomicBoolean down) {
        return request -> {
            String method = JSON.readTree(request).path("method").asString();
            methods.add(method);
            if (down.get()) throw new IOException("timeout");
            return method.startsWith("eth_") ? error(-32601, "Method not found") : result();
        };
    }

    private static FailoverJsonRpcTransport failover(JsonRpcTransport primary, JsonRpcTransport backup,
            JsonRpcDialect dialect) {
        return new FailoverJsonRpcTransport(List.of(new FailoverJsonRpcTransport.Endpoint("primary", primary),
                new FailoverJsonRpcTransport.Endpoint("backup", backup)),
                new FailoverJsonRpcTransport.Config(1, Duration.ZERO, 3), dialect);
    }

    private static String request(String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":[]}";
    }

    private static String result() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"ok\"}";
    }

    private static String error(int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"}}";
    }
}
