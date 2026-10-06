package com.wontlost.web3.autoconfigure.security;

import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

import com.wontlost.web3.siwe.VerifiedSignIn;

/** Context supplied to authority resolvers after SIWE verification. */
public record SiweAuthenticationContext(Web3Principal principal, VerifiedSignIn signIn,
        HttpServletRequest request) {
    /** Creates a context for a verified sign-in. */
    public SiweAuthenticationContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(signIn, "signIn");
    }
}
