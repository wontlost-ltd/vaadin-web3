package com.wontlost.web3.autoconfigure;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.chain.FailoverJsonRpcTransport;

/** Owns transports and the shared HTTP client used by configured chains. */
public final class RpcTransportLifecycle implements AutoCloseable {
    private final HttpClient client;
    private final List<JsonRpcTransport> transports = new ArrayList<>();
    private final Map<Long, FailoverJsonRpcTransport> failovers = new LinkedHashMap<>();
    private final Map<Long, List<String>> endpointIds = new LinkedHashMap<>();

    public RpcTransportLifecycle(HttpClient client) { this.client = client; }
    public HttpClient client() { return client; }

    public synchronized <T extends JsonRpcTransport> T track(T transport) {
        transports.add(transport);
        return transport;
    }

    public synchronized <T extends JsonRpcTransport> T track(long chainId, T transport) {
        track(transport);
        if (transport instanceof FailoverJsonRpcTransport failover) failovers.put(chainId, failover);
        return transport;
    }

    public synchronized void endpoints(long chainId, List<String> ids) { endpointIds.put(chainId, List.copyOf(ids)); }

    public synchronized Map<Long, FailoverJsonRpcTransport> failovers() { return Map.copyOf(failovers); }
    public synchronized Map<Long, List<String>> endpointIds() { return Map.copyOf(endpointIds); }

    @Override
    public synchronized void close() throws Exception {
        Exception failure = null;
        for (JsonRpcTransport transport : transports) {
            try { transport.close(); }
            catch (Exception exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        client.close();
        if (failure != null) throw failure;
    }
}
