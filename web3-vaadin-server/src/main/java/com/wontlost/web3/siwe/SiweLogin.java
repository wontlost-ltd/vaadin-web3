package com.wontlost.web3.siwe;

import java.io.Serializable;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.web3j.crypto.Keys;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.Chains;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.screening.AddressScreening;

/**
 * Vaadin sign-in control that requests an EIP-4361 signature and verifies it on the server.
 * <p>
 * Supply an application-scoped {@link NonceStore}; it is registered with the current
 * {@link VaadinContext} when a service is available and restored after UI deserialization.
 * Configure the public domain and URI explicitly in production, especially behind a proxy.
 * <p>
 * Smart-contract wallets (ERC-1271, and ERC-6492 for wallets not deployed yet) are accepted automatically when a
 * {@link ChainRegistry} is stored in the {@link VaadinContext} with an RPC client for the signing chain; otherwise
 * only externally owned accounts can sign in.
 */
public class SiweLogin extends Composite<HorizontalLayout> {

    private final Web3Connect wallet = new Web3Connect(true);
    private final Button signInButton = new Button();
    private transient NonceStore nonces;
    /** 使用者通过 setVerifier 提供的校验器；为空时每次按当前上下文构建默认校验器。 */
    private transient SiweVerifier customVerifier;
    private transient Supplier<VaadinContext> contextLookup = SiweLogin::currentContext;
    /** 测试用：替换当前请求来源，以便验证 session ID 轮换；为空时使用 VaadinRequest.getCurrent()。 */
    private transient Supplier<VaadinRequest> requestLookup;
    private String domain;
    private String uri;
    private String statement;
    private Set<Long> allowedChainIds = Set.of();
    private boolean flowInProgress;
    private boolean disconnectWalletOnSignOut = true;
    private boolean navigateToContinueTarget = true;
    private boolean sessionIdRotation = true;
    private java.time.Duration maxAge;
    private SiweLoginI18n i18n = new SiweLoginI18n();

    /** Creates a SIWE component using the supplied one-time nonce store. */
    public SiweLogin(NonceStore nonces) {
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        signInButton.setText(i18n.getButton());
        VaadinContext context = currentContext();
        if (context != null) {
            registerNonceStore(context, nonces);
        }
        signInButton.addClickListener(event -> beginSignIn());
        wallet.addErrorListener(event -> {
            if (flowInProgress) {
                finishFailure(null, event.getCode(), event.isUserRejected());
            }
        });
        getContent().add(wallet, signInButton);
    }

    /** Sets the expected domain. Configure this explicitly in production, especially behind a proxy. */
    public SiweLogin setDomain(String value) { domain = value; return this; }
    /** Sets the expected URI. Configure this explicitly in production, especially behind a proxy. */
    public SiweLogin setUri(String value) { uri = value; return this; }
    /** Sets an optional EIP-4361 statement. */
    public SiweLogin setStatement(String value) { statement = value; return this; }
    /** Restricts accepted chain ids; an empty set allows any positive chain id. */
    public SiweLogin setAllowedChainIds(Set<Long> values) { allowedChainIds = Set.copyOf(values); return this; }
    /**
     * Rejects messages whose {@code Issued At} is older than the given age; {@code null} (the default) applies no
     * limit beyond the message's own expiration time.
     */
    public SiweLogin setMaxAge(java.time.Duration value) {
        if (value != null && (value.isNegative() || value.isZero())) throw new IllegalArgumentException("maxAge must be positive");
        maxAge = value;
        return this;
    }
    /** Replaces the verifier, for example to provide a custom clock or policy. */
    public SiweLogin setVerifier(SiweVerifier value) { customVerifier = Objects.requireNonNull(value); return this; }

    /** Reattaches the application nonce store after UI deserialization. */
    public SiweLogin setNonceStore(NonceStore value) {
        nonces = Objects.requireNonNull(value);
        VaadinContext context = currentContext();
        if (context != null) {
            registerNonceStore(context, value);
        }
        return this;
    }

    /** Registers the application-scoped nonce store for UI restoration after deserialization. */
    public static void registerNonceStore(VaadinContext context, NonceStore store) {
        Objects.requireNonNull(context, "context").setAttribute(NonceStore.class, Objects.requireNonNull(store, "store"));
    }

