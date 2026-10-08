package com.wontlost.web3.siwe;

import com.vaadin.flow.server.VaadinRequest;

/**
 * Derives the sign-in domain and URI from the current request, honouring {@code Forwarded} and
 * {@code X-Forwarded-*} headers, the same way {@link SiweLogin} does. Configure the domain and URI explicitly in
 * production instead, because these headers are only trustworthy behind a proxy that sets them.
 */
public final class RequestOrigins {
    private RequestOrigins() {
    }

    /** The host (and port, if any) the browser used, for example {@code app.example.com}. */
    public static String domain(VaadinRequest request) {
        return SiweLogin.requestDomain(request);
    }

    /** The origin the browser used, for example {@code https://app.example.com}. */
    public static String uri(VaadinRequest request) {
        return SiweLogin.requestUri(request);
    }

    /** Whether a {@code continue} query parameter is a safe in-application route rather than an external URL. */
    public static boolean isSafeContinueTarget(String target) {
        return SiweLogin.isSafeContinueTarget(target);
    }
}
