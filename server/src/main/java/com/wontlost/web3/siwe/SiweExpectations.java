package com.wontlost.web3.siwe;

import java.time.Duration;
import java.util.Set;

/** Expected values and time limits applied during SIWE verification. */
/**
 * Verification policy for a SIWE request.
 *
 * @param domain required expected domain
 * @param uri optional expected URI
 * @param allowedChainIds optional allowlist; an empty set accepts any chain
 * @param maxAge optional maximum age of the issued-at timestamp
 */
public record SiweExpectations(String domain, String uri, Set<Long> allowedChainIds, Duration maxAge) {
    public SiweExpectations {
        allowedChainIds = allowedChainIds == null ? Set.of() : Set.copyOf(allowedChainIds);
    }

    /** Creates expectations that require the given domain. */
    public static SiweExpectations forDomain(String domain) {
        return new SiweExpectations(domain, null, Set.of(), null);
    }

    /** Returns a copy requiring the given URI. */
    public SiweExpectations withUri(String value) {
        return new SiweExpectations(domain, value, allowedChainIds, maxAge);
    }

    /** Returns a copy restricted to the given chain ids. */
    public SiweExpectations withAllowedChainIds(Set<Long> values) {
        return new SiweExpectations(domain, uri, values, maxAge);
    }

    /** Returns a copy with the given maximum message age. */
    public SiweExpectations withMaxAge(Duration value) {
        return new SiweExpectations(domain, uri, allowedChainIds, value);
    }
}
