package com.wontlost.web3.x402.http;

import java.util.List;
import java.util.Locale;

public record HttpResourcePolicy(String resourceId, String method, String pathTemplate,
        boolean idempotent, boolean requireSiwx, List<Long> allowedChainIds) {
    public HttpResourcePolicy {
        if (resourceId == null || resourceId.isBlank() || method == null || pathTemplate == null) {
            throw new IllegalArgumentException("HTTP payment resource fields are required");
        }
        method = method.toUpperCase(Locale.ROOT);
        pathTemplate = ResourcePolicyRegistry.normalizePath(pathTemplate);
        if (isReservedFrameworkPath(pathTemplate)) {
            throw new IllegalArgumentException("HTTP payment routes cannot protect framework paths");
        }
        allowedChainIds = List.copyOf(allowedChainIds);
        if (allowedChainIds.stream().anyMatch(chainId -> chainId == null || chainId <= 0)) {
            // 启动期拒绝非正链 ID，避免在请求处理中签发无法验证的 SIWX 挑战
            throw new IllegalArgumentException("HTTP payment resource chain ids must be positive");
        }
        if (!method.equals("GET") && !method.equals("HEAD") && !idempotent) {
            throw new IllegalArgumentException("only GET/HEAD or explicitly idempotent resources may require payment");
        }
    }

    private static boolean isReservedFrameworkPath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return List.of("/vaadin", "/uidl", "/frontend", "/actuator", "/webjars").stream()
                .anyMatch(prefix -> normalized.equals(prefix) || normalized.startsWith(prefix + "/"));
    }
}
