package com.wontlost.web3.x402.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.wontlost.web3.x402.payment.AccessDecision;
import com.wontlost.web3.x402.payment.PaymentAttempt;
import com.wontlost.web3.x402.payment.PaymentOutcome;
import com.wontlost.web3.x402.payment.PaymentStatus;
import com.wontlost.web3.x402.payment.X402PaymentService;
import com.wontlost.web3.x402.protocol.Eip3009Payload;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;
import com.wontlost.web3.x402.protocol.PaymentRequirements;
import com.wontlost.web3.x402.protocol.SettlementResponse;
import com.wontlost.web3.x402.protocol.TransferAuthorization;
import com.wontlost.web3.x402.protocol.X402Codec;
import com.wontlost.web3.x402.protocol.X402Resource;
import com.wontlost.web3.x402.siwx.InMemorySiwxChallengeStore;
import com.wontlost.web3.x402.siwx.SiwxVerifier;

class X402PaymentFilterTest {
    private static final X402Resource RESOURCE = new X402Resource("https://example.test/article", "Article", "text/plain");
    private static final PaymentRequirements REQUIREMENTS = new PaymentRequirements(
            "exact", "eip155:1", "1", "0x0000000000000000000000000000000000000001",
            "0x0000000000000000000000000000000000000002", 60, Map.of());
    private static final PaymentPayload PAYLOAD = new PaymentPayload(2, RESOURCE, REQUIREMENTS,
            new Eip3009Payload("signature", new TransferAuthorization(
                    "0x0000000000000000000000000000000000000003", REQUIREMENTS.payTo(), "1", "0", "1", "nonce")));
    private static final PaymentRequired CHALLENGE = new PaymentRequired(2, null, RESOURCE,
            List.of(REQUIREMENTS), Map.of());

    @Test void resourceSendErrorReturnsStatusWithoutSettlingPayment() throws Exception {
        AtomicInteger settlements = new AtomicInteger();
        X402PaymentService payments = payments(settlements);
        ResourcePolicyRegistry registry = new InMemoryResourcePolicyRegistry(List.of(
                new HttpResourcePolicy("article", "GET", "/article", false, false, List.of(1L))));
        X402Codec codec = new X402Codec() {
            @Override public String encodePaymentRequired(PaymentRequired value) { return "challenge"; }
            @Override public PaymentRequired decodePaymentRequired(String value) { return CHALLENGE; }
            @Override public String encodePaymentResponse(SettlementResponse value) { return "response"; }
            @Override public SettlementResponse decodePaymentResponse(String value) { return null; }
            @Override public PaymentPayload decodePaymentPayload(String value) { return PAYLOAD; }
            @Override public String encodePaymentPayload(PaymentPayload value) { return "payload"; }
        };
        Clock clock = Clock.systemUTC();
        SiwxVerifier siwx = new SiwxVerifier(new InMemorySiwxChallengeStore(10, clock), clock, Duration.ofMinutes(5), null);
        X402PaymentFilter filter = new X402PaymentFilter(registry, payments, codec, siwx,
                request -> Optional.empty(), URI.create("https://example.test"), 1024);
        HttpServletRequest request = request();
        Response response = new Response();

        FilterChain chain = (servletRequest, servletResponse) -> ((HttpServletResponse) servletResponse).sendError(404);
        filter.doFilter(request, response.proxy(), chain);

        assertEquals(404, response.status);
        assertEquals(0, settlements.get());
    }

    private static X402PaymentService payments(AtomicInteger settlements) {
        return new X402PaymentService() {
            @Override public PaymentRequired createChallenge(String resourceId) { return CHALLENGE; }
            @Override public PaymentRequired createChallenge(String resourceId, URI canonicalUri) { return CHALLENGE; }
            @Override public PaymentAttempt prepare(String resourceId, String walletAddress) { return null; }
            @Override public PaymentOutcome verifyPayment(String resourceId, PaymentPayload payload) {
                return new PaymentOutcome("id", PaymentStatus.VERIFIED, null, null);
            }
            @Override public PaymentOutcome settlePayment(String resourceId, PaymentPayload payload) {
                settlements.incrementAndGet();
                return new PaymentOutcome("id", PaymentStatus.SETTLED, "0xhash", null);
            }
            @Override public PaymentOutcome verifyAndSettle(String resourceId, PaymentPayload payload) { return null; }
            @Override public PaymentOutcome reconcile(String paymentId) { return null; }
            @Override public AccessDecision hasAccess(String resourceId, String walletAddress) { return AccessDecision.PAYMENT_REQUIRED; }
            @Override public Optional<PaymentOutcome> latestOutcome(String resourceId, String walletAddress) { return Optional.empty(); }
        };
    }

    private static HttpServletRequest request() {
        return (HttpServletRequest) Proxy.newProxyInstance(X402PaymentFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServletPath" -> "/article";
                    case "getPathInfo" -> null;
                    case "getMethod" -> "GET";
                    case "getHeaders" -> java.util.Collections.enumeration(
                            X402PaymentFilter.PAYMENT_SIGNATURE.equals(args[0]) ? List.of("payment") : List.of());
                    case "toString" -> "request-test-double";
                    default -> null;
                });
    }

    private static final class Response {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = 200;

        private HttpServletResponse proxy() {
            return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setStatus" -> status = (int) args[0];
                            case "sendError" -> status = (int) args[0];
                            case "getOutputStream" -> {
                                return new ServletOutputStream() {
                                    @Override public void write(int value) { body.write(value); }
                                    @Override public boolean isReady() { return true; }
                                    @Override public void setWriteListener(WriteListener listener) { }
                                };
                            }
                            case "getStatus" -> { return status; }
                            case "isCommitted" -> { return false; }
                            case "toString" -> { return "response-test-double"; }
                            default -> { }
                        }
                        return null;
                    });
        }
    }
}
