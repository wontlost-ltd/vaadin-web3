package com.wontlost.web3.autoconfigure.security;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

/** Bridges verified SIWE events into a persisted Spring Security context. */
public final class SiweSecurityBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(SiweSecurityBridge.class);
    private final SecurityContextRepository repository;
    private final List<String> authorities;

    /** Creates a bridge using the provided context repository and authorities. */
    public SiweSecurityBridge(SecurityContextRepository repository, List<String> authorities) {
        this.repository = Objects.requireNonNull(repository);
        this.authorities = List.copyOf(authorities);
    }

    /** Attaches this bridge to a SIWE component; detach the returned registration with the component lifecycle. */
    public Registration attach(SiweLogin login) {
        Objects.requireNonNull(login, "login");
        Registration signedIn = login.addSignedInListener(event -> onSignedIn(event));
        Registration signedOut = login.addSignedOutListener(event -> clearContext());
        return () -> { signedIn.remove(); signedOut.remove(); };
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
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new Web3Principal(verified.address(), verified.chainId()), null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            repository.saveContext(context, request.getHttpServletRequest(), response.getHttpServletResponse());
        } catch (RuntimeException failure) {
            SecurityContextHolder.clearContext();
            event.getSource().signOut();
            throw new IllegalStateException("Could not persist Spring Security context; Web3 session was rolled back", failure);
        }
    }

    private void clearContext() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(context);
        VaadinServletRequest request = VaadinServletRequest.getCurrent();
        VaadinServletResponse response = VaadinServletResponse.getCurrent();
        if (request == null) {
            LOGGER.error("SIWE sign-out cleared the Vaadin session but no servlet request was available to clear the persisted SecurityContext");
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
