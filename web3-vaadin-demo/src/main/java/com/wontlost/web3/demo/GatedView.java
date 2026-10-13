package com.wontlost.web3.demo;

import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.gate.RequiresToken;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;

/** Demonstrates a token gated route. */
@Route(value = "holders", layout = MainLayout.class)
@AnonymousAllowed
@RequiresToken(chainId = 11155111, token = "USDC", minBalance = "1")
public class GatedView extends DemoPage {
    public GatedView() {
        super("Welcome, USDC holder", "This route is available to wallets meeting the configured token requirement.");
        add(new DemoSection("Access granted", "The connected wallet satisfies the USDC holding requirement."));
    }
}
