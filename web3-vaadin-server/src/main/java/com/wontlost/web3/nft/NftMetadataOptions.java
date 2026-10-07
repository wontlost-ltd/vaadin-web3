package com.wontlost.web3.nft;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 元数据传输和缓存的有界配置。 */
public record NftMetadataOptions(List<String> ipfsGateways, int maxResponseBytes, int maxDataUriBytes,
        int maxUriLength, int maxRedirects, Duration requestTimeout, int cacheCapacity, Duration positiveTtl,
        Duration negativeTtl, Duration errorTtl, int maxConcurrency, Set<Integer> allowedPorts,
        int maxAttributes, int maxTextLength) {
    public NftMetadataOptions {
        ipfsGateways = List.copyOf(ipfsGateways);
        allowedPorts = Set.copyOf(allowedPorts);
        limit("max-response-bytes", maxResponseBytes, 1, 16 * 1024 * 1024);
        limit("max-data-uri-bytes", maxDataUriBytes, 1, 16 * 1024 * 1024);
        limit("max-uri-length", maxUriLength, 1, 1_000_000);
        limit("max-redirects", maxRedirects, 0, 10);
        limit("cache-capacity", cacheCapacity, 1, 1_000_000);
        limit("max-concurrency", maxConcurrency, 1, 64);
        limit("max-attributes", maxAttributes, 1, 1000);
        limit("max-text-length", maxTextLength, 1, 100_000);
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("web3.nft.metadata.request-timeout must be positive");
        }
        requirePositive("positive-ttl", positiveTtl);
        requirePositive("negative-ttl", negativeTtl);
        requirePositive("error-ttl", errorTtl);
        if (ipfsGateways.isEmpty() || allowedPorts.isEmpty()) {
            throw new IllegalArgumentException("NFT metadata gateways and allowed ports must not be empty");
        }
        if (allowedPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            throw new IllegalArgumentException("web3.nft.metadata.allowed-ports must contain valid TCP ports");
        }
        if (ipfsGateways.stream().anyMatch(gateway -> gateway == null || !gateway.startsWith("https://"))) {
            throw new IllegalArgumentException("web3.nft.metadata.ipfs-gateways must use HTTPS");
        }
    }

    public NftMetadataOptions(List<String> ipfsGateways, int maxResponseBytes, int maxDataUriBytes,
            int maxRedirects, Duration requestTimeout, int cacheCapacity, Duration positiveTtl,
            Duration negativeTtl, Duration errorTtl, int maxConcurrency, Set<Integer> allowedPorts,
            int maxAttributes, int maxTextLength) {
        this(ipfsGateways, maxResponseBytes, maxDataUriBytes, 8192, maxRedirects, requestTimeout, cacheCapacity,
                positiveTtl, negativeTtl, errorTtl, maxConcurrency, allowedPorts, maxAttributes, maxTextLength);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("web3.nft.metadata." + name + " must be positive");
        }
    }

    private static void limit(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("web3.nft.metadata." + name + " must be between " + min + " and " + max);
        }
    }

    public static NftMetadataOptions defaults() {
        return new NftMetadataOptions(List.of("https://ipfs.io/ipfs/"), 262144, 262144, 8192, 3,
                Duration.ofSeconds(10), 10000, Duration.ofHours(1), Duration.ofMinutes(1),
                Duration.ofSeconds(10), 8, Set.of(443), 100, 2048);
    }
}
