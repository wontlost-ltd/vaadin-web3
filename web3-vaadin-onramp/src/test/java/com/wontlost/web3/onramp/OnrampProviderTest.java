package com.wontlost.web3.onramp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.chain.Tokens;

import tools.jackson.databind.JsonNode;

class OnrampProviderTest {
    private static final String FIXTURES = "/fixtures/";

    @Test void moonPayOfficialSignatureVectorAndUrlEncoding() {
        String message = "?apiKey=pk_test_DocsVector00&currencyCode=eth&walletAddress=0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe";
        assertEquals("oIJxSghyzll/BLhUFdQZhkxf7DAS8REFaWr/ibO+K8Q=", MoonPayOnramp.signQuery(message, "sk_test_DocsVector00"));
        assertNotEquals("z2PF5gVnemAZat6jSnLdgG7MB9SNX1TCrRc2bkHq8DQ=", MoonPayOnramp.signQuery(message, "sk_test_DocsVector00"));
        assertThrows(IllegalArgumentException.class, () -> MoonPayOnramp.signQuery(message.substring(1), "secret"));
    }

    @Test void moonPayUsesExactLiveAddressesAndTestSymbolChainMatching() {
        String fixture = read("moonpay-currencies.json");
        MoonPayOnramp live = new MoonPayOnramp("pk_live_key", "secret", fixed(fixture), Clock.systemUTC());
        assertTrue(live.supports(token("USDC", 1)));
        assertTrue(live.supports(token("USDC", 8453)));
        assertTrue(live.supports(token("USDT", 1)));
        assertTrue(live.supports(token("PYUSD", 1)));
        assertFalse(live.supports(token("USDT", 10)));
        assertFalse(live.supports(token("EURC", 1)));
        String testFixture = fixture.replace("\"code\": \"usdt_optimism\"", "\"code\": \"usdt_optimism\"").replace("\"chainId\": \"\",\n   \"networkCode\": \"optimism\"", "\"chainId\": \"10\",\n   \"networkCode\": \"optimism\"");
        MoonPayOnramp test = new MoonPayOnramp("pk_test_key", "secret", fixed(testFixture), Clock.systemUTC());
        assertTrue(test.supports(token("USDT", 10)));
    }

    @Test void moonPayUrlParametersAreOrderedEncodedAndIndependentlySigned() {
        MoonPayOnramp provider = new MoonPayOnramp("pk_test_key", "secret", fixed(read("moonpay-currencies.json")), Clock.systemUTC());
        OnrampOrder order = new OnrampOrder(token("USDC", 1), "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe",
                new BigDecimal("12.5"), null, null, "https://example.test/return?a=b&c=d", null, "order id");
        String url = provider.createSession(order).toString();
        String query = url.substring(url.indexOf('?'), url.indexOf("&signature="));
        assertTrue(query.startsWith("?apiKey=pk_test_key&currencyCode=usdc&walletAddress=0x"));
        assertTrue(query.contains("quoteCurrencyAmount=12.5&redirectURL=https%3A%2F%2Fexample.test%2Freturn%3Fa%3Db%26c%3Dd&externalTransactionId=order%20id"));
        String signature = url.substring(url.indexOf("signature=") + 10);
        assertEquals(OnrampSupport.encode(OnrampSupport.hmacSha256(query, "secret")), signature);
    }

