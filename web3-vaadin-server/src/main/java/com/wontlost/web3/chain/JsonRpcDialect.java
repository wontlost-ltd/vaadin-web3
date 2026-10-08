package com.wontlost.web3.chain;

/**
 * Chain-specific JSON-RPC conventions used by {@link FailoverJsonRpcTransport}: the method that checks whether the
 * primary endpoint has recovered, and how node errors are classified to decide whether to switch endpoints.
 * <p>
 * The classification reuses {@link EthRpcException.Category} for every chain. Implementations must be thread-safe
 * and must never return {@code null}.
 */
public interface JsonRpcDialect {
    /** EVM nodes: probes with {@code eth_chainId}; the classification {@link FailoverJsonRpcTransport} always used. */
    JsonRpcDialect ETHEREUM = of("eth_chainId", EthRpcErrorClassifier::classify);

    /**
     * Solana nodes: probes with {@code getHealth} (error -32005 while unhealthy or behind) and classifies the Agave
     * RPC error codes. Providers that block {@code getHealth} need {@link #of} with another probe, such as
     * {@code getSlot}.
     */
    JsonRpcDialect SOLANA = of("getHealth", SolanaRpcErrorClassifier::classify);

    /** The parameterless JSON-RPC method sent to check whether the primary endpoint has recovered. */
    String healthProbeMethod();

    /** Classifies a JSON-RPC error returned by a node; {@code message} and {@code data} may be {@code null}. */
    EthRpcException.Category classify(int code, String message, String data);

    /** Creates a dialect from a probe method and a thread-safe classifier. */
    static JsonRpcDialect of(String healthProbeMethod, Classifier classifier) {
        return new BasicJsonRpcDialect(healthProbeMethod, classifier);
    }

    /** Classifies a JSON-RPC error; must be thread-safe and must not return {@code null}. */
    @FunctionalInterface
    interface Classifier {
        EthRpcException.Category classify(int code, String message, String data);
    }
}
