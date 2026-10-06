package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class FailoverJsonRpcTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void retriesTransientTransportAndRpcErrorsButNotDeterministicOrOtherClientErrors() throws Exception {
        AtomicInteger standbyCalls = new AtomicInteger();
        var transport = failover(request -> { throw new HttpJsonRpcTransport.JsonRpcHttpException(503, "offline"); }, request -> {
            standbyCalls.incrementAndGet(); return result();
        });
        assertEquals(result(), transport.send("{}"));
        assertEquals(1, standbyCalls.get());

        AtomicInteger throttledBackup = new AtomicInteger();
        var throttled = failover(request -> { throw new HttpJsonRpcTransport.JsonRpcHttpException(429, "throttled"); }, request -> {
            throttledBackup.incrementAndGet(); return result();
        });
        assertEquals(result(), throttled.send("{}"));
        assertEquals(1, throttledBackup.get());

        AtomicInteger timeoutBackup = new AtomicInteger();
        var timeout = failover(request -> { throw new HttpJsonRpcTransport.JsonRpcHttpException(408, "timeout"); }, request -> {
            timeoutBackup.incrementAndGet(); return result();
        });
        assertEquals(result(), timeout.send("{}"));
        assertEquals(1, timeoutBackup.get());

        var unauthorized = failover(request -> { throw new HttpJsonRpcTransport.JsonRpcHttpException(401, "auth"); }, request -> result());
        assertThrows(HttpJsonRpcTransport.JsonRpcHttpException.class, () -> unauthorized.send("{}"));

        AtomicInteger deterministicStandby = new AtomicInteger();
        var deterministic = failover(request -> error(-32000, "execution reverted"), request -> {
            deterministicStandby.incrementAndGet(); return result();
        });
        assertEquals(error(-32000, "execution reverted"), deterministic.send("{}"));
        assertEquals(0, deterministicStandby.get());

        var rateLimited = failover(request -> error(-32005, "rate limit exceeded"), request -> result());
        assertEquals(result(), rateLimited.send("{}"));
    }

    @Test void staysOnBackupAndReturnsToPrimaryAtTheNextRequestBoundary() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger standbyCalls = new AtomicInteger();
        AtomicInteger primaryUnavailable = new AtomicInteger(1);
        var transport = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", request -> {
                    primaryCalls.incrementAndGet();
                    if (primaryUnavailable.getAndDecrement() > 0) throw new IOException("timeout");
                    return result();
                }), new FailoverJsonRpcTransport.Endpoint("backup", request -> { standbyCalls.incrementAndGet(); return result(); })),
                new FailoverJsonRpcTransport.Config(3, Duration.ofMillis(100), 3));
        assertEquals(result(), transport.send("{}"));
        assertEquals(1, standbyCalls.get());
        assertEquals(result(), transport.send("{}"));
        assertEquals(2, standbyCalls.get());
        assertEquals(1, primaryCalls.get());
        try { Thread.sleep(120); } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new AssertionError(exception);
        }
        assertEquals(result(), transport.send("{}"));
        assertEquals(0, transport.healthSnapshot().getFirst().consecutiveFailures());
        assertEquals(3, standbyCalls.get());
        assertEquals(result(), transport.send("{}"));
        assertEquals(3, standbyCalls.get());
        assertEquals(3, primaryCalls.get());
    }

    @Test void opensAfterConfiguredConsecutiveFailures() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        var transport = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", request -> {
                    primaryCalls.incrementAndGet(); throw new IOException("offline");
                }), new FailoverJsonRpcTransport.Endpoint("backup", request -> result())),
                new FailoverJsonRpcTransport.Config(3, Duration.ofMillis(30), 3));
        for (int i = 0; i < 3; i++) {
            assertEquals(result(), transport.send("{}"));
            if (i < 2) Thread.sleep(40);
        }
        assertEquals(FailoverJsonRpcTransport.State.OPEN, transport.healthSnapshot().getFirst().state());
        assertEquals(3, primaryCalls.get());
    }

    @Test void pinnedViewDoesNotFailOverAndHalfOpenAllowsOnlyOneConcurrentProbe() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger backupCalls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean unavailable = new java.util.concurrent.atomic.AtomicBoolean(false);
        var transport = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("primary", request -> {
                    primaryCalls.incrementAndGet();
                    if (unavailable.get()) throw new IOException("offline");
                    return result();
                }), new FailoverJsonRpcTransport.Endpoint("backup", request -> { backupCalls.incrementAndGet(); return result(); })),
                new FailoverJsonRpcTransport.Config(1, Duration.ZERO, 3));
        assertEquals(result(), transport.send("{}"));
        JsonRpcTransport pinned = transport.pinned();
        unavailable.set(true);
        assertThrows(IOException.class, () -> pinned.send("{}"));

        AtomicInteger probeCalls = new AtomicInteger();
        var halfOpen = new FailoverJsonRpcTransport(List.of(
                new FailoverJsonRpcTransport.Endpoint("probe", request -> {
                    if (JSON.readTree(request).path("method").asString().equals("eth_chainId")) {
                        probeCalls.incrementAndGet();
                        try { Thread.sleep(60); } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt(); throw new IOException(exception);
                        }
                    }
                    throw new IOException("probe endpoint unavailable");
                }),
                new FailoverJsonRpcTransport.Endpoint("backup", request -> { backupCalls.incrementAndGet(); return result(); })),
                new FailoverJsonRpcTransport.Config(1, Duration.ofMillis(100), 3));
        assertEquals(result(), halfOpen.send("{}"));
        Thread.sleep(120);
        var pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 16; i++) pool.submit(() -> {
            ready.countDown();
            try { start.await(); halfOpen.send("{}"); } catch (Exception ignored) { }
        });
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(1, probeCalls.get());
    }

    @Test void reportsEveryEndpointDownAndLeavesUnknownRpcErrorsOnCurrentNode() throws Exception {
        var unavailable = failover(request -> { throw new IOException("down"); }, request -> { throw new IOException("also down"); });
        IOException exception = assertThrows(IOException.class, () -> unavailable.send("{}"));
        assertTrue(exception.getMessage().contains("primary"));
        assertTrue(exception.getMessage().contains("backup"));

        AtomicInteger backup = new AtomicInteger();
        var unknown = failover(request -> error(-32000, "opaque failure"), request -> { backup.incrementAndGet(); return result(); });
        assertEquals(error(-32000, "opaque failure"), unknown.send("{}"));
        assertEquals(0, backup.get());
    }

    private static FailoverJsonRpcTransport failover(JsonRpcTransport primary, JsonRpcTransport backup) {
        return new FailoverJsonRpcTransport(List.of(new FailoverJsonRpcTransport.Endpoint("primary", primary),
                new FailoverJsonRpcTransport.Endpoint("backup", backup)), new FailoverJsonRpcTransport.Config(1, Duration.ZERO, 3));
    }
    private static String result() { return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"ok\"}"; }
    private static String error(int code, String message) { return "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":" + code + ",\"message\":" + JSON.writeValueAsString(message) + "}}"; }
}
