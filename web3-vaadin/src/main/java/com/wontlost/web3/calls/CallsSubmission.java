package com.wontlost.web3.calls;

import java.util.List;

import tools.jackson.databind.ObjectMapper;

/** Result of submitting a call batch. */
public record CallsSubmission(String id, boolean nonAtomicFallback,
        List<String> transactionHashes, List<String> errors) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CallsSubmission {
        transactionHashes = List.copyOf(transactionHashes);
        errors = List.copyOf(errors);
    }

    /** Parses the wallet_sendCalls response. */
    public static CallsSubmission fromJson(String json) {
        var node = MAPPER.readTree(json);
        return new CallsSubmission(node.path("id").asString(null), false, List.of(), List.of());
    }
}
