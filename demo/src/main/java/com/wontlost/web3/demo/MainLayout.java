package com.wontlost.web3.demo;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.wontlost.web3.walletconnect.WalletConnect;

/** Provides navigation between the demo views. */
public class MainLayout extends AppLayout {
    public MainLayout(@Value("${web3.walletconnect.project-id:}") String projectId) {
        H1 title = new H1("Vaadin Web3");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)").set("margin", "0");
        addToNavbar(title);

        SideNav navigation = new SideNav();
        navigation.addItem(new SideNavItem("Wallet", "/"));
        navigation.addItem(new SideNavItem("Sign in", "/login"));
        navigation.addItem(new SideNavItem("Token holders", "/holders"));
        navigation.addItem(new SideNavItem("Checkout", "/checkout"));
        navigation.addItem(new SideNavItem("Payments", "/payments"));
        addToDrawer(navigation);

        if (!projectId.isBlank()) {
            WalletConnect walletConnect = new WalletConnect(projectId).setChains(11155111, 84532);
            walletConnect.getStyle().set("display", "none");
            addToNavbar(walletConnect);
        }
    }
}