    @Test void transakMatchesLiveAndStagingAndCachesAccessToken() {
        AtomicInteger refreshCount = new AtomicInteger();
        AtomicInteger catalogCount = new AtomicInteger();
        List<HttpRequest> requests = new ArrayList<>();
        HttpExchange exchange = request -> {
            requests.add(request);
            String path = request.uri().getPath();
            if (path.endsWith("crypto-currencies")) {
                catalogCount.incrementAndGet();
                return response(request, read("transak-currencies.json"));
            }
            if (path.endsWith("refresh-token")) {
                refreshCount.incrementAndGet();
                return response(request, "{\"data\":{\"accessToken\":\"token-value\",\"expiresAt\":1893456000}} ");
            }
            return response(request, "{\"data\":{\"widgetUrl\":\"https://widget.test/session\"}}");
        };
        TransakOnramp provider = new TransakOnramp("api", "secret", "shop.example", false, exchange,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));
        assertTrue(provider.supports(token("USDC", 8453)));
        assertTrue(provider.supports(token("USDT", 137)));
        assertTrue(provider.supports(token("EURC", 8453)));
        assertTrue(provider.supports(token("PYUSD", 1)));
        assertEquals(1, catalogCount.get());
        OnrampOrder order = new OnrampOrder(token("USDC", 8453), "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe",
                new BigDecimal("5"), null, null, "https://shop.example/return", "203.0.113.4", "order-7");
        assertEquals(URI.create("https://widget.test/session"), provider.createSession(order));
        assertEquals(URI.create("https://widget.test/session"), provider.createSession(order));
        assertEquals(1, refreshCount.get());
        HttpRequest session = requests.stream().filter(r -> r.uri().getPath().endsWith("auth/session")).findFirst().orElseThrow();
        assertEquals("token-value", session.headers().firstValue("access-token").orElseThrow());
        assertEquals("api", session.headers().firstValue("x-api-key").orElseThrow());
        assertEquals("203.0.113.4", session.headers().firstValue("x-user-ip").orElseThrow());
        String body = body(session);
        assertTrue(body.contains("\"cryptoCurrencyCode\":\"USDC\""));
        assertTrue(body.contains("\"network\":\"base\""));
        assertTrue(body.contains("\"disableWalletAddressForm\":true"));
        assertTrue(body.contains("\"partnerOrderId\":\"order-7\""));
        assertThrows(IllegalArgumentException.class, () -> provider.createSession(new OnrampOrder(token("USDC", 8453),
                "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe", BigDecimal.ONE, null, null, null, null, null)));

