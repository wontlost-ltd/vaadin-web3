package com.wontlost.web3.siwe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.autoconfigure.security.SiweSecurityBridge;
import com.wontlost.web3.autoconfigure.security.Web3Principal;

class SiweSecurityBridgeTest {
    @AfterEach
    void clearCurrentState() {
        com.vaadin.flow.internal.CurrentInstance.clearAll();
        VaadinService.setCurrent(null);
        VaadinSession.setCurrent(null);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void persistsVerifiedPrincipalAndClearsItOnSignOutWithoutRotatingSession() {
        CountingRequest servletRequest = new CountingRequest();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        VaadinServletService service = mock(VaadinServletService.class);
        VaadinServletRequest request = new VaadinServletRequest(servletRequest, service);
        VaadinServletResponse response = new VaadinServletResponse(servletResponse, service);
        doCallRealMethod().when(service).setCurrentInstances(request, response);
        doCallRealMethod().when(service).setCurrentInstances(request, null);
        service.setCurrentInstances(request, response);
        setCurrentSession(service);

        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        SiweSecurityBridge bridge = new SiweSecurityBridge(new HttpSessionSecurityContextRepository(),
                List.of("ROLE_WEB3_USER", "ROLE_MEMBER"));
        bridge.attach(login);
        fire(login, new SiweLogin.SignedInEvent(login,
                new VerifiedSignIn("0x0000000000000000000000000000000000000001", 31337, null, Instant.now())));

        Object value = servletRequest.getSession(false).getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(value).isInstanceOf(SecurityContext.class);
        SecurityContext saved = (SecurityContext) value;
        assertThat(saved.getAuthentication().getPrincipal()).isEqualTo(
                new Web3Principal("0x0000000000000000000000000000000000000001", 31337));
        assertThat(saved.getAuthentication().getAuthorities()).extracting("authority")
                .containsExactly("ROLE_WEB3_USER", "ROLE_MEMBER");
        assertThat(servletRequest.rotationCount).isZero();

        service.setCurrentInstances(request, null);
        login.signOut();
        Object afterLogout = servletRequest.getSession(false)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(afterLogout).isNull();
    }

    @Test
    void rollsBackWeb3SessionWhenServletResponseIsUnavailable() {
        VaadinServletService service = mock(VaadinServletService.class);
        setCurrentSession(service);
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        SiweSecurityBridge bridge = new SiweSecurityBridge(new HttpSessionSecurityContextRepository(),
                List.of("ROLE_WEB3_USER"));
        bridge.attach(login);
        VerifiedSignIn identity = new VerifiedSignIn("0x0000000000000000000000000000000000000002", 31337, null, Instant.now());
        Web3Session.store(identity);
        VaadinService.setCurrent(service);

        assertThatThrownBy(() -> fire(login, new SiweLogin.SignedInEvent(login, identity)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("current servlet request and response");
        assertThat(Web3Session.current()).isEmpty();
    }

    private static final class CountingRequest extends MockHttpServletRequest {
        private int rotationCount;
        @Override public String changeSessionId() {
            rotationCount++;
            return super.changeSessionId();
        }
    }

    private static void setCurrentSession(VaadinService service) {
        VaadinSession session = mock(VaadinSession.class);
        java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        when(session.getService()).thenReturn(service);
        when(session.getAttribute(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(session).setAttribute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(Object.class));
        VaadinSession.setCurrent(session);
    }

    private static void fire(SiweLogin login, com.vaadin.flow.component.ComponentEvent<?> event) {
        try {
            var fireEvent = com.vaadin.flow.component.Component.class
                    .getDeclaredMethod("fireEvent", com.vaadin.flow.component.ComponentEvent.class);
            fireEvent.setAccessible(true);
            fireEvent.invoke(login, event);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
