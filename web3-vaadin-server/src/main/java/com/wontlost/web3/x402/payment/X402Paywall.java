package com.wontlost.web3.x402.payment;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.siwe.Web3Session;
import com.wontlost.web3.x402.protocol.Eip3009Payload;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.X402Resource;

public final class X402Paywall extends VerticalLayout {
    private static final Logger LOGGER = LoggerFactory.getLogger(X402Paywall.class);
    private static final Executor DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final X402PaymentService payments;
    private final String resourceId;
    private final X402Resource resource;
    private final String amount;
    private final String network;
    private final String asset;
    private final int decimals;
    private final Span stateText = new Span();
    private final Span errorText = new Span();
    private final Span txText = new Span();
    private final H2 titleText;
    private final Span descriptionText;
    private final Span priceText;
    private final Span networkText;
    private final Button connectButton = new Button();
    private final Button switchButton = new Button();
    private final Button payButton = new Button();
    private final Button checkButton = new Button();
    private final AtomicLong generation = new AtomicLong();
    private final Executor executor;
    private final Supplier<String> identityAddress;

    private Web3Connect wallet = new Web3Connect(true);
    private X402PaywallI18n i18n = new X402PaywallI18n();
    private PaywallState state = PaywallState.READY;
    private String returnTarget;
    private String assetLabel;
    private String networkLabel;
    private volatile String paymentId;
    private volatile boolean detached;

    public X402Paywall(X402PaymentService payments, String resourceId, X402Resource resource,
            String amount, int decimals, String network, String asset) {
        this(payments, resourceId, resource, amount, decimals, network, asset, DEFAULT_EXECUTOR,
                currentSessionIdentity());
    }

    private static Supplier<String> currentSessionIdentity() {
        VaadinSession session = VaadinSession.getCurrent();
        return () -> Web3Session.current(session).map(signIn -> signIn.address()).orElse(null);
    }

    X402Paywall(X402PaymentService payments, String resourceId, X402Resource resource,
            String amount, int decimals, String network, String asset, Executor executor,
            Supplier<String> identityAddress) {
        this.payments = Objects.requireNonNull(payments);
        this.executor = Objects.requireNonNull(executor);
        this.identityAddress = Objects.requireNonNull(identityAddress);
        this.resourceId = Objects.requireNonNull(resourceId);
        this.resource = Objects.requireNonNull(resource);
        this.amount = Objects.requireNonNull(amount);
        this.decimals = decimals;
        this.network = Objects.requireNonNull(network);
        this.asset = Objects.requireNonNull(asset);
        this.assetLabel = asset;
        this.networkLabel = network;
        if (decimals < 0 || decimals > 255) {
            throw new IllegalArgumentException("decimals out of range");
        }

        setId("x402-paywall");
        titleText = new H2(i18n.getTitle());
        descriptionText = new Span(resource.description());
        priceText = new Span();
        networkText = new Span();
        refreshOfferLabels();
        add(titleText, descriptionText, priceText, networkText);

        stateText.getElement().setAttribute("role", "status");
        errorText.getElement().setAttribute("role", "alert");
        connectButton.setText(i18n.getConnect());
        connectButton.addClickListener(event -> connectWallet());
        switchButton.setText(i18n.getWrongNetwork());
        switchButton.addClickListener(event -> switchToRequiredNetwork());
        payButton.setText(i18n.getPay());
        payButton.addClickListener(event -> beginPayment());
        checkButton.setText(i18n.getCheck());
        checkButton.setVisible(false);
        checkButton.addClickListener(event -> reconcile());
        add(stateText, errorText, txText, wallet, connectButton, switchButton, payButton, checkButton);
        installWalletListeners(wallet);
        renderState();
    }

    public X402Paywall setResourceDescription(String value) {
        descriptionText.setText(Objects.requireNonNull(value));
        return this;
    }

    public X402Paywall setWallet(Web3Connect value) {
        remove(wallet);
        wallet = Objects.requireNonNull(value);
        addComponentAtIndex(Math.min(7, getComponentCount()), wallet);
        installWalletListeners(wallet);
        renderState();
        return this;
    }

