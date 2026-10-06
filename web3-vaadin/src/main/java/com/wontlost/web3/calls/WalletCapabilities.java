package com.wontlost.web3.calls;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/** Raw wallet capability response, retained to preserve wallet-specific extensions. */
public record WalletCapabilities(Map<String, AtomicStatus> atomicByChain, String rawJson) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parses a wallet_getCapabilities response. */
    public static WalletCapabilities fromJson(String json) {
        var root = MAPPER.readTree(json);
        Map<String, AtomicStatus> statuses = new LinkedHashMap<>();
        root.properties().forEach(entry -> {
            var atomic = entry.getValue().path("atomic");
            statuses.put(entry.getKey(), atomic.isMissingNode() ? AtomicStatus.ABSENT
                    : AtomicStatus.fromValue(atomic.path("status").asString("absent")));
        });
        return new WalletCapabilities(Map.copyOf(statuses), json);
    }

    /** Returns the raw JSON response. */
    public String toJson() {
        return rawJson;
    }

    /** Atomic batch capability state for one chain, when advertised. */
    public enum AtomicStatus {
        SUPPORTED, READY, UNSUPPORTED, ABSENT;

        static AtomicStatus fromValue(String value) {
            return switch (value) {
                case "supported" -> SUPPORTED;
                case "ready" -> READY;
                case "unsupported" -> UNSUPPORTED;
                default -> ABSENT;
            };
        }
    }
}
