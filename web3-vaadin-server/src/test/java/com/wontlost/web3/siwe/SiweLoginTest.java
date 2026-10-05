package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import com.vaadin.flow.server.VaadinContext;
import org.junit.jupiter.api.Test;

class SiweLoginTest {

    @Test void siweFailureEventsExposeLocalizedReasonAndWalletMessages() {
        SiweLogin login = new SiweLogin(new InMemoryNonceStore()).setI18n(new SiweLoginI18n()
                .setMessage(SiweException.Reason.EXPIRED, "Signature expired")
                .setUserRejected("Rejected locally").setNetworkError("Wallet offline"));
        assertEquals("Signature expired", new SiweLogin.SignInFailedEvent(login,
                SiweException.Reason.EXPIRED, -1, false).getLocalizedMessage());
        assertEquals("Rejected locally", new SiweLogin.SignInFailedEvent(login,
                null, 4001, true).getLocalizedMessage());
        assertEquals("Wallet offline", new SiweLogin.SignInFailedEvent(login,
                null, -1, false).getLocalizedMessage());
    }

    @Test void screeningRejectsBeforeSessionCanBeWrittenAndFailsClosedOnExceptions() {
        VerifiedSignIn verified = new VerifiedSignIn("0x0000000000000000000000000000000000000001", 1, null,
                java.time.Instant.now());
        SiweException blocked = assertThrows(SiweException.class, () -> SiweLogin.screenVerifiedAddress(verified,
                address -> com.wontlost.web3.screening.AddressScreening.ScreeningDecision.block("blocked")));
        assertEquals(SiweException.Reason.ADDRESS_BLOCKED, blocked.getReason());
        SiweException unavailable = assertThrows(SiweException.class, () -> SiweLogin.screenVerifiedAddress(verified,
                address -> { throw new IllegalStateException("offline"); }));
        assertEquals(SiweException.Reason.SCREENING_UNAVAILABLE, unavailable.getReason());
        SiweLogin.screenVerifiedAddress(verified, null);
        assertEquals(java.util.Optional.empty(), Web3Session.current());
    }

    @Test void blockedAndUnavailableScreeningFireFailedEventBeforeSessionWrite() throws Exception {
        VerifiedSignIn verified = new VerifiedSignIn("0x0000000000000000000000000000000000000001", 1, null,
                java.time.Instant.now());
        record Scenario(com.wontlost.web3.screening.AddressScreening screening, SiweException.Reason reason) { }
        var scenarios = java.util.List.of(
                new Scenario(address -> com.wontlost.web3.screening.AddressScreening.ScreeningDecision.block("policy"),
                        SiweException.Reason.ADDRESS_BLOCKED),
                new Scenario(address -> { throw new IllegalStateException("oracle offline"); },
                        SiweException.Reason.SCREENING_UNAVAILABLE));
        for (Scenario scenario : scenarios) {
            VaadinContext context = context(new HashMap<>());
            com.wontlost.web3.screening.AddressScreening.register(context, scenario.screening());
            SiweLogin login = new SiweLogin(new InMemoryNonceStore());
            login.setContextLookup(() -> context);
            setField(login, "flowInProgress", true);
            java.util.concurrent.atomic.AtomicReference<SiweLogin.SignInFailedEvent> failed = new java.util.concurrent.atomic.AtomicReference<>();
            login.addSignInFailedListener(failed::set);
            var complete = SiweLogin.class.getDeclaredMethod("completeVerifiedSignIn", VerifiedSignIn.class);
            complete.setAccessible(true);
            complete.invoke(login, verified);
            assertEquals(scenario.reason(), failed.get().getReason());
            assertEquals(java.util.Optional.empty(), Web3Session.current());
        }
    }

    @Test void addressScreeningRegistrationIsIdempotentButRejectsReplacement() {
        Map<Class<?>, Object> attributes = new HashMap<>();
        VaadinContext context = context(attributes);
        var screening = (com.wontlost.web3.screening.AddressScreening)
                address -> com.wontlost.web3.screening.AddressScreening.ScreeningDecision.allow();
        com.wontlost.web3.screening.AddressScreening.register(context, screening);
        com.wontlost.web3.screening.AddressScreening.register(context, screening);
        assertEquals(screening, com.wontlost.web3.screening.AddressScreening.find(context));
        assertThrows(IllegalStateException.class, () -> com.wontlost.web3.screening.AddressScreening.register(context,
                address -> com.wontlost.web3.screening.AddressScreening.ScreeningDecision.allow()));
    }

    @Test
    void acceptsOnlyApplicationRelativeContinueTargets() {
        assertEquals(true, SiweLogin.isSafeContinueTarget("/holders?tab=owned"));
        assertEquals(false, SiweLogin.isSafeContinueTarget("https://evil"));
        assertEquals(false, SiweLogin.isSafeContinueTarget("//evil"));
        assertEquals(false, SiweLogin.isSafeContinueTarget("\\\\evil"));
    }