    public X402Paywall setI18n(X402PaywallI18n value) {
        i18n = Objects.requireNonNull(value);
        titleText.setText(i18n.getTitle());
        connectButton.setText(i18n.getConnect());
        switchButton.setText(i18n.getWrongNetwork());
        checkButton.setText(i18n.getCheck());
        refreshOfferLabels();
        renderState();
        return this;
    }

    public X402Paywall setAssetLabel(String value) {
        assetLabel = Objects.requireNonNull(value);
        refreshOfferLabels();
        return this;
    }

    public X402Paywall setNetworkLabel(String value) {
        networkLabel = Objects.requireNonNull(value);
        refreshOfferLabels();
        return this;
    }

    public X402Paywall setReturnTarget(String value) {
        if (!PaymentGate.isSafeInternalPath(value)) {
            throw new IllegalArgumentException("return target must be an internal route");
        }
        returnTarget = value;
        return this;
    }

    public PaywallState getState() {
        return state;
    }

    public Registration addStateChangeListener(ComponentEventListener<StateChangeEvent> listener) {
        return addListener(StateChangeEvent.class, listener);
    }

    public Registration addPaymentSettledListener(ComponentEventListener<PaymentSettledEvent> listener) {
        return addListener(PaymentSettledEvent.class, listener);
    }

    private void connectWallet() {
        if (wallet.isConnected() || state == PaywallState.CONNECTING) {
            return;
        }
        long operation = generation.incrementAndGet();
        UI ui = UI.getCurrent();
        transition(PaywallState.CONNECTING);
        wallet.connect().whenComplete((account, failure) -> update(ui, operation, () -> {
            if (failure != null) {
                rejectedOrError(failure, "wallet_connect_failed");
                return;
            }
            transition(PaywallState.READY);
            if (identityAddress.get() == null) {
                errorText.setText(i18n.getSignInRequired());
            } else if (!identityAddress.get().equalsIgnoreCase(account)) {
                errorText.setText(i18n.getAccountMismatch());
            } else {
                errorText.setText("");
            }
            renderState();
        }));
    }

    private void switchToRequiredNetwork() {
        if (!wallet.isConnected()) {
            return;
        }
        long operation = generation.incrementAndGet();
        UI ui = UI.getCurrent();
        transition(PaywallState.CONNECTING);
        String chainId = "0x" + Long.toHexString(Long.parseLong(network.substring(network.indexOf(':') + 1)));
        wallet.switchChain(chainId).whenComplete((ignored, failure) -> update(ui, operation, () -> {
            if (failure != null) {
                rejectedOrError(failure, "wallet_network_switch_failed");
            } else {
                errorText.setText("");
                transition(PaywallState.READY);
            }
        }));
    }

    private void beginPayment() {
        if (state == PaywallState.SETTLEMENT_PENDING || state == PaywallState.SETTLING) {
            return;
        }
        String expectedAddress = identityAddress.get();
        if (expectedAddress == null || expectedAddress.isBlank()) {
            fail("siwe_login_required", null);
            return;
        }
        long operation = generation.incrementAndGet();
        UI ui = UI.getCurrent();
        if (!wallet.isConnected()) {
            connectWallet();
            return;
        }
        if (!expectedAddress.equalsIgnoreCase(wallet.getAccount())) {
            fail("wallet_account_mismatch", null);
            return;
        }
        if (!chainMatches(wallet.getChainId())) {
            fail("wrong_network", null);
            return;
        }
        requestAuthorization(expectedAddress, operation, ui);
    }

    private void requestAuthorization(String address, long operation, UI ui) {
        transition(PaywallState.SIGNING);
        CompletableFuture.supplyAsync(() -> payments.prepare(resourceId, address), executor)
                .whenComplete((attempt, prepareFailure) -> update(ui, operation, () -> {
                    if (prepareFailure != null) {
                        fail("prepare_failed", prepareFailure);
                        return;
                    }
                    String failureCode = paymentContextFailure(address);
                    if (failureCode != null) {
                        generation.incrementAndGet();
                        fail(failureCode, null);
                        return;
                    }
                    signAuthorization(attempt, address, operation, ui);
                }));
    }

