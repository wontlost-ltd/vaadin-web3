package com.wontlost.web3.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.nft.NftCollection;
import com.wontlost.web3.nft.NftOwnershipPage;
import com.wontlost.web3.nft.NftOwnershipSource;
import com.wontlost.web3.nft.RpcNftOwnershipSource;

class Web3NftAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Web3NftAutoConfiguration.class))
            .withUserConfiguration(NftPropertiesTestConfiguration.class);

    @Test
    void isDisabledByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(NftOwnershipSource.class));
    }

    @Test
    void registersConfiguredSourceAndCollectionListOnlyWhenEnabled() {
        runner.withPropertyValues("web3.nft.enabled=true", "web3.nft.max-concurrency=3",
                "web3.nft.queue-capacity=7",
                "web3.nft.collections[0].chain-id=31337",
                "web3.nft.collections[0].contract=0x0000000000000000000000000000000000000001",
                "web3.nft.collections[0].standard=ERC1155", "web3.nft.collections[0].token-ids[0]=42",
                "web3.nft.collections[0].ranges[0].first=100", "web3.nft.collections[0].ranges[0].last=101")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NftOwnershipSource.class)).isInstanceOf(RpcNftOwnershipSource.class);
                    assertThat(context.getBean(Web3Properties.class).getNft().getQueueCapacity()).isEqualTo(7);
                    assertThat(context).doesNotHaveBean(List.class);
                    NftCollections configuredCollections = context.getBean("web3NftCollections", NftCollections.class);
                    List<NftCollection> collections = configuredCollections.collections();
                    assertThat(collections).hasSize(1);
                    NftCollection collection = (NftCollection) collections.getFirst();
                    assertThat(collection.tokenIds()).containsExactly(BigInteger.valueOf(42));
                    assertThat(collection.tokenIdRanges()).hasSize(1);
                });
    }

    @Test
    void keepsAnApplicationProvidedSource() {
        NftOwnershipSource custom = (chainId, owner, collections, cursor, pageSize) ->
                new NftOwnershipPage(List.of(), null, 0, List.of());
        runner.withPropertyValues("web3.nft.enabled=true")
                .withBean(NftOwnershipSource.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NftOwnershipSource.class)).isSameAs(custom);
                    assertThat(context).doesNotHaveBean(RpcNftOwnershipSource.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(Web3Properties.class)
    static class NftPropertiesTestConfiguration {
        @Bean
        ChainRegistry nftTestChainRegistry() {
            return new ChainRegistry();
        }
    }

    @Test
    void keepsAnApplicationProvidedCollectionHolder() {
        NftCollections custom = new NftCollections(List.of());
        runner.withPropertyValues("web3.nft.enabled=true")
                .withBean(NftCollections.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NftCollections.class)).isSameAs(custom);
                });
    }

    @Test
    void rejectsNftResourceLimitsOutsideSupportedRangesAtStartup() {
        assertInvalidSetting("max-concurrency", "65", "web3.nft.max-concurrency must be between 1 and 64");
        assertInvalidSetting("batch-size", "501", "web3.nft.batch-size must be between 1 and 500");
        assertInvalidSetting("max-page-size", "501", "web3.nft.max-page-size must be between 1 and 500");
        assertInvalidSetting("max-token-ids", "10001", "web3.nft.max-token-ids must be between 1 and 10000");
        assertInvalidSetting("queue-capacity", "4097", "web3.nft.queue-capacity must be between 1 and 4096");
    }

    private void assertInvalidSetting(String name, String value, String expectedMessage) {
        runner.withPropertyValues("web3.nft.enabled=true", "web3.nft." + name + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(expectedMessage);
                });
    }
}
