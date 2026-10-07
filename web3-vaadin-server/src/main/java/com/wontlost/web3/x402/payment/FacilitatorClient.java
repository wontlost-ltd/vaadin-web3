package com.wontlost.web3.x402.payment;

import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequirements;

public interface FacilitatorClient {
    SupportedResponse supported();
    VerifyResult verify(PaymentPayload payload, PaymentRequirements requirements);
    SettlementResult settle(PaymentPayload payload, PaymentRequirements requirements);
}
