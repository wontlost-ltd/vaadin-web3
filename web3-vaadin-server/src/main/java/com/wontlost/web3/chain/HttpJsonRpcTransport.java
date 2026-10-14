package com.wontlost.web3.chain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** HTTP JSON-RPC transport backed by the JDK HTTP client. */
public final class HttpJsonRpcTransport implements JsonRpcTransport {
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;
    private final boolean ownsClient;
    private final int maxResponseBytes;

    /** Creates a transport with a privately owned HTTP client and a ten-second timeout. */
    public HttpJsonRpcTransport(String endpoint) { this(endpoint, Duration.ofSeconds(10)); }

    /** Creates a transport with a privately owned HTTP client. */
    public HttpJsonRpcTransport(String endpoint, Duration timeout) {
        this(endpoint, timeout, HttpClient.newBuilder().connectTimeout(timeout).build(), true, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /** Creates a transport with a privately owned HTTP client and a response size limit. */
    public HttpJsonRpcTransport(String endpoint, Duration timeout, int maxResponseBytes) {
        this(endpoint, timeout, HttpClient.newBuilder().connectTimeout(timeout).build(), true, maxResponseBytes);
    }

    /** Creates a transport using a caller-owned shared HTTP client. */
    public HttpJsonRpcTransport(String endpoint, Duration timeout, HttpClient client) {
        this(endpoint, timeout, client, false, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /** Creates a transport using a caller-owned shared HTTP client and response size limit. */
    public HttpJsonRpcTransport(String endpoint, Duration timeout, HttpClient client, int maxResponseBytes) {
        this(endpoint, timeout, client, false, maxResponseBytes);
    }

    private HttpJsonRpcTransport(String endpoint, Duration timeout, HttpClient client, boolean ownsClient,
            int maxResponseBytes) {
        this.endpoint = URI.create(Objects.requireNonNull(endpoint));
        this.timeout = Objects.requireNonNull(timeout);
        this.client = Objects.requireNonNull(client);
        this.ownsClient = ownsClient;
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("JSON-RPC response limit must be positive");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String send(String requestJson) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "web3-vaadin")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson)).build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new JsonRpcHttpException(response.statusCode(),
                            "JSON-RPC HTTP request failed with status " + response.statusCode());
                }
                return readBounded(body);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("JSON-RPC request interrupted", exception);
        }
    }

    private String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maxResponseBytes) {
                throw new IOException("JSON-RPC response exceeds limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
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
