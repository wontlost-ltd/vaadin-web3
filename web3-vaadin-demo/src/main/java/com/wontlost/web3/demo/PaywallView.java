package com.wontlost.web3.demo;


import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.x402.payment.ResourcePolicy;
import com.wontlost.web3.x402.payment.X402PaymentService;
import com.wontlost.web3.x402.payment.X402Paywall;

@Route(value = "paywall", layout = MainLayout.class)
@AnonymousAllowed
public class PaywallView extends VerticalLayout implements BeforeEnterObserver {
    private final X402PaymentService payments;
    private final ResourcePolicy policy;
    private final X402DemoConfiguration.X402DemoFixture fixture;

    public PaywallView(X402PaymentService payments, ResourcePolicy policy,
            X402DemoConfiguration.X402DemoFixture fixture) {
        this.payments = payments;
        this.policy = policy;
        this.fixture = fixture;
        setMaxWidth("720px");
        getStyle().set("margin", "0 auto");
    }

    @Override public void beforeEnter(BeforeEnterEvent event) {
        removeAll();
        String resource = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("resource", java.util.List.of()).stream().findFirst().orElse("");
        if (!"demo-article".equals(resource)) {
            add(new H1("Payment unavailable"), new Paragraph("The requested paid resource is unknown."));
            return;
        }
        if (!fixture.available()) {
            add(new H1("Payment unavailable"), new Paragraph(fixture.error()),
                    new Paragraph("Sign in with the Development wallet first, then restart with the demo profile and Anvil."));
            return;
        }
        String target = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("continue", java.util.List.of()).stream().findFirst().orElse("paid-article");
        if (com.wontlost.web3.siwe.Web3Session.current().isEmpty())
            add(new Paragraph("Sign in with the Development wallet before paying."), new Anchor("/login", "Sign in with Ethereum"));
        X402Paywall paywall = new X402Paywall(payments, resource, policy.resource(), policy.amount().toString(), 6,
                policy.network(), policy.asset())
                .setAssetLabel(policy.tokenName())
                .setNetworkLabel("Anvil (" + policy.network() + ")");
        if (com.wontlost.web3.x402.payment.PaymentGate.isSafeInternalPath(target)) paywall.setReturnTarget(target);
        add(paywall);
    }
}
