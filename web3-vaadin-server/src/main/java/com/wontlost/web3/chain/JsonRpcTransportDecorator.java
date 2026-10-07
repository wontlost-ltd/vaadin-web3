package com.wontlost.web3.chain;

/**
 * Decorates JSON-RPC transports for observation or wrapping.
 *
 * <p>Decorators are intended only to observe or wrap {@link JsonRpcTransport#send(String)}. They must not change
 * request or response semantics. They are applied in {@code @Order} order, with the highest-priority decorator
 * forming the outermost layer. Implementations must return a non-null transport.
 */
@FunctionalInterface
public interface JsonRpcTransportDecorator {
    /** Returns an observed or wrapped transport for the supplied chain. */
    JsonRpcTransport decorate(long chainId, JsonRpcTransport transport);
}
