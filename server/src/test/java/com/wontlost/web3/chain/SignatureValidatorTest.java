package com.wontlost.web3.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SignatureValidatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void callDataMatchesViemEncodingByteForByte() throws IOException {
        // 参考数据由 viem 2.57.2 的 encodeDeployData(erc6492SignatureValidator, [address, hash, signature]) 生成
        JsonNode golden = MAPPER.readTree(getClass().getResourceAsStream("/fixtures/erc6492-viem-golden.json").readAllBytes());
        byte[] hash = Numeric.hexStringToByteArray(golden.path("hash").asString());
        assertEquals(golden.path("hash").asString(),
                Numeric.toHexString(Sign.getEthereumMessageHash("hello vaadin".getBytes(StandardCharsets.UTF_8))));
        assertEquals(golden.path("data").asString(), SignatureValidator.callData(golden.path("address").asString(), hash,
                Numeric.hexStringToByteArray(golden.path("signature").asString())));
    }

    @Test void sendsCreationCallWithoutTargetAndAcceptsOnlyTrue() {
        List<JsonNode> requests = new ArrayList<>();
        assertTrue(SignatureValidator.isValidSignature(client(requests, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x01\"}"),
                "0x00000000000000000000000000000000c0ffee00", new byte[32], new byte[] { 1 }));
        JsonNode call = requests.get(0).path("params").get(0);
        assertEquals("eth_call", requests.get(0).path("method").asString());
        assertTrue(call.path("to").isMissingNode(), "validator must run as creation code");
        assertEquals("latest", requests.get(0).path("params").get(1).asString());

        assertFalse(SignatureValidator.isValidSignature(client(requests, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x00\"}"),
                "0x00000000000000000000000000000000c0ffee00", new byte[32], new byte[] { 1 }));
        assertFalse(SignatureValidator.isValidSignature(client(requests, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x\"}"),
                "0x00000000000000000000000000000000c0ffee00", new byte[32], new byte[] { 1 }));
    }

    @Test void revertMeansInvalidButOtherRpcErrorsPropagate() {
        List<JsonNode> requests = new ArrayList<>();
        assertFalse(SignatureValidator.isValidSignature(client(requests,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":3,\"message\":\"execution reverted\"}}"),
                "0x00000000000000000000000000000000c0ffee00", new byte[32], new byte[] { 1 }));
        assertFalse(SignatureValidator.isValidSignature(client(requests,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"execution reverted: bad sig\"}}"),
                "0x00000000000000000000000000000000c0ffee00", new byte[32], new byte[] { 1 }));
        assertThrows(EthRpcException.class, () -> SignatureValidator.isValidSignature(client(requests,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"rate limited\"}}"),
                "0x00000000000000000000000000000000c0ffee00", new byte[32], new byte[] { 1 }));
        assertThrows(IllegalArgumentException.class, () -> SignatureValidator.isValidSignature(client(requests, "{}"),
                "0x00000000000000000000000000000000c0ffee00", new byte[31], new byte[] { 1 }));
    }

    private static EthRpcClient client(List<JsonNode> requests, String response) {
        return new EthRpcClient(request -> {
            requests.add(MAPPER.readTree(request));
            return response;
        });
    }
}
