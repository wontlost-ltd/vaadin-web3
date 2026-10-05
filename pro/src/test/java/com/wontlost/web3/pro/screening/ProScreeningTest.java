package com.wontlost.web3.pro.screening;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Hash;

import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.pro.jdbc.ProSchema;
import com.wontlost.web3.screening.AddressScreening;

class ProScreeningTest {
    private JdbcDataSource source;
    @BeforeEach void setup() {
        source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:audit" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ProSchema.create(source);
    }

    @Test void oracleEncodesSelectorParsesAndCachesBooleanResult() {
        AtomicInteger requests = new AtomicInteger();
        EthRpcClient client = new EthRpcClient(request -> {
            requests.incrementAndGet();
            assertTrue(request.contains("0x" + Hash.sha3String("isSanctioned(address)").substring(2, 10)));
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + "0".repeat(63) + "1\"}";
        });
        ChainalysisSanctionsOracle oracle = ChainalysisSanctionsOracle.forChain(1, client);
        assertFalse(oracle.screen("0x0000000000000000000000000000000000000001").allowed());
        assertFalse(oracle.screen("0x0000000000000000000000000000000000000001").allowed());
        assertEquals(1, requests.get());
        EthRpcClient allowedClient = new EthRpcClient(request ->
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + "0".repeat(64) + "\"}");
        assertTrue(ChainalysisSanctionsOracle.forChain(1, allowedClient).screen(
                "0x0000000000000000000000000000000000000002").allowed());
        assertEquals("0x40C57923924B5c5c5455c48D93317139ADDaC8fb", oracle.contractAddress());
    }

    @Test void oracleResolvesKnownContractAddressesAndPropagatesRpcFailure() {
        EthRpcClient client = new EthRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + "0".repeat(64) + "\"}");
        MapExpectations.expected().forEach((chain, address) -> assertEquals(Keys.toChecksumAddress(address),
                ChainalysisSanctionsOracle.forChain(chain, client).contractAddress()));
        assertThrows(IllegalArgumentException.class, () -> ChainalysisSanctionsOracle.forChain(11155111, client));
        ChainalysisSanctionsOracle broken = ChainalysisSanctionsOracle.forChain(1,
                new EthRpcClient(request -> { throw new java.io.IOException("offline"); }));
        assertThrows(IllegalStateException.class, () -> broken.screen("0x0000000000000000000000000000000000000001"));
    }

    @Test void auditDecoratorPersistsDecisionAndCompositionBlocksDenyList() {
        JdbcScreeningAuditLog log = new JdbcScreeningAuditLog(source);
        AddressScreening local = new AllowListScreening(Set.of("0xabc"), address -> AddressScreening.ScreeningDecision.allow());
        AddressScreening audited = new AuditedScreening(local, log, "unit", java.time.Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        assertFalse(audited.screen("0xAbC").allowed());
        assertEquals(1, log.find("0xAbC", Instant.parse("2025-12-31T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z")).size());
    }

    private static final class MapExpectations {
        static java.util.Map<Long, String> expected() {
            return java.util.Map.of(1L,"0x40C57923924B5c5c5455c48D93317139ADDaC8fb",137L,"0x40C57923924B5c5c5455c48D93317139ADDaC8fb",
                    42161L,"0x40C57923924B5c5c5455c48D93317139ADDaC8fb",10L,"0x40C57923924B5c5c5455c48D93317139ADDaC8fb",
                    43114L,"0x40C57923924B5c5c5455c48D93317139ADDaC8fb",8453L,"0x3A91A31cB3dC49b4db9Ce721F50a9D076c8D739B");
        }
    }

    @Test void decisionCacheStaysBoundedWhenNewAddressesKeepArriving() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        EthRpcClient client = new EthRpcClient(request -> { calls.incrementAndGet();
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + "0".repeat(64) + "\"}"; });
        ChainalysisSanctionsOracle oracle = ChainalysisSanctionsOracle.forChain(1, client);
        for (int i = 0; i < ChainalysisSanctionsOracle.MAX_CACHE_ENTRIES + 50; i++) {
            oracle.screen(String.format("0x%040x", i + 1));
        }
        var field = ChainalysisSanctionsOracle.class.getDeclaredField("cache");
        field.setAccessible(true);
        int size = ((java.util.Map<?, ?>) field.get(oracle)).size();
        // 缓存不会随新地址无界增长
        org.junit.jupiter.api.Assertions.assertTrue(size <= ChainalysisSanctionsOracle.MAX_CACHE_ENTRIES, "cache size " + size);
    }

    @Test void failedScreeningIsAuditedAsDeniedAndRethrown() {
        java.util.List<ScreeningAuditLog.AuditEntry> entries = new java.util.ArrayList<>();
        ScreeningAuditLog log = new ScreeningAuditLog() {
            @Override public void record(AuditEntry entry) { entries.add(entry); }
            @Override public java.util.List<AuditEntry> find(String address, Instant from, Instant to) { return entries; }
        };
        AddressScreening failing = address -> { throw new IllegalStateException("oracle offline"); };
        AddressScreening audited = new AuditedScreening(failing, log, "unit",
                java.time.Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        assertThrows(IllegalStateException.class, () -> audited.screen("0x0000000000000000000000000000000000000002"));
        // 故障关闭的拒绝也必须留下审计记录
        assertEquals(1, entries.size());
        org.junit.jupiter.api.Assertions.assertFalse(entries.getFirst().allowed());
        assertTrue(entries.getFirst().reason().startsWith("SCREENING_UNAVAILABLE"));
    }
}
