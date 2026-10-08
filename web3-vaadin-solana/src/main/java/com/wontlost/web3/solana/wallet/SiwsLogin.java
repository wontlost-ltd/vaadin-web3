package com.wontlost.web3.solana.wallet;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.screening.AddressScreening;
import com.wontlost.web3.siwe.RequestOrigins;
import com.wontlost.web3.siwe.SessionIds;
import com.wontlost.web3.siwe.Web3Session;
import com.wontlost.web3.siws.SiwsChallenge;
import com.wontlost.web3.siws.SiwsException;
import com.wontlost.web3.siws.SiwsVerifier;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.siws.VerifiedSolanaSignIn;

/**
 * Vaadin sign-in control for Sign-In With Solana: the server issues a challenge, the wallet signs it, and the server
 * verifies the exact signed bytes before storing the identity in {@link Web3Session}.
 * <p>
 * Supply an application-scoped {@link SiwsVerifier}; it is registered with the current {@link VaadinContext} and
 * restored after UI deserialization. Configure the public domain and URI explicitly in production, especially behind
 * a proxy. When an {@link AddressScreening} service is registered, the signer's address is screened before sign-in.
 */
public class SiwsLogin extends Composite<HorizontalLayout> {
    /** Failure code when address screening blocks the signer. */
    public static final String ADDRESS_BLOCKED = "siws_address_blocked";
    /** Failure code when address screening cannot be reached. */
    public static final String SCREENING_UNAVAILABLE = "siws_screening_unavailable";
    /** Failure code when the request origin cannot be determined. */
    public static final String ORIGIN_UNAVAILABLE = "siws_origin_unavailable";
    /** Failure code for an unexpected server error, for example a full challenge store. */
    public static final String INTERNAL_ERROR = "siws_internal_error";
    /** How long an issued challenge stays valid. */
    public static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);

    private static final Logger LOGGER = LoggerFactory.getLogger(SiwsLogin.class);
    private final SolanaConnect wallet;
    private final Button signInButton = new Button();
    private final SolanaCluster cluster;
    private transient SiwsVerifier verifier;
    /** 测试用替身；transient 字段反序列化后为 null，届时回退到当前 Vaadin 上下文与请求。 */
    private transient Supplier<VaadinContext> contextLookup;
    private transient Supplier<VaadinRequest> requestLookup;
    private String domain;
    private String uri;
    private String statement;
    private boolean flowInProgress;
    private boolean disconnectWalletOnSignOut = true;
    private boolean navigateToContinueTarget = true;
    private boolean sessionIdRotation = true;
    private SiwsLoginI18n i18n = new SiwsLoginI18n();

    /** Creates a sign-in control for {@code cluster} using the application's verifier. */
    public SiwsLogin(SiwsVerifier verifier, SolanaCluster cluster) {
        this(verifier, cluster, new SolanaConnect(true));
    }

    SiwsLogin(SiwsVerifier verifier, SolanaCluster cluster, SolanaConnect wallet) {
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        VaadinContext context = currentContext();
        if (context != null) registerVerifier(context, verifier);
        wallet.setCluster(cluster);
        signInButton.setText(i18n.getButton());
        signInButton.addClickListener(event -> beginSignIn());
        getContent().add(wallet, signInButton);
    }

    /** Registers the application-scoped verifier so UIs restored after deserialization can find it. */
    public static void registerVerifier(VaadinContext context, SiwsVerifier verifier) {
        Objects.requireNonNull(context, "context").setAttribute(SiwsVerifier.class, Objects.requireNonNull(verifier));
    }

    /** Sets the expected domain. Configure this explicitly in production, especially behind a proxy. */
    public SiwsLogin setDomain(String value) { domain = value; return this; }
    /** Sets the expected URI. Configure this explicitly in production, especially behind a proxy. */
    public SiwsLogin setUri(String value) { uri = value; return this; }
    /** Sets an optional statement shown in the wallet. */
    public SiwsLogin setStatement(String value) { statement = value; return this; }
    /** Sets whether signing out also disconnects the wallet. */
    public SiwsLogin setDisconnectWalletOnSignOut(boolean value) { disconnectWalletOnSignOut = value; return this; }
    /** Sets whether a successful sign-in navigates to a safe {@code continue} target in the current URL. */
    public SiwsLogin setNavigateToContinueTarget(boolean value) { navigateToContinueTarget = value; return this; }
    /** Enables session ID rotation after sign-in; disable only when the application rotates it itself. */
    public SiwsLogin setSessionIdRotation(boolean value) { sessionIdRotation = value; return this; }
    /** The wallet component, for customization. */
    public SolanaConnect getWallet() { return wallet; }
    /** The cluster named in the sign-in message. */
    public SolanaCluster getCluster() { return cluster; }
    /** Localized labels and failure messages. */
    public SiwsLoginI18n getI18n() { return i18n; }

    /** Sets localized labels and failure messages. */
    public SiwsLogin setI18n(SiwsLoginI18n value) {
        i18n = Objects.requireNonNull(value);
        signInButton.setText(value.getButton());
        return this;
    }

    /** Reattaches the application verifier after UI deserialization. */
    public SiwsLogin setVerifier(SiwsVerifier value) {
        verifier = Objects.requireNonNull(value);
        return this;
    }

    /** Clears the verified session, disconnects the wallet by default, and fires a signed-out event. */
    public void signOut() {
        Web3Session.signOut();
        if (disconnectWalletOnSignOut) wallet.disconnect();
        fireEvent(new SignedOutEvent(this));
    }

    void setContextLookup(Supplier<VaadinContext> lookup) { contextLookup = Objects.requireNonNull(lookup); }
    void setRequestLookup(Supplier<VaadinRequest> lookup) { requestLookup = Objects.requireNonNull(lookup); }

    void beginSignIn() {
        if (flowInProgress) return;
        flowInProgress = true;
        signInButton.setEnabled(false);
        String expectedDomain;
        String expectedUri;
        try {
            VaadinRequest request = request();
            expectedDomain = domain == null ? RequestOrigins.domain(request) : domain;
            expectedUri = uri == null ? RequestOrigins.uri(request) : uri;
        } catch (RuntimeException exception) {
            finishFailure(ORIGIN_UNAVAILABLE, -1, false);
            return;
        }
        CompletableFuture<String> connected = wallet.isConnected()
                ? CompletableFuture.completedFuture(wallet.getAccount()) : wallet.connect();
        connected.thenCompose(address -> {
            SiwsChallenge challenge = requireVerifier().issue(expectedDomain, expectedUri, statement, cluster,
                    CHALLENGE_TTL, List.of());
            return wallet.signIn(challenge, address);
        }).whenComplete((signed, error) -> {
            if (error != null) {
                failWith(unwrap(error));
                return;
            }
            try {
                VerifiedSolanaSignIn verified = requireVerifier().verify(signed.signedMessage(), signed.signature(),
                        signed.publicKey());
                screen(verified.address(), AddressScreening.find(context()));
                if (sessionIdRotation) SessionIds.rotate(request());
                Web3Session.signIn(verified);
                finishSuccess(verified);
            } catch (SiwsException exception) {
                finishFailure(exception.code(), -1, false);
            } catch (RuntimeException exception) {
                // 例如没有当前会话或监听器抛错：必须结束流程，否则按钮保持禁用且不会再触发事件
                LOGGER.error("Sign-In With Solana failed unexpectedly", exception);
                finishFailure(INTERNAL_ERROR, -1, false);
            }
        });
    }

    static void screen(String address, AddressScreening screening) {
        if (screening == null) return;
        AddressScreening.ScreeningDecision decision;
        try {
            decision = Objects.requireNonNull(screening.screen(address), "screening decision");
        } catch (RuntimeException failure) {
            throw new SiwsException(SCREENING_UNAVAILABLE);
        }
        if (!decision.allowed()) throw new SiwsException(ADDRESS_BLOCKED);
    }

    private void failWith(Throwable cause) {
        if (cause instanceof SiwsException siws) {
            finishFailure(siws.code(), -1, false);
        } else if (cause instanceof SolanaConnect.SolanaWalletException walletError) {
            finishFailure(null, walletError.getCode(), walletError.isUserRejected());
        } else {
            finishFailure(INTERNAL_ERROR, -1, false);
        }
    }

    private SiwsVerifier requireVerifier() {
        if (verifier == null) {
            VaadinContext context = context();
            SiwsVerifier restored = context == null ? null : context.getAttribute(SiwsVerifier.class);
            if (restored == null) {
                throw new IllegalStateException("No application SiwsVerifier is registered; call "
                        + "SiwsLogin.registerVerifier(VaadinContext, SiwsVerifier) before restoring the UI");
            }
            verifier = restored;
        }
        return verifier;
    }

    private void finishSuccess(VerifiedSolanaSignIn verified) {
        if (!flowInProgress) return;
        flowInProgress = false;
        signInButton.setEnabled(true);
        // 先通知监听器（例如写入 Spring Security），再导航：否则受保护的 continue 目标会在认证建立前被拦截
        fireEvent(new SignedInEvent(this, verified));
        // 监听器可能已注销登录，此时不导航
        if (!navigateToContinueTarget || Web3Session.currentIdentity().isEmpty()) return;
        getUI().ifPresent(ui -> {
            QueryParameters parameters = ui.getInternals().getActiveViewLocation().getQueryParameters();
            parameters.getSingleParameter("continue").filter(RequestOrigins::isSafeContinueTarget)
                    .ifPresent(ui::navigate);
        });
    }

    private void finishFailure(String code, int walletErrorCode, boolean userRejected) {
        if (!flowInProgress) return;
        flowInProgress = false;
        signInButton.setEnabled(true);
        fireEvent(new SignInFailedEvent(this, code, walletErrorCode, userRejected));
    }

    private VaadinContext context() {
        return contextLookup == null ? currentContext() : contextLookup.get();
    }

    private VaadinRequest request() {
        return requestLookup == null ? VaadinRequest.getCurrent() : requestLookup.get();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static VaadinContext currentContext() {
        VaadinService service = VaadinService.getCurrent();
        return service == null ? null : service.getContext();
    }

    /** Registers a listener for successful sign-ins. */
    public Registration addSignedInListener(ComponentEventListener<SignedInEvent> listener) {
        return addListener(SignedInEvent.class, listener);
    }

    /** Registers a listener for failed sign-ins. */
    public Registration addSignInFailedListener(ComponentEventListener<SignInFailedEvent> listener) {
        return addListener(SignInFailedEvent.class, listener);
    }

    /** Registers a listener for sign-outs. */
    public Registration addSignedOutListener(ComponentEventListener<SignedOutEvent> listener) {
        return addListener(SignedOutEvent.class, listener);
    }

    /** Fired after the wallet's signature has been verified and the identity stored. */
    public static class SignedInEvent extends ComponentEvent<SiwsLogin> {
        private final VerifiedSolanaSignIn signIn;

        public SignedInEvent(SiwsLogin source, VerifiedSolanaSignIn signIn) {
            super(source, false);
            this.signIn = signIn;
        }

        /** The verified identity. */
        public VerifiedSolanaSignIn getSignIn() { return signIn; }
    }

    /** Fired when the wallet fails or the signature cannot be verified. */
    public static class SignInFailedEvent extends ComponentEvent<SiwsLogin> {
        private final String code;
        private final int walletErrorCode;
        private final boolean userRejected;

        public SignInFailedEvent(SiwsLogin source, String code, int walletErrorCode, boolean userRejected) {
            super(source, false);
            this.code = code;
            this.walletErrorCode = walletErrorCode;
            this.userRejected = userRejected;
        }

        /** The failure code, such as {@code siws_expired}, or {@code null} for a wallet error. */
        public String getCode() { return code; }
        /** The wallet error code, or -1 when unavailable. */
        public int getWalletErrorCode() { return walletErrorCode; }
        /** Whether the user rejected the request in the wallet. */
        public boolean isUserRejected() { return userRejected; }

        /** The localized message configured on the source component. */
        public String getLocalizedMessage() {
            SiwsLoginI18n messages = getSource().getI18n();
            if (code != null) return messages.getMessage(code);
            return userRejected ? messages.getUserRejected() : messages.getWalletError();
        }
    }

    /** Fired after the verified identity has been cleared. */
    public static class SignedOutEvent extends ComponentEvent<SiwsLogin> {
        public SignedOutEvent(SiwsLogin source) { super(source, false); }
    }
}
