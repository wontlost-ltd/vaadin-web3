package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EthRpcClientFailoverTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void retriesIdenticalRawTransactionOnBackupAfterTransportFailure() {
        String raw = "0x1234";
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger backup = new AtomicInteger();
        FailoverJsonRpcTransport failover = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", request -> { primary.incrementAndGet(); throw new IOException("timeout"); }),
                new FailoverJsonRpcTransport.Endpoint("backup", request -> {
                    backup.incrementAndGet();
                    assertEquals(raw, JSON.readTree(request).path("params").get(0).asString());
                    return response(request, "\"0xabc\"");
                })), new FailoverJsonRpcTransport.Config(1, Duration.ofSeconds(10), 3));
        assertEquals("0xabc", new EthRpcClient(failover).sendRawTransaction(raw));
        assertEquals(1, primary.get());
        assertEquals(1, backup.get());
    }

    @Test void decoratedRawTransactionUsesLimitedFailoverChannelOnce() {
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger decorated = new AtomicInteger();
        List<FailoverJsonRpcTransport.Endpoint> endpoints = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new FailoverJsonRpcTransport.Endpoint("node-" + index, request -> {
                    sends.incrementAndGet();
                    throw new IOException("offline");
                })).toList();
        FailoverJsonRpcTransport failover = new FailoverJsonRpcTransport(endpoints,
                new FailoverJsonRpcTransport.Config(1, Duration.ofSeconds(30), 3));
        EthRpcClient rpc = new EthRpcClient(failover, transport -> request -> {
            decorated.incrementAndGet();
            return transport.send(request);
        });

        assertThrows(IllegalStateException.class, () -> rpc.sendRawTransaction("0x1234"));
        assertEquals(3, sends.get());
        assertEquals(1, decorated.get());
    }

    @Test void decoratorSeesOneLogicalSendAcrossFailoverAndPinnedReads() {
        AtomicInteger primary = new AtomicInteger();
        AtomicInteger backup = new AtomicInteger();
        AtomicInteger decorated = new AtomicInteger();
        AtomicReference<String> lastRequest = new AtomicReference<>();
        FailoverJsonRpcTransport failover = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", request -> {
                    primary.incrementAndGet(); throw new IOException("offline");
                }), new FailoverJsonRpcTransport.Endpoint("backup", request -> {
                    backup.incrementAndGet(); return response(request, "\"0x10\"");
                })), new FailoverJsonRpcTransport.Config(1, Duration.ofSeconds(10), 3));
        EthRpcClient rpc = new EthRpcClient(failover, transport -> request -> {
            decorated.incrementAndGet();
            lastRequest.set(request);
            return transport.send(request);
        });

        assertEquals(16, rpc.blockNumber());
        assertEquals("eth_blockNumber", JSON.readTree(lastRequest.get()).path("method").asString());
        assertEquals(1, primary.get());
        assertEquals(1, backup.get());
        assertEquals(1, decorated.get());

        rpc.pinned().blockNumber();
        assertEquals(2, decorated.get());
    }

    @Test void decoratorDoesNotHideLagToleranceOrFailoverRejection() {
        AtomicInteger heads = new AtomicInteger();
        FailoverJsonRpcTransport failover = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", request -> response(request,
                        heads.getAndIncrement() == 0 ? "\"0xa\"" : "\"0x6\"")),
                new FailoverJsonRpcTransport.Endpoint("backup", request -> response(request, "\"0x9\""))),
                new FailoverJsonRpcTransport.Config(3, Duration.ofSeconds(15), 3));
        EthRpcClient rpc = new EthRpcClient(failover, transport -> request -> transport.send(request));

        assertEquals(10, rpc.blockNumber());
        assertThrows(EthRpcException.class, rpc::blockNumber);
        assertEquals(FailoverJsonRpcTransport.State.OPEN, failover.healthSnapshot().getFirst().state());
    }

    @Test void rejectsNullWrapperResultAndKeepsLegacyConstructorBehavior() {
        assertThrows(NullPointerException.class, () -> new EthRpcClient(request -> "{}", transport -> null));
        EthRpcClient rpc = new EthRpcClient(request -> response(request, "\"0x2\""));
        assertEquals(2, rpc.blockNumber());
    }

    @Test void ordinaryRequestSendsOnceThroughItsTransport() {
        AtomicInteger sends = new AtomicInteger();
        EthRpcClient rpc = new EthRpcClient(request -> {
            sends.incrementAndGet();
            return response(request, "\"0x2\"");
        });

        assertEquals(2, rpc.blockNumber());
        assertEquals(1, sends.get());
    }

    @Test void sendsRawTransactionToAtMostThreeEndpoints() {
        AtomicInteger sent = new AtomicInteger();
        List<FailoverJsonRpcTransport.Endpoint> endpoints = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new FailoverJsonRpcTransport.Endpoint("node-" + index, request -> {
                    sent.incrementAndGet(); throw new IOException("offline");
                })).toList();
        EthRpcClient client = new EthRpcClient(new FailoverJsonRpcTransport(endpoints,
                new FailoverJsonRpcTransport.Config(1, Duration.ofSeconds(30), 3)));
        assertThrows(IllegalStateException.class, () -> client.sendRawTransaction("0x1234"));
        assertEquals(3, sent.get());
    }

    @Test void acceptsAlreadyKnownOnlyWhenExpectedHashIsFound() {
        String raw = "0x1234";
        String hash = Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray(raw)));
        AtomicInteger txQueries = new AtomicInteger();
        EthRpcClient found = new EthRpcClient(request -> {
            JsonNode parsed = JSON.readTree(request);
            if ("eth_sendRawTransaction".equals(parsed.path("method").asString())) return error(request, "already known");
            txQueries.incrementAndGet();
            return response(request, "{\"hash\":\"" + hash + "\",\"input\":\"0x\",\"value\":\"0x0\",\"blockNumber\":null}");
        });
        assertEquals(hash, found.sendRawTransaction(raw));
        assertEquals(1, txQueries.get());

        EthRpcException original = assertThrows(EthRpcException.class, () -> new EthRpcClient(request -> {
            JsonNode parsed = JSON.readTree(request);
            if ("eth_sendRawTransaction".equals(parsed.path("method").asString())) return error(request, "nonce too low");
            return response(request, "null");
        }).sendRawTransaction(raw));
        assertTrue(original.getMessage().contains("nonce too low"));
    }

    @Test void rejectsLaggingFailoverEndpointInsteadOfReturningItsHeight() {
        AtomicInteger heights = new AtomicInteger();
        JsonRpcTransport primary = request -> response(request, heights.getAndIncrement() == 0 ? "\"0xa\"" : "\"0x6\"");
        FailoverJsonRpcTransport failover = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", primary),
                new FailoverJsonRpcTransport.Endpoint("backup", request -> response(request, "\"0x7\""))),
                new FailoverJsonRpcTransport.Config(3, Duration.ofSeconds(15), 3));
        EthRpcClient rpc = new EthRpcClient(failover);
        assertEquals(10, rpc.blockNumber());
        EthRpcException lag = assertThrows(EthRpcException.class, rpc::blockNumber);
        assertEquals(EthRpcException.Category.TRANSIENT_NODE, lag.getCategory());
    }

    @Test void pinnedLagFailureDoesNotChangeSharedFailoverState() {
        AtomicInteger heights = new AtomicInteger();
        FailoverJsonRpcTransport failover = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", request -> response(request,
                        heights.getAndIncrement() == 0 ? "\"0xa\"" : "\"0x0\"")),
                new FailoverJsonRpcTransport.Endpoint("backup", request -> response(request, "\"0x9\""))),
                new FailoverJsonRpcTransport.Config(1, Duration.ofSeconds(10), 3));
        EthRpcClient rpc = new EthRpcClient(failover);
        assertEquals(10, rpc.blockNumber());
        assertThrows(EthRpcException.class, () -> rpc.pinned().blockNumber());
        assertEquals(FailoverJsonRpcTransport.State.CLOSED, failover.healthSnapshot().getFirst().state());
    }

    private static String response(String request, String result) throws IOException {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.readTree(request).path("id").asString() + ",\"result\":" + result + "}";
    }
    private static String error(String request, String message) throws IOException {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.readTree(request).path("id").asString()
                + ",\"error\":{\"code\":-32000,\"message\":\"" + message + "\"}}";
    }
}
