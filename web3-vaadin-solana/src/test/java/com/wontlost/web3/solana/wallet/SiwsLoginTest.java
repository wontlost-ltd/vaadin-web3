package com.wontlost.web3.solana.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.screening.AddressScreening;
import com.wontlost.web3.siwe.Web3Session;
import com.wontlost.web3.siws.InMemorySiwsChallengeStore;
import com.wontlost.web3.siws.SiwsChallenge;
import com.wontlost.web3.siws.SiwsVerifier;
import com.wontlost.web3.siws.SolanaCluster;

class SiwsLoginTest {
    private final Clock clock = Clock.systemUTC();
    private final SolanaDevWallet signer = SolanaDevWallet.random(SolanaCluster.DEVNET);
    private final Map<Class<?>, Object> attributes = new HashMap<>();
    /** CurrentInstance 只持有弱引用：测试中必须强引用会话。 */
    private VaadinSession session;

    @BeforeEach
    void currentSession() {
        session = new VaadinSession(null) {
            private final Map<String, Object> values = new HashMap<>();
            @Override public boolean hasLock() { return true; }
            @Override public void setAttribute(String name, Object value) { values.put(name, value); }
            @Override public Object getAttribute(String name) { return values.get(name); }
        };
        VaadinSession.setCurrent(session);
    }

    @AfterEach
    void clearSession() {
        VaadinSession.setCurrent(null);
        session = null;
    }

    @Test void successfulSignInVerifiesTheWalletSignatureAndStoresTheIdentity() {
        FakeWallet wallet = new FakeWallet(signer.address(), this::signHonestly);
        SiwsLogin login = login(wallet);
        List<SiwsLogin.SignedInEvent> signedIn = new ArrayList<>();
        login.addSignedInListener(signedIn::add);

        login.beginSignIn();

        assertEquals(1, signedIn.size());
        assertEquals(signer.address(), signedIn.getFirst().getSignIn().address());
        assertEquals(signer.address(), Web3Session.currentIdentity().orElseThrow().account().address());
        assertTrue(Web3Session.current().isEmpty(), "a Solana identity is not an EVM session");
        SiwsChallenge challenge = wallet.challenges.getFirst();
        assertEquals("app.example.com", challenge.domain());
        assertEquals("https://app.example.com", challenge.uri());
        assertEquals("Sign in to the demo", challenge.statement());
        assertEquals(SolanaCluster.DEVNET, challenge.cluster());
    }

    @Test void walletRejectionIsReportedWithoutAnIdentity() {
        SiwsLogin login = login(new FakeWallet(signer.address(), challenge -> CompletableFuture.failedFuture(
                new SolanaConnect.SolanaWalletException(4001, "User rejected the request", true))));
        List<SiwsLogin.SignInFailedEvent> failed = new ArrayList<>();
        login.addSignInFailedListener(failed::add);

        login.beginSignIn();

        assertEquals(1, failed.size());
        assertNull(failed.getFirst().getCode());
        assertTrue(failed.getFirst().isUserRejected());
        assertEquals(4001, failed.getFirst().getWalletErrorCode());
        assertEquals(login.getI18n().getUserRejected(), failed.getFirst().getLocalizedMessage());
        assertTrue(Web3Session.currentIdentity().isEmpty());
    }

    @Test void tamperedOrForeignSignaturesAreRejected() {
        SolanaDevWallet other = SolanaDevWallet.random(SolanaCluster.DEVNET);
        assertFailure("siws_invalid_signature", challenge -> {
            byte[] message = message(challenge);
            byte[] signature = signer.signMessage(message);
            signature[0] ^= 1;
            return CompletableFuture.completedFuture(new SolanaConnect.SignedSignIn(signer.address(), message, signature));
        });
        assertFailure("siws_message_mismatch", challenge -> {
            byte[] message = message(challenge);
            return CompletableFuture.completedFuture(new SolanaConnect.SignedSignIn(other.address(), message,
                    other.signMessage(message)));
        });
        assertFailure("siws_message_mismatch", challenge -> {
            byte[] message = message(challenge).clone();
            message[0] = 'x';
            return CompletableFuture.completedFuture(new SolanaConnect.SignedSignIn(signer.address(), message,
                    signer.signMessage(message)));
        });
    }

