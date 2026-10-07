package com.wontlost.web3.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import com.wontlost.web3.x402.http.HttpIdentityResolver;
import com.wontlost.web3.x402.http.HttpResourcePolicy;
import com.wontlost.web3.x402.http.InMemoryResourcePolicyRegistry;
import com.wontlost.web3.x402.http.X402PaymentFilter;
import com.wontlost.web3.x402.payment.AccessDecision;
import com.wontlost.web3.x402.payment.PaymentAttempt;
import com.wontlost.web3.x402.payment.PaymentOutcome;
import com.wontlost.web3.x402.payment.PaymentStatus;
import com.wontlost.web3.x402.payment.X402PaymentService;
import com.wontlost.web3.x402.protocol.Eip3009Payload;
import com.wontlost.web3.x402.protocol.JacksonX402Codec;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;
import com.wontlost.web3.x402.protocol.PaymentRequirements;
import com.wontlost.web3.x402.protocol.SettlementResponse;
import com.wontlost.web3.x402.protocol.TransferAuthorization;
import com.wontlost.web3.x402.protocol.X402Resource;
import com.wontlost.web3.x402.siwx.InMemorySiwxChallengeStore;
import com.wontlost.web3.x402.siwx.SiwxVerifier;
import com.wontlost.web3.x402.siwx.IdentitySource;
import com.wontlost.web3.x402.siwx.VerifiedWallet;

class X402PaymentFilterTest {
    private static final String ADDRESS = "0x0000000000000000000000000000000000000001";
    private static final URI ORIGIN = URI.create("https://merchant.example");

    @Test void unauthenticatedRequestGetsExact402HeaderProtocolFields() throws Exception {
        FakePayments payments = new FakePayments();
        X402PaymentFilter filter = filter(payments, 100);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(402, response.getStatus());
        assertEquals(0, chain.getRequest() == null ? 0 : 1);
        PaymentRequired challenge = payments.codec.decodePaymentRequired(response.getHeader("PAYMENT-REQUIRED"));
        assertEquals(2, challenge.x402Version());
        assertEquals(new X402Resource("https://merchant.example/api/x402/quote", "A quote", "application/json"),
                challenge.resource());
        assertEquals(1, challenge.accepts().size());
        assertEquals(requirement(), challenge.accepts().getFirst());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    }

