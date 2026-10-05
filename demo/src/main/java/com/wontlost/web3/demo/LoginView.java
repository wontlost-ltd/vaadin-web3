package com.wontlost.web3.demo;


import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.wontlost.web3.pro.jdbc.JdbcNonceStore;
import com.wontlost.web3.siwe.SiweLogin;

/** Demonstrates server-verified Sign-In with Ethereum. */
@Route(value = "login", layout = MainLayout.class)
public class LoginView extends VerticalLayout {

    private final Paragraph status = new Paragraph("Not signed in.");
    private final Button signOut;

    public LoginView(JdbcNonceStore nonces) {
        setMaxWidth("720px");
        getStyle().set("margin", "0 auto");
        signOut = new Button("Sign out");
        signOut.setVisible(false);

        SiweLogin login = new SiweLogin(nonces);
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
        login.addSignedOutListener(event -> {
            status.setText("Signed out.");
            signOut.setVisible(false);
        });
        signOut.addClickListener(event -> login.signOut());
        add(new H1("Sign-In with Ethereum"), login, status, signOut);
    }

}
