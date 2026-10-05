package com.wontlost.web3.demo;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.InMemoryPaymentLedger;
import com.wontlost.web3.onramp.CoinbaseOnramp;
import com.wontlost.web3.onramp.MoonPayOnramp;
import com.wontlost.web3.onramp.OnrampProvider;
import com.wontlost.web3.onramp.OnrampProviders;
import com.wontlost.web3.onramp.TransakOnramp;
import com.wontlost.web3.monitor.PaymentMonitorClient;
import java.net.URI;

/** Configures application-scoped chain clients for demo routes and payment verification. */
@Configuration
public class Web3ChainConfiguration {
    @Bean
    ChainRegistry chainRegistry(org.springframework.core.env.Environment environment) {
        ChainRegistry registry = new ChainRegistry();
        Map.of(11155111L, "web3.rpc.11155111", 84532L, "web3.rpc.84532").forEach((id, key) -> {
            String endpoint = environment.getProperty(key);
            if (endpoint != null && !endpoint.isBlank()) registry.register(id, endpoint);
        });
        return registry;
    }

    @Bean
    InMemoryPaymentLedger paymentLedger() { return new InMemoryPaymentLedger(); }


    @Bean
    DemoIntegrations demoIntegrations(org.springframework.core.env.Environment environment) {
        String monitorUrl = environment.getProperty("web3.monitor.url", "");
        String monitorKey = environment.getProperty("web3.monitor.api-key", "");
        PaymentMonitorClient monitor = monitorUrl.isBlank() || monitorKey.isBlank() ? null
                : new PaymentMonitorClient(URI.create(monitorUrl), monitorKey);
        return new DemoIntegrations(onrampProvider(environment), monitor);
    }

    private static OnrampProvider onrampProvider(org.springframework.core.env.Environment environment) {
        String moonKey = environment.getProperty("web3.onramp.moonpay.publishable-key", "");
        String moonSecret = environment.getProperty("web3.onramp.moonpay.secret-key", "");
        if (!moonKey.isBlank() && !moonSecret.isBlank()) return new MoonPayOnramp(moonKey, moonSecret);
        String transakKey = environment.getProperty("web3.onramp.transak.api-key", "");
        String transakSecret = environment.getProperty("web3.onramp.transak.api-secret", "");
        if (!transakKey.isBlank() && !transakSecret.isBlank()) {
            return new TransakOnramp(transakKey, transakSecret, environment.getProperty("web3.onramp.transak.referrer-domain", "localhost"),
                    environment.getProperty("web3.onramp.transak.staging", Boolean.class, true));
        }
        String coinbaseId = environment.getProperty("web3.onramp.coinbase.key-id", "");
        String coinbaseSecret = environment.getProperty("web3.onramp.coinbase.key-secret", "");
        if (!coinbaseId.isBlank() && !coinbaseSecret.isBlank()) return new CoinbaseOnramp(coinbaseId, coinbaseSecret);
        return null;
    }

    @Bean
    VaadinServiceInitListener chainRegistryInitializer(ChainRegistry registry, DemoIntegrations integrations) {
        return (ServiceInitEvent event) -> {
            event.getSource().getContext().setAttribute(ChainRegistry.class, registry);
            // 凭据不随会话序列化：组件在会话恢复后从应用上下文的注册表找回服务商与监控客户端
            if (integrations.onrampProvider() != null) OnrampProviders.register(event.getSource().getContext(), integrations.onrampProvider());
            if (integrations.monitorClient() != null) PaymentMonitorClient.register(event.getSource().getContext(), integrations.monitorClient());
        };
    }
}
