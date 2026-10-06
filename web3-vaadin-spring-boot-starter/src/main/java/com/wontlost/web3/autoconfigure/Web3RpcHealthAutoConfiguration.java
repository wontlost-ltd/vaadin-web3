package com.wontlost.web3.autoconfigure;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.FailoverJsonRpcTransport;

/** Optional Actuator health support for configured JSON-RPC endpoints. */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
public class Web3RpcHealthAutoConfiguration {
    @Bean("web3RpcHealthIndicator")
    @ConditionalOnMissingBean(name = "web3RpcHealthIndicator")
    org.springframework.boot.health.contributor.HealthIndicator web3RpcHealthIndicator(
            ChainRegistry chains, RpcTransportLifecycle lifecycle) {
        return () -> {
            var failovers = lifecycle.failovers();
            var endpointIds = lifecycle.endpointIds();
            if (endpointIds.isEmpty() || chains.clients().isEmpty()) {
                return org.springframework.boot.health.contributor.Health.unknown().build();
            }
            // 任一链的全部端点都不可用即 DOWN：该链上的登录与收款已无法服务
            boolean anyChainDown = false;
            Map<String, Object> details = new LinkedHashMap<>();
            for (var entry : endpointIds.entrySet()) {
                FailoverJsonRpcTransport failover = failovers.get(entry.getKey());
                if (failover == null) {
                    // 单端点直连没有熔断器，也就没有健康数据：如实标注，不冒充"已检查且健康"
                    details.put(Long.toString(entry.getKey()), entry.getValue().stream().map(id -> {
                        Map<String, Object> endpoint = new LinkedHashMap<>();
                        endpoint.put("id", redact(id));
                        endpoint.put("state", "UNMONITORED");
                        return endpoint;
                    }).toList());
                    continue;
                }
                List<FailoverJsonRpcTransport.Health> values = failover.healthSnapshot();
                if (!values.isEmpty() && values.stream().allMatch(value -> value.state() == FailoverJsonRpcTransport.State.OPEN)) {
                    anyChainDown = true;
                }
                details.put(Long.toString(entry.getKey()), values.stream().map(value -> {
                    Map<String, Object> endpoint = new LinkedHashMap<>();
                    endpoint.put("id", redact(value.id()));
                    endpoint.put("state", value.state().name());
                    endpoint.put("consecutiveFailures", value.consecutiveFailures());
                    endpoint.put("lastErrorCategory", value.lastErrorCategory());
                    endpoint.put("lastLatency", value.lastLatency());
                    return endpoint;
                }).toList());
            }
            var builder = anyChainDown ? org.springframework.boot.health.contributor.Health.down()
                    : org.springframework.boot.health.contributor.Health.up();
            return builder.withDetails(details).build();
        };
    }

    static String redact(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host == null) return "***";
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("(?i)(^|/)[a-z0-9_-]{16,}(?=/|$)", "$1***");
            return uri.getScheme() + "://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + path;
        } catch (RuntimeException exception) {
            return "***";
        }
    }
}