    @Test void screeningCanBlockOrBeUnavailable() {
        attributes.put(AddressScreening.class, (AddressScreening) address -> AddressScreening.ScreeningDecision.block("sanctioned"));
        SiwsLogin.SignInFailedEvent blocked = assertFailure(SiwsLogin.ADDRESS_BLOCKED, this::signHonestly);
        assertEquals("This address is not allowed to sign in.", blocked.getLocalizedMessage());

        attributes.put(AddressScreening.class, (AddressScreening) address -> {
            throw new IllegalStateException("screening down");
        });
        assertFailure(SiwsLogin.SCREENING_UNAVAILABLE, this::signHonestly);

        List<String> screened = new ArrayList<>();
        attributes.put(AddressScreening.class, (AddressScreening) address -> {
            screened.add(address);
            return AddressScreening.ScreeningDecision.allow();
        });
        login(new FakeWallet(signer.address(), this::signHonestly)).beginSignIn();
        assertEquals(List.of(signer.address()), screened);
        assertTrue(Web3Session.currentIdentity().isPresent());
    }

    @Test void missingOriginAndServerErrorsHaveTheirOwnCodes() {
        SiwsLogin noOrigin = new SiwsLogin(verifier(), SolanaCluster.DEVNET, new FakeWallet(signer.address(), this::signHonestly));
        noOrigin.setContextLookup(() -> context(attributes));
        noOrigin.setRequestLookup(() -> null);
        List<SiwsLogin.SignInFailedEvent> failed = new ArrayList<>();
        noOrigin.addSignInFailedListener(failed::add);
        noOrigin.beginSignIn();
        assertEquals(SiwsLogin.ORIGIN_UNAVAILABLE, failed.getFirst().getCode());

        SiwsVerifier full = new SiwsVerifier(new InMemorySiwsChallengeStore(1, clock), clock);
        full.issue("app.example.com", "https://app.example.com", null, SolanaCluster.DEVNET, SiwsLogin.CHALLENGE_TTL, List.of());
        SiwsLogin login = new SiwsLogin(full, SolanaCluster.DEVNET, new FakeWallet(signer.address(), this::signHonestly))
                .setDomain("app.example.com").setUri("https://app.example.com");
        login.setContextLookup(() -> context(attributes));
        List<SiwsLogin.SignInFailedEvent> internal = new ArrayList<>();
        login.addSignInFailedListener(internal::add);
        login.beginSignIn();
        assertEquals(SiwsLogin.INTERNAL_ERROR, internal.getFirst().getCode());
        assertEquals(login.getI18n().getVerificationFailed(), internal.getFirst().getLocalizedMessage());
    }

    @Test void aSecondClickWhileSigningIsIgnored() {
        CompletableFuture<SolanaConnect.SignedSignIn> pending = new CompletableFuture<>();
        FakeWallet wallet = new FakeWallet(signer.address(), challenge -> pending);
        SiwsLogin login = login(wallet);

        login.beginSignIn();
        login.beginSignIn();

        assertEquals(1, wallet.challenges.size());
        pending.complete(signHonestly(wallet.challenges.getFirst()).join());
        login.beginSignIn();
        assertEquals(2, wallet.challenges.size(), "the flow can start again after it finished");
    }

    @Test void connectsFirstWhenNoAccountIsConnected() {
        FakeWallet wallet = new FakeWallet(signer.address(), this::signHonestly);
        wallet.connected = false;
        SiwsLogin login = login(wallet);

        login.beginSignIn();

        assertEquals(1, wallet.connects);
        assertTrue(Web3Session.currentIdentity().isPresent());
    }

    @Test void signOutClearsTheIdentityAndDisconnectsByDefault() {
        FakeWallet wallet = new FakeWallet(signer.address(), this::signHonestly);
        SiwsLogin login = login(wallet);
        login.beginSignIn();
        List<SiwsLogin.SignedOutEvent> signedOut = new ArrayList<>();
        login.addSignedOutListener(signedOut::add);

        login.signOut();

        assertTrue(Web3Session.currentIdentity().isEmpty());
        assertEquals(1, wallet.disconnects);
        assertEquals(1, signedOut.size());

        login.setDisconnectWalletOnSignOut(false).signOut();
        assertEquals(1, wallet.disconnects);
    }

