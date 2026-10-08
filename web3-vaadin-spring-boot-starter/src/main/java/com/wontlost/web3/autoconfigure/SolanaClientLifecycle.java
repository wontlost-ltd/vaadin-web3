package com.wontlost.web3.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import com.wontlost.web3.solana.SolanaRpcClient;

/** Owns Solana RPC clients created by the auto-configuration. */
class SolanaClientLifecycle implements AutoCloseable {
    private final List<SolanaRpcClient> clients = new ArrayList<>();
    private int closedClients;

    synchronized SolanaRpcClient track(SolanaRpcClient client) {
        clients.add(client);
        return client;
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        for (SolanaRpcClient client : clients) {
            try {
                client.close();
                closedClients++;
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        clients.clear();
        if (failure != null) throw failure;
    }

    synchronized int closedClients() { return closedClients; }
}
