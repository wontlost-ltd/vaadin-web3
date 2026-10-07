package com.wontlost.web3.x402.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;

import org.junit.jupiter.api.Test;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;

class PaymentGateTest {
    private static final String ADDRESS_A = "0x0000000000000000000000000000000000000001";
    private static final String ADDRESS_B = "0x0000000000000000000000000000000000000002";
    private static final RequiresPayment REQUIREMENT = PaidView.class.getAnnotation(RequiresPayment.class);

    @Test void unpaidAndAnonymousUsersForwardWithResourceAndInternalReturnTarget() {
        List<String> identities = new ArrayList<>();
        PaymentGate gate = new PaymentGate(new FakePayments(address -> { identities.add(address); return AccessDecision.PAYMENT_REQUIRED; }));
        var anonymous = gate.evaluate(REQUIREMENT, null, "paid/article", "chapter=2");
        var signedIn = gate.evaluate(REQUIREMENT, ADDRESS_A, "paid/article", "chapter=2");
        assertFalse(anonymous.allowed());
        assertEquals("paywall", anonymous.route());
        assertEquals("paid/article?chapter=2", anonymous.continueTarget());
        assertEquals(ADDRESS_A, identities.getFirst());
        assertEquals("demo-article", signedIn.resourceId());
    }

    @Test void onlySettledIdentityGetsAccessAndChangedAccountDoesNotReuseAccess() {
        PaymentGate gate = new PaymentGate(new FakePayments(address -> ADDRESS_A.equals(address)
                ? AccessDecision.ALLOW : AccessDecision.PAYMENT_REQUIRED));
        assertTrue(gate.evaluate(REQUIREMENT, ADDRESS_A, "paid/article", "").allowed());
        assertFalse(gate.evaluate(REQUIREMENT, ADDRESS_B, "paid/article", "").allowed());
    }

    @Test void beforeEnterDoesNotForwardPaidIdentity() {
        TestBeforeEnterEvent event = event();
        PaymentGate gate = new PaymentGate(new FakePayments(address -> AccessDecision.ALLOW), () -> ADDRESS_A);

        gate.beforeEnter(event);

        assertEquals(0, event.forwardCalls);
        assertEquals(null, event.forwardedRoute);
    }

    @Test void beforeEnterForwardsUnpaidAndAnonymousUsersWithSafeParameters() {
        for (String identity : List.of(ADDRESS_A, "")) {
            TestBeforeEnterEvent event = event();
            String address = identity.isEmpty() ? null : identity;
            PaymentGate gate = new PaymentGate(new FakePayments(value -> AccessDecision.PAYMENT_REQUIRED), () -> address);

            gate.beforeEnter(event);

            assertEquals(1, event.forwardCalls);
            assertEquals("paywall", event.forwardedRoute);
            assertEquals(Map.of("resource", List.of("demo-article"), "continue", List.of("paid/article?chapter=2")),
                    event.forwardedParameters.getParameters());
        }
    }

    private static TestBeforeEnterEvent event() {
        com.vaadin.flow.server.RouteRegistry registry = (com.vaadin.flow.server.RouteRegistry) Proxy.newProxyInstance(
                PaymentGateTest.class.getClassLoader(), new Class<?>[] { com.vaadin.flow.server.RouteRegistry.class },
                (proxy, method, args) -> null);
        return new TestBeforeEnterEvent(new com.vaadin.flow.router.Router(registry),
                new Location("paid/article", QueryParameters.simple(Map.of("chapter", "2"))));
    }

    private static final class TestBeforeEnterEvent extends BeforeEnterEvent {
        private String forwardedRoute;
        private QueryParameters forwardedParameters;
        private int forwardCalls;

        TestBeforeEnterEvent(com.vaadin.flow.router.Router router, Location location) {
            super(router, com.vaadin.flow.router.NavigationTrigger.UI_NAVIGATE, location, PaidView.class,
                    new UI(), List.of());
        }

        @Override public void forwardTo(String route, QueryParameters parameters) {
            forwardCalls++;
            forwardedRoute = route;
            forwardedParameters = parameters;
        }
    }

    @Test void rejectsAllOpenRedirectFormsAndEncodedSeparators() {
        for (String unsafe : List.of("https://example.org", "//host/path", "\\\\host\\path", "%2F%2Fhost",
                "%5C%5Chost", "javascript:alert(1)", "%6aavascript:alert(1)"))
            assertFalse(PaymentGate.isSafeInternalPath(unsafe), unsafe);
    }

    @RequiresPayment(resourceId = "demo-article", paywallRoute = "paywall")
    private static final class PaidView extends com.vaadin.flow.component.orderedlayout.VerticalLayout { }

    private interface Access { AccessDecision check(String address); }
    private static final class FakePayments implements X402PaymentService {
        private final Access access;
        private FakePayments(Access access) { this.access = access; }
        @Override public AccessDecision hasAccess(String resource, String address) { return access.check(address); }
        @Override public PaymentRequired createChallenge(String resource, URI uri) { throw new UnsupportedOperationException(); }
        @Override public PaymentAttempt prepare(String resource, String address) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome verifyAndSettle(String resource, PaymentPayload payload) { throw new UnsupportedOperationException(); }
        @Override public PaymentOutcome reconcile(String id) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<PaymentOutcome> latestOutcome(String resource, String address) {
            return java.util.Optional.empty();
        }
    }
}
