package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.VaadinRequest;

class RequestOriginsTest {
    @Test void derivesDomainAndUriFromForwardedHeaders() {
        VaadinRequest request = request(Map.of("Forwarded", "for=1.2.3.4;proto=https;host=app.example.com",
                "Host", "internal:8080"), false);

        assertEquals("app.example.com", RequestOrigins.domain(request));
        assertEquals("https://app.example.com", RequestOrigins.uri(request));
    }

    @Test void fallsBackToHostAndRequestSchemeAndRejectsMissingHost() {
        VaadinRequest request = request(Map.of("Host", "localhost:8090"), false);

        assertEquals("localhost:8090", RequestOrigins.domain(request));
        assertEquals("http://localhost:8090", RequestOrigins.uri(request));
        assertThrows(IllegalStateException.class, () -> RequestOrigins.domain(null));
        assertThrows(IllegalStateException.class, () -> RequestOrigins.uri(request(Map.of(), true)));
    }

    @Test void continueTargetsMustStayInTheApplication() {
        assertTrue(RequestOrigins.isSafeContinueTarget("account"));
        for (String unsafe : new String[] {null, "", " ", "https://evil.example", "//evil.example", "a\\\\b"}) {
            assertFalse(RequestOrigins.isSafeContinueTarget(unsafe), String.valueOf(unsafe));
        }
    }

    @Test void sessionIdsRotateWithoutAServletRequestOnlyWarns() {
        SessionIds.rotate(null);
    }

    private static VaadinRequest request(Map<String, String> headers, boolean secure) {
        return (VaadinRequest) Proxy.newProxyInstance(VaadinRequest.class.getClassLoader(),
                new Class<?>[] {VaadinRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> headers.get((String) args[0]);
                    case "isSecure" -> secure;
                    default -> null;
                });
    }
}
