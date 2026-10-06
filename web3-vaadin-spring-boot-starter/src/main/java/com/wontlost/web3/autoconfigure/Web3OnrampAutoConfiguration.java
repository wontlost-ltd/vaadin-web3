package com.wontlost.web3.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.onramp.CoinbaseOnramp;
import com.wontlost.web3.onramp.MoonPayOnramp;
import com.wontlost.web3.onramp.OnrampProvider;
import com.wontlost.web3.onramp.OnrampProviders;
import com.wontlost.web3.onramp.TransakOnramp;

/**
 * Configures the fiat on-ramp provider when {@code web3-vaadin-onramp} is on the classpath.
 * <p>
 * Kept separate from {@link Web3VaadinAutoConfiguration}: the on-ramp module is an optional dependency, and any
 * reference to its types from the main configuration class would fail class introspection in applications without it.
 */
@AutoConfiguration(after = Web3VaadinAutoConfiguration.class)
@ConditionalOnClass(name = "com.wontlost.web3.onramp.OnrampProvider")
public class Web3OnrampAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OnrampProvider.class)
    OnrampProvider web3OnrampProvider(Web3Properties properties) {
        var p = properties.getOnramp();
        if (!p.getMoonpay().getPublishableKey().isBlank() && !p.getMoonpay().getSecretKey().isBlank()) {
            return new MoonPayOnramp(p.getMoonpay().getPublishableKey(), p.getMoonpay().getSecretKey());
        }
        if (!p.getTransak().getApiKey().isBlank() && !p.getTransak().getApiSecret().isBlank()) {
            return new TransakOnramp(p.getTransak().getApiKey(), p.getTransak().getApiSecret(),
                    p.getTransak().getReferrerDomain(), p.getTransak().isStaging());
        }
        if (!p.getCoinbase().getKeyId().isBlank() && !p.getCoinbase().getKeySecret().isBlank()) {
            return new CoinbaseOnramp(p.getCoinbase().getKeyId(), p.getCoinbase().getKeySecret());
        }
        return null;
    }

    // 按名称判断，原因同 web3VaadinContextInitializer：VaadinServiceInitListener 是通用类型
    @Bean
    @ConditionalOnMissingBean(name = "web3OnrampContextInitializer")
    VaadinServiceInitListener web3OnrampContextInitializer(ObjectProvider<OnrampProvider> onramps) {
        return event -> {
            OnrampProvider onramp = onramps.getIfAvailable();
            if (onramp != null) OnrampProviders.register(event.getSource().getContext(), onramp);
        };
    }
}
