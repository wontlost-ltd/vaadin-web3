package com.wontlost.web3.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.wontlost.web3.x402.facilitator.HttpFacilitatorClient;
import com.wontlost.web3.x402.payment.FacilitatorClient;
import com.wontlost.web3.x402.payment.X402PaymentService;
import com.wontlost.web3.x402.protocol.JacksonX402Codec;
import com.wontlost.web3.x402.store.InMemoryPaidResourceStore;
import com.wontlost.web3.x402.store.PaidResourceStore;

class X402AutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(X402AutoConfiguration.class));

    @Test void disabledByDefaultAndDoesNotCreateX402Beans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PaidResourceStore.class);
            assertThat(context).doesNotHaveBean(X402PaymentService.class);
        });
    }

    @Test void enabledRequiresOriginAndFacilitatorAndCreatesDefaultBeans() {
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example",
                "web3.x402.facilitator.api-key=never-log-this", "web3.x402.facilitator.request-timeout=PT3S",
                "web3.x402.valid-after-skew=PT45S",
                "web3.x402.protocol.max-header-bytes=32768", "web3.x402.allowed-chain-ids[0]=84532",
                "web3.x402.allowed-assets[0]=0x0000000000000000000000000000000000000001")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PaidResourceStore.class);
                    assertThat(context.getBean(PaidResourceStore.class)).isInstanceOf(InMemoryPaidResourceStore.class);
                    assertThat(context.getBean(com.wontlost.web3.x402.protocol.X402Codec.class)).isInstanceOf(JacksonX402Codec.class);
                    assertThat(context.getBean(FacilitatorClient.class)).isInstanceOf(HttpFacilitatorClient.class);
                    assertThat(context).hasSingleBean(X402PaymentService.class);
                    Web3Properties.X402 x402 = context.getBean(Web3Properties.class).getX402();
                    assertThat(x402.getFacilitator().getApiKey()).isEqualTo("never-log-this");
                    assertThat(x402.getFacilitator().getRequestTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
                    assertThat(x402.getProtocol().getMaxHeaderBytes()).isEqualTo(32768);
                    assertThat(x402.getValidAfterSkew()).isEqualTo(java.time.Duration.ofSeconds(45));
                    assertThat(x402.getReconcileInterval()).isEqualTo(java.time.Duration.ZERO);
                    assertThat(x402.getReconcileConfirmations()).isEqualTo(3);
                    assertThat(x402.getAllowedChainIds()).containsExactly(84532L);
                    assertThat(x402.toString()).doesNotContain("never-log-this");
                });
    }

    @Test void enabledRejectsMissingBaseUrlAndInvalidOrigin() {
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example/path",
                "web3.x402.facilitator.base-url=https://facilitator.example")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString()).contains("origin");
                });
    }

    @Test void validAfterSkewDefaultsToTenMinutesAndRejectsNegativeValues() {
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Web3Properties.class).getX402().getValidAfterSkew())
                            .isEqualTo(java.time.Duration.ofSeconds(600));
                    assertThat(context.getBean(Web3Properties.class).getX402().getReconcileInterval())
                            .isEqualTo(java.time.Duration.ZERO);
                });
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example", "web3.x402.valid-after-skew=PT-1S")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example", "web3.x402.reconcile-interval=PT1M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Web3Properties.class).getX402().getReconcileInterval())
                            .isEqualTo(java.time.Duration.ofMinutes(1));
                });
    }

    @Test void reconcileConfirmationsDefaultsToThreeAllowsZeroAndRejectsNegativeValues() {
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example", "web3.x402.reconcile-confirmations=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Web3Properties.class).getX402().getReconcileConfirmations()).isZero();
                });
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example", "web3.x402.reconcile-confirmations=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString()).contains("web3.x402.reconcile-confirmations");
                });
    }

    @Test void rejectsInvalidConfiguredCaip2NetworkAtStartup() {
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example", "web3.x402.allowed-networks[0]=ethereum:1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().toString()).contains("web3.x402.allowed-networks")
                            .contains("CAIP-2");
                });
    }

    @Test void productionModeRejectsDefaultInMemoryStoreUnlessExplicitlyAllowed() {
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaidResourceStore.class)).isInstanceOf(InMemoryPaidResourceStore.class);
                    assertThatThrownBy(() -> initializeProduction(context))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("persistent PaidResourceStore");
                });
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                "web3.x402.facilitator.base-url=https://facilitator.example", "web3.x402.allow-in-memory-store=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> initializeProduction(context));
                });
    }

    @Test void honorsApplicationProvidedBeansAndRejectsLocalFacilitatorInProductionMode() {
        runner.withPropertyValues("web3.x402.enabled=true", "web3.x402.origin=https://merchant.example",
                        "web3.x402.facilitator.base-url=https://facilitator.example")
                .withBean(PaidResourceStore.class, InMemoryPaidResourceStore::new)
                .withBean(FacilitatorClient.class, () -> new StubFacilitator())
                .withBean("devLocalFacilitator", LocalFacilitator.class, LocalFacilitator::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(FacilitatorClient.class)).hasSize(1);
                    VaadinService service = mock(VaadinService.class);
                    DeploymentConfiguration deployment = mock(DeploymentConfiguration.class);
                    when(deployment.isProductionMode()).thenReturn(true);
                    when(service.getDeploymentConfiguration()).thenReturn(deployment);
                    when(service.getContext()).thenReturn(mock(com.vaadin.flow.server.VaadinContext.class));
                    assertThatThrownBy(() -> context.getBean("x402VaadinProductionModeGuard",
                            com.vaadin.flow.server.VaadinServiceInitListener.class).serviceInit(new ServiceInitEvent(service)))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("LocalFacilitator");
                });
    }

    private static final class LocalFacilitator { }
    private static void initializeProduction(org.springframework.context.ApplicationContext context) {
        VaadinService service = mock(VaadinService.class);
        DeploymentConfiguration deployment = mock(DeploymentConfiguration.class);
        when(deployment.isProductionMode()).thenReturn(true);
        when(service.getDeploymentConfiguration()).thenReturn(deployment);
        when(service.getContext()).thenReturn(mock(com.vaadin.flow.server.VaadinContext.class));
        context.getBean("x402VaadinProductionModeGuard", com.vaadin.flow.server.VaadinServiceInitListener.class)
                .serviceInit(new ServiceInitEvent(service));
    }
    private static final class StubFacilitator implements FacilitatorClient {
        @Override public com.wontlost.web3.x402.payment.SupportedResponse supported() { return new com.wontlost.web3.x402.payment.SupportedResponse(2, java.util.List.of()); }
        @Override public com.wontlost.web3.x402.payment.VerifyResult verify(com.wontlost.web3.x402.protocol.PaymentPayload p, com.wontlost.web3.x402.protocol.PaymentRequirements r) { return new com.wontlost.web3.x402.payment.VerifyResult(false, "invalid", null); }
        @Override public com.wontlost.web3.x402.payment.SettlementResult settle(com.wontlost.web3.x402.protocol.PaymentPayload p, com.wontlost.web3.x402.protocol.PaymentRequirements r) { return new com.wontlost.web3.x402.payment.SettlementResult(com.wontlost.web3.x402.payment.SettlementState.REJECTED, null, "invalid", null); }
    }
}
