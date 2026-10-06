package com.wontlost.web3.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for the Web3Vaadin starter. */
@ConfigurationProperties("web3")
public class Web3Properties {
    private Map<Long, Chain> chains = new LinkedHashMap<>();
    private Map<Long, String> rpc = new LinkedHashMap<>();
    private final Siwe siwe = new Siwe();
    private final WalletConnect walletconnect = new WalletConnect();
    private final Onramp onramp = new Onramp();
    private final Monitor monitor = new Monitor();
    private final Dev dev = new Dev();
    private final Security security = new Security();
    private final Payments payments = new Payments();

    public Map<Long, Chain> getChains() { return chains; }
    public void setChains(Map<Long, Chain> chains) { this.chains = chains; }
    public Map<Long, String> getRpc() { return rpc; }
    public void setRpc(Map<Long, String> rpc) { this.rpc = rpc; }
    public Siwe getSiwe() { return siwe; }
    /** Returns WalletConnect settings for constructing the opt-in {@code WalletConnect} UI component. */
    public WalletConnect getWalletconnect() { return walletconnect; }
    public Onramp getOnramp() { return onramp; }
    public Monitor getMonitor() { return monitor; }
    public Dev getDev() { return dev; }
    public Security getSecurity() { return security; }
    public Payments getPayments() { return payments; }

    public static class Chain {
        private String rpcUrl;
        public String getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl; }
    }

    public static class Siwe {
        private String domain;
        private String uri;
        private List<Long> allowedChainIds = new ArrayList<>();
        private Duration maxAge = Duration.ofMinutes(5);
        private Duration nonceTtl = Duration.ofMinutes(5);
        private List<String> authorities = new ArrayList<>(List.of("ROLE_WEB3_USER"));
        public String getDomain() { return domain; }
        public void setDomain(String domain) { this.domain = domain; }
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public List<Long> getAllowedChainIds() { return allowedChainIds; }
        public void setAllowedChainIds(List<Long> value) { allowedChainIds = value; }
        /**
         * Returns the requested maximum SIWE issued-at age. The current server component does not expose a hook for
         * supplying this expectation, so this value is bound for consumers but is not enforced by {@code SiweLogin}.
         */
        public Duration getMaxAge() { return maxAge; }
        public void setMaxAge(Duration maxAge) { this.maxAge = maxAge; }
        public Duration getNonceTtl() { return nonceTtl; }
        public void setNonceTtl(Duration nonceTtl) { this.nonceTtl = nonceTtl; }
        public List<String> getAuthorities() { return authorities; }
        public void setAuthorities(List<String> authorities) { this.authorities = authorities; }
    }

    public static class WalletConnect {
        private String projectId = "";
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
    }

    public static class Onramp {
        private Moonpay moonpay = new Moonpay();
        private Transak transak = new Transak();
        private Coinbase coinbase = new Coinbase();
        public Moonpay getMoonpay() { return moonpay; }
        public void setMoonpay(Moonpay moonpay) { this.moonpay = moonpay; }
        public Transak getTransak() { return transak; }
        public void setTransak(Transak transak) { this.transak = transak; }
        public Coinbase getCoinbase() { return coinbase; }
        public void setCoinbase(Coinbase coinbase) { this.coinbase = coinbase; }
    }
    public static class Moonpay {
        private String publishableKey = "";
        private String secretKey = "";
        public String getPublishableKey() { return publishableKey; }
        public void setPublishableKey(String value) { publishableKey = value; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String value) { secretKey = value; }
    }
    public static class Transak {
        private String apiKey = "";
        private String apiSecret = "";
        private String referrerDomain = "localhost";
        private boolean staging = true;
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public String getApiSecret() { return apiSecret; }
        public void setApiSecret(String value) { apiSecret = value; }
        public String getReferrerDomain() { return referrerDomain; }
        public void setReferrerDomain(String value) { referrerDomain = value; }
        public boolean isStaging() { return staging; }
        public void setStaging(boolean value) { staging = value; }
    }
    public static class Coinbase {
        private String keyId = "";
        private String keySecret = "";
        public String getKeyId() { return keyId; }
        public void setKeyId(String value) { keyId = value; }
        public String getKeySecret() { return keySecret; }
        public void setKeySecret(String value) { keySecret = value; }
    }
    public static class Monitor {
        private String url = "";
        private String apiKey = "";
        private String webhookSecret = "";
        public String getUrl() { return url; }
        public void setUrl(String value) { url = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String value) { webhookSecret = value; }
    }
    public static class Dev {
        private final MockWallet mockWallet = new MockWallet();
        public MockWallet getMockWallet() { return mockWallet; }
    }
    public static class MockWallet {
        private boolean enabled;
        private String privateKey = "";
        private long chainId = 31337;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String value) { privateKey = value; }
        public long getChainId() { return chainId; }
        public void setChainId(long value) { chainId = value; }
    }
    public static class Security {
        private final SecuritySiwe siwe = new SecuritySiwe();
        public SecuritySiwe getSiwe() { return siwe; }
    }
    public static class SecuritySiwe {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
    }
    public static class Payments {
        private final Ledger ledger = new Ledger();
        public Ledger getLedger() { return ledger; }
    }
    public static class Ledger {
        private String type = "in-memory";
        public String getType() { return type; }
        public void setType(String value) { type = value; }
    }
}
