package com.wontlost.web3.chain;

import java.util.Locale;

final class EthRpcErrorClassifier {
    private static final java.util.regex.Pattern WHOLE_WORD_429 = java.util.regex.Pattern.compile("\\b429\\b");

    private EthRpcErrorClassifier() { }

    static EthRpcException.Category classify(int code, String message, String data) {
        if (code == -32600 || code == -32601 || code == -32602)
            return EthRpcException.Category.INVALID_REQUEST;
        String messageText = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (code == 3 || contains(messageText, "execution reverted", "revert", "insufficient funds"))
            return EthRpcException.Category.DETERMINISTIC;
        if (code == 429 || contains(messageText, "rate limit", "too many requests", "limit exceeded")
                || WHOLE_WORD_429.matcher(messageText).find())
            return EthRpcException.Category.RATE_LIMITED;
        String text = (messageText + " " + (data == null ? "" : data).toLowerCase(Locale.ROOT));
        if (contains(text, "header not found", "unknown block", "missing trie node", "not synced"))
            return EthRpcException.Category.TRANSIENT_NODE;
        return EthRpcException.Category.UNKNOWN;
    }

    private static boolean contains(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