    private void signAuthorization(PaymentAttempt attempt, String address, long operation, UI ui) {
        wallet.signTypedData(attempt.typedDataJson())
                .whenComplete((signature, signFailure) -> update(ui, operation, () -> {
                    if (signFailure != null) {
                        rejectedOrError(signFailure, "wallet_sign_failed");
                        return;
                    }
                    if (!address.equalsIgnoreCase(wallet.getAccount())) {
                        fail("wallet_account_mismatch", null);
                        return;
                    }
                    if (!chainMatches(wallet.getChainId())) {
                        fail("wrong_network", null);
                        return;
                    }
                    paymentId = attempt.paymentId();
                    settle(new SignedAttempt(attempt, signature), ui, operation);
                }));
    }

    private void settle(SignedAttempt signed, UI ui, long operation) {
        transition(PaywallState.SETTLING);
        PaymentAttempt attempt = signed.attempt();
        PaymentPayload payload = new PaymentPayload(2, resource, attempt.requirements(),
                new Eip3009Payload(signed.signature(), attempt.authorization()));
        CompletableFuture.supplyAsync(() -> {
            String currentIdentity = identityAddress.get();
            if (currentIdentity == null || currentIdentity.isBlank()) {
                return new SettlementResult(null, "siwe_login_required");
            }
            if (!signed.attempt().authorization().from().equalsIgnoreCase(currentIdentity)) {
                return new SettlementResult(null, "wallet_account_mismatch");
            }
            return new SettlementResult(payments.verifyAndSettle(resourceId, payload), null);
        }, executor).whenComplete((result, failure) -> update(ui, operation, () -> {
                    if (failure != null) {
                        fail("verification_failed", failure);
                        return;
                    }
                    if (result.failureCode() != null) {
                        generation.incrementAndGet();
                        fail(result.failureCode(), null);
                        return;
                    }
                    PaymentOutcome outcome = result.outcome();
                    showOutcome(outcome);
                    switch (outcome.status()) {
                        case SETTLED -> paid(outcome.paymentId());
                        case PENDING, UNKNOWN, SETTLING -> {
                            paymentId = outcome.paymentId();
                            transition(PaywallState.SETTLEMENT_PENDING);
                        }
                        case FAILED -> fail(outcome.failureCode(), null);
                        default -> fail("payment_not_settled", null);
                    }
                }));
    }

    private String paymentContextFailure(String expectedAddress) {
        String currentIdentity = identityAddress.get();
        if (currentIdentity == null || currentIdentity.isBlank()) {
            return "siwe_login_required";
        }
        if (!expectedAddress.equalsIgnoreCase(currentIdentity)) {
            return "wallet_account_mismatch";
        }
        if (!wallet.isConnected() || !expectedAddress.equalsIgnoreCase(wallet.getAccount())) {
            return "wallet_account_mismatch";
        }
        if (!chainMatches(wallet.getChainId())) {
            return "wrong_network";
        }
        return null;
    }

