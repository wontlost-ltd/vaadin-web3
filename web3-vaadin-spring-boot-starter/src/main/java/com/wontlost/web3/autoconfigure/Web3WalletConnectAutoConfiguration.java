package com.wontlost.web3.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Optional factory integration for the WalletConnect UI component. */
@AutoConfiguration
@ConditionalOnClass(name = "com.wontlost.web3.walletconnect.WalletConnect")
@ConditionalOnProperty(prefix = "web3.walletconnect", name = "project-id")
public class Web3WalletConnectAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    Web3WalletConnectFactory web3WalletConnectFactory(Web3Properties properties) {
        return new Web3WalletConnectFactory(properties.getWalletconnect().getProjectId());
    }
}
