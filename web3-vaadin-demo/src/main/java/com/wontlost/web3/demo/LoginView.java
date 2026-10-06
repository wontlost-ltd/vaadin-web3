package com.wontlost.web3.demo;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.autoconfigure.Web3SiweLoginConfigurer;
import com.wontlost.web3.siwe.NonceStore;
import com.wontlost.web3.siwe.SiweLogin;

/** Demonstrates server-verified Sign-In with Ethereum. */
@Route(value = "login", layout = MainLayout.class)
@AnonymousAllowed
public class LoginView extends VerticalLayout {

    private final Paragraph status = new Paragraph("Not signed in.");

    public LoginView(NonceStore nonceStore, Web3SiweLoginConfigurer loginConfigurer) {
        setMaxWidth("720px");
        getStyle().set("margin", "0 auto");
        SiweLogin login = loginConfigurer.configure(new SiweLogin(nonceStore));
        login.addSignedInListener(event -> {
            var verified = event.getSignIn();
            status.setText("Signed in as " + verified.address() + " on chain " + verified.chainId());
        });
        login.addSignInFailedListener(event -> {
            if (event.getReason() != null) {
                status.setText("Sign-in failed: " + event.getReason());
            } else {
                status.setText("Wallet error " + event.getWalletErrorCode()
                        + (event.isUserRejected() ? " (user rejected)" : ""));
            }
        });
        login.addSignedOutListener(event -> {
            status.setText("Signed out.");
        });
        add(new H1("Sign-In with Ethereum"), login, status);
    }

}
