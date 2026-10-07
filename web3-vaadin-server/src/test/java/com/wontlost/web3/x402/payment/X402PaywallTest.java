package com.wontlost.web3.x402.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;

class X402PaywallTest {
    @Test void formatsAtomicAmountsWithoutFloatingPoint() {
        assertEquals("1.000001", X402Paywall.formatAmount("1000001", 6));
        assertEquals("0.000000000000000001", X402Paywall.formatAmount("1", 18));
        assertEquals("1000", X402Paywall.formatAmount("1000", 0));
    }

    @Test void displaysConfiguredAssetAndReadableNetworkLabels() {
        X402Paywall wall = new X402Paywall(new EmptyPayments(), "article",
                new com.wontlost.web3.x402.protocol.X402Resource("http://localhost/article", "Article", "text/plain"),
                "1000000", 6, "eip155:31337", "0x0000000000000000000000000000000000000001")
                .setAssetLabel("X402 Test Token")
                .setNetworkLabel("Anvil (eip155:31337)");

        assertEquals("Price: 1 X402 Test Token", wall.getPriceTextForTest());
        assertEquals("Network: Anvil (eip155:31337)", wall.getNetworkTextForTest());
        assertEquals("Connecting…", new X402PaywallI18n().getConnecting());
    }

    @Test void returnTargetAllowsOnlyRelativeInternalPaths() {
        assertTrue(PaymentGate.isSafeInternalPath("paid-article?chapter=2"));
        assertFalse(PaymentGate.isSafeInternalPath("https://example.com"));
        assertFalse(PaymentGate.isSafeInternalPath("//example.com/path"));
        assertFalse(PaymentGate.isSafeInternalPath("%2f%2fexample.com"));
        assertFalse(PaymentGate.isSafeInternalPath("%2e%2e/admin"));
        assertFalse(PaymentGate.isSafeInternalPath("../admin"));
        assertFalse(PaymentGate.isSafeInternalPath("paid\narticle"));
    }

    @Test void i18nAndAccessibleRolesAreExposed() {
        X402PaywallI18n i18n = new X402PaywallI18n().setTitle("Checkout").setPending("Wait");
        assertEquals("Checkout", i18n.getTitle());
        assertEquals("Wait", i18n.getPending());
        X402Paywall paywall = new X402Paywall(new EmptyPayments(), "article",
                new com.wontlost.web3.x402.protocol.X402Resource("http://localhost/article", "Article", "text/plain"),
                "1000000", 6, "eip155:31337", "0x0000000000000000000000000000000000000001");
        assertEquals("status", paywall.getElement().getChildren().filter(e -> "status".equals(e.getAttribute("role"))).findFirst().orElseThrow().getAttribute("role"));
        assertEquals("alert", paywall.getElement().getChildren().filter(e -> "alert".equals(e.getAttribute("role"))).findFirst().orElseThrow().getAttribute("role"));
    }

    private static final class EmptyPayments implements X402PaymentService {
        @Override public PaymentRequired createChallenge(String resourceId) { throw new UnsupportedOperationException(); }
        @Override public PaymentRequired createChallenge(String resourceId, URI uri) { throw new UnsupportedOperationException(); }
        @Override public PaymentAttempt prepare(String resourceId, String address) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome verifyPayment(String resourceId, PaymentPayload payload) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome settlePayment(String resourceId, PaymentPayload payload) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome verifyAndSettle(String resourceId, PaymentPayload payload) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome reconcile(String paymentId) { throw new UnsupportedOperationException(); }
        @Override public AccessDecision hasAccess(String resourceId, String address) { return AccessDecision.PAYMENT_REQUIRED; }
        @Override public java.util.Optional<PaymentOutcome> latestOutcome(String resourceId, String address) {
            return java.util.Optional.empty();
        }
    }
}