    @Test
    void signOutCanSkipWalletDisconnectAndFiresEvent() throws Exception {
        SiweLogin login = new SiweLogin(new InMemoryNonceStore(java.time.Duration.ofMinutes(5)))
                .setDisconnectWalletOnSignOut(false);
        boolean[] fired = { false };
        login.addSignedOutListener(event -> fired[0] = true);

        login.signOut();

        assertEquals(true, fired[0]);
        Field field = SiweLogin.class.getDeclaredField("disconnectWalletOnSignOut");
        field.setAccessible(true);
        assertEquals(false, field.get(login));
    }

    @Test
    void derivesDomainAndUriFromRequestValues() {
        assertEquals("example.com:8443", SiweLogin.deriveDomain("example.com:8443"));
        assertEquals("https://example.com:8443", SiweLogin.deriveUri("https", "example.com:8443"));
    }

    @Test
    void rejectsMissingRequestValues() {
        assertThrows(IllegalStateException.class, () -> SiweLogin.deriveDomain(" "));
        assertThrows(IllegalStateException.class, () -> SiweLogin.deriveUri(null, "example.com"));
    }

    @Test
    void prefersForwardedThenXForwardedThenRequestValues() {
        assertEquals("public.example.com:8443", SiweLogin.deriveDomain(
                "for=192.0.2.1;host=public.example.com:8443;proto=https, for=192.0.2.2;host=ignored",
                "proxy.example.com, internal.example.com", "internal.example.com"));
        assertEquals("https://public.example.com:8443", SiweLogin.deriveUri(
                "for=192.0.2.1;host=public.example.com:8443;proto=https, proto=http",
                "http, https", "proxy.example.com, internal.example.com", "internal.example.com", "http"));
        assertEquals("proxy.example.com:9443", SiweLogin.deriveDomain(null,
                "proxy.example.com:9443, internal.example.com", "internal.example.com"));
        assertEquals("https://proxy.example.com:9443", SiweLogin.deriveUri(null,
                "https, http", "proxy.example.com:9443, internal.example.com", "internal.example.com", "http"));
        assertEquals("http://internal.example.com:8080", SiweLogin.deriveUri(null, null, null,
                "internal.example.com:8080", "http"));
    }

    @Test
    void restoresTransientNonceStoreAndVerifierFromApplicationContext() throws Exception {
        Map<Class<?>, Object> attributes = new HashMap<>();
        InvocationHandler handler = (proxy, method, args) -> {
            if ("setAttribute".equals(method.getName())) {
                attributes.put((Class<?>) args[0], args[1]);
                return null;
            }
            if ("getAttribute".equals(method.getName())) {
                Object value = attributes.get(args[0]);
                return value == null && args.length == 2 ? ((java.util.function.Supplier<?>) args[1]).get() : value;
            }
            return null;
        };
        VaadinContext context = (VaadinContext) Proxy.newProxyInstance(VaadinContext.class.getClassLoader(),
                new Class<?>[] { VaadinContext.class }, handler);
        NonceStore store = new NonceStore() {
            @Override public String issue() { return "nonce12345678"; }
            @Override public boolean consume(String nonce) { return true; }
        };
        SiweLogin.registerNonceStore(context, store);
        SiweLogin login = new SiweLogin(store);
        setField(login, "nonces", null);
        setField(login, "customVerifier", null);
        com.wontlost.web3.chain.ChainRegistry chains = new com.wontlost.web3.chain.ChainRegistry();
        context.setAttribute(com.wontlost.web3.chain.ChainRegistry.class, chains);
        login.setContextLookup(() -> context);

        assertEquals(store, invoke(login, "requireNonces"));
        Object verifier = invoke(login, "requireVerifier");
        Field nonceField = SiweVerifier.class.getDeclaredField("nonces");
        nonceField.setAccessible(true);
        assertEquals(store, nonceField.get(verifier));
        // 应用注册的 ChainRegistry 自动用于合约钱包签名校验
        Field chainsField = SiweVerifier.class.getDeclaredField("chains");
        chainsField.setAccessible(true);
        assertEquals(chains, chainsField.get(verifier));
    }

    private static VaadinContext context(Map<Class<?>, Object> attributes) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("setAttribute".equals(method.getName())) { attributes.put((Class<?>) args[0], args[1]); return null; }
            if ("getAttribute".equals(method.getName())) {
                Object value = attributes.get(args[0]);
                return value == null && args.length == 2 ? ((java.util.function.Supplier<?>) args[1]).get() : value;
            }
            return null;
        };
        return (VaadinContext) Proxy.newProxyInstance(VaadinContext.class.getClassLoader(), new Class<?>[] { VaadinContext.class }, handler);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = SiweLogin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name) throws Exception {
        var method = SiweLogin.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
