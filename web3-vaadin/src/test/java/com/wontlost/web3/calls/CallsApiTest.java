package com.wontlost.web3;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.calls.Call;
import com.wontlost.web3.calls.CallsRequest;
import com.wontlost.web3.calls.CallsStatus;
import com.wontlost.web3.calls.CallsSubmission;
import com.wontlost.web3.calls.FallbackPolicy;
import com.wontlost.web3.calls.WalletCapabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallsApiTest {
    @Test
    void serializesAndDeserializesCallRecordsWithHexChainIds() {
        CallsRequest request = new CallsRequest(null, "0xabc", 1, false,
                List.of(new Call("0xdef", "0x1234", "0x0")), Map.of());
        String json = request.toProviderJson();
        assertTrue(json.contains("\"chainId\":\"0x1\""));
        assertTrue(json.contains("\"version\":\"2.0.0\""));
        assertFalse(json.contains("0x01"));
        CallsSubmission response = CallsSubmission.fromJson("{\"id\":\"batch-1\"}");
        assertEquals("batch-1", response.id());
        assertTrue(response.transactionHashes().isEmpty());
    }

    @Test
    void mapsStatusCodesAndParsesHexChainAndReceipts() {
        assertEquals(CallsStatus.Status.PENDING, CallsStatus.Status.fromCode(100));
        assertEquals(CallsStatus.Status.CONFIRMED, CallsStatus.Status.fromCode(200));
        assertEquals(CallsStatus.Status.OFFCHAIN_FAILURE, CallsStatus.Status.fromCode(400));
        assertEquals(CallsStatus.Status.REVERTED, CallsStatus.Status.fromCode(500));
        assertEquals(CallsStatus.Status.PARTIALLY_REVERTED, CallsStatus.Status.fromCode(600));
        assertEquals(CallsStatus.Status.UNKNOWN, CallsStatus.Status.fromCode(999));
        CallsStatus status = CallsStatus.fromJson("{\"id\":\"x\",\"chainId\":\"0x89\",\"status\":200,"
                + "\"atomic\":false,\"receipts\":[{\"transactionHash\":\"0xhash\",\"status\":\"0x1\"}]}");
        assertEquals(137L, status.chainId());
        assertEquals("0xhash", status.receipts().getFirst().transactionHash());
        assertEquals(CallsStatus.Status.CONFIRMED, status.statusType());
        assertTrue(status.toJson().contains("\"chainId\":\"0x89\""));
        assertFalse(status.toJson().contains("0x089"));
    }

    @Test
    void readsAtomicCapabilityPerChainAndPreservesOriginalJson() {
        WalletCapabilities capabilities = WalletCapabilities.fromJson("{\"0x1\":{\"atomic\":{"
                + "\"status\":\"ready\"}},\"0x2\":{}} ");
        assertEquals(WalletCapabilities.AtomicStatus.READY, capabilities.atomicByChain().get("0x1"));
        assertEquals(WalletCapabilities.AtomicStatus.ABSENT, capabilities.atomicByChain().get("0x2"));
        assertTrue(capabilities.toJson().contains("\"status\":\"ready\""));
    }

    @Test
    void appliesFallbackDecisionTable() {
        for (int code : new int[] {4200, -32601}) {
            assertTrue(Web3Connect.shouldFallback(code, FallbackPolicy.ALLOW_NON_ATOMIC, false));
            assertFalse(Web3Connect.shouldFallback(code, FallbackPolicy.NEVER, false));
            assertFalse(Web3Connect.shouldFallback(code, FallbackPolicy.ALLOW_NON_ATOMIC, true));
        }
        for (int code : new int[] {4001, 4100, -32602, 5700, 5760}) {
            assertFalse(Web3Connect.shouldFallback(code, FallbackPolicy.ALLOW_NON_ATOMIC, false));
        }
    }

    @Test
    void sequentialFallbackStopsAtFirstFailureAndReturnsHashesSentSoFar() {
        CallsRequest request = new CallsRequest("batch", null, 1, false,
                List.of(new Call("0x1", null, null), new Call("0x2", null, null),
                        new Call("0x3", null, null)), Map.of());
        java.util.List<String> sent = new java.util.ArrayList<>();
        CallsSubmission result = Web3Connect.sendCallsSequentially(request, call -> {
            sent.add(call.to());
            return call.to().equals("0x2")
                    ? java.util.concurrent.CompletableFuture.failedFuture(new Web3Connect.Web3Exception(4001, "rejected"))
                    : java.util.concurrent.CompletableFuture.completedFuture("hash-" + call.to());
        }).join();
        assertEquals(List.of("0x1", "0x2"), sent);
        assertEquals(List.of("hash-0x1"), result.transactionHashes());
        assertEquals(List.of("rejected"), result.errors());
        assertTrue(result.nonAtomicFallback());
    }

    @Test
    void javaAndFrontendServerWalletAllowListsMatch() throws Exception {
        Field field = Web3Connect.class.getDeclaredField("SERVER_WALLET_METHODS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> javaMethods = (Set<String>) field.get(null);
        String frontend = new String(Web3Connect.class.getResourceAsStream("/META-INF/frontend/web3-server-wallet.js")
                .readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("const FORWARDED_METHODS = \\[(.*?)\\];", Pattern.DOTALL).matcher(frontend);
        assertTrue(matcher.find());
        Matcher methods = Pattern.compile("'([^']+)'").matcher(matcher.group(1));
        java.util.HashSet<String> frontendMethods = new java.util.HashSet<>();
        while (methods.find()) frontendMethods.add(methods.group(1));
        assertEquals(javaMethods, frontendMethods);
    }

}