    @Test void signInWorksAfterTheComponentIsDeserialized() throws Exception {
        SiwsLogin original = new SiwsLogin(verifier(), SolanaCluster.DEVNET, new FakeWallet(signer.address(), this::signHonestly))
                .setDomain("app.example.com").setUri("https://app.example.com");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        SiwsLogin restored;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (SiwsLogin) in.readObject();
        }
        ((FakeWallet) restored.getWallet()).signing = this::signHonestly;
        List<SiwsLogin.SignInFailedEvent> failed = new ArrayList<>();
        restored.addSignInFailedListener(failed::add);

        // 反序列化后没有可用的应用校验器：流程以 INTERNAL_ERROR 结束，而不是卡住或空指针
        restored.beginSignIn();
        assertEquals(SiwsLogin.INTERNAL_ERROR, failed.getFirst().getCode());

        // README 推荐的路径：启动时注册到上下文，恢复后的组件从上下文取回校验器
        SiwsVerifier registered = verifier();
        Map<Class<?>, Object> restoredAttributes = new HashMap<>();
        SiwsLogin.registerVerifier(context(restoredAttributes), registered);
        restored.setContextLookup(() -> context(restoredAttributes));
        List<SiwsLogin.SignedInEvent> fromContext = new ArrayList<>();
        restored.addSignedInListener(fromContext::add);
        restored.beginSignIn();
        assertEquals(1, fromContext.size(), "the verifier registered in the context is found after restore");
        Web3Session.signOut();

