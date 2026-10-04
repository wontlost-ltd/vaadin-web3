package com.wontlost.web3.demo;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;

/** Provides navigation between the demo views. */
public class MainLayout extends AppLayout {
    public MainLayout() {
        H1 title = new H1("Vaadin Web3");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)").set("margin", "0");
        addToNavbar(title);

        SideNav navigation = new SideNav();
        navigation.addItem(new SideNavItem("Wallet", "/"));
        navigation.addItem(new SideNavItem("Sign in", "/login"));
        navigation.addItem(new SideNavItem("Token holders", "/holders"));
        navigation.addItem(new SideNavItem("Checkout", "/checkout"));
        addToDrawer(navigation);
    }
}
