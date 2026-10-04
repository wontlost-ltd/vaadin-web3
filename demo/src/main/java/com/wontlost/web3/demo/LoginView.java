package com.wontlost.web3.demo;

import java.time.Duration;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.wontlost.web3.siwe.InMemoryNonceStore;
import com.wontlost.web3.siwe.SiweLogin;
import com.wontlost.web3.siwe.Web3Session;

/** Demonstrates server-verified Sign-In with Ethereum. */
@Route("login")
public class LoginView extends VerticalLayout {

    private static final InMemoryNonceStore NONCES = new InMemoryNonceStore(Duration.ofMinutes(5));

    private final Paragraph status = new Paragraph("Not signed in.");
    private final Button signOut;

    public LoginView() {
        setMaxWidth("720px");
        getStyle().set("margin", "0 auto");
        signOut = new Button("Sign out", event -> signOut());
        signOut.setVisible(false);

        SiweLogin login = new SiweLogin(NONCES);
        login.addSignedInListener(event -> {
            var verified = event.getSignIn();
            status.setText("Signed in as " + verified.address() + " on chain " + verified.chainId());
            signOut.setVisible(true);
        });
        login.addSignInFailedListener(event -> {
            if (event.getReason() != null) {
                status.setText("Sign-in failed: " + event.getReason());
            } else {
                status.setText("Wallet error " + event.getWalletErrorCode()
                        + (event.isUserRejected() ? " (user rejected)" : ""));
            }
        });
        add(new H1("Sign-In with Ethereum"), login, status, signOut);
    }

    private void signOut() {
        Web3Session.signOut();
        status.setText("Signed out.");
        signOut.setVisible(false);
    }
}
