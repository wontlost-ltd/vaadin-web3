package com.wontlost.web3.x402.payment;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

public record SupportedResponse(List<Kind> kinds, List<String> extensions) {
    public SupportedResponse {
        kinds = List.copyOf(kinds);
        extensions = List.copyOf(extensions);
    }

    public boolean supports(int x402Version, String scheme, String network) {
        return kinds.stream().anyMatch(kind -> kind.x402Version() == x402Version
                && kind.scheme().equals(scheme) && kind.network().equals(network));
    }

    public record Kind(int x402Version, String scheme, String network, Map<String, JsonNode> extra) {
        public Kind {
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }
    }
}
