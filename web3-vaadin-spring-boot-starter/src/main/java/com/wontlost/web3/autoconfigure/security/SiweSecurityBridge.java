package com.wontlost.web3.autoconfigure.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.siwe.SiweLogin;
import com.wontlost.web3.siwe.Web3Session;

/**
 * Bridges verified SIWE events into a persisted Spring Security context.
 * <p>
 * Sign-in requires a servlet request and response (Vaadin's default {@code WEBSOCKET_XHR} or long-polling transport);
 * without them the Web3 session is rolled back. Sign-out without a servlet request (a pure WebSocket push callback)
 * clears the context through the Vaadin-wrapped HTTP session under the default
 * {@link HttpSessionSecurityContextRepository#SPRING_SECURITY_CONTEXT_KEY}; applications using a custom
 * {@link SecurityContextRepository} that stores the context elsewhere must sign out through an HTTP request, for
 * example Spring Security's {@code /logout} with {@link Web3LogoutHandler}.
 */
public final class SiweSecurityBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(SiweSecurityBridge.class);
    private final SecurityContextRepository repository;
    private final List<String> authorities;
    private final List<SiweAuthoritiesResolver> resolvers;
    private final ApplicationEventPublisher eventPublisher;

    /** Creates a bridge using the provided context repository and authorities. */
    public SiweSecurityBridge(SecurityContextRepository repository, List<String> authorities) {
        this(repository, authorities, List.of(), null);
    }

    /** Creates a bridge with ordered authority resolvers and no event publisher. */
    public SiweSecurityBridge(SecurityContextRepository repository, List<String> authorities,
            List<SiweAuthoritiesResolver> resolvers) {
        this(repository, authorities, resolvers, null);
    }

    /** Creates a bridge with ordered authority resolvers and optional event publishing. */
    public SiweSecurityBridge(SecurityContextRepository repository, List<String> authorities,
            List<SiweAuthoritiesResolver> resolvers, ApplicationEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository);
        this.authorities = List.copyOf(authorities);
        this.resolvers = List.copyOf(resolvers);
        this.eventPublisher = eventPublisher;
    }

    /** Attaches this bridge to a SIWE component; detach the returned registration with the component lifecycle. */
    public Registration attach(SiweLogin login) {
        Objects.requireNonNull(login, "login");
        Registration signedIn = login.addSignedInListener(event -> onSignedIn(event));
        Registration signedOut = login.addSignedOutListener(event -> onSignedOut());
        Registration failed = login.addSignInFailedListener(event -> publishFailure(
                UsernamePasswordAuthenticationToken.unauthenticated("SIWE", null),
                new BadCredentialsException("SIWE sign-in verification failed")));
        return () -> { signedIn.remove(); signedOut.remove(); failed.remove(); };
    }

    private void onSignedIn(SiweLogin.SignedInEvent event) {
        VaadinServletRequest request = VaadinServletRequest.getCurrent();
        VaadinServletResponse response = VaadinServletResponse.getCurrent();
        if (request == null || response == null) {
            SecurityContextHolder.clearContext();
            event.getSource().signOut();
            throw new IllegalStateException("SIWE Security requires a current servlet request and response; Web3 session was rolled back");
        }
        var verified = event.getSignIn();
        Web3Principal principal = new Web3Principal(verified.address(), verified.chainId());
        LinkedHashSet<org.springframework.security.core.GrantedAuthority> granted = new LinkedHashSet<>();
        authorities.stream().map(SimpleGrantedAuthority::new).forEach(granted::add);
        try {
            SiweAuthenticationContext resolverContext = new SiweAuthenticationContext(principal, verified,
                    request.getHttpServletRequest());
            for (SiweAuthoritiesResolver resolver : resolvers) {
                Collection<? extends org.springframework.security.core.GrantedAuthority> resolved =
                        Objects.requireNonNull(resolver.resolve(resolverContext), "resolver authorities");
                resolved.forEach(authority -> granted.add(Objects.requireNonNull(authority, "authority")));
            }
        } catch (RuntimeException failure) {
            rollback(event.getSource());
            IllegalStateException rollback = new IllegalStateException(
                    "SIWE authority resolver failed; Web3 session was rolled back");
            LOGGER.warn("SIWE authority resolver failed; Web3 session was rolled back");
            publishFailure(UsernamePasswordAuthenticationToken.unauthenticated(principal, null),
                    new BadCredentialsException("SIWE authority resolver failed"));
            throw rollback;
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.copyOf(granted));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            repository.saveContext(context, request.getHttpServletRequest(), response.getHttpServletResponse());
        } catch (RuntimeException failure) {
            rollback(event.getSource());
            throw new IllegalStateException("Could not persist Spring Security context; Web3 session was rolled back", failure);
        }
        publish(new AuthenticationSuccessEvent(authentication));
    }

    private void rollback(SiweLogin login) {
        SecurityContextHolder.clearContext();
        login.signOut();
    }

    private void onSignedOut() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        clearContext();
        if (authentication != null) publish(new LogoutSuccessEvent(authentication));
    }

    private void publishFailure(Authentication authentication, AuthenticationException failure) {
        publish(new AuthenticationFailureBadCredentialsEvent(authentication, failure));
    }

    private void publish(Object event) {
        if (eventPublisher != null) eventPublisher.publishEvent(event);
    }

    private void clearContext() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);
        VaadinServletRequest request = VaadinServletRequest.getCurrent();
        VaadinServletResponse response = VaadinServletResponse.getCurrent();
        if (request == null) {
            // 纯 WebSocket 推送回调没有 servlet 请求：经 Vaadin 包装的 HTTP 会话直接删除持久化的认证，
            // 不能只记日志，否则 Web3Session 已登出而 Spring 仍视为已登录
            com.vaadin.flow.server.VaadinSession session = com.vaadin.flow.server.VaadinSession.getCurrent();
            if (session == null || session.getSession() == null) {
                // 在真实 UI 请求中 VaadinSession 总带有包装会话；这里抛异常只会让登出本身失败，因此记录错误
                LOGGER.error("SIWE sign-out could not reach the HTTP session to clear the persisted Spring Security context");
                return;
            }
            session.getSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            return;
        }
        try {
            repository.saveContext(context, request.getHttpServletRequest(),
                    response == null ? null : response.getHttpServletResponse());
        } catch (RuntimeException failure) {
            LOGGER.error("SIWE sign-out could not clear the persisted Spring Security context", failure);
        }
    }

    /** Returns the configured repository, useful for application integration. */
    public SecurityContextRepository repository() { return repository; }
}
