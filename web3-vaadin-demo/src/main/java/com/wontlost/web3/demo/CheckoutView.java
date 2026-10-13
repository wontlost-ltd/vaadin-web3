package com.wontlost.web3.demo;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.router.Route;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.PaymentLedger;
import com.wontlost.web3.pay.StablecoinCheckout;
import com.wontlost.web3.onramp.FiatOnrampButton;
import com.wontlost.web3.monitor.PaymentMonitorClient;
import com.wontlost.web3.onramp.OnrampProvider;
import org.springframework.beans.factory.ObjectProvider;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;
import com.wontlost.web3.demo.ui.DemoCallout;
import com.vaadin.flow.component.icon.VaadinIcon;

/** Demonstrates a Sepolia stablecoin checkout. */
@Route(value = "checkout", layout = MainLayout.class)
@AnonymousAllowed
public class CheckoutView extends DemoPage {
    public CheckoutView(ChainRegistry chains, PaymentLedger ledger,
            @Value("${web3.demo.recipient}") String recipient,
            ObjectProvider<PaymentMonitorClient> monitorClients,
            ObjectProvider<OnrampProvider> onrampProviders) {
        super("Stablecoin checkout", "Complete a test network purchase with a supported stablecoin.");
        PaymentMonitorClient monitorClient = monitorClients.getIfAvailable();
        OnrampProvider onrampProvider = onrampProviders.getIfAvailable();
        StablecoinCheckout checkout = new StablecoinCheckout(chains, ledger, recipient,
                new BigDecimal("1.00")).setTokens("USDC", "PYUSD").setPreferredChain(11155111)
                .setPaymentMonitor(monitorClient != null);
        if (onrampProvider != null) {
            checkout.setOnrampAction(FiatOnrampButton.forCheckout(onrampProvider));
        }
        add(new DemoCallout("info", VaadinIcon.INFO_CIRCLE.create(),
                "Use Sepolia for checkout. Local Anvil checkout requires a Sepolia fork."),
                new DemoSection("Payment", "Choose a token and confirm the checkout in your wallet.", checkout));
    }
}
