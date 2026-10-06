package com.wontlost.web3.ui;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of transaction explorer base URLs. */
public final class Explorers {
    private static final Map<Long, String> URLS = new ConcurrentHashMap<>(Map.ofEntries(
            Map.entry(1L, "https://etherscan.io"), Map.entry(11155111L, "https://sepolia.etherscan.io"),
            Map.entry(8453L, "https://basescan.org"), Map.entry(84532L, "https://sepolia.basescan.org"),
            Map.entry(42161L, "https://arbiscan.io"), Map.entry(421614L, "https://sepolia.arbiscan.io"),
            Map.entry(10L, "https://optimistic.etherscan.io"), Map.entry(11155420L, "https://sepolia-optimism.etherscan.io"),
            Map.entry(137L, "https://polygonscan.com"), Map.entry(80002L, "https://amoy.polygonscan.com")));
    private Explorers() { }
    /** Registers or replaces an explorer base URL for an application chain. */
    public static void register(long chainId, String baseUrl) {
        java.net.URI uri;
        try { uri = java.net.URI.create(baseUrl); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("baseUrl must be an absolute HTTPS URL", invalid); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("baseUrl must be an absolute HTTPS URL without credentials, query or fragment");
        URLS.put(chainId, baseUrl.replaceAll("/+$", ""));
    }
    /** Returns the transaction URL when the chain has an explorer registered. */
    public static Optional<String> transactionUrl(long chainId, String hash) {
        if (hash == null || hash.isBlank()) return Optional.empty();
        return Optional.ofNullable(URLS.get(chainId)).map(url -> url + "/tx/" + hash);
    }
}
