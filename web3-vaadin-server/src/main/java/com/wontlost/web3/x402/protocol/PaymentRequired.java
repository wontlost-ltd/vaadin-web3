package com.wontlost.web3.x402.protocol;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

public record PaymentRequired(int x402Version, String error, X402Resource resource,
        List<PaymentRequirements> accepts, Map<String, JsonNode> extensions) { }
