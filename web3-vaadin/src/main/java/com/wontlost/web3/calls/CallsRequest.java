package com.wontlost.web3.calls;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/** An EIP-5792 wallet_sendCalls request. */
public record CallsRequest(String id, String from, long chainId, boolean atomicRequired,
        List<Call> calls, Map<String, Object> capabilities) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CallsRequest {
        if (chainId < 0) throw new IllegalArgumentException("chainId must not be negative");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls is required"));
        if (calls.isEmpty()) throw new IllegalArgumentException("calls must not be empty");
        capabilities = Capabilities.freeze(capabilities);
    }

    /** Serializes the request with the EIP-5792 hexadecimal chain ID representation. */
    public String toProviderJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("version", "2.0.0");
        if (id != null) node.put("id", id);
        if (from != null) node.put("from", from);
        node.put("chainId", "0x" + Long.toHexString(chainId));
        node.put("atomicRequired", atomicRequired);
        ArrayNode callNodes = node.putArray("calls");
        for (Call call : calls) {
            ObjectNode callNode = callNodes.addObject();
            if (call.to() != null) callNode.put("to", call.to());
            if (call.data() != null) callNode.put("data", call.data());
            if (call.value() != null) callNode.put("value", call.value());
            if (!call.capabilities().isEmpty()) callNode.set("capabilities", MAPPER.valueToTree(call.capabilities()));
        }
        if (!capabilities.isEmpty()) node.set("capabilities", MAPPER.valueToTree(capabilities));
        return node.toString();
    }

    /**
     * Returns the name of the first capability, at request or call level, that the wallet must honour (not marked
     * {@code "optional": true}), or {@code null} when every capability is optional.
     */
    public String firstRequiredCapability() {
        String required = firstRequired(capabilities);
        for (int i = 0; required == null && i < calls.size(); i++) required = firstRequired(calls.get(i).capabilities());
        return required;
    }

    private static String firstRequired(Map<String, Object> capabilities) {
        for (Map.Entry<String, Object> entry : capabilities.entrySet()) {
            // EIP-5792：仅显式标注 optional=true 的能力可被忽略
            boolean optional = entry.getValue() instanceof Map<?, ?> config && Boolean.TRUE.equals(config.get("optional"));
            if (!optional) return entry.getKey();
        }
        return null;
    }
}
