package com.wontlost.web3.nft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.JsonRpcTransport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RpcNftMetadataResolverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTRACT = "0x0000000000000000000000000000000000000033";
    private static final BigInteger MAXIMUM = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    @Test
    void parsesDataUriAndKeepsOnlySafeImageAndTextFields() {
        String document = "{\"name\":\"title\",\"description\":\"desc\",\"image\":\"ipfs://cid/item.png\","
                + "\"animation_url\":\"javascript:alert(1)\",\"external_url\":\"http://bad.example\","
                + "\"attributes\":[{\"trait_type\":\"Level\",\"value\":7}]}";
        String uri = "data:application/json;base64," + Base64.getEncoder()
                .encodeToString(document.getBytes(StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        try (RpcNftMetadataResolver resolver = resolver(uri, (target, timeout, limit) -> {
            fetches.incrementAndGet();
            throw new AssertionError("data URI must not issue an HTTP request");
        })) {
            NftMetadataResult result = resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst();

            assertTrue(result.successful(), result.toString());
            assertEquals("title", result.metadata().name());
            assertEquals(uri, result.sourceUri());
            assertEquals("https://ipfs.io/ipfs/cid/item.png", result.displayableImageUrl());
            assertEquals("javascript:alert(1)", result.metadata().animationUrl());
            assertNull(result.metadata().externalUrl());
            assertEquals("7", result.metadata().attributes().getFirst().value());
            assertEquals(0, fetches.get());
        }
    }

    @Test
    void expandsMaximumErc1155IdOnlyForLowercasePlaceholderAndMarksSvgUnavailable() {
        String expected = "0".repeat(64).substring(MAXIMUM.toString(16).length()) + MAXIMUM.toString(16);
        String uri = "data:application/json,%7B%22name%22%3A%22{id}%20{ID}%22%2C%22image%22%3A%22https%3A%2F%2F8.8.8.8%2Fa.svg%22%7D";
        try (RpcNftMetadataResolver resolver = resolver(uri, unusedFetcher())) {
            NftMetadataResult result = resolver.resolve(List.of(holding(NftStandard.ERC1155, MAXIMUM))).getFirst();

            assertTrue(result.successful(), result.toString());
            assertEquals(expected + " {ID}", result.metadata().name());
            assertNull(result.displayableImageUrl());
            assertEquals("SVG_IMAGE", result.imageReasonCode());
        }
    }

    @Test
    void rejectsUnsupportedDataMimeAndMalformedJsonWithStableCodes() {
        try (RpcNftMetadataResolver resolver = resolver("data:text/html,<script>x</script>", unusedFetcher())) {
            NftMetadataResult result = resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst();
            assertEquals(NftMetadataFailureCode.UNSUPPORTED_URI, result.failureCode());
            assertEquals("data:text/html,<script>x</script>", result.sourceUri());
        }
        try (RpcNftMetadataResolver resolver = resolver("data:application/json,%7Bbad", unusedFetcher())) {
            assertEquals(NftMetadataFailureCode.INVALID_JSON,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
        try (RpcNftMetadataResolver resolver = resolver("data:application/json,%7B%7D%20garbage", unusedFetcher())) {
            assertEquals(NftMetadataFailureCode.INVALID_JSON,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
    }

    @Test
    void rejectsUnsafeHostsAndPortsBeforeFetcherAndRejectsRedirectToPrivateAddress() {
        for (String target : List.of("http://127.0.0.1/a", "https://[::1]/a", "https://10.0.0.1/a",
                "https://169.254.169.254/latest", "https://[::ffff:127.0.0.1]/a")) {
            try (RpcNftMetadataResolver resolver = resolver(target, unusedFetcher())) {
                NftMetadataFailureCode expected = target.startsWith("http:")
                        ? NftMetadataFailureCode.UNSUPPORTED_URI : NftMetadataFailureCode.UNSAFE_TARGET;
                assertEquals(expected, resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE)))
                        .getFirst().failureCode());
            }
        }
        NftMetadataFetcher redirect = (target, timeout, limit) -> new NftMetadataFetchResponse(302, "", "http://127.0.0.1/", null);
        try (RpcNftMetadataResolver resolver = resolver("https://example.com/a", redirect)) {
            assertEquals(NftMetadataFailureCode.UNSAFE_TARGET,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
    }

    @Test
    void rejectsPrivateDnsAnswersAndIpfsTraversal() throws Exception {
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(new UriTransport("https://private.example/item")));
        NftDnsResolver privateDns = host -> new java.net.InetAddress[] { java.net.InetAddress.getByName("10.0.0.1") };
        try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, unusedFetcher(),
                NftMetadataOptions.defaults(), privateDns)) {
            assertEquals(NftMetadataFailureCode.UNSAFE_TARGET,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
        assertFalse(com.wontlost.web3.net.PublicAddressPolicy.isPublic(
                java.net.InetAddress.getByName("192.88.99.3")));
        try (RpcNftMetadataResolver resolver = resolver("ipfs://bafybeigdyrzt/path/%2e%2e/secret", unusedFetcher())) {
            assertEquals(NftMetadataFailureCode.INVALID_URI,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
        for (String path : List.of("ipfs://bafybeigdyrzt/%252e%252e/secret", "ipfs://bafybeigdyrzt/a%252fb")) {
            try (RpcNftMetadataResolver resolver = resolver(path, unusedFetcher())) {
                assertEquals(NftMetadataFailureCode.INVALID_URI,
                        resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
            }
        }
    }

    @Test
    void rejectsLegacyLoopbackHostFormsAndResolvesIdnThroughInjectedDns() throws Exception {
        // JDK 21 URI.getHost 对 127.1 与 0x7f.1 返回 null；十进制整数由 InetAddress 解析为回环地址。
        List<String> unsafeTargets = List.of("https://2130706433/", "https://0x7f.1/",
                "https://127.1/", "https://localhost./");
        for (String target : unsafeTargets) {
            AtomicInteger fetches = new AtomicInteger();
            NftDnsResolver systemLookup = InetAddress::getAllByName;
            try (RpcNftMetadataResolver resolver = resolver(target,
                    (uri, timeout, limit) -> {
                        fetches.incrementAndGet();
                        return jsonResponse("{}");
                    }, systemLookup)) {
                NftMetadataResult result = resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE)))
                        .getFirst();

                assertEquals(NftMetadataFailureCode.UNSAFE_TARGET, result.failureCode(), target);
                assertEquals(0, fetches.get(), target);
            }
        }

        // Java 21 不把该八进制整数识别为 IPv4；地址策略按其 127.0.0.1 数值形式稳定拒绝。
        try (RpcNftMetadataResolver resolver = resolver("https://017700000001/", unusedFetcher())) {
            assertEquals(NftMetadataFailureCode.UNSAFE_TARGET,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }

        AtomicInteger fetches = new AtomicInteger();
        NftDnsResolver idnLookup = host -> {
            assertEquals("xn--fsq.com", host);
            return new InetAddress[] { InetAddress.getByName("8.8.8.8") };
        };
        try (RpcNftMetadataResolver resolver = resolver("https://xn--fsq.com/item.json",
                (uri, timeout, limit) -> {
                    fetches.incrementAndGet();
                    return jsonResponse("{}");
                }, idnLookup)) {
            assertTrue(resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().successful());
            assertEquals(1, fetches.get());
        }
    }

    @Test
    void connectionTimeDnsRebindingToPrivateAddressIsClassifiedAsUnsafeTarget() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        NftDnsResolver changingLookup = host -> {
            if (lookups.incrementAndGet() == 1) {
                return new InetAddress[] { InetAddress.getByName("8.8.8.8") };
            }
            return new InetAddress[] { InetAddress.getByName("10.0.0.1") };
        };
        try (ApacheNftMetadataFetcher fetcher = new ApacheNftMetadataFetcher(Set.of(443), changingLookup);
                RpcNftMetadataResolver resolver = resolver("https://nft.example/item.json", fetcher, changingLookup)) {
            NftMetadataResult result = resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE)))
                    .getFirst();

            assertEquals(NftMetadataFailureCode.UNSAFE_TARGET, result.failureCode());
            assertTrue(lookups.get() >= 2);
        }
    }

    @Test
    void triesConfiguredIpfsGatewaysAndCachesSuccessfulResponse() {
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(new UriTransport("ipfs://bafybeigdyrzt/item.json")));
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        NftMetadataOptions options = new NftMetadataOptions(
                List.of("https://8.8.8.8/ipfs/", "https://1.1.1.1/ipfs/"), defaults.maxResponseBytes(),
                defaults.maxDataUriBytes(), defaults.maxRedirects(), defaults.requestTimeout(), defaults.cacheCapacity(),
                defaults.positiveTtl(), defaults.negativeTtl(), defaults.errorTtl(), defaults.maxConcurrency(),
                defaults.allowedPorts(), defaults.maxAttributes(), defaults.maxTextLength());
        AtomicInteger calls = new AtomicInteger();
        try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, (uri, timeout, limit) -> {
            calls.incrementAndGet();
            if (uri.getHost().equals("8.8.8.8")) {
                return new NftMetadataFetchResponse(503, "", null, null);
            }
            return new NftMetadataFetchResponse(200, "application/json", null,
                    "{\"name\":\"cached\"}".getBytes(StandardCharsets.UTF_8));
        }, options)) {
            NftHolding holding = holding(NftStandard.ERC721, BigInteger.valueOf(9));
            assertTrue(resolver.resolve(List.of(holding)).getFirst().successful());
            assertTrue(resolver.resolve(List.of(holding)).getFirst().successful());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void gatewayFallbackStopsForContentErrorsButContinuesForTransportFailures() {
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(new UriTransport("ipfs://bafybeigdyrzt/item.json")));
        NftMetadataOptions options = optionsWithGateways();
        AtomicInteger contentCalls = new AtomicInteger();
        NftMetadataFetcher badContent = (uri, timeout, limit) -> {
            contentCalls.incrementAndGet();
            return new NftMetadataFetchResponse(200, "text/plain", null, "{}".getBytes(StandardCharsets.UTF_8));
        };
        try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, badContent, options)) {
            assertEquals(NftMetadataFailureCode.UNSUPPORTED_CONTENT_TYPE,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
            assertEquals(1, contentCalls.get());
        }

        List<NftMetadataFailureCode> expectedCodes = List.of(NftMetadataFailureCode.RESPONSE_TOO_LARGE,
                NftMetadataFailureCode.INVALID_JSON);
        List<NftMetadataFetcher> invalidResponses = List.of(
                (uri, timeout, limit) -> new NftMetadataFetchResponse(200, "application/json", null,
                        new byte[limit + 1]),
                (uri, timeout, limit) -> jsonResponse("{bad"));
        for (int index = 0; index < invalidResponses.size(); index++) {
            AtomicInteger calls = new AtomicInteger();
            NftMetadataFetcher source = invalidResponses.get(index);
            NftMetadataFetcher counted = (uri, timeout, limit) -> {
                calls.incrementAndGet();
                return source.fetch(uri, timeout, limit);
            };
            try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, counted, options)) {
                NftMetadataResult result = resolver.resolve(List.of(holding(NftStandard.ERC721,
                        BigInteger.valueOf(index + 2)))).getFirst();
                assertEquals(expectedCodes.get(index), result.failureCode());
                assertEquals(1, calls.get());
            }
        }
    }

    @Test
    void retriesNextIpfsGatewayAfterTimeout() {
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(new UriTransport("ipfs://bafybeigdyrzt/item.json")));
        AtomicInteger calls = new AtomicInteger();
        NftMetadataFetcher fetcher = (uri, timeout, limit) -> {
            calls.incrementAndGet();
            if (uri.getHost().equals("8.8.8.8")) {
                throw new NftMetadataException(NftMetadataFailureCode.TIMEOUT);
            }
            return jsonResponse("{}");
        };
        try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, fetcher, optionsWithGateways())) {
            assertTrue(resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().successful());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void rejectsOversizedHttpsUriAndAcceptsUriExactlyAtConfiguredLimit() {
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        String tooLong = "https://8.8.8.8/" + "a".repeat(32);
        AtomicInteger fetches = new AtomicInteger();
        try (RpcNftMetadataResolver resolver = resolver(tooLong, (uri, timeout, limit) -> {
            fetches.incrementAndGet();
            return jsonResponse("{}");
        }, InetAddress::getAllByName, options(defaults, tooLong.length() - 1, defaults.maxDataUriBytes()))) {
            assertEquals(NftMetadataFailureCode.URI_TOO_LONG,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
            assertEquals(0, fetches.get());
        }

        String exact = "https://8.8.8.8/";
        try (RpcNftMetadataResolver resolver = resolver(exact, (uri, timeout, limit) -> jsonResponse("{}"),
                InetAddress::getAllByName, options(defaults, exact.codePointCount(0, exact.length()),
                        defaults.maxDataUriBytes()))) {
            assertTrue(resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.TWO))).getFirst().successful());
        }
    }

    @Test
    void appliesDataUriByteLimitInsteadOfGeneralUriLengthLimit() {
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        String tooLargeDocument = "{\"name\":\"" + "x".repeat(40) + "\"}";
        String oversizedDataUri = "data:application/json;base64," + Base64.getEncoder()
                .encodeToString(tooLargeDocument.getBytes(StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        try (RpcNftMetadataResolver resolver = resolver(oversizedDataUri, (uri, timeout, limit) -> {
            fetches.incrementAndGet();
            return jsonResponse("{}");
        }, InetAddress::getAllByName, options(defaults, 8, 8))) {
            assertEquals(NftMetadataFailureCode.URI_TOO_LONG,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
            assertEquals(0, fetches.get());
        }

        String exactDocument = "{\"a\":1}";
        String exactDataUri = "data:application/json;base64," + Base64.getEncoder()
                .encodeToString(exactDocument.getBytes(StandardCharsets.UTF_8));
        try (RpcNftMetadataResolver resolver = resolver(exactDataUri, unusedFetcher(), InetAddress::getAllByName,
                options(defaults, 8, exactDocument.getBytes(StandardCharsets.UTF_8).length))) {
            assertTrue(resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.TWO))).getFirst().successful());
        }
    }

    @Test
    void dataUriMayExceedGeneralUriLengthWhenItsDecodedDocumentIsWithinLimit() {
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        String document = "{\"padding\":\"" + "x".repeat(9000) + "\"}";
        String uri = "data:application/json;base64," + Base64.getEncoder()
                .encodeToString(document.getBytes(StandardCharsets.UTF_8));
        assertTrue(uri.length() > 8192);
        try (RpcNftMetadataResolver resolver = resolver(uri, unusedFetcher(), InetAddress::getAllByName,
                options(defaults, 8192, 10000))) {
            assertTrue(resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().successful());
        }
    }

    @Test
    void cachedMetadataUsesTheLatestErc1155HoldingAmount() {
        AtomicInteger fetches = new AtomicInteger();
        UriTransport transport = new UriTransport("https://8.8.8.8/item.json");
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(transport));
        NftMetadataFetcher fetcher = (uri, timeout, limit) -> {
            fetches.incrementAndGet();
            return jsonResponse("{\"name\":\"cached\"}");
        };
        try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, fetcher,
                NftMetadataOptions.defaults())) {
            NftHolding first = new NftHolding(31337, CONTRACT, BigInteger.ONE, BigInteger.valueOf(2),
                    NftStandard.ERC1155);
            NftHolding updated = new NftHolding(31337, CONTRACT, BigInteger.ONE, BigInteger.valueOf(9),
                    NftStandard.ERC1155);

            assertEquals(BigInteger.valueOf(2), resolver.resolve(List.of(first)).getFirst().holding().amount());
            NftMetadataResult second = resolver.resolve(List.of(updated)).getFirst();

            assertEquals(BigInteger.valueOf(9), second.holding().amount());
            assertEquals("cached", second.metadata().name());
            assertEquals(1, fetches.get());
            assertEquals(2, transport.uriCalls.get());
        }
    }

    @Test
    void keepsBatchFetchConcurrencyWithinConfiguredLimit() {
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(new UriTransport("https://8.8.8.8/item.json")));
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        NftMetadataOptions options = new NftMetadataOptions(defaults.ipfsGateways(), defaults.maxResponseBytes(),
                defaults.maxDataUriBytes(), defaults.maxRedirects(), defaults.requestTimeout(), defaults.cacheCapacity(),
                defaults.positiveTtl(), defaults.negativeTtl(), defaults.errorTtl(), 2, defaults.allowedPorts(),
                defaults.maxAttributes(), defaults.maxTextLength());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        NftMetadataFetcher fetcher = (uri, timeout, limit) -> {
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            try {
                Thread.sleep(30);
                return new NftMetadataFetchResponse(200, "application/json", null, "{}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new NftMetadataException(NftMetadataFailureCode.TIMEOUT);
            } finally {
                active.decrementAndGet();
            }
        };
        try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, fetcher, options)) {
            List<NftHolding> holdings = java.util.stream.IntStream.rangeClosed(1, 5)
                    .mapToObj(id -> holding(NftStandard.ERC721, BigInteger.valueOf(id))).toList();

            assertEquals(5, resolver.resolve(holdings).size());
            assertTrue(maximum.get() <= 2);
            assertEquals(2, maximum.get());
        }
    }

    @Test
    void expiresCachedParseErrorsUsingTheShortErrorTtl() throws Exception {
        UriTransport transport = new UriTransport("data:application/json,%7Bbad");
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(transport));
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        NftMetadataOptions options = new NftMetadataOptions(defaults.ipfsGateways(), defaults.maxResponseBytes(),
                defaults.maxDataUriBytes(), defaults.maxRedirects(), defaults.requestTimeout(), defaults.cacheCapacity(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(20), defaults.maxConcurrency(),
                defaults.allowedPorts(), defaults.maxAttributes(), defaults.maxTextLength());
        try (RpcNftMetadataResolver resolver = new RpcNftMetadataResolver(chains, unusedFetcher(), options)) {
            NftHolding holding = holding(NftStandard.ERC721, BigInteger.valueOf(10));

            resolver.resolve(List.of(holding));
            resolver.resolve(List.of(holding));
            assertEquals(1, transport.uriCalls.get());
            Thread.sleep(40);
            resolver.resolve(List.of(holding));
            assertEquals(2, transport.uriCalls.get());
        }
    }

    @Test
    void enforcesContentTypeRedirectAndBodyBounds() {
        NftMetadataFetcher badType = (target, timeout, limit) -> new NftMetadataFetchResponse(200, "text/plain", null, "{}".getBytes());
        try (RpcNftMetadataResolver resolver = resolver("https://example.com/item", badType)) {
            assertEquals(NftMetadataFailureCode.UNSUPPORTED_CONTENT_TYPE,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
        NftMetadataFetcher bigBody = (target, timeout, limit) -> new NftMetadataFetchResponse(200, "application/json", null, new byte[limit + 1]);
        try (RpcNftMetadataResolver resolver = resolver("https://example.com/item", bigBody)) {
            assertEquals(NftMetadataFailureCode.RESPONSE_TOO_LARGE,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
        NftMetadataFetcher redirect = (target, timeout, limit) -> new NftMetadataFetchResponse(302, "", "/next", null);
        try (RpcNftMetadataResolver resolver = resolver("https://example.com/item", redirect)) {
            assertEquals(NftMetadataFailureCode.TOO_MANY_REDIRECTS,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
    }

    @Test
    void reportsTimeoutAndUnsafeImageReasonsWithoutFailingOtherMetadata() {
        NftMetadataFetcher timeout = (target, requestTimeout, limit) -> {
            throw new NftMetadataException(NftMetadataFailureCode.TIMEOUT);
        };
        try (RpcNftMetadataResolver resolver = resolver("https://8.8.8.8/timeout", timeout)) {
            assertEquals(NftMetadataFailureCode.TIMEOUT,
                    resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.ONE))).getFirst().failureCode());
        }
        String document = "{\"name\":\"kept\",\"image\":\"javascript:alert(1)\"}";
        String dataUri = "data:application/json," + java.net.URLEncoder.encode(document, StandardCharsets.UTF_8);
        try (RpcNftMetadataResolver resolver = resolver(dataUri, unusedFetcher())) {
            NftMetadataResult result = resolver.resolve(List.of(holding(NftStandard.ERC721, BigInteger.TWO))).getFirst();
            assertTrue(result.successful());
            assertEquals("kept", result.metadata().name());
            assertNull(result.displayableImageUrl());
            assertEquals("UNSUPPORTED_URI", result.imageReasonCode());
        }
    }

    @Test
    void validatesConfigurationBounds() {
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> new NftMetadataOptions(defaults.ipfsGateways(),
                defaults.maxResponseBytes(), defaults.maxDataUriBytes(), defaults.maxRedirects(),
                defaults.requestTimeout(), defaults.cacheCapacity(), defaults.positiveTtl(), defaults.negativeTtl(),
                defaults.errorTtl(), 65, Set.of(443), defaults.maxAttributes(), defaults.maxTextLength()));
    }

    private RpcNftMetadataResolver resolver(String uri, NftMetadataFetcher fetcher) {
        return resolver(uri, fetcher, InetAddress::getAllByName);
    }

    private RpcNftMetadataResolver resolver(String uri, NftMetadataFetcher fetcher, NftDnsResolver dnsResolver) {
        return resolver(uri, fetcher, dnsResolver, NftMetadataOptions.defaults());
    }

    private RpcNftMetadataResolver resolver(String uri, NftMetadataFetcher fetcher, NftDnsResolver dnsResolver,
            NftMetadataOptions options) {
        ChainRegistry chains = new ChainRegistry();
        chains.register(31337, new EthRpcClient(new UriTransport(uri)));
        return new RpcNftMetadataResolver(chains, fetcher, options, dnsResolver);
    }

    private NftMetadataOptions options(NftMetadataOptions defaults, int maxUriLength, int maxDataUriBytes) {
        return new NftMetadataOptions(defaults.ipfsGateways(), defaults.maxResponseBytes(), maxDataUriBytes,
                maxUriLength, defaults.maxRedirects(), defaults.requestTimeout(), defaults.cacheCapacity(),
                defaults.positiveTtl(), defaults.negativeTtl(), defaults.errorTtl(), defaults.maxConcurrency(),
                defaults.allowedPorts(), defaults.maxAttributes(), defaults.maxTextLength());
    }

    private NftMetadataOptions optionsWithGateways() {
        NftMetadataOptions defaults = NftMetadataOptions.defaults();
        return new NftMetadataOptions(List.of("https://8.8.8.8/ipfs/", "https://1.1.1.1/ipfs/"),
                defaults.maxResponseBytes(), defaults.maxDataUriBytes(), defaults.maxRedirects(),
                defaults.requestTimeout(), defaults.cacheCapacity(), defaults.positiveTtl(), defaults.negativeTtl(),
                defaults.errorTtl(), defaults.maxConcurrency(), defaults.allowedPorts(), defaults.maxAttributes(),
                defaults.maxTextLength());
    }

    private NftMetadataFetchResponse jsonResponse(String document) {
        return new NftMetadataFetchResponse(200, "application/json", null, document.getBytes(StandardCharsets.UTF_8));
    }

    private NftMetadataFetcher unusedFetcher() {
        return (target, timeout, limit) -> {
            throw new AssertionError("unexpected network call");
        };
    }

    private NftHolding holding(NftStandard standard, BigInteger id) {
        return new NftHolding(31337, CONTRACT, id, BigInteger.ONE, standard);
    }

    private static class UriTransport implements JsonRpcTransport {
        private final String uri;
        private final AtomicInteger uriCalls = new AtomicInteger();
        private UriTransport(String uri) { this.uri = uri; }
        @Override
        public String send(String requestJson) throws IOException {
            JsonNode request = JSON.readTree(requestJson);
            String method = request.path("method").asString();
            String result = "\"0x1\"";
            if ("eth_call".equals(method)) {
                uriCalls.incrementAndGet();
                result = "\"" + encodeString(uri) + "\"";
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").toString() + ",\"result\":" + result + "}";
        }
        private String encodeString(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            String hex = java.util.HexFormat.of().formatHex(bytes);
            int padding = (64 - hex.length() % 64) % 64;
            return "0x" + String.format("%064x", 32) + String.format("%064x", bytes.length) + hex + "0".repeat(padding);
        }
    }
}
