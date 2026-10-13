package com.wontlost.web3.demo;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import com.wontlost.web3.autoconfigure.security.Web3Principal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;

@Route(value = "account", layout = MainLayout.class)
@PermitAll
public class AccountView extends DemoPage {
    public AccountView() {
        super("Account", "View the wallet identity associated with the current verified session.");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Web3Principal principal = (Web3Principal) authentication.getPrincipal();
        add(new DemoSection("Verified identity", "These values come from the authenticated SIWE session.",
                new Paragraph("Wallet: " + principal.address()), new Paragraph("Chain ID: " + principal.chainId())));
    }
}
