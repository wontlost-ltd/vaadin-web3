package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EthRpcErrorClassifierTest {
    @Test void classifiesOnlyDocumentedTransientAndDeterministicErrors() {
        Object[][] cases = {
                {-32000, "header not found", null, EthRpcException.Category.TRANSIENT_NODE},
                {-32000, "unknown block", null, EthRpcException.Category.TRANSIENT_NODE},
                {-32000, "missing trie node", null, EthRpcException.Category.TRANSIENT_NODE},
                {-32000, "node not synced", null, EthRpcException.Category.TRANSIENT_NODE},
                {-32005, "rate limit exceeded", null, EthRpcException.Category.RATE_LIMITED},
                {-32005, "too many requests", null, EthRpcException.Category.RATE_LIMITED},
                {-32005, "limit exceeded", null, EthRpcException.Category.RATE_LIMITED},
                {3, "execution reverted", "0xdead", EthRpcException.Category.DETERMINISTIC},
                {-32601, "method not found", null, EthRpcException.Category.INVALID_REQUEST},
                {-32000, "provider internal error", null, EthRpcException.Category.UNKNOWN},
                {-32000, "", "{\"detail\":\"header not found\"}", EthRpcException.Category.TRANSIENT_NODE}
        };
        for (Object[] testCase : cases) {
            EthRpcException exception = new EthRpcException((int) testCase[0], (String) testCase[1], (String) testCase[2]);
            assertEquals(testCase[3], exception.getCategory(), testCase[1].toString());
        }
    }
}
