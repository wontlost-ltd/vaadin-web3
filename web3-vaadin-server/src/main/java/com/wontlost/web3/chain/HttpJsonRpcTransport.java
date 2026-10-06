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
    private final boolean ownsClient;

    /** Creates a transport with a privately owned HTTP client and a ten-second timeout. */
    public HttpJsonRpcTransport(String endpoint) { this(endpoint, Duration.ofSeconds(10)); }

    /** Creates a transport with a privately owned HTTP client. */
    public HttpJsonRpcTransport(String endpoint, Duration timeout) {
        this(endpoint, timeout, HttpClient.newBuilder().connectTimeout(timeout).build(), true);
    }

    /** Creates a transport using a caller-owned shared HTTP client. */
    public HttpJsonRpcTransport(String endpoint, Duration timeout, HttpClient client) {
        this(endpoint, timeout, client, false);
    }

    private HttpJsonRpcTransport(String endpoint, Duration timeout, HttpClient client, boolean ownsClient) {
        this.endpoint = URI.create(Objects.requireNonNull(endpoint));
        this.timeout = Objects.requireNonNull(timeout);
        this.client = Objects.requireNonNull(client);
        this.ownsClient = ownsClient;
    }

    @Override
    public String send(String requestJson) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "web3-vaadin")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson)).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new JsonRpcHttpException(response.statusCode(),
                        "JSON-RPC HTTP request failed with status " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("JSON-RPC request interrupted", exception);
        }
    }

    /** Closes the HTTP client only when this transport created it. */
    @Override
    public void close() {
        if (ownsClient) client.close();
    }

    public static final class JsonRpcHttpException extends IOException {
        private final int status;
        /** Creates an exception retaining the HTTP response status. */
        public JsonRpcHttpException(int status, String message) { super(message); this.status = status; }
        /** Returns the HTTP response status. */
        public int status() { return status; }
    }
}
