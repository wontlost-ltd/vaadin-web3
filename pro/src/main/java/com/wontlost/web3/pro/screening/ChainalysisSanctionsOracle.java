package com.wontlost.web3.pro.screening;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.crypto.Keys;

import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.screening.AddressScreening;

/** Screens EVM addresses against Chainalysis sanctions contracts. EVM addresses are chain-independent, so an oracle on Ethereum can screen payers on any chain, including test networks. The boolean result contains no evidence and is not a complete compliance program; combine it with your own compliance process and audit log. */
public final class ChainalysisSanctionsOracle implements AddressScreening {
    private static final Map<Long, String> CONTRACTS = Map.of(
            1L, "0x40C57923924B5c5c5455c48D93317139ADDaC8fb",
            137L, "0x40C57923924B5c5c5455c48D93317139ADDaC8fb",
            42161L, "0x40C57923924B5c5c5455c48D93317139ADDaC8fb",
            10L, "0x40C57923924B5c5c5455c48D93317139ADDaC8fb",
            43114L, "0x40C57923924B5c5c5455c48D93317139ADDaC8fb",
            8453L, "0x3A91A31cB3dC49b4db9Ce721F50a9D076c8D739B");
    private final EthRpcClient client;
    private final String contract;
    private final Duration cacheTtl;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    /** 缓存容量上限（近似值：并发插入时可能短暂超出少量条目，但不会无界增长）。 */
    static final int MAX_CACHE_ENTRIES = 10_000;

    private ChainalysisSanctionsOracle(EthRpcClient client, String contract, Duration cacheTtl) {
        this.client = Objects.requireNonNull(client, "client");
        this.contract = contract;
        if (cacheTtl == null || cacheTtl.isNegative()) throw new IllegalArgumentException("cacheTtl must not be negative");
        this.cacheTtl = cacheTtl;
    }

    /** Creates an oracle for a deployed network, caching decisions for one hour. */
    public static ChainalysisSanctionsOracle forChain(long chainId, EthRpcClient client) {
        return forChain(chainId, client, Duration.ofHours(1));
    }
    /** Creates an oracle for a deployed network with a configurable decision cache. */
    public static ChainalysisSanctionsOracle forChain(long chainId, EthRpcClient client, Duration cacheTtl) {
        String contract = CONTRACTS.get(chainId);
        if (contract == null) throw new IllegalArgumentException("No sanctions oracle is configured for chain " + chainId);
        return new ChainalysisSanctionsOracle(client, Keys.toChecksumAddress(contract), cacheTtl);
    }
    /** Returns the deployed contract address for this instance. */
    public String contractAddress() { return contract; }

    @Override public ScreeningDecision screen(String address) {
        String canonical = Keys.toChecksumAddress(Objects.requireNonNull(address, "address"));
        Instant now = Instant.now();
        Cached existing = cache.get(canonical.toLowerCase(java.util.Locale.ROOT));
        if (existing != null && now.isBefore(existing.expiresAt())) return decision(existing.sanctioned());
        boolean sanctioned = query(canonical);
        if (!cacheTtl.isZero()) {
            // 持续出现新地址时防止无界增长：超过上限先清理过期项，仍超限则整体清空（只是缓存，可重新查询）
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
                if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
            }
            cache.put(canonical.toLowerCase(java.util.Locale.ROOT), new Cached(sanctioned, now.plus(cacheTtl)));
        }
        return decision(sanctioned);
    }

    private boolean query(String address) {
        Function function = new Function("isSanctioned", List.of(new Address(address)),
                List.of(new TypeReference<Bool>() { }));
        String encoded = FunctionEncoder.encode(function);
        String returned = client.call(contract, encoded, "latest");
        String data = returned.startsWith("0x") ? returned.substring(2) : returned;
        if (data.length() < 64) throw new IllegalStateException("Sanctions oracle returned less than one ABI word");
        String word = data.substring(data.length() - 64);
        return new java.math.BigInteger(word, 16).signum() != 0;
    }
    private static ScreeningDecision decision(boolean sanctioned) {
        return sanctioned ? ScreeningDecision.block("Address is sanctioned") : ScreeningDecision.allow();
    }
    private record Cached(boolean sanctioned, Instant expiresAt) { }
}
