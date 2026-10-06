package com.wontlost.web3.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

import com.wontlost.web3.siwe.SiweLogin;

/** Applies the starter's SIWE domain, URI, and chain allowlist to an application-created login component. */
public final class Web3SiweLoginConfigurer {
    private final Web3Properties.Siwe properties;
    private final java.util.List<Web3SiweLoginCustomizer> customizers;
    private final java.util.List<SiweLoginCustomizer> requestCustomizers;

    /** Creates a configurer for the supplied SIWE settings without customizers. */
    public Web3SiweLoginConfigurer(Web3Properties.Siwe properties) {
        this(properties, java.util.List.of(), java.util.List.of());
    }

    /** Creates a configurer for the supplied SIWE settings and customizers. */
    public Web3SiweLoginConfigurer(Web3Properties.Siwe properties, java.util.List<Web3SiweLoginCustomizer> customizers) {
        this(properties, customizers, java.util.List.of());
    }

    /** Creates a configurer for the supplied SIWE settings and ordered customizers. */
    public Web3SiweLoginConfigurer(Web3Properties.Siwe properties,
            java.util.List<Web3SiweLoginCustomizer> customizers, java.util.List<SiweLoginCustomizer> requestCustomizers) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.customizers = java.util.List.copyOf(customizers);
        this.requestCustomizers = java.util.List.copyOf(requestCustomizers);
    }

    /**
     * Applies the configured domain, URI, allowed chain IDs and maximum message age, then every
     * {@link Web3SiweLoginCustomizer} (with Spring Security present, this attaches the authentication bridge).
     * Call it right after creating the component, before adding your own listeners.
     */
    public SiweLogin configure(SiweLogin login) {
        // 应用通常在路由构造器中调用此方法：取当前 Vaadin 请求，使按请求定制（如多租户）也能生效；无请求时传 null
        com.vaadin.flow.server.VaadinServletRequest current = com.vaadin.flow.server.VaadinServletRequest.getCurrent();
        return configure(login, current == null ? null : current.getHttpServletRequest());
    }

    /** Applies the configured values and customizers using the current request. */
    public SiweLogin configure(SiweLogin login, HttpServletRequest request) {
        if (properties.getDomain() != null && !properties.getDomain().isBlank()) login.setDomain(properties.getDomain());
        if (properties.getUri() != null && !properties.getUri().isBlank()) login.setUri(properties.getUri());
        login.setAllowedChainIds(Set.copyOf(properties.getAllowedChainIds()));
        login.setMaxAge(properties.getMaxAge());
        customizers.forEach(customizer -> customizer.customize(login));
        requestCustomizers.forEach(customizer -> customizer.customize(login, request));
        return login;
    }
}
