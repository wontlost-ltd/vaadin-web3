package com.wontlost.web3.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;

import com.wontlost.web3.siwe.SiweLogin;

/** Customizes a SIWE login with the request that selected its tenant or origin. */
@FunctionalInterface
public interface SiweLoginCustomizer {
    /** Applies this customization; the request may be {@code null}. */
    void customize(SiweLogin login, HttpServletRequest request);
}
