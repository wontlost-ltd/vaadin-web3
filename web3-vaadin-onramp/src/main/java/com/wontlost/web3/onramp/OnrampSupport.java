package com.wontlost.web3.onramp;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.chain.Tokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OnrampSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final Duration CATALOG_TTL = Duration.ofHours(1);
    /** 目录后台刷新：守护线程、容量有界，饱和时直接放弃本次刷新（下次再试）。 */
    static final java.util.concurrent.Executor BACKGROUND = new java.util.concurrent.ThreadPoolExecutor(1, 2, 30,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "web3-onramp-catalog");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private OnrampSupport() { }

    static HttpExchange http(HttpClient client) {
        return request -> client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> send(HttpExchange exchange, HttpRequest request, String provider) {
        try {
            HttpResponse<String> response = exchange.send(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new OnrampException(provider, response.statusCode(), response.body());
            return response;
        } catch (OnrampException error) {
            throw error;
        } catch (Exception error) {
            throw new OnrampException(provider, "Request failed: " + error.getMessage(), error);
        }
    }

    static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(TIMEOUT);
    }

    static JsonNode json(String value, String provider) {
        try { return JSON.readTree(value); }
        catch (RuntimeException error) { throw new OnrampException(provider, "Invalid JSON response", error); }
    }

    static String body(Object value) {
        return JSON.writeValueAsString(value);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~").replace("*", "%2A");
    }

    static String param(String key, String value) { return key + "=" + encode(value); }

    static boolean exactRegisteredToken(TokenInfo token, String chainId, String address) {
        if (token == null || chainId == null || address == null) return false;
        try {
            long id = Long.parseLong(chainId);
            return token.chainId() == id && Tokens.find(token.symbol(), id)
                    .filter(registered -> registered.address().equalsIgnoreCase(token.address()))
                    .filter(registered -> registered.address().equalsIgnoreCase(address)).isPresent();
        } catch (NumberFormatException exception) { return false; }
    }

    static boolean testToken(TokenInfo token, String symbol, String chainId) {
        if (token == null || symbol == null || chainId == null) return false;
        try { return token.chainId() == Long.parseLong(chainId) && token.symbol().equalsIgnoreCase(symbol); }
        catch (NumberFormatException exception) { return false; }
    }

    static String hmacSha256(String message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    static String lower(String value) { return value.toLowerCase(Locale.ROOT); }
}