    private static VaadinContext currentContext() {
        VaadinService service = VaadinService.getCurrent();
        return service == null ? null : service.getContext();
    }

    void setContextLookup(Supplier<VaadinContext> lookup) {
        contextLookup = Objects.requireNonNull(lookup);
    }
    void setRequestLookup(Supplier<VaadinRequest> lookup) {
        requestLookup = Objects.requireNonNull(lookup);
    }
    /** Returns the wallet component for external customization. */
    public Web3Connect getWallet() { return wallet; }
    /** Sets the sign-in button label; the most recently called text or i18n setter controls the visible label. */
    public SiweLogin setButtonText(String text) {
        i18n.setButton(Objects.requireNonNull(text));
        signInButton.setText(text);
        return this;
    }
    /** Sets localized sign-in labels and failure messages. */
    public SiweLogin setI18n(SiweLoginI18n value) {
        i18n = Objects.requireNonNull(value);
        signInButton.setText(value.getButton());
        return this;
    }
    /** Returns localized sign-in labels and failure messages. */
    public SiweLoginI18n getI18n() { return i18n; }
    /** Sets whether signing out also disconnects the wallet. */
    public SiweLogin setDisconnectWalletOnSignOut(boolean value) {
        disconnectWalletOnSignOut = value;
        return this;
    }
    /** Sets whether a successful sign-in navigates to a safe continue target in the current URL. */
    public SiweLogin setNavigateToContinueTarget(boolean value) {
        navigateToContinueTarget = value;
        return this;
    }
    /**
     * Enables session ID rotation after successful verification; disable only when the application handles it itself.
     * Rotation requires a servlet request capable of setting response cookies, such as the default {@code WEBSOCKET_XHR}
     * or long-polling transport. Pure WebSocket callbacks log a warning and complete sign-in without rotation.
     */
    public SiweLogin setSessionIdRotation(boolean value) { sessionIdRotation = value; return this; }

    /** Clears the verified session, disconnects the wallet by default, and fires a signed-out event. */
    public void signOut() {
        Web3Session.signOut();
        if (disconnectWalletOnSignOut) wallet.disconnect();
        fireEvent(new SignedOutEvent(this));
    }

