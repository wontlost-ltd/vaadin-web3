package com.wontlost.web3.demo;


import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.beans.factory.annotation.Qualifier;
import com.wontlost.web3.x402.payment.ResourcePolicy;
import com.wontlost.web3.x402.payment.X402PaymentService;
import com.wontlost.web3.x402.payment.X402Paywall;
import com.wontlost.web3.demo.ui.DemoCallout;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;

@Route(value = "paywall", layout = MainLayout.class)
@AnonymousAllowed
public class PaywallView extends DemoPage implements BeforeEnterObserver {
    private final X402PaymentService payments;
    private final ResourcePolicy policy;
    private final X402DemoConfiguration.X402DemoFixture fixture;
    private final Div paymentContent = new Div();

    public PaywallView(X402PaymentService payments, @Qualifier("x402DemoArticlePolicy") ResourcePolicy policy,
            X402DemoConfiguration.X402DemoFixture fixture) {
        super("Payment", "Review the requested resource and continue through the x402 payment flow.");
        this.payments = payments;
        this.policy = policy;
        this.fixture = fixture;
        paymentContent.addClassName("demo-page__content");
        add(paymentContent);
    }

    @Override public void beforeEnter(BeforeEnterEvent event) {
        paymentContent.removeAll();
        String resource = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("resource", java.util.List.of()).stream().findFirst().orElse("");
        if (!"demo-article".equals(resource)) {
            paymentContent.add(new DemoSection("Payment unavailable", "The requested paid resource is unknown."));
            return;
        }
        if (!fixture.available()) {
            paymentContent.add(new DemoSection("Payment unavailable", "The local payment fixture is not ready.",
                    new DemoCallout("warning", VaadinIcon.WARNING.create(), fixture.error()),
                    new Paragraph("Sign in with the Development wallet first, then restart with the demo profile and Anvil.")));
            return;
        }
        String target = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("continue", java.util.List.of()).stream().findFirst().orElse("paid-article");
        if (com.wontlost.web3.siwe.Web3Session.current().isEmpty())
            paymentContent.add(new DemoCallout("info", VaadinIcon.INFO_CIRCLE.create(), "Sign in with the Development wallet before paying."),
                    new Anchor("/login", "Sign in with Ethereum"));
        X402Paywall paywall = new X402Paywall(payments, resource, policy.resource(), policy.amount().toString(), 6,
                policy.network(), policy.asset())
                .setAssetLabel(policy.tokenName())
                .setNetworkLabel("Anvil (" + policy.network() + ")");
        if (com.wontlost.web3.x402.payment.PaymentGate.isSafeInternalPath(target)) paywall.setReturnTarget(target);
        paymentContent.add(new DemoSection("Checkout", "Confirm the requested amount and authorize payment.", paywall));
    }
}
