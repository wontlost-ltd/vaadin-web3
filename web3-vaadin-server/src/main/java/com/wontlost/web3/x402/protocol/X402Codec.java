package com.wontlost.web3.x402.protocol;

public interface X402Codec {
    String encodePaymentRequired(PaymentRequired value);
    PaymentRequired decodePaymentRequired(String headerValue);
    String encodePaymentResponse(SettlementResponse value);
    SettlementResponse decodePaymentResponse(String headerValue);
    PaymentPayload decodePaymentPayload(String headerValue);
    String encodePaymentPayload(PaymentPayload value);
}
