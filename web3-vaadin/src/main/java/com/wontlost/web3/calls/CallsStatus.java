package com.wontlost.web3.calls;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/** Status and transaction receipts for an EIP-5792 call batch. */
public record CallsStatus(String id, long chainId, int status, boolean atomic, List<Receipt> receipts) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CallsStatus {
        receipts = List.copyOf(receipts);
    }

    /** Maps the EIP-5792 numeric status to its known status name. */
    public Status statusType() {
        return Status.fromCode(status);
    }

    /** Serializes the status with the EIP-5792 hexadecimal chain ID representation. */
    public String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("version", "2.0.0");
        if (id != null) node.put("id", id);
        node.put("chainId", "0x" + Long.toHexString(chainId));
        node.put("status", status);
        node.put("atomic", atomic);
        ArrayNode receiptNodes = node.putArray("receipts");
        for (Receipt receipt : receipts) {
            ObjectNode receiptNode = receiptNodes.addObject();
            if (receipt.transactionHash() != null) receiptNode.put("transactionHash", receipt.transactionHash());
            if (receipt.blockHash() != null) receiptNode.put("blockHash", receipt.blockHash());
            if (receipt.blockNumber() != null) receiptNode.put("blockNumber", receipt.blockNumber());
            if (receipt.gasUsed() != null) receiptNode.put("gasUsed", receipt.gasUsed());
            if (receipt.status() != null) receiptNode.put("status", receipt.status());
        }
        return node.toString();
    }

    /** Parses a wallet_getCallsStatus response. */
    public static CallsStatus fromJson(String json) {
        var node = MAPPER.readTree(json);
        String chain = node.path("chainId").asString("0x0");
        long chainId = Long.parseUnsignedLong(chain.substring(2), 16);
        List<Receipt> receipts = new ArrayList<>();
        for (var receipt : node.path("receipts")) {
            receipts.add(new Receipt(text(receipt, "transactionHash"), text(receipt, "blockHash"),
                    text(receipt, "blockNumber"), text(receipt, "gasUsed"), text(receipt, "status")));
        }
        return new CallsStatus(node.path("id").asString(null), chainId,
                node.path("status").asInt(0), node.path("atomic").asBoolean(false), receipts);
    }

    private static String text(tools.jackson.databind.JsonNode node, String name) {
        var value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** Standard EIP-5792 batch states. */
    public enum Status {
        PENDING(100), CONFIRMED(200), OFFCHAIN_FAILURE(400), REVERTED(500), PARTIALLY_REVERTED(600), UNKNOWN(-1);

        private final int code;
        Status(int code) { this.code = code; }
        public int code() { return code; }
        public static Status fromCode(int code) {
            for (Status value : values()) if (value.code == code) return value;
            return UNKNOWN;
        }
    }

    /** Receipt fields returned by wallet_getCallsStatus. */
    public record Receipt(String transactionHash, String blockHash, String blockNumber, String gasUsed, String status) {
    }
}
