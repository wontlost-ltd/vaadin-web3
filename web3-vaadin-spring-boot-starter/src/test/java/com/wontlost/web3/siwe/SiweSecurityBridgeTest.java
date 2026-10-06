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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.autoconfigure.security.SiweSecurityBridge;
import com.wontlost.web3.autoconfigure.security.Web3Principal;
import com.wontlost.web3.autoconfigure.security.SiweAuthoritiesResolver;

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
    void combinesAuthoritiesInResolverOrderAndPublishesAuthenticationEvents() {
        CountingRequest servletRequest = new CountingRequest();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        VaadinServletService service = mock(VaadinServletService.class);
        VaadinServletRequest request = new VaadinServletRequest(servletRequest, service);
        VaadinServletResponse response = new VaadinServletResponse(servletResponse, service);
        doCallRealMethod().when(service).setCurrentInstances(request, response);
        service.setCurrentInstances(request, response);
        setCurrentSession(service);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SiweAuthoritiesResolver first = context -> {
            assertThat(context.request()).isInstanceOf(VaadinServletRequest.class);
            assertThat(context.request().getSession(false)).isSameAs(servletRequest.getSession(false));
            return List.of(new SimpleGrantedAuthority("ROLE_FIRST"), new SimpleGrantedAuthority("ROLE_SHARED"));
        };
        SiweAuthoritiesResolver second = context -> List.of(new SimpleGrantedAuthority("ROLE_SECOND"),
                new SimpleGrantedAuthority("ROLE_SHARED"));
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        new SiweSecurityBridge(new HttpSessionSecurityContextRepository(), List.of("ROLE_STATIC", "ROLE_SHARED"),
                List.of(first, second), publisher).attach(login);

        fire(login, new SiweLogin.SignedInEvent(login,
                new VerifiedSignIn("0x0000000000000000000000000000000000000005", 31337, null, Instant.now())));

        SecurityContext saved = (SecurityContext) servletRequest.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved.getAuthentication().getAuthorities()).extracting("authority")
                .containsExactly("ROLE_STATIC", "ROLE_SHARED", "ROLE_FIRST", "ROLE_SECOND");
        org.mockito.ArgumentCaptor<Object> events = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(publisher).publishEvent(events.capture());
        assertThat(events.getValue()).isInstanceOf(
                org.springframework.security.authentication.event.AuthenticationSuccessEvent.class);

        login.signOut();
        org.mockito.Mockito.verify(publisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        org.springframework.security.authentication.event.LogoutSuccessEvent logout =
                (org.springframework.security.authentication.event.LogoutSuccessEvent) events.getAllValues()
                        .get(events.getAllValues().size() - 1);
        assertThat(logout.getAuthentication()).isSameAs(saved.getAuthentication());
    }

    @Test
    void resolverFailureClearsSecurityContextSignsOutAndPublishesSafeFailure() {
        CountingRequest servletRequest = new CountingRequest();
        VaadinServletService service = mock(VaadinServletService.class);
        VaadinServletRequest request = new VaadinServletRequest(servletRequest, service);
        VaadinServletResponse response = new VaadinServletResponse(new MockHttpServletResponse(), service);
        doCallRealMethod().when(service).setCurrentInstances(request, response);
        service.setCurrentInstances(request, response);
        setCurrentSession(service);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        new SiweSecurityBridge(new HttpSessionSecurityContextRepository(), List.of(),
                List.of(context -> { throw new IllegalArgumentException("sensitive resolver detail"); }), publisher)
                .attach(login);
        java.util.concurrent.atomic.AtomicBoolean signedOut = new java.util.concurrent.atomic.AtomicBoolean();
        login.addSignedOutListener(event -> signedOut.set(true));
        VerifiedSignIn identity = new VerifiedSignIn("0x0000000000000000000000000000000000000006", 31337, null, Instant.now());

        assertThatThrownBy(() -> fire(login, new SiweLogin.SignedInEvent(login, identity)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SIWE authority resolver failed; Web3 session was rolled back")
                .hasNoCause();
        assertThat(Web3Session.current()).isEmpty();
        assertThat(signedOut).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        org.mockito.ArgumentCaptor<Object> event = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(event.capture());
        assertThat(event.getAllValues()).anySatisfy(published -> {
            assertThat(published).isInstanceOf(
                    org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent.class);
            assertThat(((org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent) published)
                    .getException().getMessage()).doesNotContain("sensitive resolver detail");
        });
    }

    @Test
    void signInFailureEventPublishesBadCredentialsEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        new SiweSecurityBridge(new HttpSessionSecurityContextRepository(), List.of(), List.of(), publisher).attach(login);
        fire(login, new SiweLogin.SignInFailedEvent(login, SiweException.Reason.MALFORMED, -1, false));

        org.mockito.ArgumentCaptor<Object> event = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(
                org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent.class);
        assertThat(((org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent) event.getValue())
                .getException().getMessage()).doesNotContain("signature");
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

    @Test
    void signOutWithoutServletRequestClearsPersistedContextThroughVaadinSession() {
        CountingRequest servletRequest = new CountingRequest();
        VaadinServletService service = mock(VaadinServletService.class);
        VaadinServletRequest request = new VaadinServletRequest(servletRequest, service);
        VaadinServletResponse response = new VaadinServletResponse(new MockHttpServletResponse(), service);
        doCallRealMethod().when(service).setCurrentInstances(request, response);
        service.setCurrentInstances(request, response);
        setCurrentSession(service);
        VaadinSession session = VaadinSession.getCurrent();
        when(session.getSession()).thenReturn(
                new com.vaadin.flow.server.WrappedHttpSession(servletRequest.getSession(true)));

        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        new SiweSecurityBridge(new HttpSessionSecurityContextRepository(), List.of("ROLE_WEB3_USER")).attach(login);
        fire(login, new SiweLogin.SignedInEvent(login,
                new VerifiedSignIn("0x0000000000000000000000000000000000000003", 31337, null, Instant.now())));
        assertThat(servletRequest.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();

        // 模拟纯 WebSocket 推送回调：没有当前 servlet 请求/响应
        com.vaadin.flow.internal.CurrentInstance.clearAll();
        VaadinSession.setCurrent(session);
        login.signOut();

        assertThat(servletRequest.getSession(false).getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
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
