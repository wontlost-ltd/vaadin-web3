package com.wontlost.web3.chain;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** HTTP JSON-RPC transport backed by the JDK HTTP client. */
public final class HttpJsonRpcTransport implements JsonRpcTransport {
    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;

    public HttpJsonRpcTransport(String endpoint) { this(endpoint, Duration.ofSeconds(10)); }

    public HttpJsonRpcTransport(String endpoint, Duration timeout) {
        this.endpoint = URI.create(Objects.requireNonNull(endpoint));
        this.timeout = Objects.requireNonNull(timeout);
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String send(String requestJson) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "vaadin-web3")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson)).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("JSON-RPC HTTP request failed with status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("JSON-RPC request interrupted", exception);
        }
    }
}
