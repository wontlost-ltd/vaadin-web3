package com.wontlost.web3.pay;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.Chains;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.Erc20;
import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.chain.Tokens;
import com.wontlost.web3.siwe.Web3Session;

/**
 * A wallet-backed ERC-20 checkout that polls the server-side payment verifier.
 * When no SIWE identity is present, the payer restriction uses the connected
 * wallet account reported by the client; applications requiring an authenticated
 * payer should require SIWE before accepting the payment.
 */
public class StablecoinCheckout extends VerticalLayout {
    private static final String POLLER_COUNT = StablecoinCheckout.class.getName() + ".pollerCount";
    private static final Executor DEFAULT_EXECUTOR = new ThreadPoolExecutor(2, 4, 30,
            java.util.concurrent.TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "web3-payment-verifier");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ChainRegistry chains;
    private final PaymentLedger ledger;
    private final String recipient;
    private final Executor verificationExecutor;
    private final BigDecimal amount;
    private final Web3Connect wallet = new Web3Connect(true);
    private final Select<Long> network = new Select<>();
    private final Select<String> tokenSelect = new Select<>();
    private final Button pay = new Button();
    private final Span status = new Span("Waiting for payment.");
    private final Span amountLabel;
    private OnrampAction onrampAction;
    private com.vaadin.flow.component.Component onrampComponent;
    private Set<Long> allowedChainIds;
    private List<String> tokens = List.of("USDC");
    private String selectedToken = "USDC";
    private String orderId = UUID.randomUUID().toString();
    private Long preferredChain;
    private int minConfirmations = 1;
    private String buttonText;
    private String transactionHash;
    private PaymentRequest submittedRequest;
    private TokenInfo submittedToken;
    private String submittedOrderId;
    private Registration pollRegistration;
    private UI pollingUi;
    /** 每个代次一个标志：旧订单仍在校验时不会阻塞新订单的轮询。 */
    private AtomicBoolean verificationInProgress = new AtomicBoolean();
    private int networkFailures;
    private boolean paid;
    /** 校验代次：reset/停止轮询时递增，使之前发起的异步校验与钱包回调失效。 */
    private long generation;

    public StablecoinCheckout(ChainRegistry chains, PaymentLedger ledger, String recipient, BigDecimal amount) {
        this(chains, ledger, recipient, amount, DEFAULT_EXECUTOR);
    }

    public StablecoinCheckout(ChainRegistry chains, PaymentLedger ledger, String recipient, BigDecimal amount,
            Executor verificationExecutor) {
        this.chains = Objects.requireNonNull(chains);
        this.ledger = Objects.requireNonNull(ledger);
        this.recipient = Objects.requireNonNull(recipient);
        this.amount = Objects.requireNonNull(amount);
        this.verificationExecutor = Objects.requireNonNull(verificationExecutor);
        this.allowedChainIds = Set.copyOf(chains.clients().keySet());
        network.setLabel("Network");
        network.setItemLabelGenerator(StablecoinCheckout::networkName);
        tokenSelect.setLabel("Token");
        tokenSelect.setItems(tokens);
        tokenSelect.setValue(selectedToken);
        tokenSelect.setVisible(false);
        amountLabel = new Span(amount.toPlainString() + " " + selectedToken);
        tokenSelect.addValueChangeListener(event -> {
            if (event.getValue() != null) {
                selectedToken = event.getValue();
                amountLabel.setText(amount.toPlainString() + " " + selectedToken);
                pay.setText(label());
                refreshNetworks();
            }
        });
        network.addValueChangeListener(event -> refreshOnrampComponent());
        refreshNetworks();
        pay.setText(label());
        pay.addClickListener(event -> beginPayment());
        add(wallet, amountLabel, tokenSelect, network, pay, status);
        setSpacing(true);
    }

