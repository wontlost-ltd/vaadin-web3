package com.wontlost.web3.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.chain.FailoverJsonRpcTransport;
import com.wontlost.web3.chain.HttpJsonRpcTransport;
import com.wontlost.web3.chain.JsonRpcDialect;
import com.wontlost.web3.siws.InMemorySiwsChallengeStore;
import com.wontlost.web3.siws.SiwsChallengeStore;
import com.wontlost.web3.siws.SiwsVerifier;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaClusters;
import com.wontlost.web3.solana.SolanaCommitment;
import com.wontlost.web3.solana.SolanaRpcClient;
import com.wontlost.web3.solana.wallet.SolanaDevWallet;

class Web3SolanaAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Web3SolanaAutoConfiguration.class));

    @Test
    void createsEmptyClustersAndSharedSiwsDefaultsWithoutProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SolanaClusters.class)
                    .hasSingleBean(SiwsChallengeStore.class).hasSingleBean(SiwsVerifier.class)
                    .doesNotHaveBean(SolanaDevWallet.class).doesNotHaveBean(Clock.class);
            assertThat(context.getBean(SolanaClusters.class).get(SolanaCluster.DEVNET)).isEmpty();
            SiwsVerifier verifier = context.getBean(SiwsVerifier.class);
            SiwsChallengeStore store = context.getBean(SiwsChallengeStore.class);
            assertThat(store).isInstanceOf(InMemorySiwsChallengeStore.class);
            var challenge = verifier.issue("example.com", "https://example.com", "Sign in", SolanaCluster.DEVNET,
                    Duration.ofMinutes(1), List.of());
            assertThat(store.find(challenge.nonce())).contains(challenge);
        });
    }

    @Test
    void createsHttpClientForOneUrlAndUsesConfiguredCommitmentAndTimeout() throws Exception {
        runner.withPropertyValues("web3.solana.clusters.devnet.rpc-urls[0]=http://127.0.0.1:8899",
                "web3.solana.commitment=finalized", "web3.rpc.request-timeout=PT4S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SolanaRpcClient client = context.getBean(SolanaClusters.class).get(SolanaCluster.DEVNET).orElseThrow();
                    assertThat(field(client, "commitment")).isEqualTo(SolanaCommitment.FINALIZED);
                    Object transport = field(client, "transport");
                    assertThat(transport).isInstanceOf(HttpJsonRpcTransport.class);
                    assertThat(field(transport, "timeout")).isEqualTo(Duration.ofSeconds(4));
                });
    }

    @Test
    void createsSolanaFailoverForTwoOrderedUrls() throws Exception {
        runner.withPropertyValues("web3.solana.clusters.devnet.rpc-urls[0]=http://primary.example/rpc",
                "web3.solana.clusters.devnet.rpc-urls[1]=http://backup.example/rpc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Object transport = field(context.getBean(SolanaClusters.class)
                            .get(SolanaCluster.DEVNET).orElseThrow(), "transport");
                    assertThat(transport).isInstanceOf(FailoverJsonRpcTransport.class);
                    assertThat(field(transport, "dialect")).isEqualTo(JsonRpcDialect.SOLANA);
                    @SuppressWarnings("unchecked")
                    List<Object> endpoints = (List<Object>) field(transport, "endpoints");
                    assertThat(endpoints).hasSize(2);
                    assertThat(field(field(endpoints.get(0), "endpoint"), "id")).isEqualTo("solana-devnet-0");
                });
    }

    @Test
    void rejectsUnknownClusterKeyWithItsName() {
        runner.withPropertyValues("web3.solana.clusters.moonnet.rpc-urls[0]=http://localhost")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("moonnet");
                });
    }

    @Test
    void createsDeterministicDevelopmentWalletOnlyWhenEnabled() {
        String seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
        runner.withPropertyValues("web3.solana.dev-wallet.enabled=true", "web3.solana.dev-wallet.seed=" + seed)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SolanaDevWallet.class);
                    assertThat(context.getBean(SolanaDevWallet.class).address())
                            .isEqualTo("FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z");
                });
    }

    @Test
    void refusesDevelopmentWalletAtVaadinServiceInitInProduction() {
        runner.withPropertyValues("web3.solana.dev-wallet.enabled=true")
                .run(context -> {
                    VaadinContext vaadinContext = mock(VaadinContext.class);
                    VaadinService service = mock(VaadinService.class);
                    DeploymentConfiguration configuration = mock(DeploymentConfiguration.class);
                    ServiceInitEvent event = mock(ServiceInitEvent.class);
                    when(service.getContext()).thenReturn(vaadinContext);
                    when(service.getDeploymentConfiguration()).thenReturn(configuration);
                    when(configuration.isProductionMode()).thenReturn(true);
                    when(event.getSource()).thenReturn(service);
                    VaadinServiceInitListener listener = context.getBean("web3SolanaUiContextInitializer",
                            VaadinServiceInitListener.class);
                    assertThatThrownBy(() -> listener.serviceInit(event))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("web3.solana.dev-wallet.enabled");
                });
    }

    @Test
    void rejectsInvalidDevelopmentWalletSeed() {
        runner.withPropertyValues("web3.solana.dev-wallet.enabled=true", "web3.solana.dev-wallet.seed=abcd")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("64 hexadecimal characters");
                });
    }

    @Test
    void omitsWalletBeansWhenOptionalSolanaUiIsFilteredOut() {
        runner.withClassLoader(new FilteredClassLoader(com.wontlost.web3.solana.wallet.SiwsLogin.class))
                .withPropertyValues("web3.solana.dev-wallet.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SolanaClusters.class)
                        .doesNotHaveBean("web3SolanaDevWallet"));
    }

    @Test
    void mainConfigurationSignaturesDoNotReferenceOptionalWalletTypes() {
        for (java.lang.reflect.Method method : Web3SolanaAutoConfiguration.class.getDeclaredMethods()) {
            assertThat(java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName)
                    .filter(name -> name.startsWith("com.wontlost.web3.solana.wallet.")))
                    .as(method.toGenericString()).isEmpty();
            assertThat(method.getReturnType().getName()).as(method.toGenericString())
                    .doesNotStartWith("com.wontlost.web3.solana.wallet.");
        }
    }

    @Test
    void respectsUserBeansAndUsesUserClockForBothSiwsComponents() {
        Clock clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(1));
        SiwsChallengeStore store = new InMemorySiwsChallengeStore(clock);
        runner.withBean(Clock.class, () -> clock)
                .withBean(SiwsChallengeStore.class, () -> store)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Clock.class)).isSameAs(clock);
                    assertThat(context.getBean(SiwsChallengeStore.class)).isSameAs(store);
                    assertThat(field(store, "clock")).isSameAs(clock);
                    assertThat(field(context.getBean(SiwsVerifier.class), "clock")).isSameAs(clock);
                });
    }

    @Test
    void keepsApplicationProvidedClusters() {
        SolanaClusters custom = new SolanaClusters();
        runner.withBean(SolanaClusters.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SolanaClusters.class)).isSameAs(custom);
        });
    }

    @Test
    void closesAllAutoConfiguredClientsWhenContextCloses() {
        AtomicReference<SolanaClientLifecycle> lifecycle = new AtomicReference<>();
        runner.withPropertyValues("web3.solana.clusters.devnet.rpc-urls[0]=http://localhost:8899")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    lifecycle.set(context.getBean(SolanaClientLifecycle.class));
                    assertThat(lifecycle.get().closedClients()).isZero();
                });
        assertThat(lifecycle.get().closedClients()).isEqualTo(1);
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