        TransakOnramp staging = new TransakOnramp("api", "secret", "shop.example", true,
                request -> response(request, read("transak-staging-currencies.json")), Clock.systemUTC());
        assertTrue(staging.supports(token("USDC", 11155111)));
    }

    @Test void transakRefreshesTokenOnlyInsideFiveMinuteExpiryWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AtomicInteger refreshes = new AtomicInteger();
        HttpExchange exchange = request -> {
            if (request.uri().getPath().endsWith("crypto-currencies")) return response(request, read("transak-currencies.json"));
            if (request.uri().getPath().endsWith("refresh-token")) {
                refreshes.incrementAndGet();
                return response(request, "{\"data\":{\"accessToken\":\"token-" + refreshes.get() + "\",\"expiresAt\":" + (clock.instant().getEpochSecond() + 604800) + "}}");
            }
            return response(request, "{\"data\":{\"widgetUrl\":\"https://widget.test/session\"}}");
        };
        TransakOnramp provider = new TransakOnramp("api", "secret", "shop.example", false, exchange, clock);
        OnrampOrder order = new OnrampOrder(token("USDC", 8453), "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe",
                null, BigDecimal.TEN, "USD", null, "203.0.113.4", null);
        provider.createSession(order);
        provider.createSession(order);
        assertEquals(1, refreshes.get());
        clock.set(Instant.parse("2026-01-07T23:56:00Z"));
        provider.createSession(order);
        assertEquals(2, refreshes.get());
    }

    @Test void coinbaseEd25519JwtHasSdkClaimsAndVerifies() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] seed = ((java.security.interfaces.EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();
        byte[] encodedPublic = pair.getPublic().getEncoded();
        byte[] rawPublic = java.util.Arrays.copyOfRange(encodedPublic, encodedPublic.length - 32, encodedPublic.length);
        String secret = Base64.getEncoder().encodeToString(concat(seed, rawPublic));
        CoinbaseOnramp provider = new CoinbaseOnramp("key-id", secret,
                request -> response(request, "{\"purchase_currencies\":[]}"),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));
        String jwt = provider.jwt("GET", "api.developer.coinbase.com", "/onramp/v1/buy/options");
        String[] parts = jwt.split("\\.");
        JsonNode header = OnrampSupport.json(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), "test");
        JsonNode claims = OnrampSupport.json(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), "test");
        assertEquals("EdDSA", header.path("alg").asString());
        assertEquals("key-id", header.path("kid").asString());
        assertEquals("JWT", header.path("typ").asString());
        assertTrue(header.path("nonce").asString().matches("[0-9a-f]{32}"));
        assertEquals("key-id", claims.path("sub").asString());
        assertEquals("cdp", claims.path("iss").asString());
        assertEquals("GET api.developer.coinbase.com/onramp/v1/buy/options", claims.path("uris").get(0).asString());
        assertEquals(120, claims.path("exp").asLong() - claims.path("iat").asLong());
        var pub = KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519,
                ((EdECPublicKey) pair.getPublic()).getPoint()));
        Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(pub);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }


    @Test void coinbaseMatchesExactCatalogContractsAndBuildsSessionRequest() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] seed = ((java.security.interfaces.EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();
        byte[] publicKey = java.util.Arrays.copyOfRange(pair.getPublic().getEncoded(), pair.getPublic().getEncoded().length - 32, pair.getPublic().getEncoded().length);
        String secret = Base64.getEncoder().encodeToString(concat(seed, publicKey));
        String catalog = "{\"purchase_currencies\":[{\"symbol\":\"USDC\",\"networks\":["
                + "{\"name\":\"base\",\"chain_id\":8453,\"contract_address\":\"0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913\"},"
                + "{\"name\":\"base\",\"chain_id\":8453,\"contract_address\":\"0x0000000000000000000000000000000000000001\"}]}]}";
        List<HttpRequest> requests = new ArrayList<>();
        HttpExchange exchange = request -> {
            requests.add(request);
            if (request.uri().getPath().equals("/onramp/v1/buy/options")) return response(request, catalog);
            return response(request, "{\"session\":{\"onrampUrl\":\"https://coinbase.test/session\"}}");
        };
        CoinbaseOnramp provider = new CoinbaseOnramp("kid", secret, exchange, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC")));
        provider.setCountry("CA");
        assertTrue(provider.supports(token("USDC", 8453)));
        assertFalse(provider.supports(new TokenInfo("USDC", 8453, "0x0000000000000000000000000000000000000001", 6)));
        OnrampOrder order = new OnrampOrder(token("USDC", 8453), "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe",
                new BigDecimal("4"), null, null, "https://shop.test/return", "198.51.100.7", "x".repeat(49));
        assertEquals(URI.create("https://coinbase.test/session"), provider.createSession(order));
        assertTrue(requests.getFirst().uri().toString().endsWith("?country=CA"));
        assertEquals("Bearer ", requests.getFirst().headers().firstValue("Authorization").orElseThrow().substring(0, 7));
        HttpRequest session = requests.getLast();
        String body = body(session);
        assertTrue(body.contains("\"destinationAddress\""));
        assertTrue(body.contains("\"purchaseCurrency\":\"USDC\""));
        assertTrue(body.contains("\"destinationNetwork\":\"base\""));
        assertTrue(body.contains("\"purchaseAmount\":\"4\""));
        assertTrue(body.contains("\"redirectUrl\":\"https://shop.test/return\""));
        assertTrue(body.contains("\"clientIp\":\"198.51.100.7\""));
        assertTrue(body.contains("\"partnerUserRef\":\"" + "x".repeat(49) + "\""));
        // 超长订单引用直接拒绝，不再静默截断（截断可能让两个订单撞成同一引用）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new OnrampOrder(token("USDC", 8453),
                "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe", null, null, null, null, null, "x".repeat(50)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new OnrampOrder(token("USDC", 8453),
                "0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe", null, new BigDecimal("10"), null, null, null, null));
    }

    @Test void coinbaseEs256ProducesJoseSignatureAndAcceptsPkcs8AndSec1() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        PrivateKeyHolder.verify(pem);
        byte[] der = pair.getPrivate().getEncoded();
        byte[] sec1 = extractPrivateKeyOctets(der);
        String sec1Pem = "-----BEGIN EC PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(sec1) + "\n-----END EC PRIVATE KEY-----";
        PrivateKeyHolder.verify(sec1Pem);
        CoinbaseOnramp provider = new CoinbaseOnramp("kid", pem, request -> response(request, "{}"), Clock.systemUTC());
        String[] parts = provider.jwt("POST", "api.cdp.coinbase.com", "/platform/v2/onramp/sessions").split("\\.");
        byte[] jose = Base64.getUrlDecoder().decode(parts[2]);
        assertEquals(64, jose.length);
        byte[] signatureDer = joseToDer(jose);
        Signature verifier = Signature.getInstance("SHA256withECDSA"); verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(signatureDer));
    }

    private static TokenInfo token(String symbol, long chain) { return Tokens.find(symbol, chain).orElseThrow(); }
    private static HttpExchange fixed(String body) { return request -> response(request, body); }
    private static String read(String path) {
        try (var input = OnrampProviderTest.class.getResourceAsStream(FIXTURES + path)) {
            if (input == null) throw new IllegalStateException(path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
    private static HttpResponse<String> response(HttpRequest request, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return 200; }
            public HttpRequest request() { return request; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b)->true); }
            public String body() { return body; }
            public URI uri() { return request.uri(); }
            public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
            public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        };
    }
    private static String body(HttpRequest request) { return request.bodyPublisher().map(p -> { var sink = new java.io.ByteArrayOutputStream(); p.subscribe(new java.util.concurrent.Flow.Subscriber<>() { public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);} public void onNext(java.nio.ByteBuffer b){byte[] x=new byte[b.remaining()];b.get(x);try{sink.write(x);}catch(Exception e){throw new RuntimeException(e);}} public void onError(Throwable t){} public void onComplete(){} }); return sink.toString(StandardCharsets.UTF_8); }).orElse(""); }
    private static byte[] concat(byte[] a, byte[] b) { byte[] result = java.util.Arrays.copyOf(a, a.length+b.length); System.arraycopy(b,0,result,a.length,b.length); return result; }
    private static byte[] extractPrivateKeyOctets(byte[] pkcs8) {
        int i=0; i+=2; i+=3; // outer sequence and version
        if ((pkcs8[i++]&255)!=0x30) throw new IllegalArgumentException(); int len=pkcs8[i++]&255; i+=len;
        if ((pkcs8[i++]&255)!=0x04) throw new IllegalArgumentException(); int n=pkcs8[i++]&255;
        if ((n&128)!=0) { int count=n&127; n=0; while(count-->0)n=(n<<8)|(pkcs8[i++]&255); }
        return java.util.Arrays.copyOfRange(pkcs8,i,i+n);
    }
    private static byte[] joseToDer(byte[] jose) {
        byte[] r=integer(java.util.Arrays.copyOfRange(jose,0,32)), s=integer(java.util.Arrays.copyOfRange(jose,32,64));
        byte[] content=concat(r,s); byte[] result=new byte[content.length+2]; result[0]=0x30; result[1]=(byte)content.length; System.arraycopy(content,0,result,2,content.length); return result;
    }
    private static byte[] integer(byte[] raw) {
        int first=0; while(first<raw.length-1 && raw[first]==0) first++;
        boolean pad=(raw[first]&128)!=0; int len=raw.length-first+(pad?1:0); byte[] out=new byte[len+2]; out[0]=2; out[1]=(byte)len; System.arraycopy(raw,first,out,2+(pad?1:0),raw.length-first); return out;
    }
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant value) { now = value; }
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    private static final class PrivateKeyHolder {
        static void verify(String pem) { assertDoesNotThrow(() -> CoinbaseOnramp.decodePrivateKey(pem)); }
    }
}
