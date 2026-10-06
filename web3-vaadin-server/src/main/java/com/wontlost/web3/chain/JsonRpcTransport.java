package com.wontlost.web3.chain;

import java.io.IOException;

/** Sends a JSON-RPC request and returns its response body. */
@FunctionalInterface
public interface JsonRpcTransport extends AutoCloseable {
    String send(String requestJson) throws IOException;

    /** Releases resources owned by this transport. */
    @Override
    default void close() throws Exception { }
}
