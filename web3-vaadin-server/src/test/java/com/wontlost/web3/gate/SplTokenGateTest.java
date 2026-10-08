package com.wontlost.web3.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.identity.ChainAccount;
import com.wontlost.web3.identity.Web3Identity;
import com.wontlost.web3.siwe.Web3Session;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaClusters;
import com.wontlost.web3.solana.SolanaCommitment;
import com.wontlost.web3.solana.SolanaRpcClient;

import tools.jackson.databind.ObjectMapper;

class SplTokenGateTest {
    private static final String SESSION_KEY = Web3Session.class.getName() + ".verifiedSignIn";
    private static final String MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String HOLDER = "7S3P4HxJpyyigGzodYwHtCxZyUQe9JiBMHyRWXArAaKv";
    private static final ObjectMapper JSON = new ObjectMapper();

    private VaadinSession session;
    private final AtomicInteger rpcCalls = new AtomicInteger();
    /** 返回给 getTokenAccountsByOwner 的最小单位余额（6 位小数）；null 表示 RPC 失败。 */
    private volatile String heldAmount = "2500000";

    private record Identity(ChainAccount account, Instant verifiedAt) implements Web3Identity { }

    @RequiresSplToken(mint = MINT, minBalance = "2.5", cluster = SolanaCluster.DEVNET, symbol = "USDC")
    private static final class GatedView extends VerticalLayout { }

    @RequiresSplToken(mint = MINT, minBalance = "not a number", cluster = SolanaCluster.DEVNET)
    private static final class BrokenView extends VerticalLayout { }

    private static final class OpenView extends VerticalLayout { }

    @BeforeEach
    void currentSession() {
        session = new VaadinSession(null) {
            private final Map<String, Object> attributes = new HashMap<>();
            @Override public boolean hasLock() { return true; }
            @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
            @Override public Object getAttribute(String name) { return attributes.get(name); }
        };
        VaadinSession.setCurrent(session);
    }

    @AfterEach
    void clearSession() {
        VaadinSession.setCurrent(null);
        session = null;
    }

    @Test void holdersAtOrAboveTheMinimumEnter() {
        signIn("solana", SolanaCluster.DEVNET.reference());
        TestEvent event = event(GatedView.class);

        gate(Duration.ofMinutes(1)).beforeEnter(event);

        assertNull(event.errorType);
        assertNull(event.forwardedRoute);
        assertEquals(1, rpcCalls.get());
    }

    @Test void holdersBelowTheMinimumAreDenied() {
        heldAmount = "2499999";
        signIn("solana", SolanaCluster.DEVNET.reference());
        TestEvent event = event(GatedView.class);

        gate(Duration.ofMinutes(1)).beforeEnter(event);

        assertEquals(TokenGateDeniedException.class, event.errorType);
        assertEquals("at least 2.5 USDC", event.errorMessage);
    }

    @Test void visitorsWhoAreNotSignedInAreSentToSignIn() {
        TestEvent event = event(GatedView.class);

        gate(Duration.ofMinutes(1)).beforeEnter(event);

        assertEquals("login", event.forwardedRoute);
        assertEquals("holders", event.forwardedQuery.getSingleParameter("continue").orElseThrow());
        assertEquals(0, rpcCalls.get());
    }

    @Test void evmAccountsAndOtherClustersAreDeniedWithoutRpc() {
        signIn("eip155", "1");
        TestEvent evm = event(GatedView.class);
        gate(Duration.ofMinutes(1)).beforeEnter(evm);
        assertEquals(TokenGateDeniedException.class, evm.errorType);
        assertEquals("a Solana wallet on devnet holding at least 2.5 USDC", evm.errorMessage);

        // 其他命名空间即使引用字符串与 devnet 相同，也不是 Solana 账户
        signIn("polkadot", SolanaCluster.DEVNET.reference());
        TestEvent otherNamespace = event(GatedView.class);
        gate(Duration.ofMinutes(1)).beforeEnter(otherNamespace);
        assertEquals(TokenGateDeniedException.class, otherNamespace.errorType);

        signIn("solana", SolanaCluster.MAINNET.reference());
        TestEvent mainnet = event(GatedView.class);
        gate(Duration.ofMinutes(1)).beforeEnter(mainnet);
        assertEquals(TokenGateDeniedException.class, mainnet.errorType);
        assertEquals(0, rpcCalls.get());
    }