    private void beginSignIn() {
        if (flowInProgress) {
            return;
        }
        flowInProgress = true;
        signInButton.setEnabled(false);
        String expectedDomain;
        String expectedUri;
        try {
            expectedDomain = domain == null ? requestDomain(VaadinRequest.getCurrent()) : domain;
            expectedUri = uri == null ? requestUri(VaadinRequest.getCurrent()) : uri;
        } catch (RuntimeException exception) {
            finishFailure(SiweException.Reason.MALFORMED, -1, false);
            return;
        }

        var connected = wallet.isConnected()
                ? java.util.concurrent.CompletableFuture.completedFuture(wallet.getAccount()) : wallet.connect();
        connected.thenCompose(address -> {
            String canonicalAddress = Keys.toChecksumAddress(address);
            long chainId = Chains.toDecimal(wallet.getChainId()).longValueExact();
            String nonce = requireNonces().issue();
            Instant now = Clock.systemUTC().instant();
            SiweMessage message = SiweMessage.builder()
                    .scheme(URI.create(expectedUri).getScheme())
                    .domain(expectedDomain)
                    .address(canonicalAddress)
                    .statement(statement)
                    .uri(expectedUri)
                    .chainId(chainId)
                    .nonce(nonce)
                    .issuedAt(now)
                    .expirationTime(now.plus(Duration.ofMinutes(10)))
                    .build();
            return wallet.signMessage(message.toMessage()).thenApply(signature -> new SignedPayload(message, signature));
        }).whenComplete((payload, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof SiweException siweException) {
                    finishFailure(siweException.getReason(), -1, false);
                } else if (cause instanceof Web3Connect.Web3Exception walletException) {
                    finishFailure(null, walletException.getCode(), walletException.isUserRejected());
                } else {
                    finishFailure(SiweException.Reason.MALFORMED, -1, false);
                }
                return;
            }
            try {
                SiweExpectations expectations = SiweExpectations.forDomain(expectedDomain)
                        .withUri(expectedUri).withAllowedChainIds(allowedChainIds).withMaxAge(maxAge);
                VerifiedSignIn verified = requireVerifier().verify(payload.message().toMessage(),
                        payload.signature(), expectations);
                completeVerifiedSignIn(verified);
            } catch (SiweException exception) {
                finishFailure(exception.getReason(), -1, false);
            }
        });
    }

    private void completeVerifiedSignIn(VerifiedSignIn verified) {
        try {
            screenVerifiedAddress(verified, AddressScreening.find(contextLookup == null ? currentContext() : contextLookup.get()));
            if (sessionIdRotation) SessionIds.rotate(requestLookup == null ? VaadinRequest.getCurrent() : requestLookup.get());
            Web3Session.store(verified);
            finishSuccess(verified);
        } catch (SiweException exception) {
            finishFailure(exception.getReason(), -1, false);
        }
    }

    static void screenVerifiedAddress(VerifiedSignIn verified, AddressScreening screening) {
        if (screening == null) return;
        AddressScreening.ScreeningDecision decision;
        try {
            decision = Objects.requireNonNull(screening.screen(verified.address()), "screening decision");
        } catch (RuntimeException failure) {
            throw new SiweException(SiweException.Reason.SCREENING_UNAVAILABLE,
                    "Address screening is unavailable", failure);
        }
        if (!decision.allowed()) {
            throw new SiweException(SiweException.Reason.ADDRESS_BLOCKED,
                    decision.reason() == null ? "Address is blocked" : decision.reason());
        }
    }

    private NonceStore requireNonces() {
        if (nonces == null) {
            VaadinContext context = contextLookup == null ? currentContext() : contextLookup.get();
            NonceStore restored = context == null ? null : context.getAttribute(NonceStore.class);
            if (restored == null) {
                throw new IllegalStateException("No application NonceStore is registered; call "
                        + "SiweLogin.registerNonceStore(VaadinContext, NonceStore) before restoring the UI");
            }
            nonces = restored;
        }
        return nonces;
    }

    private SiweVerifier requireVerifier() {
        if (customVerifier != null) {
            return customVerifier;
        }
        VaadinContext context = contextLookup == null ? currentContext() : contextLookup.get();
        ChainRegistry chains = context == null ? null : context.getAttribute(ChainRegistry.class);
        return new SiweVerifier(requireNonces(), Clock.systemUTC(), chains);
    }

    private void finishSuccess(VerifiedSignIn verified) {
        if (!flowInProgress) {
            return;
        }
        flowInProgress = false;
        signInButton.setEnabled(true);
        // 先通知监听器（例如把身份写入 Spring Security），再导航：否则受保护的 continue 目标会在认证建立之前被访问控制拦截
        fireEvent(new SignedInEvent(this, verified));
        if (navigateToContinueTarget && Web3Session.current().isPresent()) {
            var ui = getUI().orElse(null);
            if (ui != null) {
                QueryParameters parameters = ui.getInternals().getActiveViewLocation().getQueryParameters();
                parameters.getSingleParameter("continue").filter(SiweLogin::isSafeContinueTarget)
                        .ifPresent(ui::navigate);
            }
        }
    }

    static boolean isSafeContinueTarget(String target) {
        return target != null && !target.isBlank() && !target.contains("://")
                && !target.startsWith("//") && !target.contains("\\");
    }

    private void finishFailure(SiweException.Reason reason, int walletErrorCode, boolean userRejected) {
        if (!flowInProgress) {
            return;
        }
        flowInProgress = false;
        signInButton.setEnabled(true);
        fireEvent(new SignInFailedEvent(this, reason, walletErrorCode, userRejected));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static String requestDomain(VaadinRequest request) {
        return deriveDomain(header(request, "Forwarded"), header(request, "X-Forwarded-Host"),
                header(request, "Host"));
    }

    static String requestUri(VaadinRequest request) {
        String scheme = request == null ? null : request.isSecure() ? "https" : "http";
        return deriveUri(header(request, "Forwarded"), header(request, "X-Forwarded-Proto"),
                header(request, "X-Forwarded-Host"), header(request, "Host"), scheme);
    }

    private static String header(VaadinRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    static String deriveDomain(String forwarded, String forwardedHost, String host) {
        String value = forwardedParameter(forwarded, "host");
        if (value == null) value = firstValue(forwardedHost);
        return deriveDomain(value == null ? host : value);
    }

    static String deriveUri(String forwarded, String forwardedProto, String forwardedHost,
            String host, String requestScheme) {
        String scheme = forwardedParameter(forwarded, "proto");
        if (scheme == null) scheme = firstValue(forwardedProto);
        if (scheme == null) scheme = requestScheme;
        return deriveUri(scheme, deriveDomain(forwarded, forwardedHost, host));
    }

    private static String firstValue(String value) {
        if (value == null) return null;
        String first = value.split(",", 2)[0].trim();
        return first.isEmpty() ? null : first;
    }

    private static String forwardedParameter(String header, String name) {
        String element = firstForwardedElement(header);
        if (element == null) return null;
        for (String parameter : element.split(";")) {
            String[] pair = parameter.trim().split("=", 2);
            if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(name)) {
                String value = pair[1].trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String firstForwardedElement(String header) {
        if (header == null) return null;
        boolean quoted = false;
        for (int i = 0; i < header.length(); i++) {
            char current = header.charAt(i);
            if (current == '"' && (i == 0 || header.charAt(i - 1) != '\\')) quoted = !quoted;
            if (current == ',' && !quoted) return header.substring(0, i);
        }
        return header;
    }

    static String deriveDomain(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Host header is unavailable");
        }
        return host;
    }

    static String deriveUri(String scheme, String host) {
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalStateException("Request scheme is unavailable");
        }
        return scheme + "://" + deriveDomain(host);
    }

    private record SignedPayload(SiweMessage message, String signature) implements Serializable {
    }

    /** Event fired after a SIWE message and its signature have been verified. */
    public static class SignedInEvent extends ComponentEvent<SiweLogin> {
        private final VerifiedSignIn signIn;

        /** Creates the successful sign-in event. */
        public SignedInEvent(SiweLogin source, VerifiedSignIn signIn) {
            super(source, false);
            this.signIn = signIn;
        }

        /** Returns the verified identity. */
        public VerifiedSignIn getSignIn() { return signIn; }
    }

    /** Event fired when signing or verification fails. */
    public static class SignInFailedEvent extends ComponentEvent<SiweLogin> {
        private final SiweException.Reason reason;
        private final int walletErrorCode;
        private final boolean userRejected;

        /** Creates the failed sign-in event. */
        public SignInFailedEvent(SiweLogin source, SiweException.Reason reason,
                int walletErrorCode, boolean userRejected) {
            super(source, false);
            this.reason = reason;
            this.walletErrorCode = walletErrorCode;
            this.userRejected = userRejected;
        }

        /** Returns the SIWE rejection reason, or {@code null} for a wallet error. */
        public SiweException.Reason getReason() { return reason; }
        /** Returns the wallet error code, or {@code -1} when unavailable. */
        public int getWalletErrorCode() { return walletErrorCode; }
        /** Returns whether the wallet reports that the user rejected the request. */
        public boolean isUserRejected() { return userRejected; }
        /** Returns the localized failure message configured on this event's source component. */
        public String getLocalizedMessage() {
            SiweLoginI18n messages = getSource().getI18n();
            if (reason != null) return messages.getMessage(reason);
            return userRejected ? messages.getUserRejected() : messages.getNetworkError();
        }
    }

    /** Registers a listener for successful sign-ins. */
    public Registration addSignedInListener(ComponentEventListener<SignedInEvent> listener) {
        return addListener(SignedInEvent.class, listener);
    }

    /** Registers a listener for failed sign-ins. */
    public Registration addSignInFailedListener(ComponentEventListener<SignInFailedEvent> listener) {
        return addListener(SignInFailedEvent.class, listener);
    }

    /** Registers a listener for signed-out events. */
    public Registration addSignedOutListener(ComponentEventListener<SignedOutEvent> listener) {
        return addListener(SignedOutEvent.class, listener);
    }

    /** Event fired after the verified session is cleared. */
    public static class SignedOutEvent extends ComponentEvent<SiweLogin> {
        public SignedOutEvent(SiweLogin source) { super(source, false); }
    }
}
