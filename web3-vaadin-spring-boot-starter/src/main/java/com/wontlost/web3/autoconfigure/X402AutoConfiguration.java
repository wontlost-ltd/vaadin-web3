package com.wontlost.web3.autoconfigure;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.SmartLifecycle;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.x402.facilitator.HttpFacilitatorClient;
import com.wontlost.web3.x402.payment.DefaultX402PaymentService;
import com.wontlost.web3.x402.payment.Eip3009TypedDataFactory;
import com.wontlost.web3.x402.payment.FacilitatorClient;
import com.wontlost.web3.x402.payment.ResourcePolicy;
import com.wontlost.web3.x402.payment.X402PaymentService;
import com.wontlost.web3.x402.protocol.JacksonX402Codec;
import com.wontlost.web3.x402.protocol.X402Codec;
import com.wontlost.web3.x402.store.InMemoryPaidResourceStore;
import com.wontlost.web3.x402.store.PaidResourceStore;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.x402.protocol.X402Validation;
import com.wontlost.web3.x402.http.HttpIdentityResolver;
import com.wontlost.web3.x402.http.HttpResourcePolicy;
import com.wontlost.web3.x402.http.InMemoryResourcePolicyRegistry;
import com.wontlost.web3.x402.http.ResourcePolicyRegistry;
import com.wontlost.web3.x402.http.X402PaymentFilter;
import com.wontlost.web3.x402.siwx.IdentitySource;
import com.wontlost.web3.x402.siwx.InMemorySiwxChallengeStore;
import com.wontlost.web3.x402.siwx.SiwxChallengeStore;
import com.wontlost.web3.x402.siwx.SiwxVerifier;
import com.wontlost.web3.x402.siwx.VerifiedWallet;
import com.wontlost.web3.x402.payment.RequiresPayment;

