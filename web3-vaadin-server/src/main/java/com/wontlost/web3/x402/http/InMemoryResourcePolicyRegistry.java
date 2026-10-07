package com.wontlost.web3.x402.http;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class InMemoryResourcePolicyRegistry implements ResourcePolicyRegistry {
    private final Map<String, HttpResourcePolicy> resources;
    private final Map<HttpResourcePolicy, Pattern> patterns;

    public InMemoryResourcePolicyRegistry(Collection<HttpResourcePolicy> policies) {
        Map<String, HttpResourcePolicy> byId = new LinkedHashMap<>();
        Map<HttpResourcePolicy, Pattern> compiled = new LinkedHashMap<>();
        for (HttpResourcePolicy policy : policies) {
            if (byId.putIfAbsent(policy.resourceId(), policy) != null) {
                throw new IllegalArgumentException("duplicate HTTP payment resource id");
            }
            for (HttpResourcePolicy previous : byId.values()) {
                if (previous == policy || !previous.method().equals(policy.method())) {
                    continue;
                }
                if (overlaps(previous.pathTemplate(), policy.pathTemplate())) {
                    throw new IllegalArgumentException("conflicting HTTP payment route mappings");
                }
            }
            compiled.put(policy, compile(policy.pathTemplate()));
        }
        resources = Map.copyOf(byId);
        patterns = Map.copyOf(compiled);
    }

    @Override public Optional<HttpResourcePolicy> find(String resourceId) {
        return Optional.ofNullable(resources.get(resourceId));
    }

    @Override public Optional<HttpResourcePolicy> match(String method, String path) {
        String normalized = ResourcePolicyRegistry.normalizePath(path);
        Optional<HttpResourcePolicy> exact = findMatch(method, normalized);
        if (exact.isPresent() || !"HEAD".equalsIgnoreCase(method)) {
            return exact;
        }
        return findMatch("GET", normalized);
    }

    private Optional<HttpResourcePolicy> findMatch(String method, String path) {
        return patterns.entrySet().stream()
                .filter(entry -> entry.getKey().method().equalsIgnoreCase(method))
                .filter(entry -> entry.getValue().matcher(path).matches())
                .map(Map.Entry::getKey).findFirst();
    }

    private static Pattern compile(String template) {
        StringBuilder regex = new StringBuilder("^");
        String[] segments = template.split("/", -1);
        for (int index = 1; index < segments.length; index++) {
            regex.append('/');
            String segment = segments[index];
            if (segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2) {
                regex.append("[^/]+");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static boolean overlaps(String left, String right) {
        String[] a = left.split("/", -1);
        String[] b = right.split("/", -1);
        if (a.length != b.length) {
            return false;
        }
        for (int index = 1; index < a.length; index++) {
            boolean aParameter = a[index].startsWith("{") && a[index].endsWith("}");
            boolean bParameter = b[index].startsWith("{") && b[index].endsWith("}");
            if (!aParameter && !bParameter && !a[index].equals(b[index])) {
                return false;
            }
        }
        return true;
    }
}
