package com.wontlost.web3.demo;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.autoconfigure.Web3SiweLoginConfigurer;
import com.wontlost.web3.siwe.NonceStore;
import com.wontlost.web3.siwe.SiweLogin;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;
import com.wontlost.web3.demo.ui.DemoStatus;

/** Demonstrates server-verified Sign-In with Ethereum. */
@Route(value = "login", layout = MainLayout.class)
@AnonymousAllowed
public class LoginView extends DemoPage {

    private final DemoStatus status = new DemoStatus("pending", "Not signed in.");

    public LoginView(NonceStore nonceStore, Web3SiweLoginConfigurer loginConfigurer) {
        super("Sign-In with Ethereum", "Verify a wallet signature to establish an application session.");
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
        add(new DemoSection("Wallet verification", "Review and sign the SIWE challenge in your wallet.", login, status));
    }

}
