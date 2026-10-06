package com.wontlost.web3.demo;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.router.Route;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.PaymentLedger;
import com.wontlost.web3.pay.StablecoinCheckout;
import com.wontlost.web3.onramp.FiatOnrampButton;
import com.wontlost.web3.monitor.PaymentMonitorClient;
import com.wontlost.web3.onramp.OnrampProvider;
import org.springframework.beans.factory.ObjectProvider;

/** Demonstrates a Sepolia stablecoin checkout. */
@Route(value = "checkout", layout = MainLayout.class)
@AnonymousAllowed
public class CheckoutView extends VerticalLayout {
    public CheckoutView(ChainRegistry chains, PaymentLedger ledger,
            @Value("${web3.demo.recipient}") String recipient,
            ObjectProvider<PaymentMonitorClient> monitorClients,
            ObjectProvider<OnrampProvider> onrampProviders) {
        PaymentMonitorClient monitorClient = monitorClients.getIfAvailable();
        OnrampProvider onrampProvider = onrampProviders.getIfAvailable();
        StablecoinCheckout checkout = new StablecoinCheckout(chains, ledger, recipient,
                new BigDecimal("1.00")).setTokens("USDC", "PYUSD").setPreferredChain(11155111)
                .setPaymentMonitor(monitorClient != null);
        if (onrampProvider != null) {
            checkout.setOnrampAction(FiatOnrampButton.forCheckout(onrampProvider));
        }
        add(new H1("Stablecoin checkout"),
                new Paragraph("Use Sepolia for checkout. Local Anvil checkout requires a Sepolia fork."), checkout);
    }
}
