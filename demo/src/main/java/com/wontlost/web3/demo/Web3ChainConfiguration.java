package com.wontlost.web3.demo;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.InMemoryPaymentLedger;

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
    VaadinServiceInitListener chainRegistryInitializer(ChainRegistry registry) {
        return (ServiceInitEvent event) -> event.getSource().getContext().setAttribute(ChainRegistry.class, registry);
    }
}
