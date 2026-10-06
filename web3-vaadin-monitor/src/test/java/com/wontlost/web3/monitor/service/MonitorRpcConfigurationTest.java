package com.wontlost.web3.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MonitorRpcConfigurationTest {
    @Test
    void commaSeparatedLegacyAndExplicitUrlsSelectDirectOrFailoverTransport() throws Exception {
        MonitorProperties properties = new MonitorProperties();
        properties.getRpc().put(1L, "https://one.example,https://two.example");
        properties.getRpcUrls().put(2L, "https://primary.example,https://backup.example");
        properties.getRpc().put(2L, "https://ignored.example");
        properties.getRpc().put(3L, "https://single.example");
        MonitorRpcTransportLifecycle lifecycle = new MonitorRpcTransportLifecycle();
        try {
            var registry = new MonitorConfiguration().chainRegistry(properties, lifecycle);
            assertThat(registry.clients()).containsKeys(1L, 2L, 3L);
            assertThat(lifecycle.failovers()).containsKeys(1L, 2L).doesNotContainKey(3L);
            assertThat(lifecycle.failovers().get(1L).healthSnapshot()).extracting("id")
                    .containsExactly("https://one.example", "https://two.example");
            assertThat(lifecycle.failovers().get(2L).healthSnapshot()).extracting("id")
                    .containsExactly("https://primary.example", "https://backup.example");
        } finally {
            lifecycle.close();
        }
    }
}
