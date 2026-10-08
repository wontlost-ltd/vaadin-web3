package com.wontlost.web3.chain;

import java.util.Locale;
import java.util.Set;

/**
 * Solana（Agave）JSON-RPC 错误分类，错误码见 {@code rpc-client-api/src/custom_error.rs}。
 * 判定原则：只有“换一个节点可能得到不同结果”的错误才切换端点。
 */
final class SolanaRpcErrorClassifier {
    /**
     * 暂态或本节点特有：
     * -32001 区块已被本节点清理、-32004 区块不可用、-32005 节点不健康/落后、-32008 本节点无快照、
     * -32010 账户键被本节点二级索引排除、-32011 本节点未开启交易历史、-32012 扫描出错、
     * -32014 区块状态尚不可用、-32016 未达到最小上下文 slot、-32019 长期存储不可达。
     */
    private static final Set<Integer> TRANSIENT = Set.of(
            -32001, -32004, -32005, -32008, -32010, -32011, -32012, -32014, -32016, -32019);
    /**
     * 任何节点都会给出同样结果：
     * -32002 预检模拟失败、-32003 签名校验失败、-32006 预编译校验失败、-32007 slot 被跳过、
     * -32009 长期存储中该 slot 被跳过、-32013 签名长度不符、-32015 交易版本不支持、
     * -32018 slot 不是纪元边界、-32020 过滤条件下交易不存在。
     */
    private static final Set<Integer> DETERMINISTIC = Set.of(
            -32002, -32003, -32006, -32007, -32009, -32013, -32015, -32018, -32020);
    /** 部分服务商以 429 / -32429 作为限流错误码。 */
    private static final Set<Integer> RATE_LIMITED = Set.of(429, -32429);

    private SolanaRpcErrorClassifier() {
    }

    static EthRpcException.Category classify(int code, String message, String data) {
        if (code == -32600 || code == -32601 || code == -32602) return EthRpcException.Category.INVALID_REQUEST;
        if (TRANSIENT.contains(code)) return EthRpcException.Category.TRANSIENT_NODE;
        if (DETERMINISTIC.contains(code)) return EthRpcException.Category.DETERMINISTIC;
        if (RATE_LIMITED.contains(code)) return EthRpcException.Category.RATE_LIMITED;
        // 只匹配限流短语，不匹配裸数字 "429"：Solana 错误信息常含 slot 号，会误判为限流
        String text = ((message == null ? "" : message) + " " + (data == null ? "" : data)).toLowerCase(Locale.ROOT);
        if (text.contains("rate limit") || text.contains("too many requests")) return EthRpcException.Category.RATE_LIMITED;
        return EthRpcException.Category.UNKNOWN;
    }
}
