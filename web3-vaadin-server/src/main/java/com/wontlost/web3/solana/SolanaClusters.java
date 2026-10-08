package com.wontlost.web3.solana;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.wontlost.web3.siws.SolanaCluster;

/**
 * The application's Solana RPC clients by cluster, the Solana counterpart of {@code ChainRegistry}. Store it in the
 * {@code VaadinContext} ({@code context.setAttribute(SolanaClusters.class, clusters)}) so components such as the
 * {@code @RequiresSplToken} gate can read balances.
 */
public final class SolanaClusters {
    private final Map<SolanaCluster, SolanaRpcClient> clients = new EnumMap<>(SolanaCluster.class);

    /** Registers the client for {@code cluster}, replacing any previous one. */
    public synchronized SolanaClusters register(SolanaCluster cluster, SolanaRpcClient client) {
        clients.put(Objects.requireNonNull(cluster), Objects.requireNonNull(client));
        return this;
    }

    /** The client registered for {@code cluster}, if any. */
    public synchronized Optional<SolanaRpcClient> get(SolanaCluster cluster) {
        return Optional.ofNullable(clients.get(cluster));
    }
}
