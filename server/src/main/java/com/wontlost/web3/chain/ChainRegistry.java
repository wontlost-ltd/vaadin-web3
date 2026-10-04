package com.wontlost.web3.chain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Application-scoped registry of chain JSON-RPC clients. */
public final class ChainRegistry {
    private final Map<Long, EthRpcClient> clients = new ConcurrentHashMap<>();
    /** Registers or replaces the client for a chain. */
    public void register(long chainId, EthRpcClient client) { clients.put(chainId, client); }
    /** Creates and registers an HTTP client for a chain. */
    public void register(long chainId, String endpoint) { register(chainId, new EthRpcClient(endpoint)); }
    /** Looks up a registered chain client. */
    public Optional<EthRpcClient> get(long chainId) { return Optional.ofNullable(clients.get(chainId)); }
    /** Returns an immutable snapshot of the configured clients. */
    public Map<Long, EthRpcClient> clients() { return Map.copyOf(clients); }
}