    public StablecoinCheckout setOrderId(String value) { orderId = Objects.requireNonNull(value); refreshOnrampComponent(); return this; }
    /** Returns the current checkout order identifier. */
    public String getOrderId() { return orderId; }
    /** Returns the connected wallet account, or {@code null} before a wallet is connected. */
    public String getConnectedAccount() { String account = wallet.getAccount(); return account == null || account.isBlank() ? null : account; }
    /**
     * Sets the optional card purchase action displayed below the payment button. The action is invoked again whenever
     * the selected token or network changes; it is hidden while a payment is in progress or paid.
     */
    public StablecoinCheckout setOnrampAction(OnrampAction action) { onrampAction = action; refreshOnrampComponent(); return this; }
    /** Returns whether the current order has been paid. */
    public boolean isPaid() { return paid; }
    /** Resets the checkout for a new order. */
    public StablecoinCheckout reset(String newOrderId) {
        stopPolling();
        orderId = Objects.requireNonNull(newOrderId);
        submittedOrderId = null;
        submittedRequest = null;
        submittedToken = null;
        transactionHash = null;
        networkFailures = 0;
        paid = false;
        status.setText("Waiting for payment.");
        pay.setText(label());
        setPayEnabled(true);
        return this;
    }
    /**
     * Restricts the networks offered for payment.
     *
     * @throws IllegalStateException while a payment is in progress or after it is paid; call {@link #reset(String)} first
     */
    public StablecoinCheckout setAllowedChainIds(Set<Long> values) {
        requireConfigurable();
        allowedChainIds = values.stream().filter(id -> chains.get(id).isPresent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        refreshNetworks();
        return this;
    }
    /**
     * Sets the accepted built-in tokens, which must share one denomination currency.
     *
     * @throws IllegalStateException while a payment is in progress or after it is paid; call {@link #reset(String)} first
     */
    public StablecoinCheckout setTokens(String... symbols) {
        requireConfigurable();
        if (symbols == null || symbols.length == 0) throw new IllegalArgumentException("At least one token is required");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        String currency = null;
        for (String symbol : symbols) {
            String canonical = Tokens.symbols().stream().filter(item -> item.equalsIgnoreCase(symbol)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown token symbol: " + symbol));
            String nextCurrency = Tokens.currency(canonical);
            if (currency != null && !currency.equals(nextCurrency))
                throw new IllegalArgumentException("All checkout tokens must use the same currency");
            currency = nextCurrency;
            normalized.add(canonical);
        }
        tokens = List.copyOf(normalized);
        if (!tokens.contains(selectedToken)) selectedToken = tokens.getFirst();
        tokenSelect.setItems(tokens);
        tokenSelect.setValue(selectedToken);
        tokenSelect.setVisible(tokens.size() > 1);
        amountLabel.setText(amount.toPlainString() + " " + selectedToken);
        pay.setText(label());
        refreshNetworks();
        return this;
    }
    /** Returns the accepted built-in token symbols in selection order. */
    public List<String> getTokens() { return tokens; }
    /**
     * Sets the network the wallet is switched to when it is on an unsupported network.
     *
     * @throws IllegalStateException while a payment is in progress or after it is paid; call {@link #reset(String)} first
     */
    public StablecoinCheckout setPreferredChain(long chainId) {
        requireConfigurable();
        preferredChain = chainId;
        if (allowedChains().contains(chainId)) network.setValue(chainId);
        return this;
    }
    public StablecoinCheckout setMinConfirmations(int value) {
        if (value < 1) throw new IllegalArgumentException("minConfirmations must be positive");
        minConfirmations = value;
        return this;
    }
    public StablecoinCheckout setButtonText(String value) { buttonText = Objects.requireNonNull(value); pay.setText(label()); return this; }

    /** 已知链显示名称，未知链回退为 "Chain <id>"（与 Tokens 中的 USDC 注册表一致）。 */
    static String networkName(Long chainId) {
        if (chainId == null) return "";
        return switch (chainId.intValue()) {
            case 1 -> "Ethereum";
            case 11155111 -> "Sepolia";
            case 8453 -> "Base";
            case 84532 -> "Base Sepolia";
            case 42161 -> "Arbitrum One";
            case 421614 -> "Arbitrum Sepolia";
            case 10 -> "OP Mainnet";
            case 11155420 -> "OP Sepolia";
            case 137 -> "Polygon";
            case 80002 -> "Polygon Amoy";
            case 43114 -> "Avalanche";
            case 43113 -> "Avalanche Fuji";
            default -> "Chain " + chainId;
        };
    }

    /** 支付进行中或已付款时界面已锁定，此时修改配置会让显示与已捕获的支付意图不一致。 */
    private void requireConfigurable() {
        if (!pay.isEnabled()) {
            throw new IllegalStateException("A payment is in progress or already paid; call reset(orderId) first");
        }
    }

    /** 支付进行中或已付款时同时锁定代币与网络选择，避免界面显示的代币与正在校验的支付不一致。 */
    private void setPayEnabled(boolean enabled) {
        pay.setEnabled(enabled);
        tokenSelect.setEnabled(enabled);
        network.setEnabled(enabled);
        if (onrampComponent != null) onrampComponent.setVisible(enabled);
    }

    private String label() { return buttonText == null ? "Pay " + amount.toPlainString() + " " + selectedToken : buttonText; }

    private Set<Long> allowedChains() {
        return allowedChains(selectedToken);
    }

    private Set<Long> allowedChains(String symbol) {
        return chains.clients().keySet().stream()
                .filter(id -> allowedChainIds.contains(id) && Tokens.find(symbol, id).isPresent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void refreshNetworks() {
        Set<Long> accepted = allowedChains();
        Long current = network.getValue();
        network.setItems(accepted.stream().sorted().toList());
        if (current != null && accepted.contains(current)) {
            network.setValue(current);
            refreshOnrampComponent();
            return;
        }
        if (preferredChain != null && accepted.contains(preferredChain)) network.setValue(preferredChain);
        else if (!accepted.isEmpty()) network.setValue(accepted.stream().min(Long::compareTo).orElseThrow());
        else network.clear();
        refreshOnrampComponent();
    }

    private void refreshOnrampComponent() {
        if (onrampComponent != null) {
            remove(onrampComponent);
            onrampComponent = null;
        }
        Long chainId = network.getValue();
        if (onrampAction == null || chainId == null) return;
        TokenInfo token = Tokens.find(selectedToken, chainId).orElse(null);
        if (token == null) return;
        com.vaadin.flow.component.Component component = onrampAction.create(this, token, amount);
        if (component == null) return;
        onrampComponent = component;
        int payIndex = getChildren().toList().indexOf(pay);
        addComponentAtIndex(payIndex + 1, component);
        component.setVisible(pay.isEnabled());
    }

    private void beginPayment() {
        if (!pay.isEnabled()) return;
        submittedToken = null;
        submittedRequest = null;
        PaymentIntent intent = capturePaymentIntent();
        if (intent == null) { status.setText("No supported network is configured."); return; }
        setPayEnabled(false);
        long started = generation;
        var connected = wallet.isConnected() ? java.util.concurrent.CompletableFuture.completedFuture(wallet.getAccount())
                : wallet.connect();
        connected.thenCompose(account -> {
            long activeChain = Chains.toDecimal(wallet.getChainId()).longValueExact();
            Set<Long> eligibleChains = allowedChains(intent.tokenSymbol());
            long targetChain = eligibleChains.contains(activeChain) ? intent.selectedChain()
                    : intent.preferredChain() != null && eligibleChains.contains(intent.preferredChain())
                            ? intent.preferredChain() : intent.selectedChain();
            TokenInfo targetToken = Tokens.find(intent.tokenSymbol(), targetChain).orElseThrow();
            submittedToken = targetToken;
            String payer = Web3Session.current().map(user -> user.address()).orElse(account);
            submittedRequest = new PaymentRequest(targetChain, targetToken.address(), recipient, intent.amountUnits(), payer,
                    minConfirmations, intent.notBefore());
            submittedOrderId = orderId;
            if (activeChain == targetChain) return send(targetToken, intent.amountUnits());
            return wallet.switchChain(Chains.toHex(targetChain)).thenCompose(ignored -> {
                network.setValue(targetChain);
                return send(targetToken, intent.amountUnits());
            });
        }).whenComplete((hash, error) -> {
            // 钱包确认期间订单已被 reset，旧订单的交易不能挂到新订单上
            if (started != generation) return;
            if (error != null) { paymentError(error); return; }
            transactionHash = hash;
            status.setText("Payment submitted: " + hash);
            fireEvent(new PaymentSubmittedEvent(this, hash, submittedToken));
            startPolling();
        });
    }

    PaymentIntent capturePaymentIntent() {
        Long selectedChain = network.getValue();
        if (selectedChain == null) return null;
        String symbol = selectedToken;
        TokenInfo token = Tokens.find(symbol, selectedChain).orElseThrow();
        // 只接受点击 Pay 之后（减去时钟误差容忍）上链的交易，旧交易不能冒充本次付款
        return new PaymentIntent(symbol, selectedChain, preferredChain,
                Tokens.toBaseUnits(amount, token.decimals()), java.time.Instant.now().minus(transactionTimeTolerance));
    }

    /** 默认的区块时间与服务器时钟误差容忍：以太坊出块时间与实际时间相差通常在秒级，服务器应同步 NTP。 */
    static final java.time.Duration NOT_BEFORE_TOLERANCE = java.time.Duration.ofMinutes(2);
    private java.time.Duration transactionTimeTolerance = NOT_BEFORE_TOLERANCE;

    /**
     * Sets how much earlier than the Pay click a transaction's block may be and still count (default two minutes),
     * to absorb differences between block timestamps and the server clock. Larger values widen the window in which an
     * unrelated concurrent transfer to the recipient could be presented as payment; keep it small.
     *
     * @throws IllegalStateException while a payment is in progress or after it is paid; call {@link #reset(String)} first
     */
    public StablecoinCheckout setTransactionTimeTolerance(java.time.Duration tolerance) {
        requireConfigurable();
        if (tolerance == null || tolerance.isNegative()) throw new IllegalArgumentException("tolerance must not be negative");
        transactionTimeTolerance = tolerance;
        return this;
    }

    record PaymentIntent(String tokenSymbol, long selectedChain, Long preferredChain, BigInteger amountUnits,
            java.time.Instant notBefore) { }

    private java.util.concurrent.CompletableFuture<String> send(TokenInfo token, BigInteger units) {
        return wallet.sendTransaction(Map.of("to", token.address(), "data", Erc20.transferData(recipient, units), "value", "0x0"));
    }

    private void paymentError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        boolean rejected = cause instanceof Web3Connect.Web3Exception walletError && walletError.isUserRejected();
        PaymentResult result = new PaymentResult(PaymentStatus.FAILED, null, null, BigInteger.ZERO, 0);
        status.setText(rejected ? "Payment was rejected in the wallet." : "Payment failed: " + cause.getMessage());
        fireEvent(new PaymentFailedEvent(this, result, rejected, submittedToken));
        setPayEnabled(true);
    }

    void startPolling() {
        UI ui = UI.getCurrent();
        pollingUi = ui;
        retainPolling(ui);
        pollRegistration = ui.addPollListener(event -> verifyPayment());
    }

    private static synchronized void retainPolling(UI ui) {
        Integer previous = (Integer) ComponentUtil.getData(ui, POLLER_COUNT);
        int count = previous == null ? 0 : previous;
        if (count == 0) ui.setPollInterval(3000);
        ComponentUtil.setData(ui, POLLER_COUNT, count + 1);
    }

    private static synchronized void releasePolling(UI ui) {
        Integer previous = (Integer) ComponentUtil.getData(ui, POLLER_COUNT);
        int count = previous == null ? 0 : previous;
        if (count <= 1) {
            ComponentUtil.setData(ui, POLLER_COUNT, null);
            ui.setPollInterval(-1);
        } else {
            ComponentUtil.setData(ui, POLLER_COUNT, count - 1);
        }
    }

    /** 测试缝隙：模拟一笔已提交、等待校验的交易。 */
    void setSubmittedTransactionForTest(String hash, String orderId, PaymentRequest request) {
        transactionHash = hash;
        submittedOrderId = orderId;
        submittedRequest = request;
        submittedToken = Tokens.symbols().stream().flatMap(symbol -> Tokens.chains(symbol).stream()
                .map(chainId -> Tokens.find(symbol, chainId).orElseThrow()))
                .filter(token -> token.address().equalsIgnoreCase(request.token())).findFirst().orElse(null);
    }

    void verifyPayment() {
        AtomicBoolean inProgress = verificationInProgress;
        if (transactionHash == null || !inProgress.compareAndSet(false, true)) return;
        String order = submittedOrderId;
        String hash = transactionHash;
        PaymentRequest request = submittedRequest;
        UI ui = pollingUi;
        long started = generation;
        java.util.concurrent.CompletableFuture<PaymentResult> verification;
        try {
            verification = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> new PaymentVerifier(chains, ledger).verify(order, hash, request), verificationExecutor);
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            // 线程池已满时 supplyAsync 会同步抛出，whenComplete 不会被挂上；
            // 必须在这里复位标志，否则本订单此后再也不会被校验。下一次轮询自动重试。
            inProgress.set(false);
            status.setText("Waiting for the network…");
            return;
        }
        verification.whenComplete((result, error) -> {
                    if (ui == null) { inProgress.set(false); return; }
                    ui.access(() -> {
                        inProgress.set(false);
                        // 订单在校验期间被 reset 或轮询已停止：结果属于旧订单，丢弃
                        if (!isAttached() || pollingUi != ui || started != generation) return;
                        if (error != null) {
                            networkFailures++;
                            status.setText("Waiting for the network…");
                            if (networkFailures >= 20) {
                                PaymentResult failed = new PaymentResult(PaymentStatus.FAILED, hash, null,
                                        BigInteger.ZERO, 0);
                                stopPolling();
                                fireEvent(new PaymentFailedEvent(this, failed, false, submittedToken));
                                setPayEnabled(true);
                            }
                            return;
                        }
                        networkFailures = 0;
                        applyVerificationResult(result);
                    });
                });
    }

    void applyVerificationResult(PaymentResult result) {
        status.setText(result.status() + " — " + result.confirmations() + " confirmations");
        if (result.status() == PaymentStatus.CONFIRMED) {
            paid = true;
            pay.setText("Paid");
            setPayEnabled(false);
            stopPolling();
            fireEvent(new PaymentConfirmedEvent(this, result, submittedToken));
        } else if (Set.of(PaymentStatus.FAILED, PaymentStatus.UNDERPAID, PaymentStatus.NO_MATCHING_TRANSFER,
                PaymentStatus.ALREADY_CLAIMED, PaymentStatus.PREDATES_ORDER).contains(result.status())) {
            stopPolling();
            fireEvent(new PaymentFailedEvent(this, result, false, submittedToken));
            setPayEnabled(true);
        }
    }

    private void stopPolling() {
        generation++;
        verificationInProgress = new AtomicBoolean();
        if (pollRegistration != null) pollRegistration.remove();
        pollRegistration = null;
        if (pollingUi != null) releasePolling(pollingUi);
        pollingUi = null;
        if (!paid) setPayEnabled(true);
    }

    @Override protected void onDetach(DetachEvent event) { stopPolling(); super.onDetach(event); }

    /** Registers for a submitted transaction. */
    public Registration addPaymentSubmittedListener(ComponentEventListener<PaymentSubmittedEvent> listener) { return addListener(PaymentSubmittedEvent.class, listener); }
    /** Registers for a confirmed payment. */
    public Registration addPaymentConfirmedListener(ComponentEventListener<PaymentConfirmedEvent> listener) { return addListener(PaymentConfirmedEvent.class, listener); }
    /** Registers for a failed payment or rejected signature. */
    public Registration addPaymentFailedListener(ComponentEventListener<PaymentFailedEvent> listener) { return addListener(PaymentFailedEvent.class, listener); }

    /** Event fired after the wallet submits a transaction. */
    public static class PaymentSubmittedEvent extends ComponentEvent<StablecoinCheckout> {
        private final String hash;
        private final TokenInfo token;
        public PaymentSubmittedEvent(StablecoinCheckout source, String hash) { this(source, hash, null); }
        public PaymentSubmittedEvent(StablecoinCheckout source, String hash, TokenInfo token) { super(source, false); this.hash = hash; this.token = token; }
        public String getHash() { return hash; }
        /** Returns the token used by this payment, or null when the token had not yet been determined. */
        public TokenInfo getToken() { return token; }
    }
    /** Event fired after the payment satisfies all on-chain requirements. */
    public static class PaymentConfirmedEvent extends ComponentEvent<StablecoinCheckout> {
        private final PaymentResult result;
        private final TokenInfo token;
        public PaymentConfirmedEvent(StablecoinCheckout source, PaymentResult result) { this(source, result, null); }
        public PaymentConfirmedEvent(StablecoinCheckout source, PaymentResult result, TokenInfo token) { super(source, false); this.result = result; this.token = token; }
        public PaymentResult getResult() { return result; }
        /** Returns the token used by this payment, or null when the token had not yet been determined. */
        public TokenInfo getToken() { return token; }
    }
    /** Event fired when a submitted payment cannot be accepted. */
    public static class PaymentFailedEvent extends ComponentEvent<StablecoinCheckout> {
        private final PaymentResult result;
        private final boolean userRejected;
        private final TokenInfo token;
        public PaymentFailedEvent(StablecoinCheckout source, PaymentResult result, boolean userRejected) { this(source, result, userRejected, null); }
        public PaymentFailedEvent(StablecoinCheckout source, PaymentResult result, boolean userRejected, TokenInfo token) { super(source, false); this.result = result; this.userRejected = userRejected; this.token = token; }
        public PaymentResult getResult() { return result; }
        public boolean isUserRejected() { return userRejected; }
        /** Returns the token used by this payment, or null when the token had not yet been determined. */
        public TokenInfo getToken() { return token; }
    }
}
