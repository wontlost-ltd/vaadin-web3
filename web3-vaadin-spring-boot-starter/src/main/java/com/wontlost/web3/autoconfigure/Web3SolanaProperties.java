package com.wontlost.web3.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for Solana RPC clients and development wallets. */
@ConfigurationProperties("web3.solana")
public class Web3SolanaProperties {
    private Map<String, Cluster> clusters = new LinkedHashMap<>();
    private String commitment = "confirmed";
    private final DevWallet devWallet = new DevWallet();

    public Map<String, Cluster> getClusters() { return clusters; }
    public void setClusters(Map<String, Cluster> value) { clusters = value; }
    public String getCommitment() { return commitment; }
    public void setCommitment(String value) { commitment = value; }
    public DevWallet getDevWallet() { return devWallet; }

    public static class Cluster {
        private List<String> rpcUrls = List.of();
        public List<String> getRpcUrls() { return rpcUrls; }
        public void setRpcUrls(List<String> value) { rpcUrls = value; }
    }

    public static class DevWallet {
        private boolean enabled;
        private String cluster = "localnet";
        private String seed;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getCluster() { return cluster; }
        public void setCluster(String value) { cluster = value; }
        public String getSeed() { return seed; }
        public void setSeed(String value) { seed = value; }
    }
}
