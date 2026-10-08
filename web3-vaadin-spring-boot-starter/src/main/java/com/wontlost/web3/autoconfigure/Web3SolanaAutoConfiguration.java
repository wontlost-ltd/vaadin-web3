package com.wontlost.web3.autoconfigure;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.chain.FailoverJsonRpcTransport;
import com.wontlost.web3.chain.HttpJsonRpcTransport;
import com.wontlost.web3.chain.JsonRpcDialect;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.siws.InMemorySiwsChallengeStore;
import com.wontlost.web3.siws.SiwsChallengeStore;
import com.wontlost.web3.siws.SiwsVerifier;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaClusters;
import com.wontlost.web3.solana.SolanaBackgroundExecutor;
import com.wontlost.web3.solana.SolanaCommitment;
import com.wontlost.web3.solana.SolanaRpcClient;

/** Default Solana RPC, SIWS, and Vaadin context configuration. */
@AutoConfiguration
@EnableConfigurationProperties({Web3SolanaProperties.class, Web3RpcProperties.class})
public class Web3SolanaAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(Web3SolanaAutoConfiguration.class);
    private static final AtomicInteger SOLANA_THREAD_NUMBER = new AtomicInteger();

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SolanaBackgroundExecutor web3SolanaBackgroundExecutor(Web3SolanaProperties properties) {
        Web3SolanaProperties.Background settings = properties.getBackground();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(settings.getMaxThreads(), settings.getMaxThreads(),
                60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(settings.getQueueCapacity()), task -> {
                    Thread thread = new Thread(task, "web3-solana-" + SOLANA_THREAD_NUMBER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return SolanaBackgroundExecutor.managed(pool);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SolanaClientLifecycle web3SolanaClientLifecycle() { return new SolanaClientLifecycle(); }

    @Bean
    @ConditionalOnMissingBean
    SolanaClusters web3SolanaClusters(Web3SolanaProperties properties, Web3RpcProperties rpc,
            SolanaClientLifecycle lifecycle) {
        SolanaClusters clusters = new SolanaClusters();
        SolanaCommitment commitment = commitment(properties.getCommitment());
        Duration timeout = positive(rpc.getRequestTimeout(), Duration.ofSeconds(10));
        for (Map.Entry<String, Web3SolanaProperties.Cluster> entry : properties.getClusters().entrySet()) {
            SolanaCluster cluster = cluster(entry.getKey());
            List<String> configuredUrls = entry.getValue() == null ? null : entry.getValue().getRpcUrls();
            List<String> urls = configuredUrls == null ? List.of()
                    : configuredUrls.stream().filter(Objects::nonNull).map(String::trim)
                            .filter(url -> !url.isEmpty()).toList();
            if (urls.isEmpty()) continue;
            JsonRpcTransport transport;
            if (urls.size() == 1) {
                transport = new HttpJsonRpcTransport(urls.getFirst(), timeout);
            } else {
                List<FailoverJsonRpcTransport.Endpoint> endpoints = new ArrayList<>();
                for (int index = 0; index < urls.size(); index++) {
                    endpoints.add(new FailoverJsonRpcTransport.Endpoint("solana-"
                            + cluster.name().toLowerCase(Locale.ROOT) + "-" + index,
                            new HttpJsonRpcTransport(urls.get(index), timeout)));
                }
                transport = new FailoverJsonRpcTransport(endpoints,
                        new FailoverJsonRpcTransport.Config(rpc.getFailureThreshold(),
                                positive(rpc.getOpenDuration(), Duration.ofSeconds(15)), rpc.getLagTolerance()),
                        JsonRpcDialect.SOLANA);
            }
            clusters.register(cluster, lifecycle.track(new SolanaRpcClient(transport, commitment)));
        }
        return clusters;
    }

    @Bean
    @ConditionalOnMissingBean
    SiwsChallengeStore web3SiwsChallengeStore(ObjectProvider<Clock> clocks) {
        return new InMemorySiwsChallengeStore(clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    SiwsVerifier web3SiwsVerifier(SiwsChallengeStore store, ObjectProvider<Clock> clocks) {
        return new SiwsVerifier(store, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(name = "web3SolanaContextInitializer")
    VaadinServiceInitListener web3SolanaContextInitializer(ObjectProvider<SolanaClusters> clusters,
            ObjectProvider<SolanaBackgroundExecutor> backgroundExecutor) {
        return event -> {
            var context = event.getSource().getContext();
            context.setAttribute(SolanaClusters.class, clusters.getObject());
            SolanaBackgroundExecutor executor = backgroundExecutor.getIfAvailable();
            if (executor != null) SolanaBackgroundExecutor.register(context, executor);
        };
    }

    private static SolanaCluster cluster(String key) {
        try {
            return SolanaCluster.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unknown Solana cluster key '" + key
                    + "'; supported keys are mainnet, devnet, testnet, and localnet", exception);
        }
    }

    private static SolanaCommitment commitment(String value) {
        try {
            return SolanaCommitment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("web3.solana.commitment must be processed, confirmed, or finalized");
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    /** Optional UI integration stays isolated so the starter works without web3-vaadin-solana. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.wontlost.web3.solana.wallet.SiwsLogin")
    static class SolanaUiConfiguration {
        private static final Logger LOGGER = LoggerFactory.getLogger(SolanaUiConfiguration.class);

        @Bean
        @ConditionalOnMissingBean(name = "web3SolanaUiContextInitializer")
        VaadinServiceInitListener web3SolanaUiContextInitializer(ObjectProvider<SiwsVerifier> verifier,
                ObjectProvider<Web3SolanaProperties> properties,
                ObjectProvider<com.wontlost.web3.solana.wallet.SolanaServerWallet> wallet) {
            return event -> {
                var context = event.getSource().getContext();
                com.wontlost.web3.solana.wallet.SiwsLogin.registerVerifier(context, verifier.getObject());
                var serverWallet = wallet.getIfAvailable();
                if (serverWallet == null) return;
                boolean production = event.getSource().getDeploymentConfiguration().isProductionMode();
                if (production) {
                    if (properties.getObject().getDevWallet().isEnabled()) {
                        throw new IllegalStateException(
                                "web3.solana.dev-wallet.enabled must not be enabled in Vaadin production mode");
                    }
                    throw new IllegalStateException("A SolanaServerWallet bean (" + serverWallet.getClass().getName()
                            + ") must not be registered in Vaadin production mode; server wallets are for development only");
                }
                com.wontlost.web3.solana.wallet.SolanaServerWallet.register(context, serverWallet);
                if (serverWallet instanceof com.wontlost.web3.solana.wallet.SolanaDevWallet) {
                    LOGGER.warn("*** SOLANA DEVELOPMENT WALLET ENABLED: address={}, cluster={}; local development only ***",
                            serverWallet.address(), serverWallet.cluster().chainId());
                }
            };
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "com.wontlost.web3.solana.wallet.SolanaDevWallet")
        @ConditionalOnProperty(prefix = "web3.solana.dev-wallet", name = "enabled", havingValue = "true")
        static class DevWalletConfiguration {
            @Bean
            @ConditionalOnMissingBean(com.wontlost.web3.solana.wallet.SolanaServerWallet.class)
            com.wontlost.web3.solana.wallet.SolanaDevWallet web3SolanaDevWallet(Web3SolanaProperties properties,
                    SolanaClusters clusters) {
                Web3SolanaProperties.DevWallet config = properties.getDevWallet();
                SolanaCluster cluster = cluster(config.getCluster());
                SolanaRpcClient client = clusters.get(cluster).orElse(null);
                String seed = config.getSeed();
                if (seed == null || seed.isBlank()) {
                    return com.wontlost.web3.solana.wallet.SolanaDevWallet.random(cluster, client);
                }
                if (!seed.matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalArgumentException("web3.solana.dev-wallet.seed must contain exactly 64 hexadecimal characters");
                }
                return new com.wontlost.web3.solana.wallet.SolanaDevWallet(
                        java.util.HexFormat.of().parseHex(seed), cluster, client);
            }
        }
    }
}
