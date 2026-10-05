package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.VaadinServletRequest;

class SessionIdsTest {
    @Test void rotatesServletSessionAndToleratesMissingRequest() {
        AtomicInteger rotations = new AtomicInteger();
        var request = (jakarta.servlet.http.HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { jakarta.servlet.http.HttpServletRequest.class },
                (proxy, method, args) -> {
                    if ("changeSessionId".equals(method.getName())) {
                        rotations.incrementAndGet();
                        return "rotated";
                    }
                    return null;
                });
        SessionIds.rotate(new VaadinServletRequest(request, null));
        assertEquals(1, rotations.get());
        assertDoesNotThrow(() -> SessionIds.rotate(null));
    }
}
