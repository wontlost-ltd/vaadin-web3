package com.wontlost.web3.solana.wallet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.siws.Base58;
import com.wontlost.web3.siws.InMemorySiwsChallengeStore;
import com.wontlost.web3.siws.SiwsChallenge;
import com.wontlost.web3.siws.SiwsVerifier;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.siws.VerifiedSolanaSignIn;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SolanaConnectTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();
    private final SiwsVerifier verifier = new SiwsVerifier(new InMemorySiwsChallengeStore(clock), clock);
    private final SolanaDevWallet wallet = SolanaDevWallet.random(SolanaCluster.LOCALNET);

    @Test void devWalletSignInProducesExactlyTheBytesTheVerifierExpects() {
        SiwsChallenge challenge = verifier.issue("app.example.com", "https://app.example.com", "Sign in to Example",
                SolanaCluster.LOCALNET, Duration.ofMinutes(5), List.of("https://app.example.com/terms"));

        JsonNode result = JSON.readTree(SolanaConnect.handleServerWalletRequest(wallet, "signIn",
                SolanaConnect.signInInput(challenge).put("address", wallet.address()).toString()));

        byte[] signed = Base64.getDecoder().decode(result.path("signedMessage").asString());
        assertEquals(challenge.toMessage(wallet.address()).toMessage(), new String(signed, StandardCharsets.UTF_8));
        VerifiedSolanaSignIn verified = verifier.verify(signed,
                Base64.getDecoder().decode(result.path("signature").asString()), wallet.address());
        assertEquals(wallet.address(), verified.address());
    }

    @Test void devWalletSignsInWithItsOwnAddressWhenTheInputHasNone() {
        SiwsChallenge challenge = verifier.issue("app.example.com", "https://app.example.com", null,
                SolanaCluster.LOCALNET, Duration.ofMinutes(5), List.of());

        JsonNode result = JSON.readTree(SolanaConnect.handleServerWalletRequest(wallet, "signIn",
                SolanaConnect.signInInput(challenge).toString()));

        byte[] signed = Base64.getDecoder().decode(result.path("signedMessage").asString());
        assertEquals(wallet.address(), verifier.verify(signed,
                Base64.getDecoder().decode(result.path("signature").asString()), wallet.address()).address());
    }

    @Test void signInInputMirrorsTheChallengeAndOmitsEmptyFields() {
        SiwsChallenge challenge = verifier.issue("app.example.com", "https://app.example.com", null,
                SolanaCluster.DEVNET, Duration.ofMinutes(5), List.of());

        ObjectNode input = SolanaConnect.signInInput(challenge);

        assertEquals("app.example.com", input.path("domain").asString());
        assertEquals("https://app.example.com", input.path("uri").asString());
        assertEquals("1", input.path("version").asString());
        assertEquals("devnet", input.path("chainId").asString());
        assertEquals(challenge.nonce(), input.path("nonce").asString());
        assertEquals(challenge.issuedAt(), input.path("issuedAt").asString());
        assertEquals(challenge.expirationTime(), input.path("expirationTime").asString());
        assertTrue(input.path("statement").isMissingNode());
        assertTrue(input.path("resources").isMissingNode());
        assertTrue(input.path("address").isMissingNode());
    }

    @Test void devWalletSignsArbitraryMessages() {
        byte[] message = {1, 2, 3};

        JsonNode result = JSON.readTree(SolanaConnect.handleServerWalletRequest(wallet, "signMessage",
                "{\"message\":\"" + Base64.getEncoder().encodeToString(message) + "\"}"));

        assertArrayEquals(wallet.signMessage(message), Base64.getDecoder().decode(result.path("signature").asString()));
    }

    @Test void devWalletRejectsInvalidRequests() {
        assertCode(4100, null, "signMessage", "{\"message\":\"AQID\"}");
        assertCode(4200, wallet, "signTransaction", "{}");
        assertCode(4200, wallet, null, "{}");
        assertCode(-32602, wallet, "signMessage", null);
        assertCode(-32602, wallet, "signMessage", "x".repeat(SolanaConnect.MAX_SERVER_WALLET_PAYLOAD + 1));
        String oversized = "{\"message\":\"" + "A".repeat(SolanaConnect.MAX_SERVER_WALLET_PAYLOAD) + "\"}";
        assertCode(-32602, wallet, "signMessage", oversized);
        String largest = "{\"message\":\"" + "A".repeat(SolanaConnect.MAX_SERVER_WALLET_PAYLOAD - 16) + "\"}";
        SolanaConnect.handleServerWalletRequest(wallet, "signMessage", largest);
        assertCode(-32602, wallet, "signMessage", "not json");
        assertCode(-32602, wallet, "signMessage", "[]");
        assertCode(-32602, wallet, "signMessage", "{\"message\":\"%%%\"}");
        assertCode(-32602, wallet, "signMessage", "{\"message\":1}");
        assertCode(-32602, wallet, "signIn", "{\"uri\":\"https://example.com\"}");
        assertCode(-32602, wallet, "signIn", "{\"domain\":\"example.com\",\"statement\":\"two\\nlines\"}");
        String other = SolanaDevWallet.random(SolanaCluster.LOCALNET).address();
        assertCode(4100, wallet, "signIn", "{\"domain\":\"example.com\",\"address\":\"" + other + "\"}");
    }

    @Test void everyServerWalletRequestGetsAnAnswer() {
        SolanaConnect.ServerWalletResponse ok = SolanaConnect.respond(wallet, "signMessage", "{\"message\":\"AQID\"}");
        assertTrue(ok.result().contains("signature"));

        SolanaConnect.ServerWalletResponse refused = SolanaConnect.respond(wallet, "signTransaction", "{}");
        assertNull(refused.result());
        assertEquals(4200, refused.code());

        SolanaServerWallet broken = new SolanaServerWallet() {
            @Override public String name() { return "Broken"; }
            @Override public String address() { return wallet.address(); }
            @Override public SolanaCluster cluster() { return SolanaCluster.LOCALNET; }
            @Override public byte[] signMessage(byte[] message) { throw new IllegalStateException("hsm offline"); }
        };
        SolanaConnect.ServerWalletResponse failed = SolanaConnect.respond(broken, "signMessage", "{\"message\":\"AQID\"}");
        assertNull(failed.result());
        assertEquals(-32603, failed.code());
        assertEquals("Server wallet request failed", failed.message());
    }

    @Test void serverWalletInfoCarriesThePublicKeyAndChain() {
        JsonNode info = SolanaConnect.serverWalletInfo(wallet);

        assertEquals(wallet.name(), info.path("name").asString());
        assertEquals(wallet.address(), info.path("address").asString());
        assertEquals("solana:localnet", info.path("chain").asString());
        assertEquals(wallet.address(), Base58.encode(Base64.getDecoder().decode(info.path("publicKey").asString())));
        assertFalse(info.toString().contains("seed"));
        assertEquals("solana:mainnet", SolanaConnect.chain(SolanaCluster.MAINNET));
    }

    @Test void parsesSignedSignInResults() {
        byte[] key = Base58.decode(wallet.address(), 32);
        byte[] signature = new byte[64];
        signature[0] = 3;
        String encodedKey = Base64.getEncoder().encodeToString(key);
        String encodedSignature = Base64.getEncoder().encodeToString(signature);
        String json = "{\"publicKey\":\"" + encodedKey + "\",\"signedMessage\":\"AQI=\",\"signature\":\""
                + encodedSignature + "\"}";

        SolanaConnect.SignedSignIn signed = SolanaConnect.parseSignedSignIn(json);

        assertEquals(wallet.address(), signed.publicKey());
        assertArrayEquals(new byte[] {1, 2}, signed.signedMessage());
        assertArrayEquals(signature, signed.signature());
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
        String shortSignature = Base64.getEncoder().encodeToString(new byte[63]);
        for (String invalid : List.of("not json", "{}",
                "{\"publicKey\":\"%%\",\"signedMessage\":\"AQI=\",\"signature\":\"" + encodedSignature + "\"}",
                "{\"publicKey\":\"" + shortKey + "\",\"signedMessage\":\"AQI=\",\"signature\":\"" + encodedSignature + "\"}",
                "{\"publicKey\":\"" + encodedKey + "\",\"signedMessage\":\"AQI=\",\"signature\":\"" + shortSignature + "\"}",
                "{\"publicKey\":\"" + encodedKey + "\",\"signedMessage\":\"\",\"signature\":\"" + encodedSignature + "\"}")) {
            assertEquals(-32603, assertThrows(SolanaConnect.SolanaWalletException.class,
                    () -> SolanaConnect.parseSignedSignIn(invalid)).getCode(), invalid);
        }
    }

    @Test void parsesWalletErrorsFromTheBrowser() {
        SolanaConnect.SolanaWalletException rejected = SolanaConnect.parseError(
                "Error: " + SolanaConnect.ERROR_MARKER + "{\"code\":4001,\"message\":\"User rejected\",\"userRejected\":true}");
        assertEquals(4001, rejected.getCode());
        assertEquals("User rejected", rejected.getMessage());
        assertTrue(rejected.isUserRejected());

        SolanaConnect.SolanaWalletException plain = SolanaConnect.parseError("boom");
        assertEquals(-1, plain.getCode());
        assertFalse(plain.isUserRejected());
        assertEquals(-1, SolanaConnect.parseError(SolanaConnect.ERROR_MARKER + "{broken").getCode());
        assertEquals("", SolanaConnect.parseError(null).getMessage());
    }

    @Test void serverWalletsAreRefusedInProductionMode() {
        SolanaServerWallet.requireDevelopmentMode(wallet, false);
        SolanaServerWallet.requireDevelopmentMode(null, true);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SolanaServerWallet.requireDevelopmentMode(wallet, true));
        assertTrue(refused.getMessage().contains("production mode"));

        java.util.Map<Class<?>, Object> attributes = new java.util.HashMap<>();
        attributes.put(com.vaadin.flow.server.startup.ApplicationConfiguration.class, configuration(true));
        com.vaadin.flow.server.VaadinContext production = context(attributes);
        assertThrows(IllegalStateException.class, () -> SolanaServerWallet.register(production, wallet));
        assertNull(SolanaServerWallet.find(production), "nothing is registered in production mode");

        assertEquals(wallet, SolanaConnect.exposableWallet(wallet, false));
        assertNull(SolanaConnect.exposableWallet(wallet, true), "a wallet slipped into production is not offered");
        assertNull(SolanaConnect.exposableWallet(null, true));
    }

    @Test void serverWalletRegistrationIsSingleInstance() {
        java.util.Map<Class<?>, Object> attributes = new java.util.HashMap<>();
        attributes.put(com.vaadin.flow.server.startup.ApplicationConfiguration.class, configuration(false));
        com.vaadin.flow.server.VaadinContext context = context(attributes);
        assertNull(SolanaServerWallet.find(null));
        assertNull(SolanaServerWallet.find(context));
        SolanaServerWallet.register(context, wallet);
        SolanaServerWallet.register(context, wallet);
        assertEquals(wallet, SolanaServerWallet.find(context));
        assertThrows(IllegalStateException.class,
                () -> SolanaServerWallet.register(context, SolanaDevWallet.random(SolanaCluster.LOCALNET)));
    }

    @Test void connectResultSetsTheWalletNameWithoutADuplicateEvent() {
        SolanaConnect connect = new SolanaConnect(true);
        List<SolanaConnect.AccountChangedEvent> events = new java.util.ArrayList<>();
        connect.addAccountChangedListener(events::add);

        // 浏览器先派发账户变化事件，随后 connect() 的结果到达
        connect.applyAccount(wallet.address(), "Phantom");
        assertEquals(wallet.address(),
                connect.applyConnected("{\"address\":\"" + wallet.address() + "\",\"wallet\":\"Phantom\"}"));

        assertEquals(1, events.size());
        assertEquals("Phantom", connect.getWalletName());
        assertEquals("Phantom", events.getFirst().getWalletName());
        assertTrue(connect.isConnected());
        connect.applyAccount(null, null);
        assertNull(connect.getAccount());
        assertNull(events.getLast().getAccount());
    }

    private static com.vaadin.flow.server.VaadinContext context(java.util.Map<Class<?>, Object> attributes) {
        return (com.vaadin.flow.server.VaadinContext) java.lang.reflect.Proxy.newProxyInstance(
                SolanaConnectTest.class.getClassLoader(), new Class<?>[] {com.vaadin.flow.server.VaadinContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAttribute" -> { attributes.put((Class<?>) args[0], args[1]); yield null; }
                    case "getAttribute" -> attributes.get(args[0]);
                    default -> null;
                });
    }

    private static com.vaadin.flow.server.startup.ApplicationConfiguration configuration(boolean production) {
        return (com.vaadin.flow.server.startup.ApplicationConfiguration) java.lang.reflect.Proxy.newProxyInstance(
                SolanaConnectTest.class.getClassLoader(),
                new Class<?>[] {com.vaadin.flow.server.startup.ApplicationConfiguration.class},
                (proxy, method, args) -> "isProductionMode".equals(method.getName()) ? production : null);
    }

    @Test void clusterSetsTheWalletStandardChain() {
        SolanaConnect connect = new SolanaConnect(true);
        assertNull(connect.getCluster());
        assertEquals("solana:devnet", connect.setCluster(SolanaCluster.DEVNET).getElement().getProperty("chain"));
        assertEquals(SolanaCluster.DEVNET, connect.getCluster());
        assertEquals("", connect.setCluster(null).getElement().getProperty("chain"));
        assertTrue(connect.getElement().getProperty("hideButton", false));
        assertFalse(connect.isConnected());
    }

    private static void assertCode(int code, SolanaServerWallet wallet, String method, String payload) {
        assertEquals(code, assertThrows(SolanaConnect.SolanaWalletException.class,
                () -> SolanaConnect.handleServerWalletRequest(wallet, method, payload)).getCode(), method + " " + payload);
    }
}
