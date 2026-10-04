package com.wontlost.web3.demo;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.InMemoryPaymentLedger;
import com.wontlost.web3.pay.StablecoinCheckout;

/** Demonstrates a Sepolia stablecoin checkout. */
@Route(value = "checkout", layout = MainLayout.class)
public class CheckoutView extends VerticalLayout {
    public CheckoutView(ChainRegistry chains, InMemoryPaymentLedger ledger,
            @Value("${web3.demo.recipient}") String recipient) {
        add(new H1("Stablecoin checkout"), new StablecoinCheckout(chains, ledger, recipient,
                new BigDecimal("1.00")).setTokens("USDC", "PYUSD").setPreferredChain(11155111));
    }
}
