package com.wontlost.web3.chain;

import java.util.Locale;

final class EthRpcErrorClassifier {
    private EthRpcErrorClassifier() { }

    static EthRpcException.Category classify(int code, String message, String data) {
        if (code == -32600 || code == -32601 || code == -32602)
            return EthRpcException.Category.INVALID_REQUEST;
        String text = ((message == null ? "" : message) + " " + (data == null ? "" : data))
                .toLowerCase(Locale.ROOT);
        if (contains(text, "rate limit", "too many requests", "limit exceeded", "429"))
            return EthRpcException.Category.RATE_LIMITED;
        if (contains(text, "header not found", "unknown block", "missing trie node", "not synced"))
            return EthRpcException.Category.TRANSIENT_NODE;
        if (code == 3 || contains(text, "execution reverted", "revert", "insufficient funds"))
            return EthRpcException.Category.DETERMINISTIC;
        return EthRpcException.Category.UNKNOWN;
    }

    private static boolean contains(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
