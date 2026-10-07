package com.wontlost.web3.x402.http;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.wontlost.web3.x402.payment.AccessDecision;
import com.wontlost.web3.x402.payment.PaymentOutcome;
import com.wontlost.web3.x402.payment.PaymentStatus;
import com.wontlost.web3.x402.payment.X402PaymentService;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;
import com.wontlost.web3.x402.protocol.SettlementResponse;
import com.wontlost.web3.x402.protocol.X402Codec;
import com.wontlost.web3.x402.siwx.SiwxChallenge;
import com.wontlost.web3.x402.siwx.SiwxVerifier;
import com.wontlost.web3.x402.siwx.VerifiedWallet;

public final class X402PaymentFilter implements Filter {
    public static final String PAYMENT_REQUIRED = "PAYMENT-REQUIRED";
    public static final String PAYMENT_SIGNATURE = "PAYMENT-SIGNATURE";
    public static final String PAYMENT_RESPONSE = "PAYMENT-RESPONSE";
    public static final String SIGN_IN_WITH_X = "SIGN-IN-WITH-X";
    private static final Logger LOGGER = LoggerFactory.getLogger(X402PaymentFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResourcePolicyRegistry registry;
    private final X402PaymentService payments;
    private final X402Codec codec;
    private final SiwxVerifier siwx;
    private final HttpIdentityResolver identities;
    private final URI origin;
    private final int maximumResponseBytes;

    public X402PaymentFilter(ResourcePolicyRegistry registry, X402PaymentService payments,
            X402Codec codec, SiwxVerifier siwx, HttpIdentityResolver identities,
            URI origin, int maximumResponseBytes) {
        this.registry = java.util.Objects.requireNonNull(registry);
        this.payments = java.util.Objects.requireNonNull(payments);
        this.codec = java.util.Objects.requireNonNull(codec);
        this.siwx = java.util.Objects.requireNonNull(siwx);
        this.identities = java.util.Objects.requireNonNull(identities);
        this.origin = java.util.Objects.requireNonNull(origin);
        if (maximumResponseBytes < 1) {
            throw new IllegalArgumentException("HTTP payment response limit must be positive");
        }
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpResourcePolicy policy;
        try {
            String servletPath = java.util.Objects.requireNonNullElse(httpRequest.getServletPath(), "");
            String pathInfo = java.util.Objects.requireNonNullElse(httpRequest.getPathInfo(), "");
            String containerPath = servletPath + pathInfo;
            if (containerPath.isEmpty()) {
                containerPath = "/";
            }
            String normalizedPath = ResourcePolicyRegistry.normalizePath(containerPath);
            String matchPath = stripPathParameters(normalizedPath);
            Optional<HttpResourcePolicy> matched = registry.match(httpRequest.getMethod(), matchPath);
            if (matched.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            if (normalizedPath.contains(";")) {
                writeError(httpResponse, HttpServletResponse.SC_BAD_REQUEST, "http_path_invalid");
                return;
            }
            policy = matched.get();
        } catch (IllegalArgumentException exception) {
            writeError(httpResponse, HttpServletResponse.SC_BAD_REQUEST, "http_path_invalid");
            return;
        }

        HeaderResult siwxResult = singleHeader(httpRequest, SIGN_IN_WITH_X, httpResponse);
        if (!siwxResult.valid()) {
            return;
        }
        String siwxHeader = siwxResult.value();
        Optional<VerifiedWallet> sessionIdentity = identities.resolve(httpRequest);
        VerifiedWallet proofIdentity = null;
        if (siwxHeader != null) {
            try {
                String resourceUrl = payments.createChallenge(policy.resourceId()).resource().url();
                proofIdentity = siwx.verify(siwxHeader, origin, policy.resourceId(), resourceUrl);
            } catch (RuntimeException exception) {
                fail(httpResponse, HttpServletResponse.SC_BAD_REQUEST, "siwx_proof_invalid", policy.resourceId(), exception);
                return;
            }
            if (sessionIdentity.isPresent()
                    && !sameAddress(sessionIdentity.get().address(), proofIdentity.address())) {
                writeError(httpResponse, HttpServletResponse.SC_BAD_REQUEST, "siwx_identity_mismatch");
                return;
            }
        }
        VerifiedWallet identity = sessionIdentity.orElse(proofIdentity);
        if (identity != null
                && payments.hasAccess(policy.resourceId(), identity.address()) == AccessDecision.ALLOW) {
            chain.doFilter(request, response);
            return;
        }
        if (policy.requireSiwx() && identity == null) {
            sendChallenge(httpRequest, httpResponse, policy, true);
            return;
        }
        HeaderResult paymentResult = singleHeader(httpRequest, PAYMENT_SIGNATURE, httpResponse);
        if (!paymentResult.valid()) {
            return;
        }
        String paymentHeader = paymentResult.value();
        if (paymentHeader == null) {
            sendChallenge(httpRequest, httpResponse, policy, identity == null && policy.requireSiwx());
            return;
        }
        processPayment(httpRequest, httpResponse, chain, policy, paymentHeader, identity);
    }

    private void processPayment(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            HttpResourcePolicy policy, String header, VerifiedWallet identity) throws IOException, ServletException {
        PaymentPayload payload;
        try {
            payload = codec.decodePaymentPayload(header);
            PaymentRequired required = payments.createChallenge(policy.resourceId());
            if (payload.resource() == null || !required.resource().equals(payload.resource())
                    || !required.accepts().contains(payload.accepted())) {
                throw new IllegalArgumentException("payment does not match resource policy");
            }
            if (identity != null && !sameAddress(identity.address(), payload.payload().authorization().from())) {
                throw new IllegalArgumentException("payment identity does not match verified wallet");
            }
        } catch (RuntimeException exception) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "payment_payload_invalid");
            return;
        }

        PaymentOutcome verified;
        try {
            verified = payments.verifyPayment(policy.resourceId(), payload);
        } catch (RuntimeException exception) {
            fail(response, HttpServletResponse.SC_BAD_GATEWAY, "payment_verification_unavailable",
                    policy.resourceId(), exception);
            return;
        }
        if (verified.status() == PaymentStatus.SETTLED) {
            replaySettled(request, response, chain, policy, verified, payload);
            return;
        }
        if (verified.status() != PaymentStatus.VERIFIED && verified.status() != PaymentStatus.SETTLING) {
            sendPaymentResult(response, HttpServletResponse.SC_PAYMENT_REQUIRED, verified, payload);
            return;
        }
        if (identity == null && payments.hasAccess(policy.resourceId(), payload.payload().authorization().from())
                == AccessDecision.ALLOW) {
            setPaymentResponse(response, verified, payload, true);
            chain.doFilter(request, response);
            return;
        }

        BoundedHttpServletResponse buffered = new BoundedHttpServletResponse(response, maximumResponseBytes);
        try {
            chain.doFilter(request, buffered);
            if (buffered.getStatus() < HttpServletResponse.SC_OK
                    || buffered.getStatus() >= HttpServletResponse.SC_MULTIPLE_CHOICES) {
                buffered.copyTo(response);
                return;
            }
        } catch (IOException | ServletException | RuntimeException exception) {
            fail(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "protected_resource_failed",
                    policy.resourceId(), exception);
            return;
        }

        PaymentOutcome settled;
        try {
            settled = payments.settlePayment(policy.resourceId(), payload);
        } catch (RuntimeException exception) {
            fail(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "settlement_unknown",
                    policy.resourceId(), exception);
            return;
        }
        switch (settled.status()) {
            case SETTLED -> {
                setPaymentResponse(buffered, settled, payload, true);
                buffered.copyTo(response);
            }
            case PENDING, SETTLING -> sendPaymentResult(response, 202, settled, payload);
            case UNKNOWN -> sendPaymentResult(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, settled, payload);
            default -> sendPaymentResult(response, HttpServletResponse.SC_PAYMENT_REQUIRED, settled, payload);
        }
    }

