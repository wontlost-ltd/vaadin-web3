package com.wontlost.web3.autoconfigure.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;

/** Resolves authorities for a verified SIWE identity. */
@FunctionalInterface
public interface SiweAuthoritiesResolver {
    /** Returns authorities granted to the verified identity. */
    Collection<? extends GrantedAuthority> resolve(SiweAuthenticationContext context);
}
