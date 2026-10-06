package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class EthRpcClientDevWalletTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void exposesTransactionAndFeeRpcMethodsThroughExistingTransport() {
        List<String> methods = new ArrayList<>();
        EthRpcClient rpc = new EthRpcClient(requestJson -> {
            JsonNode request = MAPPER.readTree(requestJson);
            String method = request.path("method").asString();
            methods.add(method);
            String result = switch (method) {
                case "eth_getTransactionCount" -> "\"0x9\"";
                case "eth_estimateGas" -> "\"0x5208\"";
                case "eth_gasPrice" -> "\"0x3b9aca00\"";
                case "eth_maxPriorityFeePerGas" -> "\"0x77359400\"";
                case "eth_getBlockByNumber" -> "{\"number\":\"0x1\",\"baseFeePerGas\":\"0x3b9aca00\"}";
                case "eth_sendRawTransaction" -> "\"0xabc\"";
                default -> throw new IllegalArgumentException(method);
            };
            return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asString() + ",\"result\":" + result + "}";
        });

        assertEquals(9, rpc.getTransactionCount("0x1", "pending").intValueExact());
        assertEquals(21000, rpc.estimateGas("{\"to\":\"0x1\"}").intValueExact());
        assertEquals(1_000_000_000, rpc.gasPrice().intValueExact());
        assertEquals(2_000_000_000, rpc.maxPriorityFeePerGas().intValueExact());
        assertEquals(1_000_000_000, rpc.latestBaseFee().intValueExact());
        assertEquals("0xabc", rpc.sendRawTransaction("0x1234"));
        assertEquals(List.of("eth_getTransactionCount", "eth_estimateGas", "eth_gasPrice",
                "eth_maxPriorityFeePerGas", "eth_getBlockByNumber", "eth_sendRawTransaction"), methods);

        EthRpcClient preLondon = new EthRpcClient(requestJson -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"number\":\"0x1\"}}");
        assertNull(preLondon.latestBaseFee());
    }
}
