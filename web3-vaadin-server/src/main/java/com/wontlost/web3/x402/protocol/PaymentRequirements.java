package com.wontlost.web3.x402.protocol;

import java.util.Map;

import tools.jackson.databind.JsonNode;

public record PaymentRequirements(String scheme, String network, String amount, String asset, String payTo,
        int maxTimeoutSeconds, Map<String, JsonNode> extra) { }
