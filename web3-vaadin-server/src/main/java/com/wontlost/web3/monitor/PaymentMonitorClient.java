package com.wontlost.web3.monitor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.vaadin.flow.server.VaadinContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synchronous client for the hosted payment monitor API. */
public final class PaymentMonitorClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final URI baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final Clock clock;

    /** Creates a client using the JDK HTTP client. */
    public PaymentMonitorClient(URI baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC());
    }

    PaymentMonitorClient(URI baseUrl, String apiKey, HttpClient httpClient, Clock clock) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Creates a payment intent for the merchant's order. */
    public MonitoredPayment createPayment(CreatePaymentRequest request) {
        return parse(send("POST", "/v1/payments", MAPPER.writeValueAsString(request)));
    }

    /** Associates a submitted transaction hash with a payment intent. */
    public MonitoredPayment submitTransaction(String paymentId, String txHash) {
        var body = MAPPER.createObjectNode().put("txHash", txHash);
        return parse(send("POST", "/v1/payments/" + pathSegment(paymentId) + "/transaction", body.toString()));
    }

    /** Returns the current state of a payment intent. */
    public MonitoredPayment getPayment(String paymentId) {
        return parse(send("GET", "/v1/payments/" + pathSegment(paymentId), null));
    }

    /** Registers this client in the application context without storing it in a Vaadin session. */
    public static void register(VaadinContext context, PaymentMonitorClient client) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(client, "client");
        Registry registry = registry(context);
        PaymentMonitorClient existing = registry.client.get();
        if (existing == null && registry.client.compareAndSet(null, client)) return;
        existing = registry.client.get();
        if (existing != client) {
            throw new IllegalStateException("A different payment monitor client is already registered");
        }
    }

    /** Finds the application-scoped payment monitor client. */
    public static Optional<PaymentMonitorClient> find(VaadinContext context) {
        if (context == null) return Optional.empty();
        Registry registry = context.getAttribute(Registry.class);
        return registry == null ? Optional.empty() : Optional.ofNullable(registry.client.get());
    }

    private String send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey).header("Accept", "application/json");
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new PaymentMonitorException(response.statusCode(), response.body());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment monitor request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Payment monitor request failed", exception);
        }
    }

    private static MonitoredPayment parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        JsonNode token = root.path("token");
        return new MonitoredPayment(root.path("id").asString(), root.path("orderId").asString(),
                root.path("chainId").asLong(), new MonitoredPayment.Token(token.path("symbol").asString(),
                        token.path("address").asString(), token.path("decimals").asInt()),
                root.path("recipient").asString(), root.path("amount").asString(), nullable(root, "payer"),
                root.path("minConfirmations").asInt(), MonitoredStatus.valueOf(root.path("status").asString()),
                nullable(root, "txHash"), root.path("paidAmount").asString(), root.path("confirmations").asLong(),
                instant(root, "notBefore"), instant(root, "expiresAt"), instant(root, "createdAt"), instant(root, "updatedAt"));
    }

    private static Instant instant(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isNull() || value.isMissingNode() ? null : Instant.parse(value.asString());
    }

    private static String nullable(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private static String pathSegment(String value) { return java.util.UUID.fromString(value).toString(); }

    private static Registry registry(VaadinContext context) {
        return context.getAttribute(Registry.class, Registry::new);
    }

    private static final class Registry { private final AtomicReference<PaymentMonitorClient> client = new AtomicReference<>(); }
}