        SiwsLogin reattached = deserialize(serialize(original));
        ((FakeWallet) reattached.getWallet()).signing = this::signHonestly;
        reattached.setVerifier(verifier());
        restored = reattached;
        List<SiwsLogin.SignedInEvent> signedIn = new ArrayList<>();
        restored.addSignedInListener(signedIn::add);
        restored.beginSignIn();
        assertEquals(1, signedIn.size(), "the documented setVerifier reattach path signs in");
        assertEquals(signer.address(), Web3Session.currentIdentity().orElseThrow().account().address());
    }

    @Test void rotatesTheSessionIdOnceAfterVerificationBeforeStoringTheIdentity() {
        List<Boolean> rotations = new ArrayList<>();
        jakarta.servlet.http.HttpServletRequest http = (jakarta.servlet.http.HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {jakarta.servlet.http.HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("changeSessionId".equals(method.getName())) {
                        rotations.add(Web3Session.currentIdentity().isPresent());
                        return "rotated";
                    }
                    return null;
                });
        SiwsLogin login = login(new FakeWallet(signer.address(), this::signHonestly));
        login.setRequestLookup(() -> new com.vaadin.flow.server.VaadinServletRequest(http, null));

        login.beginSignIn();

        assertEquals(List.of(false), rotations, "rotated exactly once, before the identity was stored");
        assertTrue(Web3Session.currentIdentity().isPresent());

        rotations.clear();
        Web3Session.signOut();
        SiwsLogin noRotation = login(new FakeWallet(signer.address(), this::signHonestly)).setSessionIdRotation(false);
        noRotation.setRequestLookup(() -> new com.vaadin.flow.server.VaadinServletRequest(http, null));
        noRotation.beginSignIn();
        assertEquals(List.of(), rotations);

        Web3Session.signOut();
        assertFailure("siws_invalid_signature", challenge -> {
            byte[] message = message(challenge);
            return CompletableFuture.completedFuture(new SolanaConnect.SignedSignIn(signer.address(), message, new byte[64]));
        });
        assertEquals(List.of(), rotations, "failed sign-ins never rotate");
    }

    @Test void unexpectedErrorsAfterVerificationEndTheFlow() {
        VaadinSession.setCurrent(null);
        SiwsLogin login = login(new FakeWallet(signer.address(), this::signHonestly));
        List<SiwsLogin.SignInFailedEvent> failed = new ArrayList<>();
        login.addSignInFailedListener(failed::add);

        login.beginSignIn();

        assertEquals(SiwsLogin.INTERNAL_ERROR, failed.getFirst().getCode());
        login.beginSignIn();
        assertEquals(2, failed.size(), "the button is usable again");
    }

    @Test void i18nMessagesAndButton() {
        SiwsLogin login = login(new FakeWallet(signer.address(), this::signHonestly));
        login.setI18n(new SiwsLoginI18n().setButton("Mit Solana anmelden").setMessage("siws_expired", "Abgelaufen"));

        assertEquals("Mit Solana anmelden", login.getI18n().getButton());
        assertEquals("Abgelaufen", login.getI18n().getMessage("siws_expired"));
        assertEquals(login.getI18n().getVerificationFailed(), login.getI18n().getMessage("siws_nonce_reused"));
        assertEquals(login.getI18n().getWalletError(),
                new SiwsLogin.SignInFailedEvent(login, null, -1, false).getLocalizedMessage());
    }

    private static byte[] serialize(Object value) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static SiwsLogin deserialize(byte[] bytes) throws Exception {
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
            return (SiwsLogin) in.readObject();
        }
    }

    private SiwsLogin.SignInFailedEvent assertFailure(String code,
            Function<SiwsChallenge, CompletableFuture<SolanaConnect.SignedSignIn>> signing) {
        Web3Session.signOut();
        SiwsLogin login = login(new FakeWallet(signer.address(), signing));
        List<SiwsLogin.SignInFailedEvent> failed = new ArrayList<>();
        login.addSignInFailedListener(failed::add);
        login.beginSignIn();
        assertEquals(1, failed.size(), code);
        assertEquals(code, failed.getFirst().getCode());
        assertFalse(failed.getFirst().isUserRejected());
        assertTrue(Web3Session.currentIdentity().isEmpty(), code);
        return failed.getFirst();
    }

    private SiwsLogin login(FakeWallet wallet) {
        SiwsLogin login = new SiwsLogin(verifier(), SolanaCluster.DEVNET, wallet)
                .setDomain("app.example.com").setUri("https://app.example.com").setStatement("Sign in to the demo");
        login.setContextLookup(() -> context(attributes));
        login.setRequestLookup(() -> null);
        return login;
    }

    private SiwsVerifier verifier() {
        return new SiwsVerifier(new InMemorySiwsChallengeStore(clock), clock);
    }

    private CompletableFuture<SolanaConnect.SignedSignIn> signHonestly(SiwsChallenge challenge) {
        byte[] message = message(challenge);
        return CompletableFuture.completedFuture(
                new SolanaConnect.SignedSignIn(signer.address(), message, signer.signMessage(message)));
    }

    private byte[] message(SiwsChallenge challenge) {
        return challenge.toMessage(signer.address()).toMessage().getBytes(StandardCharsets.UTF_8);
    }

    private static VaadinContext context(Map<Class<?>, Object> attributes) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("setAttribute".equals(method.getName())) { attributes.put((Class<?>) args[0], args[1]); return null; }
            if ("getAttribute".equals(method.getName())) return attributes.get(args[0]);
            return null;
        };
        return (VaadinContext) Proxy.newProxyInstance(VaadinContext.class.getClassLoader(),
                new Class<?>[] {VaadinContext.class}, handler);
    }

    /** 不经浏览器的钱包替身：记录收到的挑战，按给定策略签名；可序列化以测试会话恢复。 */
    private static final class FakeWallet extends SolanaConnect {
        private transient Function<SiwsChallenge, CompletableFuture<SignedSignIn>> signing;
        private final String address;
        // 序列化时为空列表；SiwsChallenge 本身不需要可序列化
        private final ArrayList<SiwsChallenge> challenges = new ArrayList<>();
        private boolean connected = true;
        private int connects;
        private int disconnects;

        private FakeWallet(String address, Function<SiwsChallenge, CompletableFuture<SignedSignIn>> signing) {
            super(true);
            this.address = address;
            this.signing = signing;
        }

        @Override public boolean isConnected() { return connected; }
        @Override public String getAccount() { return connected ? address : null; }

        @Override
        public CompletableFuture<String> connect() {
            connects++;
            connected = true;
            return CompletableFuture.completedFuture(address);
        }

        @Override
        public void disconnect() {
            disconnects++;
            connected = false;
        }

        @Override
        public CompletableFuture<SignedSignIn> signIn(SiwsChallenge challenge, String address) {
            challenges.add(challenge);
            return signing.apply(challenge);
        }
    }
}
