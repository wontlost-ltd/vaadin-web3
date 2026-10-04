package com.wontlost.web3.pay;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final Button pay = new Button();
    private final Span status = new Span("Waiting for payment.");
    private Set<Long> allowedChains;
    private String orderId = UUID.randomUUID().toString();
    private Long preferredChain;
    private int minConfirmations = 1;
    private String buttonText;
    private String transactionHash;
    private PaymentRequest submittedRequest;
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
        this.allowedChains = chains.clients().keySet().stream().filter(id -> Tokens.usdc(id).isPresent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        network.setLabel("Network");
        network.setItemLabelGenerator(StablecoinCheckout::networkName);
        network.setItems(allowedChains.stream().sorted().toList());
        if (!allowedChains.isEmpty()) network.setValue(allowedChains.stream().min(Long::compareTo).orElseThrow());
        pay.setText(label());
        pay.addClickListener(event -> beginPayment());
        add(wallet, new Span(amount.toPlainString() + " USDC"), network, pay, status);
        setSpacing(true);
    }

    public StablecoinCheckout setOrderId(String value) { orderId = Objects.requireNonNull(value); return this; }
    /** Returns whether the current order has been paid. */
    public boolean isPaid() { return paid; }
    /** Resets the checkout for a new order. */
    public StablecoinCheckout reset(String newOrderId) {
        stopPolling();
        orderId = Objects.requireNonNull(newOrderId);
        submittedOrderId = null;
        submittedRequest = null;
        transactionHash = null;
        networkFailures = 0;
        paid = false;
        status.setText("Waiting for payment.");
        pay.setText(label());
        pay.setEnabled(true);
        return this;
    }
    public StablecoinCheckout setAllowedChainIds(Set<Long> values) {
        Set<Long> accepted = values.stream().filter(id -> chains.get(id).isPresent() && Tokens.usdc(id).isPresent())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        allowedChains = accepted;
        network.setItems(accepted.stream().sorted().toList());
        if (!accepted.isEmpty() && !accepted.contains(network.getValue())) network.setValue(accepted.iterator().next());
        return this;
    }
    public StablecoinCheckout setPreferredChain(long chainId) {
        preferredChain = chainId;
        if (allowedChains.contains(chainId)) network.setValue(chainId);
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
            default -> "Chain " + chainId;
        };
    }

    private String label() { return buttonText == null ? "Pay " + amount.toPlainString() + " USDC" : buttonText; }

    private void beginPayment() {
        if (!pay.isEnabled()) return;
        Long selected = network.getValue();
        if (selected == null) { status.setText("No supported network is configured."); return; }
        TokenInfo token = Tokens.usdc(selected).orElseThrow();
        BigInteger units = Tokens.toBaseUnits(amount, token.decimals());
        pay.setEnabled(false);
        long started = generation;
        var connected = wallet.isConnected() ? java.util.concurrent.CompletableFuture.completedFuture(wallet.getAccount())
                : wallet.connect();
        connected.thenCompose(account -> {
            long activeChain = Chains.toDecimal(wallet.getChainId()).longValueExact();
            long targetChain = allowedChains.contains(activeChain) ? selected
                    : preferredChain != null && allowedChains.contains(preferredChain) ? preferredChain : selected;
            TokenInfo targetToken = Tokens.usdc(targetChain).orElseThrow();
            String payer = Web3Session.current().map(user -> user.address()).orElse(account);
            submittedRequest = new PaymentRequest(targetChain, targetToken.address(), recipient, units, payer,
                    minConfirmations);
            submittedOrderId = orderId;
            if (activeChain == targetChain) return send(targetToken, units);
            return wallet.switchChain(Chains.toHex(targetChain)).thenCompose(ignored -> {
                network.setValue(targetChain);
                return send(targetToken, units);
            });
        }).whenComplete((hash, error) -> {
            // 钱包确认期间订单已被 reset，旧订单的交易不能挂到新订单上
            if (started != generation) return;
            if (error != null) { paymentError(error); return; }
            transactionHash = hash;
            status.setText("Payment submitted: " + hash);
            fireEvent(new PaymentSubmittedEvent(this, hash));
            startPolling();
        });
    }

    private java.util.concurrent.CompletableFuture<String> send(TokenInfo token, BigInteger units) {
        return wallet.sendTransaction(Map.of("to", token.address(), "data", Erc20.transferData(recipient, units), "value", "0x0"));
    }

    private void paymentError(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        boolean rejected = cause instanceof Web3Connect.Web3Exception walletError && walletError.isUserRejected();
        PaymentResult result = new PaymentResult(PaymentStatus.FAILED, null, null, BigInteger.ZERO, 0);
        status.setText(rejected ? "Payment was rejected in the wallet." : "Payment failed: " + cause.getMessage());
        fireEvent(new PaymentFailedEvent(this, result, rejected));
        pay.setEnabled(true);
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
                                fireEvent(new PaymentFailedEvent(this, failed, false));
                                pay.setEnabled(true);
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
            pay.setEnabled(false);
            stopPolling();
            fireEvent(new PaymentConfirmedEvent(this, result));
        } else if (Set.of(PaymentStatus.FAILED, PaymentStatus.UNDERPAID, PaymentStatus.NO_MATCHING_TRANSFER,
                PaymentStatus.ALREADY_CLAIMED).contains(result.status())) {
            stopPolling();
            fireEvent(new PaymentFailedEvent(this, result, false));
            pay.setEnabled(true);
        }
    }

    private void stopPolling() {
        generation++;
        verificationInProgress = new AtomicBoolean();
        if (pollRegistration != null) pollRegistration.remove();
        pollRegistration = null;
        if (pollingUi != null) releasePolling(pollingUi);
        pollingUi = null;
        if (!paid) pay.setEnabled(true);
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
        public PaymentSubmittedEvent(StablecoinCheckout source, String hash) { super(source, false); this.hash = hash; }
        public String getHash() { return hash; }
    }
    /** Event fired after the payment satisfies all on-chain requirements. */
    public static class PaymentConfirmedEvent extends ComponentEvent<StablecoinCheckout> {
        private final PaymentResult result;
        public PaymentConfirmedEvent(StablecoinCheckout source, PaymentResult result) { super(source, false); this.result = result; }
        public PaymentResult getResult() { return result; }
    }
    /** Event fired when a submitted payment cannot be accepted. */
    public static class PaymentFailedEvent extends ComponentEvent<StablecoinCheckout> {
        private final PaymentResult result;
        private final boolean userRejected;
        public PaymentFailedEvent(StablecoinCheckout source, PaymentResult result, boolean userRejected) { super(source, false); this.result = result; this.userRejected = userRejected; }
        public PaymentResult getResult() { return result; }
        public boolean isUserRejected() { return userRejected; }
    }
}
