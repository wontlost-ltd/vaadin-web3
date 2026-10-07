package com.wontlost.web3.autoconfigure;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.nft.NftCollection;
import com.wontlost.web3.nft.NftOwnershipSource;
import com.wontlost.web3.nft.NftStandard;
import com.wontlost.web3.nft.NftTokenIdRange;
import com.wontlost.web3.nft.RpcNftOwnershipSource;
import com.wontlost.web3.nft.NftMetadataFetcher;
import com.wontlost.web3.nft.NftMetadataOptions;
import com.wontlost.web3.nft.NftMetadataResolver;
import com.wontlost.web3.nft.RpcNftMetadataResolver;
import com.wontlost.web3.nft.ApacheNftMetadataFetcher;

/** 注册可选的 RPC NFT 所有权数据源。 */
@AutoConfiguration(after = Web3VaadinAutoConfiguration.class)
@ConditionalOnProperty(prefix = "web3.nft", name = "enabled", havingValue = "true")
public class Web3NftAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(NftMetadataOptions.class)
    NftMetadataOptions web3NftMetadataOptions(Web3Properties properties) {
        Web3Properties.Nft.Metadata config = properties.getNft().getMetadata();
        return new NftMetadataOptions(config.getIpfsGateways(), config.getMaxResponseBytes(),
                config.getMaxDataUriBytes(), config.getMaxUriLength(), config.getMaxRedirects(),
                config.getRequestTimeout(),
                config.getCacheCapacity(), config.getPositiveTtl(), config.getNegativeTtl(), config.getErrorTtl(),
                config.getMaxConcurrency(), java.util.Set.copyOf(config.getAllowedPorts()),
                config.getMaxAttributes(), config.getMaxTextLength());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(NftMetadataFetcher.class)
    NftMetadataFetcher web3NftMetadataFetcher(NftMetadataOptions options) {
        return new ApacheNftMetadataFetcher(options.allowedPorts());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(NftMetadataResolver.class)
    NftMetadataResolver web3NftMetadataResolver(ChainRegistry chains, NftMetadataFetcher fetcher,
            NftMetadataOptions options) {
        return new RpcNftMetadataResolver(chains, fetcher, options);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(NftOwnershipSource.class)
    RpcNftOwnershipSource web3NftOwnershipSource(ChainRegistry chains, Web3Properties properties) {
        Web3Properties.Nft config = properties.getNft();
        int queueCapacity = config.getQueueCapacity() == null
                ? config.getMaxConcurrency() * com.wontlost.web3.nft.NftQueryLimits.DEFAULT_QUEUE_MULTIPLIER
                : config.getQueueCapacity();
        return new RpcNftOwnershipSource(chains, config.getMaxConcurrency(), config.getMaxPageSize(),
                config.getBatchSize(), config.getMaxTokenIds(), queueCapacity);
    }

    @Bean("web3NftCollections")
    @ConditionalOnMissingBean(NftCollections.class)
    NftCollections web3NftCollections(Web3Properties properties) {
        List<NftCollection> collections = properties.getNft().getCollections().stream()
                .map(collection -> new NftCollection(collection.getChainId(),
                collection.getContract(), NftStandard.valueOf(collection.getStandard().toUpperCase(Locale.ROOT)),
                collection.isEnumerable(), collection.getTokenIds().stream().map(BigInteger::new).toList(),
                collection.getRanges().stream().map(range -> new NftTokenIdRange(new BigInteger(range.getFirst()),
                        new BigInteger(range.getLast()))).toList()))
                .toList();
        return new NftCollections(collections);
    }
}
