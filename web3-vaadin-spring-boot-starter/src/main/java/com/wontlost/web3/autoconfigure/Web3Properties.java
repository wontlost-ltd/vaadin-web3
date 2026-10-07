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
    private final Siwe siwe = new Siwe();
    private final WalletConnect walletconnect = new WalletConnect();
    private final Onramp onramp = new Onramp();
    private final Monitor monitor = new Monitor();
    private final Dev dev = new Dev();
    private final Security security = new Security();
    private final Payments payments = new Payments();
    private final X402 x402 = new X402();

    public Map<Long, Chain> getChains() { return chains; }
    public void setChains(Map<Long, Chain> chains) { this.chains = chains; }
    public Siwe getSiwe() { return siwe; }
    /** Returns WalletConnect settings for constructing the opt-in {@code WalletConnect} UI component. */
    public WalletConnect getWalletconnect() { return walletconnect; }
    public Onramp getOnramp() { return onramp; }
    public Monitor getMonitor() { return monitor; }
    public Dev getDev() { return dev; }
    public Security getSecurity() { return security; }
    public Payments getPayments() { return payments; }
    public X402 getX402() { return x402; }

    public static class X402 {
        private boolean enabled;
        private String origin = "";
        private Duration validAfterSkew = Duration.ofSeconds(600);
        private Duration reconcileInterval = Duration.ZERO;
        private int reconcileConfirmations = 3;
        private boolean allowInMemoryStore;
        private final Facilitator facilitator = new Facilitator();
        private final Protocol protocol = new Protocol();
        private final LocalFacilitator localFacilitator = new LocalFacilitator();
        private List<Long> allowedChainIds = new ArrayList<>();
        private List<String> allowedNetworks = new ArrayList<>();
        private List<String> allowedAssets = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getOrigin() { return origin; }
        public void setOrigin(String value) { origin = value; }
        public Duration getValidAfterSkew() { return validAfterSkew; }
        public void setValidAfterSkew(Duration value) { validAfterSkew = value; }
        public Duration getReconcileInterval() { return reconcileInterval; }
        public void setReconcileInterval(Duration value) { reconcileInterval = value; }
        public int getReconcileConfirmations() { return reconcileConfirmations; }
        public void setReconcileConfirmations(int value) { reconcileConfirmations = value; }
        public boolean isAllowInMemoryStore() { return allowInMemoryStore; }
        public void setAllowInMemoryStore(boolean value) { allowInMemoryStore = value; }
        public Facilitator getFacilitator() { return facilitator; }
        public Protocol getProtocol() { return protocol; }
        public LocalFacilitator getLocalFacilitator() { return localFacilitator; }
        public List<Long> getAllowedChainIds() { return allowedChainIds; }
        public void setAllowedChainIds(List<Long> value) { allowedChainIds = value; }
        public List<String> getAllowedNetworks() { return allowedNetworks; }
        public void setAllowedNetworks(List<String> value) { allowedNetworks = value; }
        public List<String> getAllowedAssets() { return allowedAssets; }
        public void setAllowedAssets(List<String> value) { allowedAssets = value; }
    }
    public static class Facilitator {
        private String baseUrl = "";
        private String apiKey = "";
        private String apiKeyHeader = "Authorization";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private int maxResponseBytes = 262144;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String value) { baseUrl = value; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String value) { apiKey = value; }
        public String getApiKeyHeader() { return apiKeyHeader; }
        public void setApiKeyHeader(String value) { apiKeyHeader = value; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration value) { connectTimeout = value; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration value) { requestTimeout = value; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
    }
    public static class Protocol {
        private int maxHeaderBytes = 65536;
        private int maxJsonBytes = 49152;
        private int maxTimeoutSeconds = 300;
        public int getMaxHeaderBytes() { return maxHeaderBytes; }
        public void setMaxHeaderBytes(int value) { maxHeaderBytes = value; }
        public int getMaxJsonBytes() { return maxJsonBytes; }
        public void setMaxJsonBytes(int value) { maxJsonBytes = value; }
        public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
        public void setMaxTimeoutSeconds(int value) { maxTimeoutSeconds = value; }
    }
    public static class LocalFacilitator {
        private boolean enabled;
        private long chainId = 31337;
        private String tokenAddress = "";
        private String payTo = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public long getChainId() { return chainId; }
        public void setChainId(long value) { chainId = value; }
        public String getTokenAddress() { return tokenAddress; }
        public void setTokenAddress(String value) { tokenAddress = value; }
        public String getPayTo() { return payTo; }
        public void setPayTo(String value) { payTo = value; }
    }

    public static class Chain {
        private String rpcUrl;
        private List<String> rpcUrls = new ArrayList<>();
        public String getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl; }
        public List<String> getRpcUrls() { return rpcUrls; }
        public void setRpcUrls(List<String> rpcUrls) { this.rpcUrls = rpcUrls; }
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
