package com.wontlost.web3.monitor.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("monitor")
public class MonitorProperties {
    private Map<Long, String> rpc = new LinkedHashMap<>();
    private Map<Long, String> rpcUrls = new LinkedHashMap<>();
    private String adminToken = "";
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration defaultExpiry = Duration.ofHours(1);
    private Duration notBeforeTolerance = Duration.ofMinutes(2);
    private Duration pendingGrace = Duration.ofHours(1);
    private final Webhooks webhooks = new Webhooks();
    public Map<Long, String> getRpc() { return rpc; }
    public void setRpc(Map<Long, String> rpc) { this.rpc = rpc; }
    public Map<Long, String> getRpcUrls() { return rpcUrls; }
    public void setRpcUrls(Map<Long, String> rpcUrls) { this.rpcUrls = rpcUrls; }
    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getDefaultExpiry() { return defaultExpiry; }
    public void setDefaultExpiry(Duration defaultExpiry) { this.defaultExpiry = defaultExpiry; }
    public Duration getNotBeforeTolerance() { return notBeforeTolerance; }
    public void setNotBeforeTolerance(Duration notBeforeTolerance) { this.notBeforeTolerance = notBeforeTolerance; }
    public Duration getPendingGrace() { return pendingGrace; }
    public void setPendingGrace(Duration pendingGrace) { this.pendingGrace = pendingGrace; }
    public Webhooks getWebhooks() { return webhooks; }
    public static class Webhooks {
        private boolean allowInsecure;
        private boolean allowPrivateTargets;
        public boolean isAllowInsecure() { return allowInsecure; }
        public void setAllowInsecure(boolean allowInsecure) { this.allowInsecure = allowInsecure; }
        public boolean isAllowPrivateTargets() { return allowPrivateTargets; }
        public void setAllowPrivateTargets(boolean allowPrivateTargets) { this.allowPrivateTargets = allowPrivateTargets; }
    }
}
