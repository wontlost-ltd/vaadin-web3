package com.wontlost.web3.autoconfigure.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import com.wontlost.web3.autoconfigure.Web3Properties;

/** Optional Spring Security integration for SIWE. */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.security.web.context.SecurityContextRepository")
@ConditionalOnProperty(prefix = "web3.security.siwe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Web3SecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    org.springframework.security.web.context.SecurityContextRepository web3SecurityContextRepository() {
        return new org.springframework.security.web.context.HttpSessionSecurityContextRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    SiweSecurityBridge siweSecurityBridge(
            org.springframework.security.web.context.SecurityContextRepository repository,
            Web3Properties properties, ObjectProvider<SiweAuthoritiesResolver> resolvers,
            ApplicationEventPublisher eventPublisher) {
        return new SiweSecurityBridge(repository, properties.getSiwe().getAuthorities(),
                resolvers.orderedStream().toList(), eventPublisher);
    }

    /** 让 Web3SiweLoginConfigurer.configure(login) 自动接入认证桥，应用无需再单独调用 attach。 */
    @Bean
    com.wontlost.web3.autoconfigure.Web3SiweLoginCustomizer siweSecurityBridgeCustomizer(SiweSecurityBridge bridge) {
        return bridge::attach;
    }

    @Bean
    @ConditionalOnMissingBean
    Web3LogoutHandler web3LogoutHandler() { return new Web3LogoutHandler(); }
}
