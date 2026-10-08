package com.wontlost.web3.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.siwe.NonceStore;

/**
 * 由独立的 surefire 执行运行，onramp、walletconnect 与 Spring Security 的 jar 被真正移出 classpath——
 * 与只引入 starter 的使用者应用一致。FilteredClassLoader 无法发现"主配置类引用了可选模块类型"这类问题。
 */
class StarterWithoutOptionalModulesTest {

    @Test void optionalModulesAreReallyAbsent() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.wontlost.web3.onramp.OnrampProvider"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.wontlost.web3.walletconnect.WalletConnect"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.wontlost.web3.solana.wallet.SiwsLogin"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.security.core.context.SecurityContext"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.boot.health.contributor.HealthIndicator"));
    }

    @Test void starterStartsWithoutOptionalModules() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Web3VaadinAutoConfiguration.class,
                        Web3SolanaAutoConfiguration.class))
                .withConfiguration(AutoConfigurations.of(Web3OnrampAutoConfiguration.class,
                        Web3WalletConnectAutoConfiguration.class,
                        com.wontlost.web3.autoconfigure.security.Web3SecurityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChainRegistry.class).hasSingleBean(NonceStore.class);
                    assertThat(context).hasSingleBean(com.wontlost.web3.solana.SolanaClusters.class);
                    assertThat(context).hasBean("web3VaadinContextInitializer");
                    assertThat(context).doesNotHaveBean("web3OnrampProvider");
                });
    }

    @Test void actuatorHealthConfigurationIsSkippedWhenItsApiIsAbsent() throws Exception {
        Class<?> healthConfiguration = Class.forName(
                "com.wontlost.web3.autoconfigure.Web3RpcHealthAutoConfiguration");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Class configurationType = healthConfiguration;
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(configurationType)).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("web3RpcHealthIndicator");
        });
    }
}
