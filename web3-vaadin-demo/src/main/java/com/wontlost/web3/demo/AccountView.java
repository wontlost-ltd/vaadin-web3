package com.wontlost.web3.demo;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import com.wontlost.web3.autoconfigure.security.Web3Principal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Route(value = "account", layout = MainLayout.class)
@PermitAll
public class AccountView extends VerticalLayout {
    public AccountView() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Web3Principal principal = (Web3Principal) authentication.getPrincipal();
        add(new H1("Account"), new Paragraph("Wallet: " + principal.address()),
                new Paragraph("Chain ID: " + principal.chainId()));
    }
}
