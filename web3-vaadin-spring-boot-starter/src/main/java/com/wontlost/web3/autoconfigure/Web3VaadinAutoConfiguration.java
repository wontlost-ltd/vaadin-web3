package com.wontlost.web3.autoconfigure;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.ServerWallet;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.dev.DevWallet;
import com.wontlost.web3.monitor.PaymentMonitorClient;
import com.wontlost.web3.onramp.CoinbaseOnramp;
import com.wontlost.web3.onramp.MoonPayOnramp;
import com.wontlost.web3.onramp.OnrampProvider;
import com.wontlost.web3.onramp.OnrampProviders;
import com.wontlost.web3.onramp.TransakOnramp;
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
@EnableConfigurationProperties(Web3Properties.class)
public class Web3VaadinAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(Web3VaadinAutoConfiguration.class);
    private static final AtomicBoolean LEGACY_RPC_WARNED = new AtomicBoolean();

    @Bean
    @ConditionalOnMissingBean
    ChainRegistry web3ChainRegistry(Web3Properties properties) {
        ChainRegistry registry = new ChainRegistry();
        Map<Long, Web3Properties.Chain> modern = properties.getChains();
        properties.getRpc().forEach((id, url) -> {
            if (LEGACY_RPC_WARNED.compareAndSet(false, true)) {
                if (modern.containsKey(id)) {
                    LOGGER.warn("Both web3.chains.{}.rpc-url and deprecated web3.rpc.{} are set; the new property takes precedence",
                            id, id);
                } else {
                    LOGGER.warn("web3.rpc.<id> is deprecated; use web3.chains.<id>.rpc-url");
                }
            }
        });
        modern.forEach((id, chain) -> addChain(registry, id, chain == null ? null : chain.getRpcUrl()));
        properties.getRpc().forEach((id, url) -> {
            if (!modern.containsKey(id)) addChain(registry, id, url);
        });
        return registry;
    }

    private static void addChain(ChainRegistry registry, long chainId, String url) {
        if (url != null && !url.isBlank()) registry.register(chainId, url);
    }

    @Bean
    @ConditionalOnMissingBean
    NonceStore web3NonceStore(Web3Properties properties) {
        return new InMemoryNonceStore(positive(properties.getSiwe().getNonceTtl(), Duration.ofMinutes(5)));
    }

    @Bean
    @ConditionalOnMissingBean
    Web3SiweLoginConfigurer web3SiweLoginConfigurer(Web3Properties properties,
            org.springframework.beans.factory.ObjectProvider<Web3SiweLoginCustomizer> customizers) {
        return new Web3SiweLoginConfigurer(properties.getSiwe(), customizers.orderedStream().toList());
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

    @Bean
    @ConditionalOnClass(OnrampProvider.class)
    @ConditionalOnMissingBean(OnrampProvider.class)
    OnrampProvider web3OnrampProvider(Web3Properties properties) {
        var p = properties.getOnramp();
        if (!p.getMoonpay().getPublishableKey().isBlank() && !p.getMoonpay().getSecretKey().isBlank()) {
            return new MoonPayOnramp(p.getMoonpay().getPublishableKey(), p.getMoonpay().getSecretKey());
        }
        if (!p.getTransak().getApiKey().isBlank() && !p.getTransak().getApiSecret().isBlank()) {
            return new TransakOnramp(p.getTransak().getApiKey(), p.getTransak().getApiSecret(),
                    p.getTransak().getReferrerDomain(), p.getTransak().isStaging());
        }
        if (!p.getCoinbase().getKeyId().isBlank() && !p.getCoinbase().getKeySecret().isBlank()) {
            return new CoinbaseOnramp(p.getCoinbase().getKeyId(), p.getCoinbase().getKeySecret());
        }
        return null;
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
            ObjectProvider<OnrampProvider> onramps,
            ObjectProvider<PaymentMonitorClient> monitor,
            ObjectProvider<Web3Properties> properties) {
        return event -> initialize(event, chains, nonces, screening, wallets, onramps, monitor, properties);
    }

    private static void initialize(ServiceInitEvent event, ObjectProvider<ChainRegistry> chains,
            ObjectProvider<NonceStore> nonces, ObjectProvider<AddressScreening> screening,
            ObjectProvider<ServerWallet> wallets, ObjectProvider<OnrampProvider> onramps,
            ObjectProvider<PaymentMonitorClient> monitor, ObjectProvider<Web3Properties> properties) {
        Web3Properties config = properties.getIfAvailable(Web3Properties::new);
        ServerWallet wallet = wallets.getIfAvailable();
        if (config.getDev().getMockWallet().isEnabled()) {
            if (event.getSource().getDeploymentConfiguration().isProductionMode()) {
                throw new IllegalStateException("web3.dev.mock-wallet.enabled must not be enabled in Vaadin production mode");
            }
            if (wallet instanceof DevWallet devWallet) {
                LOGGER.warn("*** DEVELOPMENT WALLET ENABLED: address={}, chainId={}; use only on a valueless local chain ***",
                        devWallet.accounts().getFirst(), devWallet.chainId());
            }
        }
        var context = event.getSource().getContext();
        ChainRegistry chainRegistry = chains.getIfAvailable();
        if (chainRegistry != null) context.setAttribute(ChainRegistry.class, chainRegistry);
        NonceStore nonceStore = nonces.getIfAvailable();
        if (nonceStore != null) SiweLogin.registerNonceStore(context, nonceStore);
        AddressScreening addressScreening = screening.getIfAvailable();
        if (addressScreening != null) AddressScreening.register(context, addressScreening);
        if (wallet != null) ServerWallet.register(context, wallet);
        OnrampProvider onramp = onramps.getIfAvailable();
        if (onramp != null) OnrampProviders.register(context, onramp);
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
