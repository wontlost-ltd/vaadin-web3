package com.wontlost.web3.x402.http;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.wontlost.web3.x402.siwx.VerifiedWallet;

public interface HttpIdentityResolver {
    Optional<VerifiedWallet> resolve(HttpServletRequest request);
}
