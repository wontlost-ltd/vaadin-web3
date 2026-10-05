package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.screening.AddressScreening;

/** 验证登录链路中 session ID 轮换的时机：通过校验与筛查之后、写入 Web3Session 之前，且只在成功时发生。 */
class SiweSessionRotationTest {

    private static final VerifiedSignIn VERIFIED = new VerifiedSignIn(
            "0x0000000000000000000000000000000000000001", 1, null, Instant.now());

    /** 每次 changeSessionId 被调用时记录当时 Web3Session 是否已写入。 */
    private final List<Boolean> rotations = new ArrayList<>();
    /** CurrentInstance 只持有弱引用：测试里必须强引用会话，否则 GC 后 VaadinSession.getCurrent() 变为 null。 */
    private VaadinSession session;

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
    }

    @AfterEach
    void clearSession() {
        VaadinSession.setCurrent(null);
        session = null;
    }

    @Test
    void successfulSignInRotatesSessionIdOnceBeforeStoringIdentity() throws Exception {
        SiweLogin login = login(null);
        AtomicReference<SiweLogin.SignedInEvent> signedIn = new AtomicReference<>();
        login.addSignedInListener(signedIn::set);

        complete(login);

        assertEquals(List.of(false), rotations, "rotated exactly once, before the identity was stored");
        assertEquals(Optional.of(VERIFIED), Web3Session.current());
        assertTrue(signedIn.get() != null);
    }

    @Test
    void blockedAddressDoesNotRotateSessionId() throws Exception {
        SiweLogin login = login(address -> AddressScreening.ScreeningDecision.block("policy"));

        complete(login);

        assertTrue(rotations.isEmpty());
        assertEquals(Optional.empty(), Web3Session.current());
    }

    @Test
    void disabledRotationStillSignsIn() throws Exception {
        SiweLogin login = login(null).setSessionIdRotation(false);

        complete(login);

        assertTrue(rotations.isEmpty());
        assertEquals(Optional.of(VERIFIED), Web3Session.current());
    }

    @Test
    void missingServletRequestStillSignsInWithoutRotation() throws Exception {
        SiweLogin login = login(null);
        login.setRequestLookup(() -> null);

        complete(login);

        assertTrue(rotations.isEmpty());
        assertEquals(Optional.of(VERIFIED), Web3Session.current());
        assertFalse(Web3Session.current().isEmpty());
    }

    private SiweLogin login(AddressScreening screening) throws Exception {
        VaadinContext context = context();
        if (screening != null) AddressScreening.register(context, screening);
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        login.setContextLookup(() -> context);
        login.setRequestLookup(() -> new VaadinServletRequest(servletRequest(), null));
        Field flow = SiweLogin.class.getDeclaredField("flowInProgress");
        flow.setAccessible(true);
        flow.setBoolean(login, true);
        return login;
    }

    private static void complete(SiweLogin login) throws Exception {
        Method complete = SiweLogin.class.getDeclaredMethod("completeVerifiedSignIn", VerifiedSignIn.class);
        complete.setAccessible(true);
        complete.invoke(login, VERIFIED);
    }

    private jakarta.servlet.http.HttpServletRequest servletRequest() {
        return (jakarta.servlet.http.HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { jakarta.servlet.http.HttpServletRequest.class }, (proxy, method, args) -> {
                    if ("changeSessionId".equals(method.getName())) {
                        rotations.add(Web3Session.current().isPresent());
                        return "rotated-id";
                    }
                    return null;
                });
    }

    private static VaadinContext context() {
        Map<Class<?>, Object> attributes = new HashMap<>();
        return (VaadinContext) Proxy.newProxyInstance(VaadinContext.class.getClassLoader(),
                new Class<?>[] { VaadinContext.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> {
                        Object value = attributes.get((Class<?>) args[0]);
                        if (value == null && args.length > 1 && args[1] != null) {
                            value = ((java.util.function.Supplier<?>) args[1]).get();
                            if (value != null) attributes.put((Class<?>) args[0], value);
                        }
                        yield value;
                    }
                    case "setAttribute" -> {
                        if (args.length == 2) attributes.put((Class<?>) args[0], args[1]);
                        else attributes.put(args[0].getClass(), args[0]);
                        yield null;
                    }
                    case "removeAttribute" -> attributes.remove((Class<?>) args[0]);
                    default -> null;
                });
    }
}
