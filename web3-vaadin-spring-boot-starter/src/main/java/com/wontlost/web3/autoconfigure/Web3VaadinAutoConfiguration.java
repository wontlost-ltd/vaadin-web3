package com.wontlost.web3.autoconfigure;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.ServerWallet;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.FailoverJsonRpcTransport;
import com.wontlost.web3.chain.HttpJsonRpcTransport;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.chain.JsonRpcTransportDecorator;
import com.wontlost.web3.dev.DevWallet;
import com.wontlost.web3.monitor.PaymentMonitorClient;
import com.wontlost.web3.pay.InMemoryPaymentLedger;
import com.wontlost.web3.pay.PaymentLedger;
import com.wontlost.web3.pay.PaymentVerifier;
import com.wontlost.web3.screening.AddressScreening;
import com.wontlost.web3.siwe.InMemoryNonceStore;
import com.wontlost.web3.siwe.NonceStore;
import com.wontlost.web3.siwe.SiweLogin;

/** Default beans and Vaadin context registrations for Web3Vaadin. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "com.wontlost.web3.pro.cluster.Web3ClusterAutoConfiguration",
        "com.wontlost.web3.pro.payments.Web3PaymentsAutoConfiguration"
})
@EnableConfigurationProperties({Web3Properties.class, Web3RpcProperties.class})
public class Web3VaadinAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(Web3VaadinAutoConfiguration.class);
    private static final AtomicBoolean LEGACY_RPC_WARNED = new AtomicBoolean();

    @Bean
    @ConditionalOnMissingBean
    ChainRegistry web3ChainRegistry(Web3Properties properties, Web3RpcProperties rpcProperties,
            RpcTransportLifecycle lifecycle, Environment environment,
            ObjectProvider<JsonRpcTransportDecorator> decorators) {
        ChainRegistry registry = new ChainRegistry();
        Map<Long, Web3Properties.Chain> modern = properties.getChains();
        modern.forEach((id, chain) -> {
            if (chain != null && chain.getRpcUrls() != null && !chain.getRpcUrls().isEmpty()
                    && chain.getRpcUrl() != null && !chain.getRpcUrl().isBlank()
                    && LEGACY_RPC_WARNED.compareAndSet(false, true)) {
                LOGGER.warn("Both web3.chains.{}.rpc-urls and web3.chains.{}.rpc-url are set; rpc-urls takes precedence", id, id);
            }
        });
        Map<Long, String> legacy = legacyRpc(environment);
        legacy.forEach((id, url) -> {
            if (LEGACY_RPC_WARNED.compareAndSet(false, true)) {
                if (modern.containsKey(id)) {
                    LOGGER.warn("Both web3.chains.{} RPC properties and deprecated web3.rpc.{} are set; the new property takes precedence",
                            id, id);
                } else {
                    LOGGER.warn("web3.rpc.<id> is deprecated; use web3.chains.<id>.rpc-url");
                }
            }
        });
        modern.forEach((id, chain) -> {
            if (chain == null) return;
            List<String> urls = chain.getRpcUrls() == null ? List.of() : chain.getRpcUrls().stream()
                    .filter(url -> url != null && !url.isBlank()).toList();
            if (!urls.isEmpty()) addChain(registry, id, urls, rpcProperties, lifecycle, decorators.orderedStream().toList());
            else addChain(registry, id, chain.getRpcUrl() == null ? List.of() : List.of(chain.getRpcUrl()),
                    rpcProperties, lifecycle, decorators.orderedStream().toList());
        });
        legacy.forEach((id, url) -> {
            if (!modern.containsKey(id)) addChain(registry, id, url == null ? List.of() : List.of(url), rpcProperties,
                    lifecycle, decorators.orderedStream().toList());
        });
        return registry;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    RpcTransportLifecycle web3RpcTransportLifecycle(Web3RpcProperties properties) {
        Duration timeout = positive(properties.getRequestTimeout(), Duration.ofSeconds(10));
        return new RpcTransportLifecycle(HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    private static void addChain(ChainRegistry registry, long chainId, List<String> urls,
            Web3RpcProperties config, RpcTransportLifecycle lifecycle, List<JsonRpcTransportDecorator> decorators) {
        if (urls.isEmpty()) return;
        lifecycle.endpoints(chainId, urls);
        Duration timeout = positive(config.getRequestTimeout(), Duration.ofSeconds(10));
        List<FailoverJsonRpcTransport.Endpoint> endpoints = urls.stream().map(url ->
                new FailoverJsonRpcTransport.Endpoint(url, new HttpJsonRpcTransport(url, timeout, lifecycleClient(lifecycle))))
                .toList();
        UnaryOperator<JsonRpcTransport> wrapper = transport -> {
            JsonRpcTransport result = transport;
            for (int index = decorators.size() - 1; index >= 0; index--) {
                try {
                    result = java.util.Objects.requireNonNull(decorators.get(index).decorate(chainId, result));
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("Failed to decorate JSON-RPC transport for chain " + chainId
                            + " (" + exception.getClass().getSimpleName() + ")");
                }
            }
            return result;
        };
        if (endpoints.size() == 1) {
            // 单端点保持直接 HTTP 传输，避免引入故障切换状态机的额外开销。
            JsonRpcTransport base = lifecycle.track(chainId, endpoints.getFirst().transport());
            registry.register(chainId, new EthRpcClient(base, wrapper));
            return;
        }
        var failover = new FailoverJsonRpcTransport(endpoints,
                new FailoverJsonRpcTransport.Config(config.getFailureThreshold(),
                        positive(config.getOpenDuration(), Duration.ofSeconds(15)), config.getLagTolerance()));
        lifecycle.track(chainId, failover);
        registry.register(chainId, new EthRpcClient(failover, wrapper));
    }

    private static HttpClient lifecycleClient(RpcTransportLifecycle lifecycle) { return lifecycle.client(); }

    private static Map<Long, String> legacyRpc(Environment environment) {
        Map<Long, String> result = new java.util.LinkedHashMap<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (var source : configurable.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
                for (String name : enumerable.getPropertyNames()) {
                    if (!name.startsWith("web3.rpc.")) continue;
                    String suffix = name.substring("web3.rpc.".length());
                    if (!suffix.matches("\\d+")) continue;
                    try { result.putIfAbsent(Long.parseLong(suffix), environment.getProperty(name)); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
        return result;
    }

    @Bean
    @ConditionalOnMissingBean
    NonceStore web3NonceStore(Web3Properties properties) {
        return new InMemoryNonceStore(positive(properties.getSiwe().getNonceTtl(), Duration.ofMinutes(5)));
    }

    @Bean
    @ConditionalOnMissingBean
    Web3SiweLoginConfigurer web3SiweLoginConfigurer(Web3Properties properties,
            org.springframework.beans.factory.ObjectProvider<Web3SiweLoginCustomizer> customizers,
            org.springframework.beans.factory.ObjectProvider<SiweLoginCustomizer> requestCustomizers) {
        return new Web3SiweLoginConfigurer(properties.getSiwe(), customizers.orderedStream().toList(),
                requestCustomizers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    PaymentLedger web3PaymentLedger() { return new InMemoryPaymentLedger(); }

    @Bean
    @ConditionalOnMissingBean
    PaymentVerifier web3PaymentVerifier(ChainRegistry registry, PaymentLedger ledger) {
        return new PaymentVerifier(registry, ledger);
    }

    @Bean
    @ConditionalOnMissingBean
    PaymentMonitorClient web3PaymentMonitorClient(Web3Properties properties) {
        String url = properties.getMonitor().getUrl();
        String key = properties.getMonitor().getApiKey();
        return url.isBlank() || key.isBlank() ? null : new PaymentMonitorClient(URI.create(url), key);
    }

    // 按名称而非类型判断：VaadinServiceInitListener 是通用类型，vaadin-spring（启用 Spring Security 时的访问控制初始化器）
    // 与 Pro 都会注册同类型 bean，按类型的 @ConditionalOnMissingBean 会让本监听器静默失效
    @Bean
    @ConditionalOnMissingBean(name = "web3VaadinContextInitializer")
    VaadinServiceInitListener web3VaadinContextInitializer(
            ObjectProvider<ChainRegistry> chains,
            ObjectProvider<NonceStore> nonces,
            ObjectProvider<AddressScreening> screening,
            ObjectProvider<ServerWallet> wallets,
            ObjectProvider<PaymentMonitorClient> monitor,
            ObjectProvider<Web3Properties> properties) {
        return event -> initialize(event, chains, nonces, screening, wallets, monitor, properties);
    }

    private static void initialize(ServiceInitEvent event, ObjectProvider<ChainRegistry> chains,
            ObjectProvider<NonceStore> nonces, ObjectProvider<AddressScreening> screening,
            ObjectProvider<ServerWallet> wallets,
            ObjectProvider<PaymentMonitorClient> monitor, ObjectProvider<Web3Properties> properties) {
        Web3Properties config = properties.getIfAvailable(Web3Properties::new);
        ServerWallet wallet = wallets.getIfAvailable();
        // 服务端钱包按契约仅用于开发/测试：无论来自属性开关还是应用自定义 bean，生产模式一律拒绝启动
        boolean production = event.getSource().getDeploymentConfiguration().isProductionMode();
        if (production && config.getDev().getMockWallet().isEnabled()) {
            throw new IllegalStateException("web3.dev.mock-wallet.enabled must not be enabled in Vaadin production mode");
        }
        if (production && wallet != null) {
            throw new IllegalStateException("A ServerWallet bean (" + wallet.getClass().getName()
                    + ") must not be registered in Vaadin production mode; server wallets are for development only");
        }
        if (wallet instanceof DevWallet devWallet) {
            LOGGER.warn("*** DEVELOPMENT WALLET ENABLED: address={}, chainId={}; use only on a valueless local chain ***",
                    devWallet.accounts().getFirst(), devWallet.chainId());
        }
        var context = event.getSource().getContext();
        ChainRegistry chainRegistry = chains.getIfAvailable();
        if (chainRegistry != null) context.setAttribute(ChainRegistry.class, chainRegistry);
        NonceStore nonceStore = nonces.getIfAvailable();
        if (nonceStore != null) SiweLogin.registerNonceStore(context, nonceStore);
        AddressScreening addressScreening = screening.getIfAvailable();
        if (addressScreening != null) AddressScreening.register(context, addressScreening);
        if (wallet != null) ServerWallet.register(context, wallet);
        PaymentMonitorClient client = monitor.getIfAvailable();
        if (client != null) PaymentMonitorClient.register(context, client);
    }

    @Bean
    @ConditionalOnMissingBean(ServerWallet.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "web3.dev.mock-wallet", name = "enabled", havingValue = "true")
    ServerWallet web3DevWallet(Web3Properties properties, ChainRegistry chains) {
        Web3Properties.MockWallet config = properties.getDev().getMockWallet();
        var rpc = chains.get(config.getChainId()).orElse(null);
        String key = config.getPrivateKey();
        return key == null || key.isBlank()
                ? DevWallet.anvilDefault(config.getChainId(), rpc)
                : new DevWallet(key, config.getChainId(), rpc);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
