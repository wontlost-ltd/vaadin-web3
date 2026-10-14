package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import org.junit.jupiter.api.Test;

class HttpJsonRpcTransportTest {
    @Test void acceptsResponsesWithinLimit() throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"result\":\"ok\"}";
        HttpJsonRpcTransport transport = new HttpJsonRpcTransport("https://rpc.example", Duration.ofSeconds(1),
                new StubHttpClient(200, json), json.length());

        assertEquals(json, transport.send("{}"));
    }

    @Test void rejectsResponseAboveConfiguredLimit() {
        HttpJsonRpcTransport transport = new HttpJsonRpcTransport("https://rpc.example", Duration.ofSeconds(1),
                new StubHttpClient(200, "12345"), 4);

        IOException exception = assertThrows(IOException.class, () -> transport.send("{}"));
        assertEquals("JSON-RPC response exceeds limit", exception.getMessage());
    }

    private static final class StubHttpClient extends HttpClient {
        private final int status;
        private final byte[] body;

        StubHttpClient(int status, String body) {
            this.status = status;
            this.body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            @SuppressWarnings("unchecked") T input = (T) new ByteArrayInputStream(body);
            return new Response<>(status, input, request);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private record Response<T>(int statusCode, T body, HttpRequest request) implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
