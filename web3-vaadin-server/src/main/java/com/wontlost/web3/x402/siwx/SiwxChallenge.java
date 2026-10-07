package com.wontlost.web3.x402.siwx;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public record SiwxChallenge(String domain, URI uri, String nonce, Instant issuedAt,
        Instant expirationTime, String statement, String version, List<SupportedChain> supportedChains,
        List<String> resources) {
    public SiwxChallenge {
        supportedChains = List.copyOf(supportedChains);
        resources = List.copyOf(resources);
    }
}
