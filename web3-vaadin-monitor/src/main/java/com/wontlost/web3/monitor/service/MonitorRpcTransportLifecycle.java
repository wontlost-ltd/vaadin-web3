package com.wontlost.web3.monitor.service;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.chain.FailoverJsonRpcTransport;

final class MonitorRpcTransportLifecycle implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().build();
    private final List<JsonRpcTransport> transports = new ArrayList<>();
    private final Map<Long, FailoverJsonRpcTransport> failovers = new LinkedHashMap<>();
    HttpClient client() { return client; }
    synchronized <T extends JsonRpcTransport> T track(T transport) {
        transports.add(transport);
        return transport;
    }
    synchronized <T extends JsonRpcTransport> T track(long chainId, T transport) {
        track(transport);
        if (transport instanceof FailoverJsonRpcTransport failover) failovers.put(chainId, failover);
        return transport;
    }
    synchronized Map<Long, FailoverJsonRpcTransport> failovers() { return Map.copyOf(failovers); }
    @Override public synchronized void close() throws Exception {
        Exception failure = null;
        for (JsonRpcTransport transport : transports) try { transport.close(); }
        catch (Exception exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
        client.close();
        if (failure != null) throw failure;
    }
}
