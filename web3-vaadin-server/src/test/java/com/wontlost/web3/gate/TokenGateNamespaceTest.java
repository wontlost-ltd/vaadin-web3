package com.wontlost.web3.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.identity.ChainAccount;
import com.wontlost.web3.identity.Web3Identity;
import com.wontlost.web3.siwe.Web3Session;

class TokenGateNamespaceTest {
    // 与 Web3Session 内部会话键一致；测试直接写入一个非 EVM 身份，模拟后续 Solana 登录
    private static final String SESSION_KEY = Web3Session.class.getName() + ".verifiedSignIn";

    /** CurrentInstance 只持有弱引用：必须强引用会话，否则 GC 后 VaadinSession.getCurrent() 变为 null。 */
    private VaadinSession session;

    private record SolanaIdentity(ChainAccount account, Instant verifiedAt) implements Web3Identity { }

    @BeforeEach
    void currentSession() {
        session = new VaadinSession(null) {
            private final Map<String, Object> attributes = new HashMap<>();

            @Override
            public boolean hasLock() {
                return true;
            }

            @Override
            public void setAttribute(String name, Object value) {
                attributes.put(name, value);
            }

            @Override
            public Object getAttribute(String name) {
                return attributes.get(name);
            }
        };
        VaadinSession.setCurrent(session);
        session.setAttribute(SESSION_KEY, new SolanaIdentity(new ChainAccount("solana",
                "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", "7S3P4HxJpyyigGzodYwHtCxZyUQe9JiBMHyRWXArAaKv"),
                Instant.parse("2026-01-01T00:00:00Z")));
    }

    @AfterEach
    void clearSession() {
        VaadinSession.setCurrent(null);
        session = null;
    }

    @Test
    void evmOnlyAccessorTreatsANonEvmSessionAsSignedOut() {
        assertTrue(Web3Session.currentIdentity().isPresent());
        assertFalse(Web3Session.current().isPresent());
    }

    @Test
    void nonEvmSessionIsDeniedDeterministicallyWithoutAnyRpc() {
        // 未注册任何 RPC：若缺少命名空间校验，会走余额查询并以“暂不可用”结束，而不是确定性拒绝
        TokenGate gate = new TokenGate(new ChainRegistry());
        TestBeforeEnterEvent event = event();

        gate.beforeEnter(event);

        assertEquals(TokenGateDeniedException.class, event.errorType);
        assertTrue(event.errorMessage.startsWith("an EVM wallet holding at least 1 "));
        assertNull(event.forwardedRoute);
    }

    @RequiresToken(chainId = 1, token = "USDC", minBalance = "1")
    private static final class GatedView extends VerticalLayout { }

    private static TestBeforeEnterEvent event() {
        com.vaadin.flow.server.RouteRegistry registry = (com.vaadin.flow.server.RouteRegistry) Proxy.newProxyInstance(
                TokenGateNamespaceTest.class.getClassLoader(), new Class<?>[] { com.vaadin.flow.server.RouteRegistry.class },
                (proxy, method, args) -> null);
        return new TestBeforeEnterEvent(new com.vaadin.flow.router.Router(registry),
                new Location("holders", QueryParameters.empty()));
    }

    private static final class TestBeforeEnterEvent extends BeforeEnterEvent {
        private String forwardedRoute;
        private Class<? extends Exception> errorType;
        private String errorMessage;

        TestBeforeEnterEvent(com.vaadin.flow.router.Router router, Location location) {
            super(router, com.vaadin.flow.router.NavigationTrigger.UI_NAVIGATE, location, GatedView.class,
                    new UI(), List.of());
        }

        @Override public void forwardTo(String route, QueryParameters parameters) {
            forwardedRoute = route;
        }

        @Override public void rerouteToError(Class<? extends Exception> exception, String customMessage) {
            errorType = exception;
            errorMessage = customMessage;
        }
    }
}