@AutoConfiguration(after = Web3VaadinAutoConfiguration.class)
@ConditionalOnProperty(prefix = "web3.x402", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(Web3Properties.class)
public class X402AutoConfiguration {
    private static final System.Logger LOGGER = System.getLogger(X402AutoConfiguration.class.getName());
    @Bean
    SmartInitializingSingleton x402ConfigurationValidator(Web3Properties properties,
            ObjectProvider<ResourcePolicy> policies) {
        return () -> {
            Web3Properties.X402 config = properties.getX402();
            if (config.getValidAfterSkew() == null || config.getValidAfterSkew().isNegative())
                throw new IllegalStateException("web3.x402.valid-after-skew must not be negative");
            if (config.getReconcileInterval() == null || config.getReconcileInterval().isNegative())
                throw new IllegalStateException("web3.x402.reconcile-interval must not be negative");
            if (config.getReconcileConfirmations() < 0)
                throw new IllegalStateException("web3.x402.reconcile-confirmations must not be negative");
            validateOrigin(config.getOrigin());
            for (String network : config.getAllowedNetworks()) validateNetwork(network, "web3.x402.allowed-networks");
            for (ResourcePolicy policy : policies) {
                long chainId;
                try { chainId = X402Validation.chainId(policy.network(), Set.of()); }
                catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("invalid CAIP-2 network in x402 resource policy: " + policy.network(), exception);
                }
                if (config.getProtocol().getMaxTimeoutSeconds() < policy.maxTimeoutSeconds())
                    throw new IllegalStateException("x402 resource timeout exceeds web3.x402.protocol.max-timeout-seconds");
                if (!config.getAllowedNetworks().isEmpty() && !config.getAllowedNetworks().contains(policy.network()))
                    throw new IllegalStateException("x402 resource network is not allowed");
                if (!config.getAllowedChainIds().isEmpty()
                        && !config.getAllowedChainIds().contains(chainId))
                    throw new IllegalStateException("x402 resource chain is not allowed");
                if (!config.getAllowedAssets().isEmpty() && config.getAllowedAssets().stream()
                        .noneMatch(asset -> asset.equalsIgnoreCase(policy.asset())))
                    throw new IllegalStateException("x402 resource asset is not allowed");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(name = "x402PaymentGate")
    VaadinServiceInitListener x402PaymentGate(X402PaymentService payments) {
        return event -> event.getSource().addUIInitListener(uiEvent ->
                uiEvent.getUI().addBeforeEnterListener(new com.wontlost.web3.x402.payment.PaymentGate(payments)));
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock x402Clock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnMissingBean(X402Codec.class)
    X402Codec x402Codec(Web3Properties properties) {
        Web3Properties.Protocol config = properties.getX402().getProtocol();
        return new JacksonX402Codec(config.getMaxHeaderBytes(), config.getMaxJsonBytes());
    }

    @Bean
    @ConditionalOnMissingBean(PaidResourceStore.class)
    PaidResourceStore x402PaidResourceStore() { return new InMemoryPaidResourceStore(); }

    @Bean
    @ConditionalOnMissingBean(FacilitatorClient.class)
    FacilitatorClient x402FacilitatorClient(Web3Properties properties) {
        Web3Properties.X402 config = properties.getX402();
        Web3Properties.Facilitator facilitator = config.getFacilitator();
        if (facilitator.getBaseUrl() == null || facilitator.getBaseUrl().isBlank())
            throw new IllegalStateException("web3.x402.facilitator.base-url is required when x402 is enabled");
        return new HttpFacilitatorClient(facilitator.getBaseUrl(), facilitator.getApiKey(),
                facilitator.getApiKeyHeader(), facilitator.getConnectTimeout(), facilitator.getRequestTimeout(),
                facilitator.getMaxResponseBytes());
    }

    @Bean
    @ConditionalOnMissingBean(X402PaymentService.class)
    X402PaymentService x402PaymentService(ObjectProvider<ResourcePolicy> policies, PaidResourceStore store,
            FacilitatorClient facilitator, Clock clock, Web3Properties properties, ObjectProvider<ChainRegistry> chains) {
        if (properties.getX402().getReconcileConfirmations() < 0)
            throw new IllegalStateException("web3.x402.reconcile-confirmations must not be negative");
        Map<String, ResourcePolicy> policyMap = policies.orderedStream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(ResourcePolicy::resourceId, p -> p));
        return new DefaultX402PaymentService(policyMap, store, facilitator,
                new Eip3009TypedDataFactory(clock,
                        properties.getX402().getValidAfterSkew()), clock, chains.getIfAvailable(),
                properties.getX402().getFacilitator().getRequestTimeout().plusSeconds(10),
                properties.getX402().getReconcileConfirmations());
    }

    @Bean
    @ConditionalOnProperty(prefix = "web3.x402.http", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(SiwxChallengeStore.class)
    SiwxChallengeStore x402SiwxChallengeStore(Web3Properties properties, Clock clock) {
        int capacity = properties.getX402().getHttp().getSiwxChallengeCapacity();
        if (capacity < 1) {
            throw new IllegalStateException("web3.x402.http.siwx-challenge-capacity must be positive");
        }
        return new InMemorySiwxChallengeStore(capacity, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "web3.x402.http", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(SiwxVerifier.class)
    SiwxVerifier x402SiwxVerifier(SiwxChallengeStore challenges, Clock clock,
            Web3Properties properties, ObjectProvider<ChainRegistry> chains) {
        var ttl = properties.getX402().getHttp().getSiwxChallengeTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("web3.x402.http.siwx-challenge-ttl must be positive");
        }
        return new SiwxVerifier(challenges, clock, ttl, chains.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "web3.x402.http", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(HttpIdentityResolver.class)
    HttpIdentityResolver x402HttpIdentityResolver() {
        return request -> {
            var current = com.wontlost.web3.siwe.Web3Session.current();
            if (current.isPresent()) {
                var signIn = current.get();
                return java.util.Optional.of(new VerifiedWallet(signIn.address(), signIn.chainId(), IdentitySource.SIWE_SESSION));
            }
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof com.wontlost.web3.autoconfigure.security.Web3Principal principal) {
                return java.util.Optional.of(new VerifiedWallet(principal.address(), principal.chainId(), IdentitySource.SIWE_SESSION));
            }
            return java.util.Optional.empty();
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "web3.x402.http", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(ResourcePolicyRegistry.class)
    ResourcePolicyRegistry x402HttpResourcePolicyRegistry(Web3Properties properties,
            ObjectProvider<ResourcePolicy> paymentPolicies,
            ObjectProvider<RequestMappingHandlerMapping> mappings) {
        Map<String, ResourcePolicy> byId = paymentPolicies.orderedStream()
                .collect(java.util.stream.Collectors.toMap(ResourcePolicy::resourceId, policy -> policy));
        java.util.List<HttpResourcePolicy> routes = new java.util.ArrayList<>();
        for (Web3Properties.HttpResource route : properties.getX402().getHttp().getResources()) {
            routes.add(httpPolicy(route.getResourceId(), route.getMethod(), route.getPath(), route.isIdempotent(),
                    route.isRequireSiwx(), route.getAllowedChainIds(), byId));
        }
        for (RequestMappingHandlerMapping mapping : mappings) {
            mapping.getHandlerMethods().forEach((requestMapping, handler) -> {
                RequiresPayment requirement = handler.getMethodAnnotation(RequiresPayment.class);
                if (requirement == null) {
                    requirement = handler.getBeanType().getAnnotation(RequiresPayment.class);
                }
                if (requirement == null) {
                    return;
                }
                String resourceId = requirement.resource().isBlank()
                        ? requirement.resourceId() : requirement.resource();
                if (!requirement.resource().isBlank() && !requirement.resourceId().isBlank()
                        && !requirement.resource().equals(requirement.resourceId())) {
                    throw new IllegalStateException("@RequiresPayment resource and resourceId conflict");
                }
                var methods = requestMapping.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    throw new IllegalStateException("@RequiresPayment HTTP route must declare methods explicitly");
                }
                for (String path : requestMapping.getPatternValues()) {
                    for (RequestMethod method : methods) {
                        routes.add(httpPolicy(resourceId, method.name(), path, requirement.idempotent(),
                                requirement.requireSiwx(), java.util.List.of(), byId));
                    }
                }
            });
        }
        return new InMemoryResourcePolicyRegistry(routes);
    }

    private static HttpResourcePolicy httpPolicy(String resourceId, String method, String path,
            boolean idempotent, boolean requireSiwx, java.util.List<Long> allowedChainIds,
            Map<String, ResourcePolicy> paymentPolicies) {
        ResourcePolicy payment = paymentPolicies.get(resourceId);
        if (payment == null) {
            throw new IllegalStateException("HTTP payment route has no ResourcePolicy: " + resourceId);
        }
        java.util.List<Long> chains = allowedChainIds.isEmpty()
                ? java.util.List.of(X402Validation.chainId(payment.network(), Set.of())) : allowedChainIds;
        return new HttpResourcePolicy(resourceId, method, path, idempotent, requireSiwx, chains);
    }

    @Bean
    @ConditionalOnProperty(prefix = "web3.x402.http", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "x402PaymentFilter")
    X402PaymentFilter x402PaymentFilter(ResourcePolicyRegistry registry, X402PaymentService payments,
            X402Codec codec, SiwxVerifier siwx, HttpIdentityResolver identities, Web3Properties properties) {
        Web3Properties.Http config = properties.getX402().getHttp();
        if (config.getMaxResponseBytes() < 1) {
            throw new IllegalStateException("web3.x402.http.max-response-bytes must be positive");
        }
        return new X402PaymentFilter(registry, payments, codec,
                siwx, identities, URI.create(properties.getX402().getOrigin()), config.getMaxResponseBytes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "web3.x402.http", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "x402PaymentFilterRegistration")
    FilterRegistrationBean<X402PaymentFilter> x402PaymentFilterRegistration(X402PaymentFilter filter) {
        FilterRegistrationBean<X402PaymentFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("x402PaymentFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(name = "x402ReconciliationLifecycle")
    SmartLifecycle x402ReconciliationLifecycle(Web3Properties properties, X402PaymentService service,
            PaidResourceStore store) {
        return new X402ReconciliationLifecycle(properties.getX402().getReconcileInterval(), service, store);
    }

    @Bean
    @ConditionalOnMissingBean(name = "x402VaadinProductionModeGuard")
    VaadinServiceInitListener x402VaadinProductionModeGuard(ObjectProvider<Object> beans,
            ObjectProvider<PaidResourceStore> stores, Web3Properties properties) {
        return event -> {
            boolean hasLocalFacilitator = beans.stream().anyMatch(bean -> bean.getClass().getSimpleName().equals("LocalFacilitator"));
            if (hasLocalFacilitator && event.getSource().getDeploymentConfiguration().isProductionMode())
                throw new IllegalStateException("LocalFacilitator is not allowed in Vaadin production mode");
            boolean inMemoryStore = stores.stream().anyMatch(InMemoryPaidResourceStore.class::isInstance);
            if (inMemoryStore && !properties.getX402().isAllowInMemoryStore()
                    && event.getSource().getDeploymentConfiguration().isProductionMode())
                throw new IllegalStateException("InMemoryPaidResourceStore is not allowed in Vaadin production mode; provide a persistent PaidResourceStore (Pro provides JDBC) or set web3.x402.allow-in-memory-store=true");
        };
    }

    private static void validateNetwork(String network, String property) {
        try { X402Validation.chainId(network, Set.of()); }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException(property + " entries must be valid CAIP-2 networks such as eip155:1", exception);
        }
    }

    private static final class X402ReconciliationLifecycle implements SmartLifecycle {
        private final Duration interval;
        private final X402PaymentService service;
        private final PaidResourceStore store;
        private java.util.concurrent.ScheduledExecutorService executor;
        private volatile boolean running;

        private X402ReconciliationLifecycle(Duration interval, X402PaymentService service, PaidResourceStore store) {
            this.interval = interval; this.service = service; this.store = store;
        }
        @Override public synchronized void start() {
            if (running || interval.isZero()) return;
            executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "x402-reconciliation"); thread.setDaemon(true); return thread;
            });
            executor.scheduleWithFixedDelay(this::reconcileBatch, interval.toMillis(), interval.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            running = true;
        }
        private void reconcileBatch() {
            for (var record : store.findPending(100)) {
                try { service.reconcile(record.paymentId()); }
                catch (RuntimeException exception) {
                    LOGGER.log(System.Logger.Level.WARNING, "x402 reconciliation failed for payment {0}: {1}",
                            record.paymentId(), exception.getClass().getSimpleName());
                }
            }
        }
        @Override public synchronized void stop() {
            if (executor != null) executor.shutdownNow();
            executor = null; running = false;
        }
        @Override public boolean isRunning() { return running; }
        @Override public int getPhase() { return Integer.MAX_VALUE; }
        @Override public boolean isAutoStartup() { return true; }
        @Override public void stop(Runnable callback) { stop(); callback.run(); }
    }

    private static void validateOrigin(String value) {
        URI uri;
        try { uri = URI.create(value == null ? "" : value); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("web3.x402.origin must be an absolute origin", exception); }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        if ((!secure && !loopback) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getPath() != null && !uri.getPath().isEmpty())
            throw new IllegalStateException("web3.x402.origin must be an HTTPS origin (or loopback HTTP)");
    }
}
