package com.wontlost.web3.onramp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.wontlost.web3.chain.TokenInfo;

import tools.jackson.databind.JsonNode;

/** MoonPay hosted checkout. Credentials are transient and never serialized; recreate the provider after Java deserialization. */
public final class MoonPayOnramp implements OnrampProvider {
    private static final long serialVersionUID = 1L;
    private final transient String publishableKey;
    private final transient String secretKey;
    private final transient HttpExchange exchange;
    private final transient CatalogCache<Currency> catalog;
    private final boolean test;
    private final String baseUrl;

    /** Creates a live or sandbox provider based on the publishable-key prefix. */
    public MoonPayOnramp(String publishableKey, String secretKey) {
        this(publishableKey, secretKey, HttpClient.newBuilder().connectTimeout(OnrampSupport.TIMEOUT).build(), Clock.systemUTC());
    }

    MoonPayOnramp(String publishableKey, String secretKey, HttpClient client, Clock clock) {
        this(publishableKey, secretKey, OnrampSupport.http(client), clock);
    }

    MoonPayOnramp(String publishableKey, String secretKey, HttpExchange exchange, Clock clock) {
        if (publishableKey == null || secretKey == null || secretKey.isBlank()) throw new IllegalArgumentException("MoonPay keys are required");
        this.test = publishableKey.startsWith("pk_test_");
        if (!test && !publishableKey.startsWith("pk_live_")) throw new IllegalArgumentException("publishableKey must start with pk_test_ or pk_live_");
        this.publishableKey = publishableKey;
        this.secretKey = secretKey;
        this.exchange = Objects.requireNonNull(exchange);
        this.catalog = new CatalogCache<>(clock, OnrampSupport.CATALOG_TTL);
        this.baseUrl = test ? "https://buy-sandbox.moonpay.com/" : "https://buy.moonpay.com/";
    }

    /** Sets the successful currency-catalog cache duration. */
    public MoonPayOnramp setCurrencyCacheDuration(java.time.Duration duration) {
        catalog.setTtl(Objects.requireNonNull(duration));
        return this;
    }

    @Override public String name() { return "MoonPay"; }
    @Override public boolean isTestEnvironment() { return test; }

    @Override public boolean supports(TokenInfo token) {
        return currencies().stream().anyMatch(currency -> matches(token, currency));
    }

    private boolean matches(TokenInfo token, Currency currency) {
        if (test) return OnrampSupport.testToken(token, currency.symbol, currency.chainId);
        return OnrampSupport.exactRegisteredToken(token, currency.chainId, currency.address);
    }

    @Override public URI createSession(OnrampOrder order) {
        Currency currency = currencies().stream().filter(value -> matches(order.token(), value)).findFirst()
                .orElseThrow(() -> new OnrampException(name(), -1, "Token is not supported"));
        List<String> params = new ArrayList<>();
        params.add(OnrampSupport.param("apiKey", publishableKey));
        params.add(OnrampSupport.param("currencyCode", currency.code));
        params.add(OnrampSupport.param("walletAddress", order.walletAddress()));
        if (order.fiatCurrency() != null) params.add(OnrampSupport.param("baseCurrencyCode", OnrampSupport.lower(order.fiatCurrency())));
        if (order.fiatAmount() != null) params.add(OnrampSupport.param("baseCurrencyAmount", order.fiatAmount().toPlainString()));
        if (order.cryptoAmount() != null) params.add(OnrampSupport.param("quoteCurrencyAmount", order.cryptoAmount().toPlainString()));
        if (order.redirectUrl() != null) params.add(OnrampSupport.param("redirectURL", order.redirectUrl()));
        if (order.partnerOrderId() != null) params.add(OnrampSupport.param("externalTransactionId", order.partnerOrderId()));
        String query = "?" + String.join("&", params);
        return URI.create(baseUrl + query + "&signature=" + OnrampSupport.encode(signQuery(query, secretKey)));
    }

    static String signQuery(String query, String secretKey) {
        if (query == null || !query.startsWith("?")) throw new IllegalArgumentException("Signed MoonPay query must start with ?");
        return OnrampSupport.hmacSha256(query, secretKey);
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
            HttpRequest request = OnrampSupport.request(URI.create("https://api.moonpay.com/v3/currencies")).GET().build();
            JsonNode root = OnrampSupport.json(OnrampSupport.send(exchange, request, name()).body(), name());
            List<Currency> values = new ArrayList<>();
            for (JsonNode node : root) {
                if (!"crypto".equals(node.path("type").asString("")) || node.path("isSuspended").asBoolean(false)) continue;
                if (test && !node.path("supportsTestMode").asBoolean(false)) continue;
                JsonNode metadata = node.path("metadata");
                String code = node.path("code").asString("");
                values.add(new Currency(code, code.split("_")[0],
                        metadata.path("chainId").asString(""), metadata.path("contractAddress").asString("")));
            }
            return values;
    }

    private record Currency(String code, String symbol, String chainId, String address) { }
}
