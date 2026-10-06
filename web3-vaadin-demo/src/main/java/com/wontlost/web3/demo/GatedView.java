package com.wontlost.web3.demo;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.gate.RequiresToken;

/** Demonstrates a token gated route. */
@Route(value = "holders", layout = MainLayout.class)
@AnonymousAllowed
@RequiresToken(chainId = 11155111, token = "USDC", minBalance = "1")
public class GatedView extends VerticalLayout {
    public GatedView() { add(new H1("Welcome, USDC holder")); }
}
