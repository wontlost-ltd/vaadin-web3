package com.wontlost.web3.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.wontlost.web3.ServerWallet;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.dev.DevWallet;
import com.wontlost.web3.pay.InMemoryPaymentLedger;
import com.wontlost.web3.pay.PaymentLedger;
import com.wontlost.web3.pay.PaymentVerifier;
import com.wontlost.web3.siwe.InMemoryNonceStore;
import com.wontlost.web3.siwe.NonceStore;

class Web3VaadinAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Web3VaadinAutoConfiguration.class,
                    Web3WalletConnectAutoConfiguration.class,
                    com.wontlost.web3.autoconfigure.security.Web3SecurityAutoConfiguration.class));

    @Test
    void createsDefaultBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChainRegistry.class).hasSingleBean(NonceStore.class)
                    .hasSingleBean(PaymentLedger.class).hasSingleBean(PaymentVerifier.class)
                    .hasSingleBean(com.vaadin.flow.server.VaadinServiceInitListener.class);
            assertThat(context.getBean(NonceStore.class)).isInstanceOf(InMemoryNonceStore.class);
            assertThat(context.getBean(PaymentLedger.class)).isInstanceOf(InMemoryPaymentLedger.class);
        });
    }

    @Test
    void bindsRpcCompatibilityAndModernKeysWinConflicts() {
        contextRunner.withPropertyValues("web3.rpc.1=https://old.example", "web3.chains.1.rpc-url=https://new.example",
                "web3.rpc.2=https://legacy.example", "web3.siwe.max-age=PT2M",
                "web3.siwe.authorities[0]=ROLE_MEMBER")
                .run(context -> {
                    ChainRegistry chains = context.getBean(ChainRegistry.class);
                    assertThat(chains.clients()).containsKeys(1L, 2L).hasSize(2);
                    Web3Properties properties = context.getBean(Web3Properties.class);
                    assertThat(properties.getSiwe().getMaxAge()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.getSiwe().getAuthorities()).containsExactly("ROLE_MEMBER");
                });
    }

    @Test
    void configuresFailoverOnlyForMultipleUrlsAndBindsRpcSettings() {
        contextRunner.withPropertyValues("web3.chains.10.rpc-urls[0]=https://one.example",
                "web3.chains.10.rpc-urls[1]=https://two.example", "web3.chains.11.rpc-url=https://single.example",
                "web3.rpc.failure-threshold=4", "web3.rpc.open-duration=PT20S",
                "web3.rpc.request-timeout=PT4S", "web3.rpc.lag-tolerance=6")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RpcTransportLifecycle lifecycle = context.getBean(RpcTransportLifecycle.class);
                    assertThat(lifecycle.failovers()).containsKey(10L).doesNotContainKey(11L);
                    var failover = lifecycle.failovers().get(10L);
                    assertThat(failover.healthSnapshot()).hasSize(2);
                    assertThat(failover.healthSnapshot().getFirst().id()).isEqualTo("https://one.example");
                    Web3RpcProperties rpc = context.getBean(Web3RpcProperties.class);
                    assertThat(rpc.getFailureThreshold()).isEqualTo(4);
                    assertThat(rpc.getOpenDuration()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(rpc.getRequestTimeout()).isEqualTo(Duration.ofSeconds(4));
                    assertThat(rpc.getLagTolerance()).isEqualTo(6);
                });
    }

    @Test
    void redactsCredentialsQueryFragmentAndKeyLikePathSegments() {
        assertThat(Web3RpcHealthAutoConfiguration.redact(
                "https://user:pass@eth-mainnet.g.alchemy.com/v2/abcdef123456789012345678?secret=x#frag"))
                .isEqualTo("https://eth-mainnet.g.alchemy.com/v2/***");
    }

    @Test
    void closesTrackedTransportsAtLifecycleEnd() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        RpcTransportLifecycle lifecycle = new RpcTransportLifecycle(java.net.http.HttpClient.newHttpClient());
        lifecycle.track(new com.wontlost.web3.chain.JsonRpcTransport() {
            @Override public String send(String request) { return "{}"; }
            @Override public void close() { closed.set(true); }
        });
        lifecycle.close();
        assertThat(closed).isTrue();
    }

    @Test
    void rpcHealthAggregatesUnknownUpAndDown() throws Exception {
        RpcTransportLifecycle emptyLifecycle = new RpcTransportLifecycle(java.net.http.HttpClient.newHttpClient());
        var emptyHealth = new Web3RpcHealthAutoConfiguration().web3RpcHealthIndicator(new ChainRegistry(), emptyLifecycle);
        assertThat(emptyHealth.health().getStatus().getCode()).isEqualTo("UNKNOWN");

        var partial = healthFixture(true);
        assertThat(partial.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(partial.health().getDetails()).containsKey("1");

        var allOpen = healthFixture(false);
        assertThat(allOpen.health().getStatus().getCode()).isEqualTo("DOWN");
        String details = allOpen.health().getDetails().toString();
        assertThat(details).contains("eth-mainnet.example/v2/***").doesNotContain("abcdef123456789012345678");
    }

    @Test
    void rpcHealthIsDownWhenAnyChainIsFullyDownAndMarksSingleEndpointsUnmonitored() throws Exception {
        var ok = (com.wontlost.web3.chain.JsonRpcTransport) request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1\"}";
        var failing = (com.wontlost.web3.chain.JsonRpcTransport) request -> { throw new java.io.IOException("offline"); };
        var config = new com.wontlost.web3.chain.FailoverJsonRpcTransport.Config(1, Duration.ofSeconds(30), 0);
        var healthyEndpoints = java.util.List.of(
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint("https://a.example", ok),
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint("https://b.example", ok));
        var downEndpoints = java.util.List.of(
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint("https://c.example", failing),
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint("https://d.example", failing));
        var healthy = new com.wontlost.web3.chain.FailoverJsonRpcTransport(healthyEndpoints, config);
        var down = new com.wontlost.web3.chain.FailoverJsonRpcTransport(downEndpoints, config);
        try { down.send("{}"); } catch (java.io.IOException ignored) { }

        RpcTransportLifecycle lifecycle = new RpcTransportLifecycle(java.net.http.HttpClient.newHttpClient());
        lifecycle.track(1, healthy);
        lifecycle.endpoints(1, java.util.List.of("https://a.example", "https://b.example"));
        lifecycle.track(10, down);
        lifecycle.endpoints(10, java.util.List.of("https://c.example", "https://d.example"));
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(healthy));
        chains.register(10, new com.wontlost.web3.chain.EthRpcClient(down));
        var indicator = new Web3RpcHealthAutoConfiguration().web3RpcHealthIndicator(chains, lifecycle);
        assertThat(indicator.health().getStatus().getCode())
                .as("one chain fully down means sign-in and payments on it cannot be served").isEqualTo("DOWN");

        RpcTransportLifecycle single = new RpcTransportLifecycle(java.net.http.HttpClient.newHttpClient());
        single.endpoints(137, java.util.List.of("https://polygon.example"));
        ChainRegistry singleChains = new ChainRegistry();
        singleChains.register(137, "https://polygon.example");
        var singleHealth = new Web3RpcHealthAutoConfiguration().web3RpcHealthIndicator(singleChains, single).health();
        assertThat(singleHealth.getStatus().getCode()).isEqualTo("UP");
        assertThat(singleHealth.getDetails().toString()).contains("UNMONITORED");
    }

    private static org.springframework.boot.health.contributor.HealthIndicator healthFixture(boolean oneHealthy)
            throws Exception {
        var failing = (com.wontlost.web3.chain.JsonRpcTransport) request -> { throw new java.io.IOException("offline"); };
        var endpoints = new java.util.ArrayList<com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint>();
        endpoints.add(new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint(
                "https://user:pass@eth-mainnet.example/v2/abcdef123456789012345678?secret=x#frag", failing));
        if (oneHealthy) endpoints.add(new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint(
                "https://backup.example/rpc", request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1\"}"));
        else endpoints.add(new com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint(
                "https://backup.example/rpc", failing));
        var failover = new com.wontlost.web3.chain.FailoverJsonRpcTransport(endpoints,
                new com.wontlost.web3.chain.FailoverJsonRpcTransport.Config(1, Duration.ofSeconds(30), 0));
        RpcTransportLifecycle lifecycle = new RpcTransportLifecycle(java.net.http.HttpClient.newHttpClient());
        lifecycle.track(1, failover);
        lifecycle.endpoints(1, endpoints.stream().map(com.wontlost.web3.chain.FailoverJsonRpcTransport.Endpoint::id).toList());
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(failover));
        try { failover.send("{}"); } catch (java.io.IOException ignored) { }
        return new Web3RpcHealthAutoConfiguration().web3RpcHealthIndicator(chains, lifecycle);
    }

    @Test
    void userBeansOverrideDefaults() {
        ChainRegistry registry = new ChainRegistry();
        NonceStore nonceStore = mock(NonceStore.class);
        PaymentLedger ledger = mock(PaymentLedger.class);
        contextRunner.withBean(ChainRegistry.class, () -> registry).withBean(NonceStore.class, () -> nonceStore)
                .withBean(PaymentLedger.class, () -> ledger).run(context -> {
                    assertThat(context.getBean(ChainRegistry.class)).isSameAs(registry);
                    assertThat(context.getBean(NonceStore.class)).isSameAs(nonceStore);
                    assertThat(context.getBean(PaymentLedger.class)).isSameAs(ledger);
                });
    }

    @Test
    void walletConnectFactoryUsesConfiguredProjectId() {
        contextRunner.withPropertyValues("web3.walletconnect.project-id=test-project")
                .run(context -> {
                    assertThat(context).hasSingleBean(Web3WalletConnectFactory.class);
                    assertThat(context.getBean(Web3WalletConnectFactory.class).create().getElement()
                            .getProperty("projectId")).isEqualTo("test-project");
                });
    }

    @Test
    void siweConfigurerAppliesConfiguredChallengeExpectations() throws Exception {
        contextRunner.withPropertyValues("web3.siwe.domain=example.test", "web3.siwe.uri=https://example.test/login",
                "web3.siwe.allowed-chain-ids[0]=31337")
                .run(context -> {
                    var login = new com.wontlost.web3.siwe.SiweLogin(new InMemoryNonceStore());
                    assertThat(context.getBean(Web3SiweLoginConfigurer.class).configure(login)).isSameAs(login);
                    assertThat(field(login, "domain")).isEqualTo("example.test");
                    assertThat(field(login, "uri")).isEqualTo("https://example.test/login");
                    assertThat(field(login, "allowedChainIds")).isEqualTo(java.util.Set.of(31337L));
                });
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void optionalIntegrationsCanBeAbsent() {
        contextRunner.withClassLoader(new FilteredClassLoader("com.wontlost.web3.onramp",
                "com.wontlost.web3.walletconnect", "org.springframework.security"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChainRegistry.class);
                });
    }

    @Test
    void devWalletIsOffByDefaultAndCanBeEnabled() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(ServerWallet.class));
        contextRunner.withPropertyValues("web3.dev.mock-wallet.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ServerWallet.class);
            assertThat(context.getBean(ServerWallet.class)).isInstanceOf(DevWallet.class);
            assertThat(context.getBean(ServerWallet.class).chainId()).isEqualTo("0x7a69");
        });
    }

    @Test
    void rejectsDevWalletInProductionMode() {
        contextRunner.withPropertyValues("web3.dev.mock-wallet.enabled=true").run(context -> {
            VaadinService service = mock(VaadinService.class);
            DeploymentConfiguration deployment = mock(DeploymentConfiguration.class);
            when(deployment.isProductionMode()).thenReturn(true);
            when(service.getDeploymentConfiguration()).thenReturn(deployment);
            when(service.getContext()).thenReturn(mock(com.vaadin.flow.server.VaadinContext.class));
            ServiceInitEvent event = new ServiceInitEvent(service);
            assertThatThrownBy(() -> context.getBean(com.vaadin.flow.server.VaadinServiceInitListener.class)
                    .serviceInit(event)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not be enabled in Vaadin production mode");
        });
    }

    @Test
    void rejectsApplicationDefinedServerWalletInProductionModeEvenWithoutTheProperty() {
        contextRunner.withBean(ServerWallet.class, () -> com.wontlost.web3.dev.DevWallet.anvilDefault(31337, null))
                .run(context -> {
                    VaadinService service = mock(VaadinService.class);
                    DeploymentConfiguration deployment = mock(DeploymentConfiguration.class);
                    when(deployment.isProductionMode()).thenReturn(true);
                    when(service.getDeploymentConfiguration()).thenReturn(deployment);
                    when(service.getContext()).thenReturn(mock(com.vaadin.flow.server.VaadinContext.class));
                    assertThatThrownBy(() -> context.getBean("web3VaadinContextInitializer",
                            com.vaadin.flow.server.VaadinServiceInitListener.class)
                            .serviceInit(new ServiceInitEvent(service)))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("must not be registered in Vaadin production mode");
                });
    }

    @Test
    void configurerAppliesMaxAgeAndAttachesSecurityBridgeWhenSecurityIsPresent() {
        contextRunner.withPropertyValues("web3.siwe.max-age=PT2M").run(context -> {
            com.wontlost.web3.siwe.SiweLogin login =
                    new com.wontlost.web3.siwe.SiweLogin(new com.wontlost.web3.siwe.InMemoryNonceStore());
            context.getBean(Web3SiweLoginConfigurer.class).configure(login);
            java.lang.reflect.Field maxAge = com.wontlost.web3.siwe.SiweLogin.class.getDeclaredField("maxAge");
            maxAge.setAccessible(true);
            org.junit.jupiter.api.Assertions.assertEquals(java.time.Duration.ofMinutes(2), maxAge.get(login));
            org.junit.jupiter.api.Assertions.assertTrue(com.vaadin.flow.component.ComponentUtil.hasEventListener(
                    login, com.wontlost.web3.siwe.SiweLogin.SignedInEvent.class),
                    "configure() must attach the Spring Security bridge without a separate attach() call");
        });
    }

    @Test
    void configurerAttachesNothingWithoutSpringSecurity() {
        contextRunner.withClassLoader(new FilteredClassLoader("org.springframework.security")).run(context -> {
            com.wontlost.web3.siwe.SiweLogin login =
                    new com.wontlost.web3.siwe.SiweLogin(new com.wontlost.web3.siwe.InMemoryNonceStore());
            context.getBean(Web3SiweLoginConfigurer.class).configure(login);
            org.junit.jupiter.api.Assertions.assertFalse(com.vaadin.flow.component.ComponentUtil.hasEventListener(
                    login, com.wontlost.web3.siwe.SiweLogin.SignedInEvent.class));
        });
    }

    @Test
    void contextInitializerSurvivesOtherVaadinServiceInitListenerBeans() {
        contextRunner.withBean("someOtherListener", com.vaadin.flow.server.VaadinServiceInitListener.class,
                () -> event -> { }).run(context -> {
                    assertThat(context).hasBean("web3VaadinContextInitializer");
                    assertThat(context).hasBean("someOtherListener");
                });
    }
}
