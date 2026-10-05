package com.wontlost.web3.onramp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wontlost.web3.chain.TokenInfo;

import tools.jackson.databind.JsonNode;

/** Transak hosted checkout. API credentials are transient and never serialized; recreate the provider after Java deserialization. */
public final class TransakOnramp implements OnrampProvider {
    private static final long serialVersionUID = 1L;
    private final transient String apiKey;
    private final transient String apiSecret;
    private final String referrerDomain;
    private final boolean staging;
    private final transient HttpExchange exchange;
    private final transient Clock clock;
    private final transient CatalogCache<Currency> catalog;
    private transient String accessToken;
    private transient Instant accessTokenExpiresAt = Instant.MIN;

    /** Creates a Transak provider for production or staging. */
    public TransakOnramp(String apiKey, String apiSecret, String referrerDomain, boolean staging) {
        this(apiKey, apiSecret, referrerDomain, staging,
                HttpClient.newBuilder().connectTimeout(OnrampSupport.TIMEOUT).build(), Clock.systemUTC());
    }

    TransakOnramp(String apiKey, String apiSecret, String referrerDomain, boolean staging,
            HttpClient client, Clock clock) {
        this(apiKey, apiSecret, referrerDomain, staging, OnrampSupport.http(client), clock);
    }

    TransakOnramp(String apiKey, String apiSecret, String referrerDomain, boolean staging,
            HttpExchange exchange, Clock clock) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank())
            throw new IllegalArgumentException("Transak API credentials are required");
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.referrerDomain = Objects.requireNonNull(referrerDomain);
        this.staging = staging;
        this.exchange = Objects.requireNonNull(exchange);
        this.clock = Objects.requireNonNull(clock);
        this.catalog = new CatalogCache<>(clock, OnrampSupport.CATALOG_TTL);
    }

    /** Sets the successful currency-catalog cache duration. */
    public TransakOnramp setCurrencyCacheDuration(java.time.Duration duration) {
        catalog.setTtl(Objects.requireNonNull(duration));
        return this;
    }

    @Override public String name() { return "Transak"; }
    @Override public boolean isTestEnvironment() { return staging; }

    @Override public boolean supports(TokenInfo token) {
        return currencies().stream().anyMatch(currency -> matches(token, currency));
    }

    private boolean matches(TokenInfo token, Currency currency) {
        return staging ? OnrampSupport.testToken(token, currency.symbol, currency.chainId)
                : OnrampSupport.exactRegisteredToken(token, currency.chainId, currency.address);
    }

    @Override public URI createSession(OnrampOrder order) {
        if (order.clientIp() == null || order.clientIp().isBlank())
            throw new IllegalArgumentException("clientIp is required by Transak");
        Currency currency = currencies().stream().filter(value -> matches(order.token(), value)).findFirst()
                .orElseThrow(() -> new OnrampException(name(), -1, "Token is not supported"));
        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("apiKey", apiKey);
        widget.put("referrerDomain", referrerDomain);
        widget.put("productsAvailed", "BUY");
        widget.put("cryptoCurrencyCode", currency.symbol);
        widget.put("network", currency.network);
        widget.put("walletAddress", order.walletAddress());
        widget.put("disableWalletAddressForm", true);
        if (order.fiatCurrency() != null) widget.put("fiatCurrency", order.fiatCurrency());
        if (order.fiatAmount() != null) widget.put("fiatAmount", order.fiatAmount().toPlainString());
        if (order.cryptoAmount() != null) widget.put("cryptoAmount", order.cryptoAmount().toPlainString());
        if (order.partnerOrderId() != null) widget.put("partnerOrderId", order.partnerOrderId());
        if (order.redirectUrl() != null) widget.put("redirectURL", order.redirectUrl());
        Map<String, Object> payload = Map.of("widgetParams", widget);
        URI uri = URI.create(gateway() + "/api/v2/auth/session");
        HttpRequest request = OnrampSupport.request(uri).header("access-token", accessToken())
                .header("x-api-key", apiKey).header("x-user-ip", order.clientIp())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OnrampSupport.body(payload))).build();
        JsonNode root = OnrampSupport.json(OnrampSupport.send(exchange, request, name()).body(), name());
        String url = root.path("data").path("widgetUrl").asString("");
        if (url.isBlank()) throw new OnrampException(name(), -1, "Response did not contain data.widgetUrl");
        return URI.create(url);
    }

    private synchronized String accessToken() {
        if (accessToken != null && clock.instant().isBefore(accessTokenExpiresAt.minus(Duration.ofMinutes(5)))) return accessToken;
        URI uri = URI.create(api() + "/partners/api/v2/refresh-token");
        HttpRequest request = OnrampSupport.request(uri).header("api-secret", apiSecret).header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OnrampSupport.body(Map.of("apiKey", apiKey)))).build();
        JsonNode data = OnrampSupport.json(OnrampSupport.send(exchange, request, name()).body(), name()).path("data");
        String token = data.path("accessToken").asString("");
        long expiresAt = data.path("expiresAt").asLong(0);
        if (token.isBlank() || expiresAt <= 0) throw new OnrampException(name(), -1, "Invalid refresh-token response");
        accessToken = token;
        accessTokenExpiresAt = Instant.ofEpochSecond(expiresAt);
        return accessToken;
    }

    private List<Currency> currencies() {
        return catalog.get(this::loadCurrencies);
    }

    @Override public java.util.Optional<Boolean> supportsIfLoaded(TokenInfo token) {
        return catalog.peekAndRefresh(this::loadCurrencies, OnrampSupport.BACKGROUND)
                .map(list -> list.stream().anyMatch(currency -> matches(token, currency)));
    }

    @Override public void prefetch() { catalog.peekAndRefresh(this::loadCurrencies, OnrampSupport.BACKGROUND); }

    private List<Currency> loadCurrencies() {
            URI uri = URI.create(api() + "/api/v2/currencies/crypto-currencies");
            HttpRequest request = OnrampSupport.request(uri).GET().build();
            JsonNode list = OnrampSupport.json(OnrampSupport.send(exchange, request, name()).body(), name()).path("response");
            List<Currency> values = new ArrayList<>();
            for (JsonNode node : list) {
                if (!node.path("isAllowed").asBoolean(false)) continue;
                JsonNode network = node.path("network");
                values.add(new Currency(node.path("symbol").asString(""), node.path("address").asString(""),
                        network.path("chainId").asString(""), network.path("name").asString("")));
            }
            return values;
    }

    private String api() { return staging ? "https://api-stg.transak.com" : "https://api.transak.com"; }
    private String gateway() { return staging ? "https://api-gateway-stg.transak.com" : "https://api-gateway.transak.com"; }
    private record Currency(String symbol, String address, String chainId, String network) { }
}
