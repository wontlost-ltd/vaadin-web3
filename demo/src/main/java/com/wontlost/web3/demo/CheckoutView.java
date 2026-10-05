package com.wontlost.web3.demo;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.PaymentLedger;
import com.wontlost.web3.pro.payments.PaymentRecorder;
import com.wontlost.web3.pro.payments.PaymentRecordStore;
import com.wontlost.web3.pay.StablecoinCheckout;
import com.wontlost.web3.onramp.FiatOnrampButton;

/** Demonstrates a Sepolia stablecoin checkout. */
@Route(value = "checkout", layout = MainLayout.class)
public class CheckoutView extends VerticalLayout {
    public CheckoutView(ChainRegistry chains, PaymentLedger ledger,
            @Value("${web3.demo.recipient}") String recipient,
            DemoIntegrations integrations, PaymentRecordStore records) {
        StablecoinCheckout checkout = new StablecoinCheckout(chains, ledger, recipient,
                new BigDecimal("1.00")).setTokens("USDC", "PYUSD").setPreferredChain(11155111)
                .setPaymentMonitor(integrations.monitorClient() != null);
        PaymentRecorder.attach(checkout, records);
        if (integrations.onrampProvider() != null) {
            checkout.setOnrampAction(FiatOnrampButton.forCheckout(integrations.onrampProvider()));
        }
        add(new H1("Stablecoin checkout"), checkout);
    }
}
