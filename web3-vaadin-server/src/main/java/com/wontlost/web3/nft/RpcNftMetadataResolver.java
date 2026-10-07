package com.wontlost.web3.nft;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.EthRpcException;
import com.wontlost.web3.net.PublicAddressPolicy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 读取链上 token URI 并将有限 JSON 文本映射为 UI 安全模型。 */
public final class RpcNftMetadataResolver implements NftMetadataResolver, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChainRegistry chains;
    private final NftMetadataFetcher fetcher;
    private final NftMetadataOptions options;
    private final NftDnsResolver dnsResolver;
    private final ThreadPoolExecutor executor;
    private final Cache<CacheKey, CacheValue> cache;

    public RpcNftMetadataResolver(ChainRegistry chains, NftMetadataFetcher fetcher, NftMetadataOptions options) {
        this(chains, fetcher, options, java.net.InetAddress::getAllByName);
    }

    public RpcNftMetadataResolver(ChainRegistry chains, NftMetadataFetcher fetcher, NftMetadataOptions options,
            NftDnsResolver dnsResolver) {
        this.chains = java.util.Objects.requireNonNull(chains);
        this.fetcher = java.util.Objects.requireNonNull(fetcher);
        this.options = java.util.Objects.requireNonNull(options);
        this.dnsResolver = java.util.Objects.requireNonNull(dnsResolver);
        AtomicInteger number = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(options.maxConcurrency(), options.maxConcurrency(), 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(options.maxConcurrency() * 16), task -> {
                    Thread thread = new Thread(task, "web3-nft-metadata-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.cache = Caffeine.newBuilder().maximumSize(options.cacheCapacity()).build();
    }

    @Override
    public List<NftMetadataResult> resolve(List<NftHolding> holdings) {
        List<Future<NftMetadataResult>> futures = new ArrayList<>();
        for (NftHolding holding : List.copyOf(holdings)) {
            try {
                futures.add(executor.submit(() -> resolveOne(holding)));
            } catch (RuntimeException exception) {
                futures.add(null);
            }
        }
        List<NftMetadataResult> results = new ArrayList<>();
        for (int index = 0; index < futures.size(); index++) {
            if (futures.get(index) == null) {
                results.add(failure(holdings.get(index), NftMetadataFailureCode.UNAVAILABLE));
                continue;
            }
            try {
                results.add(futures.get(index).get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                futures.stream().filter(java.util.Objects::nonNull).forEach(future -> future.cancel(true));
                results.add(failure(holdings.get(index), NftMetadataFailureCode.UNAVAILABLE));
            } catch (Exception exception) {
                results.add(failure(holdings.get(index), NftMetadataFailureCode.UNAVAILABLE));
            }
        }
        return List.copyOf(results);
    }

    private NftMetadataResult resolveOne(NftHolding holding) {
        CacheKey base = new CacheKey(holding.chainId(), holding.contract(), holding.tokenId(), "");
        NftMetadataResult baseHit = getCache(base, holding);
        if (baseHit != null) {
            return baseHit;
        }
        try {
            EthRpcClient rpc = chains.get(holding.chainId()).orElseThrow(
                    () -> new NftMetadataException(NftMetadataFailureCode.UNAVAILABLE)).pinned();
            long snapshot = rpc.blockNumber();
            String callData = holding.standard() == NftStandard.ERC721
                    ? NftAbi.tokenUriData(holding.tokenId()) : NftAbi.uriData(holding.tokenId());
            String rawUri;
            try {
                rawUri = NftAbi.decodeString(rpc.call(holding.contract(), callData, "0x" + Long.toHexString(snapshot)));
            } catch (EthRpcException exception) {
                NftMetadataFailureCode code = exception.getCode() == 3 || startsReverted(exception.getMessage())
                        ? NftMetadataFailureCode.NOT_FOUND : NftMetadataFailureCode.UNAVAILABLE;
                return cache(base, failure(holding, code), code);
            }
            validateUriLength(rawUri);
            String templated = holding.standard() == NftStandard.ERC1155 ? expandId(rawUri, holding.tokenId()) : rawUri;
            validateUriLength(templated);
            CacheKey key = new CacheKey(holding.chainId(), holding.contract(), holding.tokenId(), templated);
            NftMetadataResult hit = getCache(key, holding);
            if (hit != null) {
                return hit;
            }
            byte[] document = readDocument(templated);
            JsonNode root;
            try {
                root = JSON.reader(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(new String(document, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                throw new NftMetadataException(NftMetadataFailureCode.INVALID_JSON);
            }
            if (root == null || !root.isObject()) {
                throw new NftMetadataException(NftMetadataFailureCode.INVALID_JSON);
            }
            JsonNode expanded = holding.standard() == NftStandard.ERC1155 ? replaceStringValues(root, holding.tokenId()) : root;
            NftMetadataResult result = toResult(holding, expanded);
            cache(key, result, null);
            return result;
        } catch (NftMetadataException exception) {
            return cache(base, failure(holding, exception.code()), exception.code());
        } catch (Exception exception) {
            return cache(base, failure(holding, NftMetadataFailureCode.UNAVAILABLE), NftMetadataFailureCode.UNAVAILABLE);
        }
    }

    private byte[] readDocument(String source) {
        String lowered = source.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("data:")) {
            return readDataUri(source);
        }
        long deadline = System.nanoTime() + options.requestTimeout().toNanos();
        if (lowered.startsWith("ipfs://")) {
            NftMetadataException lastFailure = null;
            for (URI gateway : ipfsGateways(source)) {
                try {
                    return readHttpsDocument(gateway, deadline);
                } catch (NftMetadataException exception) {
                    lastFailure = exception;
                    if (exception.code() != NftMetadataFailureCode.UNAVAILABLE
                            && exception.code() != NftMetadataFailureCode.TIMEOUT) {
                        // 内容错误对同一份 IPFS 文档一致，继续请求其他网关只会放大流量。
                        throw exception;
                    }
                }
            }
            throw lastFailure == null ? new NftMetadataException(NftMetadataFailureCode.UNAVAILABLE) : lastFailure;
        }
        URI uri = normalizeUri(source);
        return readHttpsDocument(uri, deadline);
    }

    private byte[] readHttpsDocument(URI uri, long deadline) {
        for (int redirect = 0; ; redirect++) {
            validateHttps(uri);
            NftMetadataFetchResponse response = fetcher.fetch(uri, remaining(deadline), options.maxResponseBytes());
            if (response.status() >= 300 && response.status() < 400 && response.location() != null) {
                if (redirect >= options.maxRedirects()) {
                    throw new NftMetadataException(NftMetadataFailureCode.TOO_MANY_REDIRECTS);
                }
                try {
                    uri = uri.resolve(response.location());
                } catch (RuntimeException exception) {
                    throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
                }
                continue;
            }
            if (response.status() < 200 || response.status() >= 300) {
                throw new NftMetadataException(NftMetadataFailureCode.UNAVAILABLE);
            }
            if (!jsonContentType(response.contentType())) {
                throw new NftMetadataException(NftMetadataFailureCode.UNSUPPORTED_CONTENT_TYPE);
            }
            if (response.body().length > options.maxResponseBytes()) {
                throw new NftMetadataException(NftMetadataFailureCode.RESPONSE_TOO_LARGE);
            }
            return response.body();
        }
    }

    private List<URI> ipfsGateways(String source) {
        try {
            URI input = URI.create(source);
            String path = input.getRawSchemeSpecificPart();
            while (path.startsWith("//")) path = path.substring(2);
            if (path.startsWith("ipfs/")) path = path.substring(5);
            rejectTraversal(path);
            if (path.isBlank() || path.startsWith("/")) {
                throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
            }
            List<URI> gateways = new ArrayList<>();
            for (String gateway : options.ipfsGateways()) {
                String base = gateway.endsWith("/") ? gateway : gateway + "/";
                gateways.add(URI.create(base + path).normalize());
            }
            return List.copyOf(gateways);
        } catch (NftMetadataException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
        }
    }

    private byte[] readDataUri(String value) {
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
        }
        String header = value.substring(0, comma).toLowerCase(Locale.ROOT);
        if (!header.equals("data:application/json;base64") && !header.equals("data:application/json")) {
            throw new NftMetadataException(NftMetadataFailureCode.UNSUPPORTED_URI);
        }
        String encoded = value.substring(comma + 1);
        long encodedLimit = header.endsWith(";base64")
                ? ((long) options.maxDataUriBytes() + 2L) / 3L * 4L
                : (long) options.maxDataUriBytes() * 3L;
        if (encoded.codePointCount(0, encoded.length()) > encodedLimit) {
            throw new NftMetadataException(NftMetadataFailureCode.URI_TOO_LONG);
        }
        try {
            byte[] decoded = header.endsWith(";base64") ? Base64.getDecoder().decode(encoded)
                    : percentDecode(encoded).getBytes(StandardCharsets.UTF_8);
            if (decoded.length > options.maxDataUriBytes()) {
                throw new NftMetadataException(NftMetadataFailureCode.URI_TOO_LONG);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
        }
    }

    private URI normalizeUri(String value) {
        try {
            URI input = URI.create(value);
            if ("ipfs".equalsIgnoreCase(input.getScheme())) {
                return ipfsGateways(value).getFirst();
            }
            if (!"https".equalsIgnoreCase(input.getScheme())) {
                throw new NftMetadataException(NftMetadataFailureCode.UNSUPPORTED_URI);
            }
            return input;
        } catch (NftMetadataException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
        }
    }

    private void validateHttps(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 && !options.allowedPorts().contains(uri.getPort())) {
            // URI 对 127.1 和 0x7f.1 不返回 host，拒绝这类不规范 authority，不尝试猜测其含义。
            throw new NftMetadataException(NftMetadataFailureCode.UNSAFE_TARGET);
        }
        try {
            String host = uri.getHost().replace("[", "").replace("]", "");
            if (isNonPublicLegacyOctalLiteral(host)) {
                throw new NftMetadataException(NftMetadataFailureCode.UNSAFE_TARGET);
            }
            java.net.InetAddress[] addresses = dnsResolver.resolve(host);
            if (addresses.length == 0) {
                throw new NftMetadataException(NftMetadataFailureCode.UNSAFE_TARGET);
            }
            for (java.net.InetAddress address : addresses) {
                if (!PublicAddressPolicy.isPublic(address)) {
                    throw new NftMetadataException(NftMetadataFailureCode.UNSAFE_TARGET);
                }
            }
        } catch (NftMetadataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NftMetadataException(NftMetadataFailureCode.UNAVAILABLE);
        }
    }

    private void validateUriLength(String value) {
        int codePoints = value.codePointCount(0, value.length());
        if (!value.regionMatches(true, 0, "data:", 0, 5)) {
            if (codePoints > options.maxUriLength()) {
                throw new NftMetadataException(NftMetadataFailureCode.URI_TOO_LONG);
            }
            return;
        }
        int comma = value.indexOf(',');
        String header = comma < 0 ? value : value.substring(0, comma);
        int headerLength = header.codePointCount(0, header.length());
        if (headerLength > 64) {
            throw new NftMetadataException(NftMetadataFailureCode.URI_TOO_LONG);
        }
        long encodedLimit = header.toLowerCase(Locale.ROOT).endsWith(";base64")
                ? ((long) options.maxDataUriBytes() + 2L) / 3L * 4L
                : (long) options.maxDataUriBytes() * 3L;
        long totalLimit = headerLength + (comma < 0 ? 0L : 1L) + encodedLimit;
        if (codePoints > totalLimit) {
            throw new NftMetadataException(NftMetadataFailureCode.URI_TOO_LONG);
        }
    }

    private boolean isNonPublicLegacyOctalLiteral(String host) {
        if (host.length() < 2 || host.charAt(0) != '0' || !host.matches("0[0-7]+")) {
            return false;
        }
        try {
            BigInteger numeric = new BigInteger(host, 8);
            if (numeric.bitLength() > 32) {
                return true;
            }
            long value = numeric.longValue();
            byte[] address = { (byte) (value >>> 24), (byte) (value >>> 16),
                    (byte) (value >>> 8), (byte) value };
            return !PublicAddressPolicy.isPublic(InetAddress.getByAddress(address));
        } catch (Exception exception) {
            return true;
        }
    }

    private NftMetadataResult toResult(NftHolding holding, JsonNode root) {
        String image = text(root.path("image"), options.maxTextLength());
        String display = null;
        String reason = null;
        if (image != null) {
            try {
                URI uri = normalizeUri(image);
                validateHttps(uri);
                String lowerPath = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
                if (lowerPath.endsWith(".svg") || "image/svg+xml".equalsIgnoreCase(text(root.path("image_mime_type"), 128))) {
                    reason = "SVG_IMAGE";
                } else {
                    display = uri.toString();
                }
            } catch (NftMetadataException exception) {
                reason = exception.code().name();
            }
        }
        List<NftMetadataAttribute> attributes = new ArrayList<>();
        JsonNode array = root.path("attributes");
        if (array.isArray()) {
            for (JsonNode attribute : array) {
                if (attributes.size() >= options.maxAttributes()) {
                    break;
                }
                String trait = text(attribute.path("trait_type"), options.maxTextLength());
                String value = scalar(attribute.path("value"));
                if (trait != null || value != null) {
                    attributes.add(new NftMetadataAttribute(trait, truncate(value, options.maxTextLength())));
                }
            }
        }
        NftMetadata metadata = new NftMetadata(text(root.path("name"), options.maxTextLength()),
                text(root.path("description"), options.maxTextLength()), normalizedImage(image),
                text(root.path("animation_url"), options.maxTextLength()), httpsOnly(text(root.path("external_url"), options.maxTextLength())), attributes);
        return new NftMetadataResult(holding, metadata, null, display, reason);
    }

    private String normalizedImage(String image) {
        if (image == null) return null;
        try {
            URI uri = normalizeUri(image);
            validateHttps(uri);
            return "https".equalsIgnoreCase(uri.getScheme()) ? uri.toString() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String httpsOnly(String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null ? value : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private JsonNode replaceStringValues(JsonNode node, BigInteger tokenId) {
        if (node.isTextual()) return JSON.valueToTree(expandId(node.asString(), tokenId));
        if (node.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            for (JsonNode child : node) array.add(replaceStringValues(child, tokenId));
            return array;
        } else if (node.isObject()) {
            ObjectNode object = JSON.createObjectNode();
            node.properties().forEach(entry -> object.set(entry.getKey(), replaceStringValues(entry.getValue(), tokenId)));
            return object;
        }
        return node;
    }

    private String expandId(String value, BigInteger tokenId) {
        // EIP-1155 规定替换字面量小写 {id}，并使用 64 位小写十六进制。
        String raw = tokenId.toString(16);
        String hex = "0".repeat(64 - raw.length()) + raw;
        return value.replace("{id}", hex);
    }

    private String text(JsonNode node, int limit) {
        return node != null && node.isTextual() ? truncate(node.asString(), limit) : null;
    }

    private String scalar(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.isString() ? node.asString() : node.toString();
    }

    private String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    private boolean jsonContentType(String contentType) {
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.equals("application/json") || type.startsWith("application/") && type.endsWith("+json");
    }

    private String percentDecode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private void rejectTraversal(String path) {
        String decoded = path;
        for (int depth = 0; depth < 5; depth++) {
            if (decoded.matches("(?i).*%(2f|5c).*")) {
                throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
            }
            for (String part : decoded.split("/")) {
                if (part.equals(".") || part.equals("..") || part.contains("\\") || part.indexOf('\0') >= 0) {
                    throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
                }
            }
            String next = percentDecode(decoded);
            if (next.equals(decoded)) return;
            decoded = next;
        }
        throw new NftMetadataException(NftMetadataFailureCode.INVALID_URI);
    }

    private Duration remaining(long deadline) {
        long value = deadline - System.nanoTime();
        if (value <= 0) throw new NftMetadataException(NftMetadataFailureCode.TIMEOUT);
        return Duration.ofNanos(value);
    }

    private boolean startsReverted(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).startsWith("execution reverted");
    }

    private NftMetadataResult failure(NftHolding holding, NftMetadataFailureCode code) {
        return new NftMetadataResult(holding, null, code, null, null);
    }

    private NftMetadataResult cache(CacheKey key, NftMetadataResult result, NftMetadataFailureCode code) {
        Duration ttl = code == null ? options.positiveTtl() : code == NftMetadataFailureCode.NOT_FOUND
                ? options.negativeTtl() : options.errorTtl();
        cache.put(key, new CacheValue(result.metadata(), result.failureCode(), result.displayableImageUrl(),
                result.imageReasonCode(), System.nanoTime() + ttl.toNanos()));
        return result;
    }

    private NftMetadataResult getCache(CacheKey key, NftHolding holding) {
        CacheValue value = cache.getIfPresent(key);
        if (value != null && value.expiresAt() <= System.nanoTime()) {
            cache.invalidate(key);
            return null;
        }
        return value == null ? null : value.toResult(holding);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record CacheKey(long chainId, String contract, BigInteger tokenId, String uri) { }
    private record CacheValue(NftMetadata metadata, NftMetadataFailureCode failureCode, String displayableImageUrl,
            String imageReasonCode, long expiresAt) {
        private NftMetadataResult toResult(NftHolding holding) {
            return new NftMetadataResult(holding, metadata, failureCode, displayableImageUrl, imageReasonCode);
        }
    }
}
