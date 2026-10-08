package com.wontlost.web3.siwe;

import java.util.Optional;
import java.util.concurrent.locks.Lock;

import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.identity.Web3Identity;
import com.wontlost.web3.siws.VerifiedSolanaSignIn;

/** Stores the verified wallet identity in the current Vaadin session. */
public final class Web3Session {

    private static final String SESSION_KEY = Web3Session.class.getName() + ".verifiedSignIn";

    private Web3Session() {
    }

    /**
     * Returns the verified EVM (SIWE) sign-in for the current Vaadin session, if any. Returns empty when the session
     * holds a non-EVM identity, so EVM-only components treat such a session as signed out.
     */
    public static Optional<VerifiedSignIn> current() {
        VaadinSession session = VaadinSession.getCurrent();
        return current(session);
    }

    public static Optional<VerifiedSignIn> current(VaadinSession session) {
        return currentIdentity(session).filter(VerifiedSignIn.class::isInstance).map(VerifiedSignIn.class::cast);
    }

    /** Returns the verified wallet identity of any chain for the current Vaadin session, if any. */
    public static Optional<Web3Identity> currentIdentity() {
        return currentIdentity(VaadinSession.getCurrent());
    }

    public static Optional<Web3Identity> currentIdentity(VaadinSession session) {
        if (session == null) {
            return Optional.empty();
        }
        Lock lock = session.getLockInstance();
        if (lock == null) {
            return identity(session.getAttribute(SESSION_KEY));
        }
        lock.lock();
        try {
            return identity(session.getAttribute(SESSION_KEY));
        } finally {
            lock.unlock();
        }
    }

    // 会话属性只应是 Web3Identity；其它类型（例如被应用误写）按未登录处理
    private static Optional<Web3Identity> identity(Object attribute) {
        return attribute instanceof Web3Identity identity ? Optional.of(identity) : Optional.empty();
    }

    /** Removes the verified sign-in from the current Vaadin session. */
    public static void signOut() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setAttribute(SESSION_KEY, null);
        }
    }

    /**
     * Stores a verified Sign-In With Solana result as the session identity, replacing any previous one. Only
     * {@link com.wontlost.web3.siws.SiwsVerifier} can create {@link VerifiedSolanaSignIn} instances, so this cannot
     * store an unverified identity. Callers should rotate the HTTP session id on sign-in, as {@code SiweLogin} does.
     */
    public static void signIn(VerifiedSolanaSignIn signIn) {
        java.util.Objects.requireNonNull(signIn);
        storeIdentity(signIn);
    }

    static void store(VerifiedSignIn signIn) {
        storeIdentity(signIn);
    }

    private static void storeIdentity(Web3Identity identity) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            throw new IllegalStateException("There is no current Vaadin session");
        }
        session.setAttribute(SESSION_KEY, identity);
    }
}
