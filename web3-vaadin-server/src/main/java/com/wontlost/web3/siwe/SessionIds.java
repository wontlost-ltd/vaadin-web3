package com.wontlost.web3.siwe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;

final class SessionIds {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionIds.class);
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private SessionIds() { }

    static void rotate(VaadinRequest request) {
        try {
            if (request instanceof VaadinServletRequest servletRequest
                    && servletRequest.getHttpServletRequest() != null) {
                servletRequest.getHttpServletRequest().changeSessionId();
                return;
            }
        } catch (RuntimeException failure) {
            warnOnce("Could not rotate the HTTP session ID after SIWE sign-in; completing sign-in without rotation", failure);
            return;
        }
        warnOnce("Could not rotate the HTTP session ID after SIWE sign-in because the current request is not a servlet request; use WEBSOCKET_XHR or long-polling transport, or rotate it in the application", null);
    }

    private static void warnOnce(String message, RuntimeException failure) {
        if (!WARNED.compareAndSet(false, true)) return;
        if (failure == null) LOGGER.warn(message);
        else LOGGER.warn(message, failure);
    }
}
