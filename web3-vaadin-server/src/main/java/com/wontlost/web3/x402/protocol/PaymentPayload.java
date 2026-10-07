package com.wontlost.web3.x402.protocol;

public record PaymentPayload(int x402Version, X402Resource resource, PaymentRequirements accepted,
        Eip3009Payload payload) { }
