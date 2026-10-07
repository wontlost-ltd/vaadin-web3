package com.wontlost.web3.x402.payment;

import java.net.URI;
import java.util.Optional;

import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;

public interface X402PaymentService {
    PaymentRequired createChallenge(String resourceId, URI canonicalUri);
    PaymentAttempt prepare(String resourceId, String walletAddress);
    PaymentOutcome verifyAndSettle(String resourceId, PaymentPayload payload);
    PaymentOutcome reconcile(String paymentId);
    AccessDecision hasAccess(String resourceId, String walletAddress);
    Optional<PaymentOutcome> latestOutcome(String resourceId, String walletAddress);
}
