package com.wontlost.web3.x402.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public final class JacksonX402Codec implements X402Codec {
    public static final int DEFAULT_MAX_HEADER_BYTES = 65_536;
    public static final int DEFAULT_MAX_JSON_BYTES = 49_152;
    private static final Set<String> EXTRA_KEYS = Set.of("name", "version", "assetTransferMethod", "paymentFlow");
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final int maxHeaderBytes;
    private final int maxJsonBytes;

    public JacksonX402Codec() { this(DEFAULT_MAX_HEADER_BYTES, DEFAULT_MAX_JSON_BYTES); }
    public JacksonX402Codec(int maxHeaderBytes, int maxJsonBytes) {
        if (maxHeaderBytes < 1 || maxJsonBytes < 1) throw new IllegalArgumentException("limits must be positive");
        this.maxHeaderBytes = maxHeaderBytes;
        this.maxJsonBytes = maxJsonBytes;
    }

    @Override public String encodePaymentRequired(PaymentRequired value) { return encode(value); }
    @Override public String encodePaymentResponse(SettlementResponse value) { return encode(value); }
    @Override public String encodePaymentPayload(PaymentPayload value) { return encode(value); }
    @Override public PaymentRequired decodePaymentRequired(String header) { return required(read(header)); }
    @Override public PaymentPayload decodePaymentPayload(String header) { return payload(read(header)); }
    @Override public SettlementResponse decodePaymentResponse(String header) { return settlement(read(header)); }

    private String encode(Object value) {
        try {
            byte[] json = mapper.writeValueAsBytes(value);
            if (json.length > maxJsonBytes) throw new IllegalArgumentException("x402 JSON exceeds limit");
            String encoded = Base64.getEncoder().withoutPadding().encodeToString(json);
            if (encoded.length() > maxHeaderBytes) throw new IllegalArgumentException("x402 header exceeds limit");
            return encoded;
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("invalid x402 object", exception); }
    }

    private JsonNode read(String header) {
        if (header == null || header.isEmpty() || header.length() > maxHeaderBytes
                || !header.matches("[A-Za-z0-9+/]+={0,2}")) throw new IllegalArgumentException("invalid x402 header");
        String body = header.replaceFirst("=+$", "");
        if (body.length() % 4 == 1) throw new IllegalArgumentException("invalid base64 length");
        int padding = header.length() - body.length();
        int expectedPadding = body.length() % 4 == 2 ? 2 : body.length() % 4 == 3 ? 1 : 0;
        if (padding != 0 && (header.length() % 4 != 0 || padding != expectedPadding))
            throw new IllegalArgumentException("invalid base64 padding");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(body); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("invalid x402 header", exception); }
        if (bytes.length > maxJsonBytes) throw new IllegalArgumentException("x402 JSON exceeds limit");
        String json;
        try { json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException exception) { throw new IllegalArgumentException("invalid UTF-8", exception); }
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("x402 value must be an object");
            checkDepth(node, 0);
            return node;
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("invalid x402 JSON", exception); }
    }

    private void checkDepth(JsonNode node, int depth) {
        if (depth > 32) throw new IllegalArgumentException("x402 JSON nesting too deep");
        if (node.isObject() || node.isArray()) for (JsonNode child : node) checkDepth(child, depth + 1);
    }

    private PaymentRequired required(JsonNode node) {
        version(node);
        X402Resource resource = resource(object(node, "resource"));
        JsonNode acceptsNode = node.get("accepts");
        if (acceptsNode == null || !acceptsNode.isArray() || acceptsNode.isEmpty())
            throw new IllegalArgumentException("accepts is required");
        List<PaymentRequirements> accepts = new ArrayList<>();
        for (JsonNode item : acceptsNode) accepts.add(requirements(item));
        return new PaymentRequired(2, stringOrNull(node, "error"), resource, List.copyOf(accepts), jsonMap(node.get("extensions")));
    }

    private PaymentPayload payload(JsonNode node) {
        version(node);
        JsonNode accepted = object(node, "accepted");
        JsonNode payload = object(node, "payload");
        TransferAuthorization authorization = authorization(object(payload, "authorization"));
        String signature = string(payload, "signature");
        X402Validation.signature(signature);
        return new PaymentPayload(2, node.has("resource") ? resource(node.get("resource")) : null,
                requirements(accepted), new Eip3009Payload(signature, authorization));
    }

    private SettlementResponse settlement(JsonNode node) {
        boolean success = bool(node, "success");
        return new SettlementResponse(success, stringOrNull(node, "errorReason"), stringOrNull(node, "errorMessage"),
                stringOrNull(node, "payer"), stringOrNull(node, "transaction"));
    }

    private PaymentRequirements requirements(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("requirements must be an object");
        String scheme = string(node, "scheme");
        if (!"exact".equals(scheme)) throw new IllegalArgumentException("unsupported scheme");
        String network = string(node, "network");
        X402Validation.chainId(network, Set.of());
        String amount = string(node, "amount"); X402Validation.amount(amount);
        String asset = X402Validation.address(string(node, "asset"));
        String payTo = X402Validation.address(string(node, "payTo"));
        int timeout = integer(node, "maxTimeoutSeconds");
        if (timeout < 1 || timeout > 86_400) throw new IllegalArgumentException("invalid timeout");
        Map<String, JsonNode> extra = jsonMap(node.get("extra"));
        for (String key : extra.keySet()) {
            if (EXTRA_KEYS.contains(key) && !extra.get(key).isString())
                throw new IllegalArgumentException("known extra values must be strings");
        }
        JsonNode method = extra.get("assetTransferMethod");
        JsonNode flow = extra.get("paymentFlow");
        String name = text(extra.get("name"));
        String version = text(extra.get("version"));
        if (name.isBlank() || name.length() > 128 || version.isBlank() || version.length() > 32)
            throw new IllegalArgumentException("EIP-712 domain is required");
        if (!"eip3009".equals(text(method))) throw new IllegalArgumentException("unsupported transfer method");
        if (!"authorization".equals(text(flow))) throw new IllegalArgumentException("unsupported payment flow");
        return new PaymentRequirements(scheme, network, amount, asset, payTo, timeout,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra)));
    }

    private TransferAuthorization authorization(JsonNode node) {
        String from = X402Validation.address(string(node, "from"));
        String to = X402Validation.address(string(node, "to"));
        String value = string(node, "value"); X402Validation.amount(value);
        String after = string(node, "validAfter"); X402Validation.uint(after);
        String before = string(node, "validBefore"); X402Validation.uint(before);
        if (new java.math.BigInteger(before).compareTo(new java.math.BigInteger(after)) <= 0)
            throw new IllegalArgumentException("invalid authorization window");
        String nonce = string(node, "nonce"); X402Validation.nonce(nonce);
        return new TransferAuthorization(from, to, value, after, before, nonce);
    }

    private X402Resource resource(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("resource must be an object");
        return new X402Resource(string(node, "url"), stringOrNull(node, "description"), stringOrNull(node, "mimeType"));
    }
    private void version(JsonNode node) {
        JsonNode value = node.get("x402Version");
        if (value == null || !value.isIntegralNumber() || value.asInt() != 2)
            throw new IllegalArgumentException("unsupported x402 version");
    }
    private JsonNode object(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isObject()) throw new IllegalArgumentException(key + " must be an object");
        return value;
    }
    private String string(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString() || value.asString().isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.asString();
    }
    private String stringOrNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException(key + " must be a string");
        return value.asString();
    }
    private int integer(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException(key + " must be an integer");
        return value.asInt();
    }
    private boolean bool(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        return value.asBoolean();
    }
    private String text(JsonNode node) { return node != null && node.isString() ? node.asString() : ""; }
    private Map<String, JsonNode> jsonMap(JsonNode node) {
        if (node == null) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException("extension values must be objects");
        Map<String, JsonNode> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().deepCopy()));
        return java.util.Collections.unmodifiableMap(values);
    }
}