    @Test void settledAuthorizationRunsBusinessBetweenVerifyAndSettleAndFlushesResponse() throws Exception {
        FakePayments payments = new FakePayments();
        X402PaymentFilter filter = filter(payments, 100);
        MockHttpServletRequest request = request();
        request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            payments.events.add("business");
            res.setContentType("application/json");
            res.getWriter().write("{\"quote\":42}");
        };

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("{\"quote\":42}", response.getContentAsString());
        assertEquals(List.of("verify", "business", "settle"), payments.events);
        SettlementResponse result = payments.codec.decodePaymentResponse(response.getHeader("PAYMENT-RESPONSE"));
        assertTrue(result.success());
        assertEquals("0xtx", result.transaction());
        assertFalse(response.getHeader("PAYMENT-RESPONSE").contains("pending"));
    }

    @Test void settledPaymentReplayRunsBusinessWithoutSettlingAgain() throws Exception {
        FakePayments payments = new FakePayments();
        payments.verificationStatus = PaymentStatus.SETTLED;
        X402PaymentFilter filter = filter(payments, 100);
        MockHttpServletRequest request = request();
        request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> res.getWriter().write("same paid response");

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("same paid response", response.getContentAsString());
        assertEquals(1, payments.verifies);
        assertEquals(0, payments.settles);
        SettlementResponse result = payments.codec.decodePaymentResponse(response.getHeader("PAYMENT-RESPONSE"));
        assertTrue(result.success());
        assertEquals("0xtx", result.transaction());
    }

    @Test void settledReplayPreservesBusinessErrorResponse() throws Exception {
        FakePayments payments = new FakePayments();
        payments.verificationStatus = PaymentStatus.SETTLED;
        MockHttpServletRequest request = request();
        request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(payments, 100).doFilter(request, response, (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(404);
            res.getWriter().write("not found");
        });

        assertEquals(404, response.getStatus());
        assertEquals("not found", response.getContentAsString());
        assertEquals(null, response.getHeader("PAYMENT-RESPONSE"));
        assertEquals(0, payments.settles);
    }

    @Test void pendingAndUnknownDiscardBufferedProtectedBodyAndDoNotGrantAccess() throws Exception {
        for (PaymentStatus status : List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN)) {
            FakePayments payments = new FakePayments();
            payments.settlementStatus = status;
            MockHttpServletRequest request = request();
            request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = (req, res) -> res.getWriter().write("secret response");

            filter(payments, 100).doFilter(request, response, chain);

            assertEquals(status == PaymentStatus.PENDING ? 202 : 503, response.getStatus());
            assertEquals("", response.getContentAsString());
            assertEquals(1, payments.verifies);
            assertEquals(1, payments.settles);
            assertFalse(payments.access == AccessDecision.ALLOW);
            SettlementResponse result = payments.codec.decodePaymentResponse(response.getHeader("PAYMENT-RESPONSE"));
            assertEquals("0xtx", result.transaction());
            assertEquals(status == PaymentStatus.PENDING ? "settlement_pending" : "settlement_unknown",
                    result.errorReason());
            assertFalse(response.getHeader("PAYMENT-RESPONSE").contains("pending"));
        }
    }

    @Test void malformedPaymentNeverRunsProtectedHandlerAndHandlerFailureNeverSettles() throws Exception {
        FakePayments payments = new FakePayments();
        MockHttpServletRequest malformed = request();
        malformed.addHeader("PAYMENT-SIGNATURE", "bad");
        MockHttpServletResponse badResponse = new MockHttpServletResponse();
        MockFilterChain badChain = new MockFilterChain();
        filter(payments, 100).doFilter(malformed, badResponse, badChain);
        assertEquals(400, badResponse.getStatus());
        assertTrue(badChain.getRequest() == null);

        MockHttpServletRequest request = request();
        request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwing = (req, res) -> { throw new java.io.IOException("downstream"); };
        filter(payments, 100).doFilter(request, response, throwing);
        assertEquals(500, response.getStatus());
        assertEquals(0, payments.settles);
    }

    @Test void responseLimitFailureDiscardsBodyAndDoesNotSettle() throws Exception {
        FakePayments payments = new FakePayments();
        MockHttpServletRequest request = request();
        request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> res.getOutputStream().write(new byte[8]);

        filter(payments, 4).doFilter(request, response, chain);

        assertEquals(500, response.getStatus());
        assertFalse(response.getContentAsString().contains("secret"));
        assertEquals(0, payments.settles);
    }

    @Test void normalizedServletPathProtectsMatrixEncodedAndTrailingSlashVariants() throws Exception {
        List<String[]> paths = List.of(
                new String[] { "/api/x402/quote;jsessionid=x", "/api/x402/quote", "402" },
                new String[] { "/api/x402/%71uote", "/api/x402/%71uote", "402" },
                new String[] { "/api/x402/quote/", "/api/x402/quote/", "402" },
                new String[] { "/api/x402/quote;jsessionid=x", "/api/x402/quote;jsessionid=x", "400" });
        for (String[] values : paths) {
            FakePayments payments = new FakePayments();
            MockHttpServletRequest request = request();
            request.setRequestURI(values[0]);
            request.setServletPath(values[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter(payments, 100).doFilter(request, response, new MockFilterChain());

            assertEquals(Integer.parseInt(values[2]), response.getStatus());
        }
    }

    @Test void registeredImagePathStillRequiresPayment() throws Exception {
        FakePayments payments = new FakePayments();
        MockHttpServletRequest request = request();
        request.setRequestURI("/api/paid/image.png");
        request.setServletPath("/api/paid/image.png");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(payments, 100, ignored -> Optional.empty(), "/api/paid/image.png", "quote")
                .doFilter(request, response, new MockFilterChain());

        assertEquals(402, response.getStatus());
    }

    @Test void siwxChallengeCapacityReturnsStable503WithoutEvictingLiveChallenge() throws Exception {
        var store = new InMemorySiwxChallengeStore(1);
        var clock = Clock.systemUTC();
        var verifier = new SiwxVerifier(store, clock, Duration.ofMinutes(5), null);
        var existing = verifier.issue(ORIGIN, List.of(31337L), "other", "https://merchant.example/other");
        var policy = new InMemoryResourcePolicyRegistry(List.of(new HttpResourcePolicy(
                "quote", "GET", "/api/x402/quote", false, true, List.of(31337L))));
        FakePayments payments = new FakePayments();
        var filter = new X402PaymentFilter(policy, payments, payments.codec, verifier,
                request -> Optional.empty(), ORIGIN, 100);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, new MockFilterChain());

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("siwx_challenge_capacity"));
        assertTrue(store.find(existing.nonce(), "other").isPresent());
    }

    @Test void downstreamClientErrorsAreReturnedWithoutSettlement() throws Exception {
        for (int status : List.of(400, 404)) {
            FakePayments payments = new FakePayments();
            MockHttpServletRequest request = request();
            request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = (req, res) -> {
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
                res.getWriter().write("downstream error");
            };

            filter(payments, 100).doFilter(request, response, chain);

            assertEquals(status, response.getStatus());
            assertEquals("downstream error", response.getContentAsString());
            assertEquals(1, payments.verifies);
            assertEquals(0, payments.settles);
        }
    }

    @Test void alreadyPaidVerifiedIdentityIgnoresPaymentHeader() throws Exception {
        FakePayments payments = new FakePayments();
        payments.access = AccessDecision.ALLOW;
        MockHttpServletRequest request = request();
        request.addHeader("PAYMENT-SIGNATURE", "invalid duplicate payment attempt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> res.getWriter().write("already paid");

        filter(payments, 100, req -> Optional.of(new VerifiedWallet(ADDRESS, 31337, IdentitySource.SIWE_SESSION)),
                "/api/x402/quote", "quote").doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("already paid", response.getContentAsString());
        assertEquals(0, payments.verifies);
        assertEquals(0, payments.settles);
    }

    @Test void anonymousSignedPaymentFromAlreadyPaidAddressDoesNotSettleAgain() throws Exception {
        FakePayments payments = new FakePayments();
        payments.access = AccessDecision.ALLOW;
        MockHttpServletRequest request = request();
        request.addHeader("PAYMENT-SIGNATURE", payments.codec.encodePaymentPayload(payload()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> res.getWriter().write("already paid");

        filter(payments, 100).doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("already paid", response.getContentAsString());
        assertEquals(1, payments.verifies);
        assertEquals(0, payments.settles);
    }

    @Test void rejectsDuplicateHeadersWithoutRelyingOnResponseStatus() throws Exception {
        FakePayments payments = new FakePayments();
        MockHttpServletRequest duplicate = request();
        duplicate.addHeader("PAYMENT-SIGNATURE", "a");
        duplicate.addHeader("PAYMENT-SIGNATURE", "b");
        MockHttpServletResponse duplicateResponse = new MockHttpServletResponse();
        filter(payments, 100).doFilter(duplicate, duplicateResponse, new MockFilterChain());
        assertEquals(400, duplicateResponse.getStatus());
    }

    private static X402PaymentFilter filter(FakePayments payments, int maxResponseBytes) {
        return filter(payments, maxResponseBytes, request -> Optional.empty(), "/api/x402/quote", "quote");
    }

    private static X402PaymentFilter filter(FakePayments payments, int maxResponseBytes,
            HttpIdentityResolver identity, String path, String resourceId) {
        var policies = new InMemoryResourcePolicyRegistry(List.of(new HttpResourcePolicy(
                resourceId, "GET", path, false, false, List.of(31337L))));
        var siwx = new SiwxVerifier(new InMemorySiwxChallengeStore(),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(5), null);
        return new X402PaymentFilter(policies, payments, payments.codec, siwx, identity, ORIGIN, maxResponseBytes);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/x402/quote");
        request.setServletPath("/api/x402/quote");
        return request;
    }

    private static PaymentRequirements requirement() {
        var configured = new com.wontlost.web3.x402.payment.ResourcePolicy("quote", "1",
                new X402Resource("https://merchant.example/api/x402/quote", "A quote", "application/json"),
                "eip155:31337", java.math.BigInteger.valueOf(42),
                "0x0000000000000000000000000000000000000002", "0x0000000000000000000000000000000000000003",
                300, "Test Token", "1");
        return configured.requirements();
    }

    private static PaymentPayload payload() {
        var resource = new X402Resource("https://merchant.example/api/x402/quote", "A quote", "application/json");
        return new PaymentPayload(2, resource, requirement(), new Eip3009Payload("0x" + "11".repeat(64) + "1b",
                new TransferAuthorization(ADDRESS, requirement().payTo(), "42", "1", "9999999999",
                        "0x" + "22".repeat(32))));
    }

    private static final class FakePayments implements X402PaymentService {
        private final JacksonX402Codec codec = new JacksonX402Codec();
        private final List<String> events = new ArrayList<>();
        private final X402Resource resource = new X402Resource(
                "https://merchant.example/api/x402/quote", "A quote", "application/json");
        private final PaymentRequired challenge = new PaymentRequired(2, null, resource,
                List.of(requirement()), Map.of());
        private PaymentStatus settlementStatus = PaymentStatus.SETTLED;
        private PaymentStatus verificationStatus = PaymentStatus.VERIFIED;
        private AccessDecision access = AccessDecision.PAYMENT_REQUIRED;
        private int verifies;
        private int settles;
        @Override public PaymentRequired createChallenge(String resourceId) { return challenge; }
        @Override public PaymentRequired createChallenge(String resourceId, URI uri) { return challenge; }
        @Override public PaymentAttempt prepare(String resourceId, String walletAddress) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome verifyPayment(String resourceId, PaymentPayload payload) {
            verifies++;
            events.add("verify");
            return new PaymentOutcome("id", verificationStatus,
                    verificationStatus == PaymentStatus.SETTLED ? "0xtx" : null, null);
        }
        @Override public PaymentOutcome settlePayment(String resourceId, PaymentPayload payload) {
            settles++;
            events.add("settle");
            return new PaymentOutcome("id", settlementStatus,
                    settlementStatus == PaymentStatus.SETTLED ? "0xtx" : "0xtx", "settlement_pending");
        }
        @Override public PaymentOutcome verifyAndSettle(String resourceId, PaymentPayload payload) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome reconcile(String paymentId) { throw new UnsupportedOperationException(); }
        @Override public AccessDecision hasAccess(String resourceId, String walletAddress) { return access; }
        @Override public Optional<PaymentOutcome> latestOutcome(String resourceId, String walletAddress) { return Optional.empty(); }
    }
}
