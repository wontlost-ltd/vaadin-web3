package com.wontlost.web3.x402.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ResourcePolicyRegistryTest {
    @Test void matchesNormalizedPathAndSeparatesMethods() {
        var registry = new InMemoryResourcePolicyRegistry(List.of(
                policy("quote", "GET", "/api/quote/{id}"),
                policy("quote-head", "HEAD", "/api/quote/{id}")));

        assertEquals("quote", registry.match("GET", "/api/quote/12").orElseThrow().resourceId());
        assertEquals("quote-head", registry.match("HEAD", "/api/quote/12").orElseThrow().resourceId());
        assertTrue(registry.match("POST", "/api/quote/12").isEmpty());
        assertEquals("quote", registry.match("GET", "/api/quote/%31%32").orElseThrow().resourceId());
    }

    @Test void rejectsTraversalEncodedSeparatorsAndOverlappingMappings() {
        for (String unsafe : List.of("/api/../quote", "/api/%2f%2fhost", "/api/%5cpath", "/api//quote")) {
            assertThrows(IllegalArgumentException.class, () -> ResourcePolicyRegistry.normalizePath(unsafe));
        }
        assertThrows(IllegalArgumentException.class, () -> new InMemoryResourcePolicyRegistry(List.of(
                policy("one", "GET", "/api/{id}"), policy("two", "GET", "/api/quote"))));
    }

    @Test void permitsPostOnlyWhenExplicitlyIdempotentAndRejectsDuplicateIds() {
        assertThrows(IllegalArgumentException.class, () -> new HttpResourcePolicy(
                "unsafe", "POST", "/api/action", false, false, List.of(1L)));
        assertTrue(new HttpResourcePolicy("safe", "POST", "/api/action", true, false,
                List.of(1L)).idempotent());
        assertThrows(IllegalArgumentException.class, () -> new InMemoryResourcePolicyRegistry(List.of(
                policy("same", "GET", "/api/one"), policy("same", "POST", "/api/two"))));
    }

    @Test void methodDifferencePreventsRouteConflict() {
        var registry = new InMemoryResourcePolicyRegistry(List.of(
                policy("get", "GET", "/api/quote"), policy("post", "POST", "/api/quote", true)));
        assertFalse(registry.match("DELETE", "/api/quote").isPresent());
    }

    @Test void rejectsVaadinInternalPathsAtRegistration() {
        for (String path : List.of("/VAADIN/build/app.js", "/uidl", "/frontend/generated/app.js",
                "/actuator/health", "/webjars/example.js")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new HttpResourcePolicy("reserved", "GET", path, false, false, List.of(31337L)));
        }
    }

    private static HttpResourcePolicy policy(String id, String method, String path) {
        return policy(id, method, path, false);
    }

    private static HttpResourcePolicy policy(String id, String method, String path, boolean idempotent) {
        return new HttpResourcePolicy(id, method, path, idempotent, false, List.of(31337L));
    }
}