    @Test void unreadableBalancesFailClosedAsUnavailable() {
        signIn("solana", SolanaCluster.DEVNET.reference());
        TestEvent noClient = event(GatedView.class);
        new SplTokenGate(TokenGateServiceInitListener.solanaClusters(null)).beforeEnter(noClient);
        assertEquals(TokenGateUnavailableException.class, noClient.errorType);

        heldAmount = null;
        TestEvent rpcDown = event(GatedView.class);
        gate(Duration.ofMinutes(1)).beforeEnter(rpcDown);
        assertEquals(TokenGateUnavailableException.class, rpcDown.errorType);

        heldAmount = "1";
        TestEvent broken = event(BrokenView.class);
        gate(Duration.ofMinutes(1)).beforeEnter(broken);
        assertEquals(TokenGateUnavailableException.class, broken.errorType);
    }

    @Test void viewsWithoutTheAnnotationAreIgnored() {
        TestEvent event = event(OpenView.class);

        gate(Duration.ofMinutes(1)).beforeEnter(event);

        assertNull(event.errorType);
        assertNull(event.forwardedRoute);
    }

    @Test void balancesAreCachedForTheTtlAndBounded() {
        signIn("solana", SolanaCluster.DEVNET.reference());
        SplTokenGate cached = gate(Duration.ofMinutes(1));
        cached.beforeEnter(event(GatedView.class));
        heldAmount = "0";
        TestEvent second = event(GatedView.class);
        cached.beforeEnter(second);
        assertNull(second.errorType, "the cached balance is used within the TTL");
        assertEquals(1, rpcCalls.get());

        SplTokenGate uncached = gate(Duration.ZERO);
        uncached.beforeEnter(event(GatedView.class));
        TestEvent fresh = event(GatedView.class);
        uncached.beforeEnter(fresh);
        assertEquals(TokenGateDeniedException.class, fresh.errorType);
        assertEquals(3, rpcCalls.get());

        SplTokenGate bounded = new SplTokenGate(clusters(), Duration.ofMinutes(1), 1);
        bounded.evaluate(GatedView.class.getAnnotation(RequiresSplToken.class),
                new ChainAccount("solana", SolanaCluster.DEVNET.reference(), HOLDER));
        bounded.evaluate(GatedView.class.getAnnotation(RequiresSplToken.class),
                new ChainAccount("solana", SolanaCluster.DEVNET.reference(), "4xiDjfo4adkCzUmY94uXEvxqp9pdq89pEUY6UPXuptkp"));
        assertEquals(1, bounded.cachedBalanceCount());
        assertTrue(new SolanaClusters().get(SolanaCluster.DEVNET).isEmpty());
    }

    private SplTokenGate gate(Duration ttl) {
        return new SplTokenGate(clusters(), ttl, 100);
    }

    private SolanaClusters clusters() {
        JsonRpcTransport transport = request -> {
            rpcCalls.incrementAndGet();
            String amount = heldAmount;
            if (amount == null) throw new java.io.IOException("node down");
            long id = JSON.readTree(request).path("id").asLong();
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"context\":{\"slot\":5},\"value\":[{\"pubkey\":\""
                    + HOLDER + "\",\"account\":{\"data\":{\"parsed\":{\"info\":{\"mint\":\"" + MINT
                    + "\",\"tokenAmount\":{\"amount\":\"" + amount + "\",\"decimals\":6}}}}}}]}}";
        };
        return new SolanaClusters().register(SolanaCluster.DEVNET,
                new SolanaRpcClient(transport, SolanaCommitment.CONFIRMED));
    }

    private void signIn(String namespace, String reference) {
        String address = "eip155".equals(namespace) ? "0x0000000000000000000000000000000000000001" : HOLDER;
        session.setAttribute(SESSION_KEY, new Identity(new ChainAccount(namespace, reference, address),
                Instant.parse("2026-01-01T00:00:00Z")));
    }

    private static TestEvent event(Class<? extends com.vaadin.flow.component.Component> view) {
        com.vaadin.flow.server.RouteRegistry registry = (com.vaadin.flow.server.RouteRegistry) Proxy.newProxyInstance(
                SplTokenGateTest.class.getClassLoader(), new Class<?>[] {com.vaadin.flow.server.RouteRegistry.class},
                (proxy, method, args) -> null);
        return new TestEvent(new com.vaadin.flow.router.Router(registry), new Location("holders", QueryParameters.empty()),
                view);
    }

    private static final class TestEvent extends BeforeEnterEvent {
        private String forwardedRoute;
        private QueryParameters forwardedQuery;
        private Class<? extends Exception> errorType;
        private String errorMessage;

        TestEvent(com.vaadin.flow.router.Router router, Location location,
                Class<? extends com.vaadin.flow.component.Component> view) {
            super(router, com.vaadin.flow.router.NavigationTrigger.UI_NAVIGATE, location, view, new UI(), List.of());
        }

        @Override public void forwardTo(String route, QueryParameters parameters) {
            forwardedRoute = route;
            forwardedQuery = parameters;
        }

        @Override public void rerouteToError(Class<? extends Exception> exception, String customMessage) {
            errorType = exception;
            errorMessage = customMessage;
        }
    }
}
