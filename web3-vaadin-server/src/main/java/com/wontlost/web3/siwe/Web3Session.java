package com.wontlost.web3.siwe;

import java.util.Optional;

import com.vaadin.flow.server.VaadinSession;

/** Stores the verified wallet identity in the current Vaadin session. */
public final class Web3Session {

    private static final String SESSION_KEY = Web3Session.class.getName() + ".verifiedSignIn";

    private Web3Session() {
    }

    /** Returns the verified sign-in for the current Vaadin session, if any. */
    public static Optional<VerifiedSignIn> current() {
        VaadinSession session = VaadinSession.getCurrent();
        return session == null ? Optional.empty()
                : Optional.ofNullable((VerifiedSignIn) session.getAttribute(SESSION_KEY));
    }

    /** Removes the verified sign-in from the current Vaadin session. */
    public static void signOut() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setAttribute(SESSION_KEY, null);
        }
    }

    static void store(VerifiedSignIn signIn) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            throw new IllegalStateException("There is no current Vaadin session");
        }
        session.setAttribute(SESSION_KEY, signIn);
    }
}
