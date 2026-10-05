package com.wontlost.web3.onramp;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.AlgorithmParameters;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.wontlost.web3.chain.TokenInfo;

import tools.jackson.databind.JsonNode;

/** Coinbase hosted checkout. API key material is transient and never serialized; recreate the provider after Java deserialization. */
public final class CoinbaseOnramp implements OnrampProvider {
    private static final long serialVersionUID = 1L;
    private static final String OPTIONS_HOST = "api.developer.coinbase.com";
    private static final String SESSION_HOST = "api.cdp.coinbase.com";
    private static final String OPTIONS_PATH = "/onramp/v1/buy/options";
    private static final String SESSION_PATH = "/platform/v2/onramp/sessions";
    private final transient String apiKeyId;
    private final transient String apiKeySecret;
    private final transient HttpExchange exchange;
    private final transient Clock clock;
    private final transient CatalogCache<Currency> catalog;
    // 后台刷新线程会读取，需 volatile
    private volatile String country = "US";

    /** Creates a Coinbase on-ramp provider. Coinbase has no sandbox environment. */
    public CoinbaseOnramp(String apiKeyId, String apiKeySecret) {
        this(apiKeyId, apiKeySecret, HttpClient.newBuilder().connectTimeout(OnrampSupport.TIMEOUT).build(), Clock.systemUTC());
    }

    CoinbaseOnramp(String apiKeyId, String apiKeySecret, HttpClient client, Clock clock) {
        this(apiKeyId, apiKeySecret, OnrampSupport.http(client), clock);
    }

    CoinbaseOnramp(String apiKeyId, String apiKeySecret, HttpExchange exchange, Clock clock) {
        if (apiKeyId == null || apiKeyId.isBlank() || apiKeySecret == null || apiKeySecret.isBlank())
            throw new IllegalArgumentException("Coinbase API credentials are required");
        this.apiKeyId = apiKeyId;
        this.apiKeySecret = apiKeySecret;
        this.exchange = Objects.requireNonNull(exchange);
        this.clock = Objects.requireNonNull(clock);
        this.catalog = new CatalogCache<>(clock, OnrampSupport.CATALOG_TTL);
        decodePrivateKey(apiKeySecret);
    }

    /** Sets the country used to request available purchase currencies and clears the catalog cache. */
    public synchronized CoinbaseOnramp setCountry(String country) {
        if (country == null || !country.matches("[A-Za-z]{2}")) throw new IllegalArgumentException("country must be a two-letter code");
        this.country = country.toUpperCase(java.util.Locale.ROOT);
        catalog.invalidate();
        return this;
    }

    /** Sets the successful currency-catalog cache duration. */
    public CoinbaseOnramp setCurrencyCacheDuration(java.time.Duration duration) {
        catalog.setTtl(Objects.requireNonNull(duration));
        return this;
    }

    @Override public String name() { return "Coinbase"; }
    @Override public boolean isTestEnvironment() { return false; }

    @Override public boolean supports(TokenInfo token) {
        return currencies().stream().anyMatch(currency -> OnrampSupport.exactRegisteredToken(token, currency.chainId, currency.address));
    }

