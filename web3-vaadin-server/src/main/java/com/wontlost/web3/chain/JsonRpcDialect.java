package com.wontlost.web3.chain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 链相关的 JSON-RPC 约定：故障转移用于探测主端点恢复的请求，以及把节点错误归类为
 * {@link EthRpcException.Category}（决定是否切换端点）。
 * <p>
 * {@link #ETHEREUM} 保持原有行为；{@link #SOLANA} 用 {@code getHealth} 探测，并按 Agave
 * {@code rpc-client-api/src/custom_error.rs} 的错误码分类。
 */
public interface JsonRpcDialect {
    /** EVM 节点：{@code eth_chainId} 探测，按错误码与常见节点报错文本分类。 */
    JsonRpcDialect ETHEREUM = new Simple("eth_chainId", EthRpcErrorClassifier::classify);

    /** Solana 节点：{@code getHealth} 探测（不健康时返回 -32005），按 Agave 自定义错误码分类。 */
    JsonRpcDialect SOLANA = new Simple("getHealth", SolanaErrors::classify);

    /** 主端点恢复探测所用的无参 JSON-RPC 方法名。 */
    String healthProbeMethod();

    /** 把节点返回的 JSON-RPC 错误归类；{@code message}、{@code data} 可能为 null。 */
    EthRpcException.Category classify(int code, String message, String data);

    /** 错误分类函数。 */
    @FunctionalInterface
    interface Classifier {
        EthRpcException.Category classify(int code, String message, String data);
    }

    /** 由探测方法名与分类函数组成的方言。 */
    record Simple(String healthProbeMethod, Classifier classifier) implements JsonRpcDialect {
        public Simple {
            Objects.requireNonNull(healthProbeMethod);
            Objects.requireNonNull(classifier);
        }

        @Override
        public EthRpcException.Category classify(int code, String message, String data) {
            return classifier.classify(code, message, data);
        }
    }

    /** Solana 错误码分类表。 */
    final class SolanaErrors {
        // 换一个节点可能成功：区块/状态尚未可用、节点落后、最小上下文 slot 未达到、长期存储不可达
        private static final Set<Integer> TRANSIENT = Set.of(-32004, -32005, -32014, -32016, -32019);
        // 换节点也会得到同样结果：预检失败、签名校验失败、预编译校验失败、签名长度不符、交易版本不支持
        private static final Set<Integer> DETERMINISTIC = Set.of(-32002, -32003, -32006, -32013, -32015);

        private SolanaErrors() {
        }

        static EthRpcException.Category classify(int code, String message, String data) {
            if (code == -32600 || code == -32601 || code == -32602) {
                return EthRpcException.Category.INVALID_REQUEST;
            }
            if (TRANSIENT.contains(code)) {
                return EthRpcException.Category.TRANSIENT_NODE;
            }
            if (DETERMINISTIC.contains(code)) {
                return EthRpcException.Category.DETERMINISTIC;
            }
            String text = ((message == null ? "" : message) + " " + (data == null ? "" : data)).toLowerCase(Locale.ROOT);
            if (text.contains("rate limit") || text.contains("too many requests") || text.contains("429")) {
                return EthRpcException.Category.RATE_LIMITED;
            }
            return EthRpcException.Category.UNKNOWN;
        }
    }
}
