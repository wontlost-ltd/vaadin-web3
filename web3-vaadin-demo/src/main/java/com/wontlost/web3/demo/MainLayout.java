package com.wontlost.web3.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.autoconfigure.security.Web3LogoutHandler;
import com.wontlost.web3.autoconfigure.security.Web3Principal;
import com.wontlost.web3.walletconnect.WalletConnect;

/** Provides navigation between the demo views. */
@AnonymousAllowed
public class MainLayout extends AppLayout implements BeforeEnterObserver {
    private final SideNavItem accountItem = new SideNavItem("Account", "/account");
    private final Button signOut = new Button("Sign out");

    public MainLayout(@Value("${web3.walletconnect.project-id:}") String projectId,
            Web3LogoutHandler web3LogoutHandler, SecurityContextRepository contextRepository) {
        H1 title = new H1("Vaadin Web3");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)").set("margin", "0");
        addToNavbar(title);

        SideNav navigation = new SideNav();
        navigation.addItem(new SideNavItem("Wallet", "/"));
        navigation.addItem(new SideNavItem("Sign in", "/login"));
        navigation.addItem(new SideNavItem("Token holders", "/holders"));
        navigation.addItem(new SideNavItem("Checkout", "/checkout"));
        navigation.addItem(accountItem);
        addToDrawer(navigation);
        signOut.addClickListener(event -> signOut(web3LogoutHandler, contextRepository));
        addToNavbar(signOut);

        if (!projectId.isBlank()) {
            WalletConnect walletConnect = new WalletConnect(projectId).setChains(11155111, 84532);
            walletConnect.getStyle().set("display", "none");
            addToNavbar(walletConnect);
        }
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Web3Principal;
        accountItem.setVisible(signedIn);
        signOut.setVisible(signedIn);
    }

    private void signOut(Web3LogoutHandler logoutHandler, SecurityContextRepository contextRepository) {
        var request = VaadinServletRequest.getCurrent();
        var response = VaadinServletResponse.getCurrent();
        if (request == null || response == null) {
            throw new IllegalStateException("SIWE sign-out requires a current servlet request and response");
        }
        logoutHandler.logout(request.getHttpServletRequest(), response.getHttpServletResponse(),
                SecurityContextHolder.getContext().getAuthentication());
        SecurityContext emptyContext = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(emptyContext);
        contextRepository.saveContext(emptyContext, request.getHttpServletRequest(), response.getHttpServletResponse());
        getUI().ifPresent(ui -> ui.navigate("login"));
    }
}
