package com.wontlost.web3.onramp;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinService;

/**
 * Application-scoped registry of on-ramp providers, keyed by {@link OnrampProvider#name()}.
 * <p>
 * Provider credentials are never serialized with the UI. Components such as {@link FiatOnrampButton} keep only the
 * provider name and look the provider up here after a Vaadin session has been deserialized (for example after a
 * server restart with persistent sessions). Register providers once at startup, typically from a
 * {@code VaadinServiceInitListener}:
 * <pre>{@code
 * // a Spring bean method
 * VaadinServiceInitListener onrampRegistration(OnrampProvider provider) {
 *     return event -> OnrampProviders.register(event.getSource().getContext(), provider);
 * }
 * }</pre>
 * Creating a {@link FiatOnrampButton} during a request also registers its provider automatically.
 */
public final class OnrampProviders {
    // 测试缝隙：默认取当前 VaadinService 的上下文
    static Supplier<VaadinContext> contextLookup = () -> {
        VaadinService service = VaadinService.getCurrent();
        return service == null ? null : service.getContext();
    };

    private final Map<String, OnrampProvider> providers = new ConcurrentHashMap<>();

    private OnrampProviders() { }

    /**
     * Registers a provider for the application and starts loading its currency catalog in the background.
     * Registering the same instance again is a no-op.
     *
     * @throws IllegalStateException if a different provider instance is already registered under the same name;
     *         components resolve providers by name, so silently replacing one would mix up credentials
     */
    public static void register(VaadinContext context, OnrampProvider provider) {
        Objects.requireNonNull(provider, "provider");
        OnrampProvider existing = registry(Objects.requireNonNull(context, "context")).providers
                .putIfAbsent(provider.name(), provider);
        if (existing != null && existing != provider) {
            throw new IllegalStateException("A different on-ramp provider named " + provider.name() + " is already registered");
        }
        if (existing == null) provider.prefetch();
    }

    /** Returns the provider registered under {@code name} in the given context. */
    public static Optional<OnrampProvider> find(VaadinContext context, String name) {
        if (context == null || name == null) return Optional.empty();
        OnrampProviders registry = context.getAttribute(OnrampProviders.class);
        return registry == null ? Optional.empty() : Optional.ofNullable(registry.providers.get(name));
    }

    /** 在当前请求的上下文中注册；没有 VaadinService（如单元测试）时什么也不做。 */
    static void registerInCurrentContext(OnrampProvider provider) {
        VaadinContext context = contextLookup.get();
        if (context != null) register(context, provider);
    }

    static Optional<OnrampProvider> findInCurrentContext(String name) {
        return find(contextLookup.get(), name);
    }

    private static OnrampProviders registry(VaadinContext context) {
        return context.getAttribute(OnrampProviders.class, OnrampProviders::new);
    }
}
