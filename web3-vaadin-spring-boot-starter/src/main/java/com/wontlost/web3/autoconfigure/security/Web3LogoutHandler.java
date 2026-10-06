package com.wontlost.web3.autoconfigure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import com.wontlost.web3.siwe.Web3Session;

/** Clears the Vaadin Web3 identity when an application uses Spring Security logout. */
public final class Web3LogoutHandler implements LogoutHandler {
    @Override public void logout(jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response, Authentication authentication) {
        Web3Session.signOut();
    }
}