    private void inspectExistingPayment(UI ui) {
        String address = identityAddress.get();
        if (address == null || address.isBlank()) {
            return;
        }
        // 用 SIWE 身份恢复本资源最近的支付；待结算时保留原支付并禁止生成新授权。
        long operation = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
            AccessDecision access = payments.hasAccess(resourceId, address);
            Optional<PaymentOutcome> latest = access == AccessDecision.PAYMENT_PENDING
                    ? payments.latestOutcome(resourceId, address) : Optional.empty();
            return new ExistingPayment(access, latest.orElse(null));
        }, executor).whenComplete((existing, failure) -> update(ui, operation, () -> {
            if (failure != null) {
                fail("payment_status_check_failed", failure);
                return;
            }
            if (existing.access() == AccessDecision.ALLOW) {
                transition(PaywallState.PAID);
                navigateToReturnTarget();
                return;
            }
            if (existing.access() == AccessDecision.PAYMENT_PENDING) {
                PaymentOutcome latest = existing.outcome();
                if (latest != null) {
                    paymentId = latest.paymentId();
                    showOutcome(latest);
                    if (latest.status() == PaymentStatus.SETTLED) {
                        transition(PaywallState.PAID);
                        navigateToReturnTarget();
                        return;
                    }
                } else {
                    showError("pending_payment_record_missing");
                }
                transition(PaywallState.SETTLEMENT_PENDING);
            }
        }));
    }

    private void reconcile() {
        if (paymentId == null || state != PaywallState.SETTLEMENT_PENDING) {
            return;
        }
        long operation = generation.incrementAndGet();
        UI ui = UI.getCurrent();
        transition(PaywallState.SETTLING);
        CompletableFuture.supplyAsync(() -> payments.reconcile(paymentId), executor)
                .whenComplete((outcome, failure) -> update(ui, operation, () -> {
                    if (failure != null) {
                        fail("reconciliation_failed", failure);
                        transition(PaywallState.SETTLEMENT_PENDING);
                        return;
                    }
                    showOutcome(outcome);
                    if (outcome.status() == PaymentStatus.SETTLED) {
                        paid(outcome.paymentId());
                    } else if (isPending(outcome.status())) {
                        transition(PaywallState.SETTLEMENT_PENDING);
                    } else {
                        fail(outcome.failureCode(), null);
                    }
                }));
    }

    private void showOutcome(PaymentOutcome outcome) {
        paymentId = outcome.paymentId();
        txText.setText(outcome.txHash() == null ? "" : i18n.getTxHash() + ": " + outcome.txHash());
    }

    private static boolean isPending(PaymentStatus status) {
        return status == PaymentStatus.PENDING || status == PaymentStatus.UNKNOWN
                || status == PaymentStatus.SETTLING;
    }

    private void paid(String id) {
        transition(PaywallState.PAID);
        fireEvent(new PaymentSettledEvent(this, id));
        navigateToReturnTarget();
    }

    private void navigateToReturnTarget() {
        if (returnTarget != null) {
            getUI().ifPresent(ui -> ui.navigate(returnTarget));
        }
    }

    private void refreshAccount() {
        if (state == PaywallState.PAID || paymentId != null) {
            return;
        }
        String address = identityAddress.get();
        if (address == null || address.isBlank()) {
            errorText.setText(i18n.getSignInRequired());
        } else if (wallet.isConnected() && wallet.getAccount().equalsIgnoreCase(address)) {
            errorText.setText("");
        } else {
            errorText.setText(i18n.getAccountMismatch());
        }
        transition(PaywallState.READY);
    }

    private void installWalletListeners(Web3Connect value) {
        value.addConnectedListener(event -> refreshAccount());
        value.addDisconnectedListener(event -> refreshAccount());
        value.addChainChangedListener(event -> refreshAccount());
    }

    void payNowForTest() {
        beginPayment();
    }

    void reconcileForTest() {
        reconcile();
    }

    String getTransactionHashForTest() {
        return txText.getText().replace(i18n.getTxHash() + ": ", "");
    }

    String getStatusMessageForTest() {
        return stateText.getText();
    }

    String getErrorMessageForTest() {
        return errorText.getText();
    }

    boolean isPaymentEnabledForTest() {
        return payButton.isEnabled();
    }

    String getPriceTextForTest() {
        return priceText.getText();
    }

    String getNetworkTextForTest() {
        return networkText.getText();
    }

    private void refreshOfferLabels() {
        priceText.setText(MessageFormat.format(i18n.getPrice(), formatAmount(amount, decimals), assetLabel));
        networkText.setText(MessageFormat.format(i18n.getNetwork(), networkLabel));
    }

    private boolean chainMatches(String chainId) {
        try {
            return Long.parseLong(network.substring(network.indexOf(':') + 1)) == Long.decode(chainId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void rejectedOrError(Throwable failure, String fallbackCode) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof Web3Connect.Web3Exception web3Exception && web3Exception.isUserRejected()) {
            fail("wallet_rejected", cause);
            transition(PaywallState.REJECTED);
            return;
        }
        fail(fallbackCode, cause);
    }

    private void fail(String failureCode, Throwable failure) {
        String code = stableFailureCode(failureCode);
        LOGGER.warn("x402 paywall failed code={} paymentId={} exceptionType={}", code, paymentId,
                failure == null ? "none" : rootCause(failure).getClass().getName());
        showError(code);
        transition(PaywallState.ERROR);
    }

    private void showError(String failureCode) {
        String code = stableFailureCode(failureCode);
        String detail = switch (code) {
            case "wrong_network" -> i18n.getWrongNetwork();
            case "wallet_account_mismatch", "siwe_identity_changed" -> i18n.getAccountMismatch();
            case "siwe_login_required" -> i18n.getSignInRequired();
            case "wallet_rejected" -> i18n.getRejected();
            default -> i18n.getError();
        };
        errorText.setText(i18n.formatError(detail, code));
    }

    private static String stableFailureCode(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}")
                ? value.toLowerCase(java.util.Locale.ROOT) : "payment_failed";
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void transition(PaywallState next) {
        PaywallState previous = state;
        state = next;
        renderState();
        if (previous != next) {
            fireEvent(new StateChangeEvent(this, previous, next));
        }
    }

    private void renderState() {
        stateText.setText(switch (state) {
            case SIGNING -> i18n.getSigning();
            case SETTLING -> i18n.getSettling();
            case SETTLEMENT_PENDING -> i18n.getPending() + " " + txText.getText();
            case PAID -> i18n.getPaid();
            case REJECTED -> i18n.getRejected();
            case ERROR -> errorText.getText();
            case CONNECTING -> i18n.getConnecting();
            default -> "";
        });
        boolean active = state == PaywallState.READY || state == PaywallState.REJECTED
                || state == PaywallState.ERROR;
        payButton.setText(state == PaywallState.REJECTED || state == PaywallState.ERROR
                ? i18n.getRetry() : i18n.getPay());
        boolean connected = wallet.isConnected();
        connectButton.setVisible(!connected);
        connectButton.setEnabled(active);
        switchButton.setVisible(connected && !chainMatches(wallet.getChainId()));
        switchButton.setEnabled(active);
        String signedInAddress = identityAddress.get();
        boolean identityMatches = signedInAddress != null && signedInAddress.equalsIgnoreCase(wallet.getAccount());
        payButton.setEnabled(active && connected && identityMatches && chainMatches(wallet.getChainId()));
        checkButton.setVisible(state == PaywallState.SETTLEMENT_PENDING);
        checkButton.setEnabled(state == PaywallState.SETTLEMENT_PENDING && paymentId != null);
    }

    private void update(UI ui, long operation, Runnable action) {
        if (ui == null) {
            return;
        }
        try {
            // generation 用于丢弃组件卸载或新操作开始后才返回的旧异步结果。
            ui.access(() -> {
                if (!detached && isAttached() && generation.get() == operation) {
                    action.run();
                }
            });
        } catch (IllegalStateException exception) {
            LOGGER.debug("x402 UI callback skipped code={} exceptionType={}", "ui_detached",
                    exception.getClass().getName());
        }
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        detached = false;
        inspectExistingPayment(event.getUI());
    }

    @Override
    protected void onDetach(DetachEvent event) {
        detached = true;
        generation.incrementAndGet();
        super.onDetach(event);
    }

    static String formatAmount(String atoms, int decimals) {
        return new BigDecimal(new BigInteger(atoms), decimals).stripTrailingZeros().toPlainString();
    }

    private record SignedAttempt(PaymentAttempt attempt, String signature) { }

    private record ExistingPayment(AccessDecision access, PaymentOutcome outcome) { }
    private record SettlementResult(PaymentOutcome outcome, String failureCode) { }

    public static final class StateChangeEvent extends ComponentEvent<X402Paywall> {
        private final PaywallState previous;
        private final PaywallState current;

        StateChangeEvent(X402Paywall source, PaywallState previous, PaywallState current) {
            super(source, false);
            this.previous = previous;
            this.current = current;
        }

        public PaywallState getPrevious() {
            return previous;
        }

        public PaywallState getCurrent() {
            return current;
        }
    }

    public static final class PaymentSettledEvent extends ComponentEvent<X402Paywall> {
        private final String paymentId;

        PaymentSettledEvent(X402Paywall source, String paymentId) {
            super(source, false);
            this.paymentId = paymentId;
        }

        public String getPaymentId() {
            return paymentId;
        }
    }
}
