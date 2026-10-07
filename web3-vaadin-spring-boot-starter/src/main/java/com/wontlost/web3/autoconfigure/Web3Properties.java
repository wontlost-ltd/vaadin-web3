package com.wontlost.web3.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wontlost.web3.nft.NftQueryLimits;

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
    private final Nft nft = new Nft();

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
    public Nft getNft() {
        return nft;
    }

    public static class Nft {
        private boolean enabled;
        private int maxConcurrency = 8;
        private int maxPageSize = 100;
        private int batchSize = 100;
        private int maxTokenIds = 1000;
        private Integer queueCapacity;
        private List<NftCollection> collections = new ArrayList<>();
        private final Metadata metadata = new Metadata();
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int value) {
            validate("max-concurrency", value, NftQueryLimits.MAX_CONCURRENCY);
            maxConcurrency = value;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int value) {
            validate("max-page-size", value, NftQueryLimits.MAX_PAGE_SIZE);
            maxPageSize = value;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int value) {
            validate("batch-size", value, NftQueryLimits.MAX_BATCH_SIZE);
            batchSize = value;
        }

        public int getMaxTokenIds() {
            return maxTokenIds;
        }

        public void setMaxTokenIds(int value) {
            validate("max-token-ids", value, NftQueryLimits.MAX_TOKEN_IDS);
            maxTokenIds = value;
        }

        public Integer getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(Integer value) {
            if (value != null) {
                validate("queue-capacity", value, NftQueryLimits.MAX_QUEUE_CAPACITY);
            }
            queueCapacity = value;
        }

        private void validate(String name, int value, int maximum) {
            if (value < 1 || value > maximum) {
                throw new IllegalArgumentException("web3.nft." + name + " must be between 1 and " + maximum);
            }
        }

        public List<NftCollection> getCollections() {
            return collections;
        }

        public void setCollections(List<NftCollection> value) {
            collections = value;
        }

        public Metadata getMetadata() { return metadata; }

        public static class Metadata {
            private List<String> ipfsGateways = new ArrayList<>(List.of("https://ipfs.io/ipfs/"));
            private int maxResponseBytes = 262144;
            private int maxDataUriBytes = 262144;
            private int maxUriLength = 8192;
            private int maxRedirects = 3;
            private Duration requestTimeout = Duration.ofSeconds(10);
            private int cacheCapacity = 10000;
            private Duration positiveTtl = Duration.ofHours(1);
            private Duration negativeTtl = Duration.ofMinutes(1);
            private Duration errorTtl = Duration.ofSeconds(10);
            private int maxConcurrency = 8;
            private List<Integer> allowedPorts = new ArrayList<>(List.of(443));
            private int maxAttributes = 100;
            private int maxTextLength = 2048;
            public List<String> getIpfsGateways() { return ipfsGateways; }
            public void setIpfsGateways(List<String> value) { ipfsGateways = value; }
            public int getMaxResponseBytes() { return maxResponseBytes; }
            public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
            public int getMaxDataUriBytes() { return maxDataUriBytes; }
            public void setMaxDataUriBytes(int value) { maxDataUriBytes = value; }
            public int getMaxUriLength() { return maxUriLength; }
            public void setMaxUriLength(int value) { maxUriLength = value; }
            public int getMaxRedirects() { return maxRedirects; }
            public void setMaxRedirects(int value) { maxRedirects = value; }
            public Duration getRequestTimeout() { return requestTimeout; }
            public void setRequestTimeout(Duration value) { requestTimeout = value; }
            public int getCacheCapacity() { return cacheCapacity; }
            public void setCacheCapacity(int value) { cacheCapacity = value; }
            public Duration getPositiveTtl() { return positiveTtl; }
            public void setPositiveTtl(Duration value) { positiveTtl = value; }
            public Duration getNegativeTtl() { return negativeTtl; }
            public void setNegativeTtl(Duration value) { negativeTtl = value; }
            public Duration getErrorTtl() { return errorTtl; }
            public void setErrorTtl(Duration value) { errorTtl = value; }
            public int getMaxConcurrency() { return maxConcurrency; }
            public void setMaxConcurrency(int value) { maxConcurrency = value; }
            public List<Integer> getAllowedPorts() { return allowedPorts; }
            public void setAllowedPorts(List<Integer> value) { allowedPorts = value; }
            public int getMaxAttributes() { return maxAttributes; }
            public void setMaxAttributes(int value) { maxAttributes = value; }
            public int getMaxTextLength() { return maxTextLength; }
            public void setMaxTextLength(int value) { maxTextLength = value; }
        }
    }

    public static class NftCollection {
        private long chainId;
        private String contract;
        private String standard = "ERC721";
        private boolean enumerable;
        private List<String> tokenIds = new ArrayList<>();
        private List<NftTokenIdRange> ranges = new ArrayList<>();
        public long getChainId() {
            return chainId;
        }

        public void setChainId(long value) {
            chainId = value;
        }

        public String getContract() {
            return contract;
        }

        public void setContract(String value) {
            contract = value;
        }

        public String getStandard() {
            return standard;
        }

        public void setStandard(String value) {
            standard = value;
        }

        public boolean isEnumerable() {
            return enumerable;
        }

        public void setEnumerable(boolean value) {
            enumerable = value;
        }

        public List<String> getTokenIds() {
            return tokenIds;
        }

        public void setTokenIds(List<String> value) {
            tokenIds = value;
        }

        public List<NftTokenIdRange> getRanges() {
            return ranges;
        }

        public void setRanges(List<NftTokenIdRange> value) {
            ranges = value;
        }
    }

    public static class NftTokenIdRange {
        private String first;
        private String last;
        public String getFirst() {
            return first;
        }

        public void setFirst(String value) {
            first = value;
        }

        public String getLast() {
            return last;
        }

        public void setLast(String value) {
            last = value;
        }
    }

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
        private final Http http = new Http();
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
        public Http getHttp() { return http; }
        public List<Long> getAllowedChainIds() { return allowedChainIds; }
        public void setAllowedChainIds(List<Long> value) { allowedChainIds = value; }
        public List<String> getAllowedNetworks() { return allowedNetworks; }
        public void setAllowedNetworks(List<String> value) { allowedNetworks = value; }
        public List<String> getAllowedAssets() { return allowedAssets; }
        public void setAllowedAssets(List<String> value) { allowedAssets = value; }
    }
    public static class Http {
        private boolean enabled;
        private int maxResponseBytes = 262144;
        private Duration siwxChallengeTtl = Duration.ofMinutes(5);
        private int siwxChallengeCapacity = 10_000;
        private List<HttpResource> resources = new ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
        public Duration getSiwxChallengeTtl() { return siwxChallengeTtl; }
        public void setSiwxChallengeTtl(Duration value) { siwxChallengeTtl = value; }
        public int getSiwxChallengeCapacity() { return siwxChallengeCapacity; }
        public void setSiwxChallengeCapacity(int value) { siwxChallengeCapacity = value; }
        public List<HttpResource> getResources() { return resources; }
        public void setResources(List<HttpResource> value) { resources = value; }
    }
    public static class HttpResource {
        private String resourceId;
        private String method = "GET";
        private String path;
        private boolean idempotent;
        private boolean requireSiwx;
        private List<Long> allowedChainIds = new ArrayList<>();
        public String getResourceId() { return resourceId; }
        public void setResourceId(String value) { resourceId = value; }
        public String getMethod() { return method; }
        public void setMethod(String value) { method = value; }
        public String getPath() { return path; }
        public void setPath(String value) { path = value; }
        public boolean isIdempotent() { return idempotent; }
        public void setIdempotent(boolean value) { idempotent = value; }
        public boolean isRequireSiwx() { return requireSiwx; }
        public void setRequireSiwx(boolean value) { requireSiwx = value; }
        public List<Long> getAllowedChainIds() { return allowedChainIds; }
        public void setAllowedChainIds(List<Long> value) { allowedChainIds = value; }
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
