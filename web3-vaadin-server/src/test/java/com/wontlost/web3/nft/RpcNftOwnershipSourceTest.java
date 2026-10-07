package com.wontlost.web3.nft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.JsonRpcTransport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RpcNftOwnershipSourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CHAIN = 31337;
    private static final String OWNER = "0x0000000000000000000000000000000000000011";
    private static final String OTHER = "0x0000000000000000000000000000000000000022";
    private static final String CONTRACT = "0x0000000000000000000000000000000000000033";

    @Test
    void pinsAllCallsToOneSnapshotAndPaginatesExplicitIds() {
        FixtureTransport transport = new FixtureTransport();
        ChainRegistry chains = registry(transport);
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(chains, 2, 2, 1, 20)) {
            NftCollection collection = collection(NftStandard.ERC1155, List.of(1, 2, 3));
            NftOwnershipPage first = source.find(CHAIN, OWNER, List.of(collection), null, 2);
            NftOwnershipPage second = source.find(CHAIN, OWNER, List.of(collection), first.nextCursor(), 2);

            assertEquals(77, first.snapshotBlock());
            assertEquals(first.snapshotBlock(), second.snapshotBlock());
            assertEquals(2, transport.blockNumberCalls.get());
            assertEquals(3, transport.batchCalls.get());
            assertEquals(3, transport.supportsCalls.get());
            assertNotNull(first.nextCursor());
            assertNull(second.nextCursor());
            assertEquals(List.of(BigInteger.ONE, BigInteger.TWO), first.holdings().stream()
                    .map(NftHolding::tokenId).toList());
            assertEquals(List.of(BigInteger.valueOf(3)), second.holdings().stream()
                    .map(NftHolding::tokenId).toList());
            assertTrue(transport.blockTags.stream().allMatch(tag -> tag.equals("0x4d")));
            assertThrows(IllegalArgumentException.class,
                    () -> source.find(CHAIN, OWNER, List.of(collection(NftStandard.ERC1155, List.of(8))),
                            first.nextCursor(), 2));
        }
    }

    @Test
    void rejectsEnumerableCursorPositionPastOwnerBalance() {
        FixtureTransport transport = new FixtureTransport();
        transport.enumerableBalance = 3;
        NftCollection collection = new NftCollection(CHAIN, CONTRACT, NftStandard.ERC721, true, List.of());
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 2, 2, 2, 20)) {
            NftOwnershipPage page = source.find(CHAIN, OWNER, List.of(collection), null, 1);
            assertNotNull(page.nextCursor(), page.toString());
            String[] fields = decodeCursor(page.nextCursor());

            assertInvalidCursor(source, collection, replace(fields, 3, "4"));
        }
    }

    @Test
    void returnsUnavailableWhenBoundedExecutorQueueIsFull() {
        FixtureTransport transport = new FixtureTransport();
        transport.enumerableBalance = 100;
        transport.tokenIndexDelayMillis = 100;
        NftCollection collection = new NftCollection(CHAIN, CONTRACT, NftStandard.ERC721, true, List.of());
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 1, 100, 2, 100, 1)) {
            long started = System.nanoTime();
            NftOwnershipPage page = source.find(CHAIN, OWNER, List.of(collection), null, 100);
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals(NftFailureCode.UNAVAILABLE, page.failures().getFirst().code());
            assertEquals(NftFailureCode.UNAVAILABLE.message(), page.failures().getFirst().message());
            assertTrue(page.holdings().isEmpty());
            assertTrue(elapsedMillis < 2_000, "bounded queue rejection must not wait for all submitted work");
        }
    }

    @Test
    void closingSourceInterruptsPendingPageAndReturnsUnavailable() throws Exception {
        FixtureTransport transport = new FixtureTransport();
        transport.enumerableBalance = 50;
        transport.tokenIndexDelayMillis = 500;
        NftCollection first = collection(NftStandard.ERC1155, List.of(1));
        NftCollection enumerable = new NftCollection(CHAIN, "0x0000000000000000000000000000000000000044",
                NftStandard.ERC721, true, List.of());
        RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 1, 100, 100, 100, 100);
        CompletableFuture<NftOwnershipPage> query = CompletableFuture.supplyAsync(
                () -> source.find(CHAIN, OWNER, List.of(first, enumerable), null, 100));

        try {
            assertTrue(transport.tokenIndexStarted.await(2, TimeUnit.SECONDS));
            source.close();

            NftOwnershipPage page = query.get(2, TimeUnit.SECONDS);
            assertTrue(page.holdings().isEmpty());
            assertEquals(2, page.failures().size());
            assertTrue(page.failures().stream().allMatch(failure -> failure.code() == NftFailureCode.UNAVAILABLE));
        } finally {
            source.close();
        }
    }

    @Test
    void interruptingCallerCancelsPendingPageAndReturnsUnavailable() throws Exception {
        FixtureTransport transport = new FixtureTransport();
        transport.enumerableBalance = 50;
        transport.tokenIndexDelayMillis = 500;
        NftCollection first = collection(NftStandard.ERC1155, List.of(1));
        NftCollection enumerable = new NftCollection(CHAIN, "0x0000000000000000000000000000000000000044",
                NftStandard.ERC721, true, List.of());
        RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 1, 100, 100, 100, 100);
        AtomicReference<NftOwnershipPage> result = new AtomicReference<>();
        AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);
        Thread caller = new Thread(() -> {
            result.set(source.find(CHAIN, OWNER, List.of(first, enumerable), null, 100));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });

        try {
            caller.start();
            assertTrue(transport.tokenIndexStarted.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);

            assertFalse(caller.isAlive());
            assertTrue(interruptRestored.get());
            assertTrue(result.get().holdings().isEmpty());
            assertEquals(2, result.get().failures().size());
            assertTrue(result.get().failures().stream()
                    .allMatch(failure -> failure.code() == NftFailureCode.UNAVAILABLE));
        } finally {
            source.close();
            caller.join(2_000);
        }
    }

    @Test
    void rejectsCursorWithOutOfRangePositionFutureSnapshotOrWrongFieldCount() {
        FixtureTransport transport = new FixtureTransport();
        NftCollection collection = collection(NftStandard.ERC1155, List.of(1, 2, 3));
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 2, 2, 1, 20)) {
            NftOwnershipPage page = source.find(CHAIN, OWNER, List.of(collection), null, 2);
            String[] fields = decodeCursor(page.nextCursor());

            assertInvalidCursor(source, collection, replace(fields, 3, "4"));
            assertInvalidCursor(source, collection, replace(fields, 5, "78"));
            assertInvalidCursor(source, collection, new String[] { fields[0], fields[1] });
        }
    }

    @Test
    void continuesFromTheMiddleOfAnExplicitRange() {
        FixtureTransport transport = new FixtureTransport();
        NftCollection collection = new NftCollection(CHAIN, CONTRACT, NftStandard.ERC1155, false, List.of(),
                List.of(new NftTokenIdRange(BigInteger.TEN, BigInteger.valueOf(14))));
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 2, 2, 2, 20)) {
            NftOwnershipPage first = source.find(CHAIN, OWNER, List.of(collection), null, 2);
            NftOwnershipPage second = source.find(CHAIN, OWNER, List.of(collection), first.nextCursor(), 2);
            NftOwnershipPage third = source.find(CHAIN, OWNER, List.of(collection), second.nextCursor(), 2);

            assertEquals(List.of(BigInteger.TEN, BigInteger.valueOf(11)), first.holdings().stream()
                    .map(NftHolding::tokenId).toList());
            assertEquals(List.of(BigInteger.valueOf(12), BigInteger.valueOf(13)), second.holdings().stream()
                    .map(NftHolding::tokenId).toList());
            assertEquals(List.of(BigInteger.valueOf(14)), third.holdings().stream()
                    .map(NftHolding::tokenId).toList());
            assertNull(third.nextCursor());
        }
    }

    @Test
    void distinguishesUnsupportedUnavailableInvalidCollectionAndMissingToken() {
        FixtureTransport unsupported = new FixtureTransport();
        unsupported.supported = false;
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(unsupported))) {
            assertEquals(NftFailureCode.UNSUPPORTED,
                    source.find(CHAIN, OWNER, List.of(collection(NftStandard.ERC1155, List.of(1))), null, 10)
                            .failures().getFirst().code());
        }

        FixtureTransport unavailable = new FixtureTransport();
        unavailable.failRpc = true;
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(unavailable))) {
            List<NftCollection> collections = List.of(collection(NftStandard.ERC1155, List.of(1)));
            assertEquals(NftFailureCode.UNAVAILABLE,
                    source.find(CHAIN, OWNER, collections, null, 10).failures().getFirst().code());
            assertEquals(NftFailureCode.UNAVAILABLE,
                    source.find(CHAIN, OWNER, collections, null, 10).failures().getFirst().code());
            assertEquals(2, unavailable.supportsCalls.get());
        }

        FixtureTransport invalidResponse = new FixtureTransport();
        invalidResponse.emptySupportsResponse = true;
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(invalidResponse))) {
            assertEquals(NftFailureCode.INVALID_RESPONSE,
                    source.find(CHAIN, OWNER, List.of(collection(NftStandard.ERC1155, List.of(1))), null, 10)
                            .failures().getFirst().code());
        }

        FixtureTransport mismatch = new FixtureTransport();
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(mismatch))) {
            NftOwnershipPage page = source.find(CHAIN, OWNER,
                    List.of(new NftCollection(CHAIN + 1, CONTRACT, NftStandard.ERC1155, false, List.of(BigInteger.ONE))),
                    null, 10);
            assertEquals(NftFailureCode.INVALID_COLLECTION, page.failures().getFirst().code());
        }

        FixtureTransport missing721 = new FixtureTransport();
        missing721.missingToken = true;
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(missing721))) {
            NftOwnershipPage page = source.find(CHAIN, OWNER,
                    List.of(collection(NftStandard.ERC721, List.of(99))), null, 10);
            assertTrue(page.holdings().isEmpty());
            assertTrue(page.failures().isEmpty());
        }

        FixtureTransport ownerUnavailable = new FixtureTransport();
        ownerUnavailable.ownerUnavailable = true;
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(ownerUnavailable))) {
            NftOwnershipPage page = source.find(CHAIN, OWNER,
                    List.of(collection(NftStandard.ERC721, List.of(99))), null, 10);
            assertEquals(NftFailureCode.UNAVAILABLE, page.failures().getFirst().code());
            assertTrue(page.holdings().isEmpty());
            assertEquals("NFT ownership service is temporarily unavailable.",
                    page.failures().getFirst().message());
        }
    }

    @Test
    void doesNotExposeRpcErrorTextInOwnershipPage() {
        String secretEndpoint = "https://secret-rpc.example/key";
        FixtureTransport transport = new FixtureTransport();
        transport.failRpc = true;
        transport.failureMessage = "request failed at " + secretEndpoint;

        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport))) {
            NftOwnershipPage page = source.find(CHAIN, OWNER,
                    List.of(collection(NftStandard.ERC1155, List.of(1))), null, 10);

            assertFalse(page.toString().contains(secretEndpoint));
            assertEquals(NftFailureCode.UNAVAILABLE.message(), page.failures().getFirst().message());
            assertFalse(page.failures().getFirst().message().contains(secretEndpoint));
        }
    }

    @Test
    void capsParallelOwnerLookupsAndRejectsOversizedPage() {
        FixtureTransport transport = new FixtureTransport();
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 2, 2, 2, 20)) {
            List<BigInteger> ids = List.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4));
            NftOwnershipPage first = source.find(CHAIN, OWNER,
                    new ArrayList<>(List.of(new NftCollection(CHAIN, CONTRACT, NftStandard.ERC721, false, ids))),
                    null, 2);
            assertEquals(2, first.holdings().size(), first.failures().toString());
            assertTrue(transport.maxConcurrent.get() <= 2);
            assertEquals(2, transport.maxConcurrent.get());
            assertTrue(first.nextCursor() != null);
            assertThrows(IllegalArgumentException.class,
                    () -> source.find(CHAIN, OWNER, List.of(collection(NftStandard.ERC1155, List.of(1))), null, 3));
        }
    }

    @Test
    void rejectsConstructorLimitsAboveSupportedMaximums() {
        ChainRegistry chains = new ChainRegistry();
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 65, 10, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 1, 501, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 1, 10, 501, 10));
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 1, 10, 10, 10_001));
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 1, 10, 10, 10, 4097));
    }

    @Test
    void returnsUnavailableForTheWholePageWhenTheExecutorQueueIsFull() {
        FixtureTransport transport = new FixtureTransport();
        transport.enumerableBalance = 100;
        transport.tokenIndexDelayMillis = 25;
        NftCollection held1155 = collection(NftStandard.ERC1155, List.of(1));
        NftCollection enumerable721 = new NftCollection(CHAIN, "0x0000000000000000000000000000000000000044",
                NftStandard.ERC721, true, List.of());
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 1, 100, 100, 1000)) {
            NftOwnershipPage page = source.find(CHAIN, OWNER, List.of(held1155, enumerable721), null, 100);

            assertTrue(page.holdings().isEmpty());
            assertEquals(2, page.failures().size());
            assertTrue(page.failures().stream().allMatch(failure -> failure.code() == NftFailureCode.UNAVAILABLE));
            assertTrue(page.failures().stream().allMatch(failure -> failure.message()
                    .equals("NFT ownership service is temporarily unavailable.")));
        }
    }

    @Test
    void rejectsResourceLimitsAboveSupportedMaximums() {
        ChainRegistry chains = registry(new FixtureTransport());

        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 65, 100, 100, 1000));
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 8, 501, 100, 1000));
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 8, 100, 501, 1000));
        assertThrows(IllegalArgumentException.class, () -> new RpcNftOwnershipSource(chains, 8, 100, 100, 10001));
    }

    @Test
    void capsTokenQueriesAcrossCollectionsAndMarksUnknownNonEnumerableOwnershipUnsupported() {
        FixtureTransport transport = new FixtureTransport();
        transport.zeroBalances = true;
        NftCollection first = collection(NftStandard.ERC1155, List.of(1, 2));
        NftCollection second = new NftCollection(CHAIN, "0x0000000000000000000000000000000000000044",
                NftStandard.ERC1155, false, List.of(BigInteger.ONE, BigInteger.TWO));
        NftCollection third = new NftCollection(CHAIN, "0x0000000000000000000000000000000000000055",
                NftStandard.ERC721, true, List.of());
        try (RpcNftOwnershipSource source = new RpcNftOwnershipSource(registry(transport), 2, 10, 1, 2)) {
            NftOwnershipPage page = source.find(CHAIN, OWNER, List.of(first, second, third), null, 10);
            NftOwnershipPage nextPage = source.find(CHAIN, OWNER, List.of(first, second, third), page.nextCursor(), 10);

            assertTrue(page.holdings().isEmpty());
            assertNotNull(page.nextCursor());
            assertTrue(nextPage.holdings().isEmpty());
            assertNotNull(nextPage.nextCursor());
            assertEquals(4, transport.batchCalls.get());
            NftOwnershipPage unsupported = source.find(CHAIN, OWNER,
                    List.of(new NftCollection(CHAIN, CONTRACT, NftStandard.ERC721, false, List.of())), null, 2);
            assertEquals(NftFailureCode.UNSUPPORTED, unsupported.failures().getFirst().code());
        }
    }

    private ChainRegistry registry(FixtureTransport transport) {
        ChainRegistry registry = new ChainRegistry();
        registry.register(CHAIN, new EthRpcClient(transport));
        return registry;
    }

    private NftCollection collection(NftStandard standard, List<Integer> ids) {
        return new NftCollection(CHAIN, CONTRACT, standard, false,
                ids.stream().map(BigInteger::valueOf).toList());
    }

    private void assertInvalidCursor(RpcNftOwnershipSource source, NftCollection collection, String[] fields) {
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join(":", fields).getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> source.find(CHAIN, OWNER, List.of(collection), cursor, 2));
        assertEquals("Invalid NFT ownership cursor", exception.getMessage());
    }

    private String[] decodeCursor(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
    }

    private String[] replace(String[] fields, int index, String value) {
        String[] changed = fields.clone();
        changed[index] = value;
        return changed;
    }

    private static String word(BigInteger value) {
        return String.format("%064x", value);
    }

    private static final class FixtureTransport implements JsonRpcTransport {
        private final List<String> blockTags = new CopyOnWriteArrayList<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger blockNumberCalls = new AtomicInteger();
        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger supportsCalls = new AtomicInteger();
        private final CountDownLatch tokenIndexStarted = new CountDownLatch(1);
        private boolean supported = true;
        private boolean failRpc;
        private String failureMessage = "RPC unavailable";
        private boolean missingToken;
        private boolean ownerUnavailable;
        private boolean emptySupportsResponse;
        private boolean zeroBalances;
        private int enumerableBalance = 1;
        private long tokenIndexDelayMillis;

        @Override
        public String send(String requestJson) throws IOException {
            JsonNode request = JSON.readTree(requestJson);
            String method = request.path("method").asString();
            JsonNode params = request.path("params");
            String result;
            if ("eth_blockNumber".equals(method)) {
                blockNumberCalls.incrementAndGet();
                result = "\"0x4d\"";
            } else if ("eth_call".equals(method)) {
                String blockTag = params.get(1).asString();
                blockTags.add(blockTag);
                String data = params.get(0).path("data").asString();
                if (data.startsWith("0x01ffc9a7")) {
                    supportsCalls.incrementAndGet();
                }
                if (failRpc) {
                    return error(request.path("id").toString(), -32000, failureMessage);
                }
                if (data.startsWith("0x01ffc9a7")) {
                    result = emptySupportsResponse ? "\"0x\"" : bool(supported);
                } else if (data.startsWith("0x4e1273f4")) {
                    batchCalls.incrementAndGet();
                    int words = (data.length() - 10) / 64;
                    int length = (words - 4) / 2;
                    StringBuilder values = new StringBuilder("0x").append(word(BigInteger.valueOf(32))).append(word(BigInteger.valueOf(length)));
                    for (int index = 0; index < length; index++) {
                        values.append(word(zeroBalances ? BigInteger.ZERO : BigInteger.valueOf(index + 1)));
                    }
                    result = "\"" + values + "\"";
                } else if (data.startsWith("0x70a08231")) {
                    result = "\"0x" + word(BigInteger.valueOf(enumerableBalance)) + "\"";
                } else if (data.startsWith("0x6352211e")) {
                    int count = active.incrementAndGet();
                    maxConcurrent.accumulateAndGet(count, Math::max);
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                    if (missingToken) {
                        return error(request.path("id").toString(), 3, "execution reverted");
                    }
                    if (ownerUnavailable) {
                        return error(request.path("id").toString(), -32005, "rate limited");
                    }
                    result = "\"0x" + "0".repeat(24) + OWNER.substring(2) + "\"";
                } else {
                    tokenIndexStarted.countDown();
                    if (tokenIndexDelayMillis > 0) {
                        try {
                            Thread.sleep(tokenIndexDelayMillis);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    result = "\"0x" + word(BigInteger.ONE) + "\"";
                }
            } else {
                result = "null";
            }
                ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.set("result", JSON.readTree(result));
            return response.toString();
        }

        private String error(String id) {
            return error(id, -32000, "RPC unavailable");
        }

        private String error(String id, int code, String message) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                    + ",\"message\":\"" + message + "\"}}";
        }

        private String bool(boolean value) {
            return "\"0x" + "0".repeat(63) + (value ? "1" : "0") + "\"";
        }
    }
}
