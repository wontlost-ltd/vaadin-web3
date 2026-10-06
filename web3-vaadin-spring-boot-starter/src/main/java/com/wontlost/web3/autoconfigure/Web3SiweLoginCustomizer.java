package com.wontlost.web3.autoconfigure;

import com.wontlost.web3.siwe.SiweLogin;

/**
 * Customizes every {@link SiweLogin} passed to {@link Web3SiweLoginConfigurer#configure(SiweLogin)}.
 * <p>
 * The starter registers one when Spring Security is present, attaching the SIWE-to-Spring-Security bridge; applications
 * can declare their own beans to add listeners or settings.
 */
@FunctionalInterface
public interface Web3SiweLoginCustomizer {
    /** Applies this customization to the component. */
    void customize(SiweLogin login);
}
