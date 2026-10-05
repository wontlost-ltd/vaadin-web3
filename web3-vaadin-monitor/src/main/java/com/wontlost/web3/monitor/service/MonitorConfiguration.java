package com.wontlost.web3.monitor.service;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.PaymentVerifier;

@Configuration
@Import(com.wontlost.web3.monitor.WebhookDeliveryWorker.class)
@EnableConfigurationProperties(MonitorProperties.class)
public class MonitorConfiguration {
    @Bean(initMethod = "migrate")
    Flyway monitorFlyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).load();
    }

    @Bean ChainRegistry chainRegistry(MonitorProperties properties) {
        ChainRegistry registry = new ChainRegistry();
        properties.getRpc().forEach(registry::register);
        return registry;
    }
    @Bean JdbcPaymentLedger paymentLedger(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactions) { return new JdbcPaymentLedger(jdbc, transactions); }
    @Bean PaymentVerifier paymentVerifier(ChainRegistry chains, JdbcPaymentLedger ledger) { return new PaymentVerifier(chains, ledger); }
    @Bean Clock monitorClock() { return Clock.systemUTC(); }
}
