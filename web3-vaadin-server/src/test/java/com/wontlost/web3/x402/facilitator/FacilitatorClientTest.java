package com.wontlost.web3.x402.facilitator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wontlost.web3.x402.payment.SettlementState;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequirements;

import tools.jackson.databind.ObjectMapper;

class FacilitatorClientTest {
    private HttpServer server;
    private AtomicInteger calls;
    private AtomicReference<Integer> status;
    private AtomicReference<String> body;
    private AtomicReference<Long> delay;
    private AtomicReference<String> requestText;
    private AtomicReference<String> apiKey;
    private AtomicReference<String> method;
    private AtomicReference<String> path;
    private AtomicReference<String> contentType;
    private HttpFacilitatorClient client;
    private final PaymentRequirements requirements = new PaymentRequirements("exact", "eip155:1", "100", "0x0000000000000000000000000000000000000001",
            "0x0000000000000000000000000000000000000002", 300, Map.of());
    private final PaymentPayload payload = new PaymentPayload(2, null, requirements, null);

    @BeforeEach void start() throws Exception {
        calls = new AtomicInteger(); status = new AtomicReference<>(200); body = new AtomicReference<>("{\"isValid\":true,\"payer\":\"payer\"}");
        delay = new AtomicReference<>(0L); requestText = new AtomicReference<>(""); apiKey = new AtomicReference<>(null);
        method = new AtomicReference<>(null); path = new AtomicReference<>(null); contentType = new AtomicReference<>(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod()); path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestText.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(delay.get()); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            byte[] content = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        client = client(Duration.ofSeconds(2), 262_144);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void sendsExpectedRequestAndParsesSuccessfulVerificationAndPendingSettlement() throws Exception {
        assertTrue(client.verify(payload, requirements).valid());
        assertEquals("test-secret", apiKey.get());
        assertEquals("POST", method.get());
        assertEquals("/verify", path.get());
        assertEquals("application/json", contentType.get());
        var request = new ObjectMapper().readTree(requestText.get());
        assertEquals(2, request.path("x402Version").asInt());
        assertTrue(request.has("paymentPayload"));
        assertTrue(request.has("paymentRequirements"));
        body.set("{\"success\":false,\"pending\":true,\"errorReason\":\"settlement_pending\",\"transaction\":\"0xabc\"}");
        assertEquals(SettlementState.PENDING, client.settle(payload, requirements).state());
        assertEquals("/settle", path.get());
        body.set("{\"x402Version\":2,\"kinds\":[\"exact:eip155:1\"]}");
        assertEquals(2, client.supported().x402Version());
        assertEquals("GET", method.get());
        assertEquals("/supported", path.get());
        assertEquals(3, calls.get());
    }

    @Test void classifiesAuthenticationRateLimitRejectedAndUnavailableResponses() {
        status.set(401); assertEquals(FacilitatorFailure.AUTHENTICATION, assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
        status.set(429); assertEquals(FacilitatorFailure.RATE_LIMITED, assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
        status.set(400); assertEquals(FacilitatorFailure.REJECTED, assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
        status.set(503); assertEquals(FacilitatorFailure.UNAVAILABLE, assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
        assertEquals(4, calls.get());
    }

    @Test void settleTreatsTimeoutRateLimitAndServerErrorsAsUnknownOnlyForSettlement() {
        for (int code : new int[] {408, 429, 500, 503}) {
            status.set(code);
            assertEquals(SettlementState.UNKNOWN, client.settle(payload, requirements).state());
        }
        status.set(400);
        assertEquals(FacilitatorFailure.REJECTED,
                assertThrows(FacilitatorException.class, () -> client.settle(payload, requirements)).failure());
        status.set(401);
        assertEquals(FacilitatorFailure.REJECTED,
                assertThrows(FacilitatorException.class, () -> client.settle(payload, requirements)).failure());
        status.set(408);
        assertEquals(FacilitatorFailure.REJECTED,
                assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
    }

    @Test void classifiesMalformedOversizedAndTimedOutResponsesWithoutRetry() {
        body.set("not-json"); assertEquals(FacilitatorFailure.MALFORMED_RESPONSE, assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
        body.set("x".repeat(200)); client = client(Duration.ofSeconds(2), 32);
        assertEquals(FacilitatorFailure.RESPONSE_TOO_LARGE, assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
        status.set(503); body.set("{}"); int before = calls.get();
        assertEquals(SettlementState.UNKNOWN, client.settle(payload, requirements).state());
        assertEquals(before + 1, calls.get());
        status.set(200); body.set("{\"success\":true,\"transaction\":\"0xabc\"}");
        delay.set(600L); client = client(Duration.ofMillis(100), 262_144);
        assertEquals(FacilitatorFailure.TIMEOUT, assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).failure());
        delay.set(600L);
        assertEquals(SettlementState.UNKNOWN, client.settle(payload, requirements).state());
    }

    @Test void errorsNeverContainTheConfiguredSecret() {
        status.set(403);
        String message = assertThrows(FacilitatorException.class, () -> client.verify(payload, requirements)).getMessage();
        assertFalse(message.contains("test-secret"));
    }

    @Test void capsConfiguredResponseLimitAt256Kib() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new HttpFacilitatorClient("http://127.0.0.1:" + server.getAddress().getPort(), "", "Authorization",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 262_145));
    }

    private HttpFacilitatorClient client(Duration timeout, int maxBytes) {
        return new HttpFacilitatorClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-secret", "Authorization",
                Duration.ofMillis(100), timeout, maxBytes);
    }
}