    private void replaySettled(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            HttpResourcePolicy policy, PaymentOutcome settled, PaymentPayload payload)
            throws IOException, ServletException {
        BoundedHttpServletResponse buffered = new BoundedHttpServletResponse(response, maximumResponseBytes);
        try {
            chain.doFilter(request, buffered);
        } catch (IOException | ServletException | RuntimeException exception) {
            fail(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "protected_resource_failed",
                    policy.resourceId(), exception);
            return;
        }
        if (buffered.getStatus() < HttpServletResponse.SC_OK
                || buffered.getStatus() >= HttpServletResponse.SC_MULTIPLE_CHOICES) {
            buffered.copyTo(response);
            return;
        }
        setPaymentResponse(buffered, settled, payload, true);
        buffered.copyTo(response);
    }

    private void sendChallenge(HttpServletRequest request, HttpServletResponse response,
            HttpResourcePolicy policy, boolean requireProof) throws IOException {
        PaymentRequired challenge = payments.createChallenge(policy.resourceId());
        if (requireProof) {
            SiwxChallenge issued;
            try {
                issued = siwx.issue(origin, policy.allowedChainIds(), policy.resourceId(), challenge.resource().url());
            } catch (com.wontlost.web3.x402.siwx.SiwxChallengeCapacityException exception) {
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "siwx_challenge_capacity");
                return;
            }
            tools.jackson.databind.node.ObjectNode extension = MAPPER.valueToTree(issued);
            extension.put("required", true);
            challenge = new PaymentRequired(challenge.x402Version(), challenge.error(), challenge.resource(),
                    challenge.accepts(), Map.of(SiwxVerifier.EXTENSION, extension));
        }
        response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        response.setHeader(PAYMENT_REQUIRED, codec.encodePaymentRequired(challenge));
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(0);
    }

    private void sendPaymentResult(HttpServletResponse response, int status,
            PaymentOutcome outcome, PaymentPayload payload) throws IOException {
        PaymentOutcome reported = outcome;
        if (outcome.status() == PaymentStatus.PENDING || outcome.status() == PaymentStatus.SETTLING) {
            reported = new PaymentOutcome(outcome.paymentId(), outcome.status(), outcome.txHash(), "settlement_pending");
        } else if (outcome.status() == PaymentStatus.UNKNOWN) {
            reported = new PaymentOutcome(outcome.paymentId(), outcome.status(), outcome.txHash(), "settlement_unknown");
        }
        setPaymentResponse(response, reported, payload, false);
        response.setStatus(status);
        response.setContentLength(0);
    }

    private void setPaymentResponse(HttpServletResponse response, PaymentOutcome outcome,
            PaymentPayload payload, boolean success) {
        SettlementResponse result = new SettlementResponse(success, outcome.failureCode(), null,
                payload.payload().authorization().from(), outcome.txHash());
        response.setHeader(PAYMENT_RESPONSE, codec.encodePaymentResponse(result));
        response.setHeader("Cache-Control", "no-store");
    }

    private record HeaderResult(boolean valid, String value) { }

    private static HeaderResult singleHeader(HttpServletRequest request, String name,
            HttpServletResponse response) throws IOException {
        List<String> values = java.util.Collections.list(request.getHeaders(name));
        if (values.size() > 1) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "duplicate_payment_header");
            return new HeaderResult(false, null);
        }
        String value = values.isEmpty() ? null : values.getFirst();
        if (value != null && value.length() > 65_536) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "payment_header_too_large");
            return new HeaderResult(false, null);
        }
        return new HeaderResult(true, value);
    }

    private static String stripPathParameters(String path) {
        return java.util.Arrays.stream(path.split("/", -1))
                .map(segment -> segment.split(";", 2)[0])
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static boolean sameAddress(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = ("{\"error\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static void fail(HttpServletResponse response, int status, String code,
            String resourceId, Throwable exception) throws IOException {
        LOGGER.warn("x402 HTTP failure code={} resource={} exceptionType={}", code, resourceId,
                exception.getClass().getName());
        writeError(response, status, code);
    }
}
