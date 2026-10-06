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
