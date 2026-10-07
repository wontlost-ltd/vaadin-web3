package com.wontlost.web3.siwe;

import java.util.Optional;
import java.util.concurrent.locks.Lock;

import com.vaadin.flow.server.VaadinSession;

/** Stores the verified wallet identity in the current Vaadin session. */
public final class Web3Session {

    private static final String SESSION_KEY = Web3Session.class.getName() + ".verifiedSignIn";

    private Web3Session() {
    }

    /** Returns the verified sign-in for the current Vaadin session, if any. */
    public static Optional<VerifiedSignIn> current() {
        VaadinSession session = VaadinSession.getCurrent();
        return current(session);
    }

    public static Optional<VerifiedSignIn> current(VaadinSession session) {
        if (session == null) {
            return Optional.empty();
        }
        Lock lock = session.getLockInstance();
        if (lock == null) {
            return Optional.ofNullable((VerifiedSignIn) session.getAttribute(SESSION_KEY));
        }
        lock.lock();
        try {
            return Optional.ofNullable((VerifiedSignIn) session.getAttribute(SESSION_KEY));
        } finally {
            lock.unlock();
        }
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
