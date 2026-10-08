package com.wontlost.web3.siwe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;

/**
 * Rotates the HTTP session ID after a verified wallet sign-in, so a session ID fixed before sign-in cannot be reused.
 * Used by {@code SiweLogin} and the Solana sign-in component; call it yourself when you store an identity directly.
 */
public final class SessionIds {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionIds.class);
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private SessionIds() { }

    /**
     * Changes the session ID of the servlet request behind {@code request}. When the request is not a servlet request
     * (for example a pure WebSocket callback) or rotation fails, logs one warning and returns without rotating.
     */
    public static void rotate(VaadinRequest request) {
        try {
            if (request instanceof VaadinServletRequest servletRequest
                    && servletRequest.getHttpServletRequest() != null) {
                servletRequest.getHttpServletRequest().changeSessionId();
                return;
            }
        } catch (RuntimeException failure) {
            warnOnce("Could not rotate the HTTP session ID after wallet sign-in; completing sign-in without rotation", failure);
            return;
        }
        warnOnce("Could not rotate the HTTP session ID after wallet sign-in because the current request is not a servlet request; use WEBSOCKET_XHR or long-polling transport, or rotate it in the application", null);
    }

    private static void warnOnce(String message, RuntimeException failure) {
        if (!WARNED.compareAndSet(false, true)) return;
        if (failure == null) LOGGER.warn(message);
        else LOGGER.warn(message, failure);
    }
}