    @Override public URI createSession(OnrampOrder order) {
        Currency currency = currencies().stream().filter(value -> OnrampSupport.exactRegisteredToken(order.token(), value.chainId, value.address))
                .findFirst().orElseThrow(() -> new OnrampException(name(), -1, "Token is not supported"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("destinationAddress", order.walletAddress());
        payload.put("purchaseCurrency", currency.symbol);
        payload.put("destinationNetwork", currency.network);
        if (order.cryptoAmount() != null) payload.put("purchaseAmount", order.cryptoAmount().toPlainString());
        if (order.fiatAmount() != null) {
            payload.put("paymentAmount", order.fiatAmount().toPlainString());
            payload.put("paymentCurrency", order.fiatCurrency() == null ? "USD" : order.fiatCurrency());
        }
        if (order.redirectUrl() != null) payload.put("redirectUrl", order.redirectUrl());
        if (order.clientIp() != null) payload.put("clientIp", order.clientIp());
        if (order.partnerOrderId() != null) payload.put("partnerUserRef", order.partnerOrderId());
        URI uri = URI.create("https://" + SESSION_HOST + SESSION_PATH);
        HttpRequest request = OnrampSupport.request(uri).header("Authorization", "Bearer " + jwt("POST", SESSION_HOST, SESSION_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OnrampSupport.body(payload))).build();
        JsonNode root = OnrampSupport.json(OnrampSupport.send(exchange, request, name()).body(), name());
        String url = root.path("session").path("onrampUrl").asString("");
        if (url.isBlank()) throw new OnrampException(name(), -1, "Response did not contain session.onrampUrl");
        return URI.create(url);
    }

    private List<Currency> currencies() {
        return catalog.get(this::loadCurrencies);
    }

    @Override public java.util.Optional<Boolean> supportsIfLoaded(TokenInfo token) {
        return catalog.peekAndRefresh(this::loadCurrencies, OnrampSupport.BACKGROUND)
                .map(list -> list.stream().anyMatch(currency -> OnrampSupport.exactRegisteredToken(token, currency.chainId, currency.address)));
    }

    @Override public void prefetch() { catalog.peekAndRefresh(this::loadCurrencies, OnrampSupport.BACKGROUND); }

    private List<Currency> loadCurrencies() {
            URI uri = URI.create("https://" + OPTIONS_HOST + OPTIONS_PATH + "?country=" + OnrampSupport.encode(country));
            HttpRequest request = OnrampSupport.request(uri).header("Authorization", "Bearer " + jwt("GET", OPTIONS_HOST, OPTIONS_PATH)).GET().build();
            JsonNode list = OnrampSupport.json(OnrampSupport.send(exchange, request, name()).body(), name()).path("purchase_currencies");
            List<Currency> values = new ArrayList<>();
            for (JsonNode currency : list) {
                for (JsonNode network : currency.path("networks")) {
                    values.add(new Currency(currency.path("symbol").asString(""), network.path("name").asString(""),
                            network.path("chain_id").asString(""), network.path("contract_address").asString("")));
                }
            }
            return values;
    }

    String jwt(String method, String host, String path) {
        try {
            byte[] nonceBytes = new byte[16];
            new java.security.SecureRandom().nextBytes(nonceBytes);
            String nonce = HexFormat.of().formatHex(nonceBytes);
            PrivateKey key = decodePrivateKey(apiKeySecret);
            String algorithm = (key.getAlgorithm().equalsIgnoreCase("Ed25519") || key.getAlgorithm().equalsIgnoreCase("EdDSA")) ? "EdDSA" : "ES256";
            Map<String, Object> header = Map.of("alg", algorithm, "kid", apiKeyId, "typ", "JWT", "nonce", nonce);
            long now = clock.instant().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", apiKeyId);
            claims.put("iss", "cdp");
            claims.put("uris", List.of(method + " " + host + path));
            claims.put("iat", now);
            claims.put("nbf", now);
            claims.put("exp", now + 120);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String signingInput = encoder.encodeToString(OnrampSupport.body(header).getBytes(StandardCharsets.UTF_8)) + "."
                    + encoder.encodeToString(OnrampSupport.body(claims).getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance(algorithm.equals("EdDSA") ? "Ed25519" : "SHA256withECDSA");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] signature = signer.sign();
            if (algorithm.equals("ES256")) signature = derToJose(signature, 32);
            return signingInput + "." + encoder.encodeToString(signature);
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("Unable to sign Coinbase JWT", exception); }
    }

    static PrivateKey decodePrivateKey(String secret) {
        try {
            if (secret.startsWith("-----BEGIN EC PRIVATE KEY-----")) {
                byte[] sec1 = pem(secret, "EC PRIVATE KEY");
                byte[] pkcs8 = wrapSec1(sec1);
                return requireP256(KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)));
            }
            if (secret.startsWith("-----BEGIN PRIVATE KEY-----")) {
                byte[] bytes = pem(secret, "PRIVATE KEY");
                PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(bytes));
                return requireP256(key);
            }
            byte[] decoded;
            try { decoded = Base64.getDecoder().decode(secret); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unsupported Coinbase API key secret format", invalid); }
            if (decoded.length != 64) throw new IllegalArgumentException("Base64 Ed25519 secret must decode to 64 bytes");
            byte[] seed = java.util.Arrays.copyOf(decoded, 32);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid Coinbase API key secret", exception); }
    }

    private static PrivateKey requireP256(PrivateKey key) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
            if (!(key instanceof ECPrivateKey ec) || !ec.getParams().getCurve().equals(expected.getCurve())
                    || !ec.getParams().getGenerator().equals(expected.getGenerator())
                    || !ec.getParams().getOrder().equals(expected.getOrder())
                    || ec.getParams().getCofactor() != expected.getCofactor())
                throw new IllegalArgumentException("Expected a P-256 EC private key");
            return key;
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("Unable to validate P-256 EC key", exception); }
    }

    private static byte[] pem(String pem, String label) {
        String body = pem.replace("-----BEGIN " + label + "-----", "").replace("-----END " + label + "-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static byte[] wrapSec1(byte[] sec1) {
        byte[] algorithm = HexFormat.of().parseHex("301306072A8648CE3D020106082A8648CE3D030107");
        byte[] inner = concat(new byte[]{0x02,0x01,0x00}, algorithm, derOctet(sec1));
        return derSequence(inner);
    }

    private static byte[] derOctet(byte[] value) {
        byte[] length = derLength(value.length);
        return concat(new byte[]{0x04}, length, value);
    }

    private static byte[] derSequence(byte[] value) { return concat(new byte[]{0x30}, derLength(value.length), value); }
    private static byte[] derLength(int length) {
        if (length < 128) return new byte[]{(byte) length};
        int count = 0; for (int n=length; n>0; n>>=8) count++;
        byte[] bytes = new byte[count+1]; bytes[0]=(byte)(0x80|count);
        for(int i=0;i<count;i++) bytes[count-i]=(byte)(length>>(8*i));
        return bytes;
    }
    private static byte[] concat(byte[]... arrays) {
        int size=0; for(byte[] a:arrays) size+=a.length;
        byte[] out=new byte[size]; int pos=0; for(byte[] a:arrays){System.arraycopy(a,0,out,pos,a.length);pos+=a.length;} return out;
    }

    static byte[] derToJose(byte[] der, int width) {
        int[] offset = {0};
        if ((der[offset[0]++] & 0xff) != 0x30) throw new IllegalArgumentException("Invalid ECDSA DER sequence");
        readLength(der, offset);
        byte[] r = readInteger(der, offset), s = readInteger(der, offset);
        byte[] out = new byte[width * 2];
        copyInteger(r, out, 0, width); copyInteger(s, out, width, width);
        return out;
    }

    private static byte[] readInteger(byte[] der, int[] offset) {
        if ((der[offset[0]++] & 0xff) != 0x02) throw new IllegalArgumentException("Invalid ECDSA DER integer");
        int length = readLength(der, offset); byte[] value = java.util.Arrays.copyOfRange(der, offset[0], offset[0] + length); offset[0] += length; return value;
    }
    private static int readLength(byte[] data, int[] offset) {
        int first=data[offset[0]++]&0xff; if(first<128)return first; int count=first&0x7f; int length=0; for(int i=0;i<count;i++)length=(length<<8)|(data[offset[0]++]&0xff); return length;
    }
    private static void copyInteger(byte[] source, byte[] target, int offset, int width) {
        int start=source.length>width?source.length-width:0; int len=source.length-start;
        System.arraycopy(source,start,target,offset+width-len,len);
    }

    private record Currency(String symbol, String network, String chainId, String address) { }
}
