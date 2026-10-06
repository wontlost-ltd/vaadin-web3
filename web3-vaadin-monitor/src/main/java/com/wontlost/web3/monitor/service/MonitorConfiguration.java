package com.wontlost.web3.monitor.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.FailoverJsonRpcTransport;
import com.wontlost.web3.chain.HttpJsonRpcTransport;
import com.wontlost.web3.pay.PaymentVerifier;

@Configuration
@Import(com.wontlost.web3.monitor.WebhookDeliveryWorker.class)
@EnableConfigurationProperties(MonitorProperties.class)
public class MonitorConfiguration {
    @Bean(initMethod = "migrate")
    Flyway monitorFlyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).load();
    }

    @Bean(destroyMethod = "close") MonitorRpcTransportLifecycle monitorRpcTransportLifecycle() {
        return new MonitorRpcTransportLifecycle();
    }

    @Bean ChainRegistry chainRegistry(MonitorProperties properties, MonitorRpcTransportLifecycle lifecycle) {
        ChainRegistry registry = new ChainRegistry();
        Map<Long, String> configured = new java.util.LinkedHashMap<>(properties.getRpc());
        configured.putAll(properties.getRpcUrls());
        configured.forEach((chainId, value) -> {
            if (value == null || value.isBlank()) return;
            var urls = Arrays.stream(value.split(",")).map(String::trim).filter(url -> !url.isEmpty()).toList();
            var endpoints = urls.stream().map(url -> new FailoverJsonRpcTransport.Endpoint(url,
                    new HttpJsonRpcTransport(url, Duration.ofSeconds(10), lifecycle.client()))).toList();
            if (endpoints.size() == 1) registry.register(chainId,
                    new EthRpcClient(lifecycle.track(endpoints.getFirst().transport())));
            else if (!endpoints.isEmpty()) registry.register(chainId, new EthRpcClient(lifecycle.track(chainId,
                    new FailoverJsonRpcTransport(endpoints))));
        });
        return registry;
    }
    @Bean JdbcPaymentLedger paymentLedger(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactions) { return new JdbcPaymentLedger(jdbc, transactions); }
    @Bean PaymentVerifier paymentVerifier(ChainRegistry chains, JdbcPaymentLedger ledger) { return new PaymentVerifier(chains, ledger); }
    @Bean Clock monitorClock() { return Clock.systemUTC(); }
}
