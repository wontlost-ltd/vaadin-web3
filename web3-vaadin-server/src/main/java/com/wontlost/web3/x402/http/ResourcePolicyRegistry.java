package com.wontlost.web3.x402.http;

import java.util.Optional;

public interface ResourcePolicyRegistry {
    Optional<HttpResourcePolicy> find(String resourceId);
    Optional<HttpResourcePolicy> match(String method, String normalizedPath);

    static String normalizePath(String path) {
        if (path == null || !path.startsWith("/") || path.contains("\\") || path.contains("//")
                || path.matches("(?i).*%2f.*") || path.matches("(?i).*%5c.*")) {
            throw new IllegalArgumentException("invalid protected HTTP path");
        }
        String decoded;
        try {
            decoded = java.net.URI.create(path.replace("{", "%7B").replace("}", "%7D")).getPath();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid protected HTTP path", exception);
        }
        if (decoded.matches("(?i).*%(?:2f|5c).*") || decoded.contains("\\")) {
            throw new IllegalArgumentException("encoded path separators are not allowed");
        }
        for (String segment : decoded.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path traversal is not allowed");
            }
        }
        if (decoded.length() > 1 && decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }
        return decoded;
    }
}
