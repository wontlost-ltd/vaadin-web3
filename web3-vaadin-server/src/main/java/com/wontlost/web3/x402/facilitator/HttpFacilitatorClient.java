package com.wontlost.web3.x402.facilitator;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.wontlost.web3.x402.payment.FacilitatorClient;
import com.wontlost.web3.x402.payment.SettlementResult;
import com.wontlost.web3.x402.payment.SettlementState;
import com.wontlost.web3.x402.payment.SupportedResponse;
import com.wontlost.web3.x402.payment.VerifyResult;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequirements;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class HttpFacilitatorClient implements FacilitatorClient {
    private final URI baseUri;
    private final HttpClient client;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final String apiKey;
    private final String apiKeyHeader;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpFacilitatorClient(String baseUrl, String apiKey, String apiKeyHeader,
            Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {
        this.baseUri = validateUri(baseUrl);
        if (connectTimeout == null || requestTimeout == null || connectTimeout.toMillis() < 100
                || connectTimeout.toSeconds() > 30 || requestTimeout.toMillis() < 100 || requestTimeout.toSeconds() > 30)
            throw new IllegalArgumentException("facilitator timeouts must be between 100ms and 30s");
        if (maxResponseBytes < 1 || maxResponseBytes > 262_144) throw new IllegalArgumentException("invalid response limit");
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.timeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiKeyHeader = apiKeyHeader == null || apiKeyHeader.isBlank() ? "Authorization" : apiKeyHeader;
        if (!this.apiKey.isEmpty() && !this.apiKeyHeader.matches("[A-Za-z0-9-]{1,64}"))
            throw new IllegalArgumentException("invalid API key header");
    }

    @Override public SupportedResponse supported() {
        JsonNode root = request("GET", "/supported", null, false);
        JsonNode kinds = root.path("kinds");
        if (!kinds.isArray()) throw malformed();
        return new SupportedResponse(root.path("x402Version").asInt(),
                java.util.stream.StreamSupport.stream(kinds.spliterator(), false).map(JsonNode::asString).toList());
    }
    @Override public VerifyResult verify(PaymentPayload payload, PaymentRequirements requirements) {
        JsonNode root = request("POST", "/verify", body(payload, requirements), false);
        JsonNode valid = root.get("isValid");
        if (valid == null) valid = root.get("valid");
        if (valid == null || !valid.isBoolean()) throw malformed();
        return new VerifyResult(valid.asBoolean(), text(root, "invalidReason"), text(root, "payer"));
    }
    @Override public SettlementResult settle(PaymentPayload payload, PaymentRequirements requirements) {
        try {
            JsonNode root = request("POST", "/settle", body(payload, requirements), true);
            boolean pending = root.path("pending").asBoolean(false)
                    || "settlement_pending".equalsIgnoreCase(text(root, "errorReason"));
            JsonNode successNode = root.get("success");
            if (successNode == null || !successNode.isBoolean()) throw malformed();
            boolean success = successNode.asBoolean();
            String tx = text(root, "transaction");
            if (pending) return new SettlementResult(SettlementState.PENDING, tx, "settlement_pending", null);
            if (success && tx != null && tx.matches("0x[0-9a-fA-F]{64}")) return new SettlementResult(SettlementState.SETTLED, tx, null, null);
            if (success) return new SettlementResult(SettlementState.UNKNOWN, tx, "settlement_unknown", null);
            return new SettlementResult(SettlementState.REJECTED, tx, text(root, "errorReason"), null);
        } catch (FacilitatorException exception) {
            if (exception.failure() == FacilitatorFailure.TIMEOUT || exception.failure() == FacilitatorFailure.UNAVAILABLE
                    || exception.failure() == FacilitatorFailure.SETTLEMENT_UNKNOWN
                    || exception.failure() == FacilitatorFailure.MALFORMED_RESPONSE
                    || exception.failure() == FacilitatorFailure.RESPONSE_TOO_LARGE)
                return new SettlementResult(SettlementState.UNKNOWN, null, "settlement_unknown", null);
            throw exception;
        }
    }

    private Map<String, Object> body(PaymentPayload payload, PaymentRequirements requirements) {
        return Map.of("x402Version", 2, "paymentPayload", payload, "paymentRequirements", requirements);
    }
    private JsonNode request(String method, String path, Object body, boolean settling) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(baseUri.getPath().endsWith("/") ? path.substring(1) : path))
                .timeout(timeout).header("Accept", "application/json");
        if (!apiKey.isEmpty()) builder.header(apiKeyHeader, apiKey);
        try {
            if ("GET".equals(method)) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                try (InputStream ignored = response.body()) { }
                if (settling && (status == 408 || status == 429 || status >= 500))
                    throw new FacilitatorException(FacilitatorFailure.SETTLEMENT_UNKNOWN, status,
                            "facilitator settlement result is unknown", null);
                if (settling && status >= 400 && status < 500)
                    throw new FacilitatorException(FacilitatorFailure.REJECTED, status,
                            "facilitator rejected settlement", null);
                if (status == 401 || status == 403) throw new FacilitatorException(FacilitatorFailure.AUTHENTICATION, status, "facilitator authentication failed", null);
                if (status == 429) throw new FacilitatorException(FacilitatorFailure.RATE_LIMITED, status, "facilitator rate limited request", null);
                if (status >= 500) throw new FacilitatorException(FacilitatorFailure.UNAVAILABLE, status, "facilitator unavailable", null);
                throw new FacilitatorException(FacilitatorFailure.REJECTED, status, "facilitator rejected request", null);
            }
            byte[] responseBytes;
            try (InputStream input = response.body()) { responseBytes = readBounded(input); }
            String responseText;
            try {
                responseText = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(responseBytes)).toString();
            } catch (java.nio.charset.CharacterCodingException exception) { throw malformed(); }
            JsonNode node;
            try { node = mapper.readTree(responseText); }
            catch (Exception exception) { throw malformed(); }
            if (node == null || !node.isObject()) throw malformed();
            return node;
        } catch (FacilitatorException exception) { throw exception; }
        catch (java.net.http.HttpTimeoutException exception) {
            throw new FacilitatorException(settling ? FacilitatorFailure.SETTLEMENT_UNKNOWN : FacilitatorFailure.TIMEOUT,
                    "facilitator request timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FacilitatorException(settling ? FacilitatorFailure.SETTLEMENT_UNKNOWN : FacilitatorFailure.UNAVAILABLE,
                    "facilitator request interrupted");
        } catch (Exception exception) {
            if (exception instanceof ResponseTooLargeException) throw new FacilitatorException(FacilitatorFailure.RESPONSE_TOO_LARGE, "facilitator response exceeds limit");
            throw new FacilitatorException(settling ? FacilitatorFailure.SETTLEMENT_UNKNOWN : FacilitatorFailure.UNAVAILABLE,
                    "facilitator request failed");
        }
    }
    private byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192));
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maxResponseBytes) throw new ResponseTooLargeException();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.isString() ? value.asString() : null;
    }
    private FacilitatorException malformed() { return new FacilitatorException(FacilitatorFailure.MALFORMED_RESPONSE, "malformed facilitator response"); }
    private URI validateUri(String value) {
        URI uri = URI.create(value == null ? "" : value);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost())))
            throw new IllegalArgumentException("facilitator URL must be HTTPS, or loopback HTTP");
        String path = uri.getPath();
        return URI.create(uri.toString().endsWith("/") ? uri.toString() : uri.toString() + "/");
    }
    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }
    private static final class ResponseTooLargeException extends Exception { }
}
