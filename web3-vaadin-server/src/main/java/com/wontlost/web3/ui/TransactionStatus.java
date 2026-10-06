package com.wontlost.web3.ui;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.TransactionReceipt;
import com.wontlost.web3.pay.Finality;

/** Displays the server-observed confirmation state of a transaction. */
public class TransactionStatus extends com.vaadin.flow.component.Composite<Div> {
    public enum Status { SUBMITTED, PENDING, CONFIRMING, CONFIRMED, FAILED, UNKNOWN }
    private final Span text = new Span();
    private final Span error = new Span();
    private final Anchor explorer = new Anchor();
    private long chainId;
    private String hash;
    private Duration pollInterval = Duration.ofSeconds(3);
    private Duration timeout = Duration.ofMinutes(10);
    private Finality finality = Finality.confirmations(1);
    private TransactionStatusI18n i18n = new TransactionStatusI18n();
    private Status status;
    private long generation;
    private long startedAt;
    private int confirmations;
    private Registration polling;
    private boolean checking;

    public TransactionStatus() {
        text.getElement().setAttribute("role", "status");
        text.getElement().setAttribute("aria-live", "polite");
        explorer.setTarget("_blank");
        explorer.getElement().setAttribute("rel", "noopener noreferrer");
        explorer.setVisible(false);
        getContent().add(text, error, explorer);
        addAttachListener(event -> restartPolling());
    }
    public TransactionStatus setChainId(long value) {
        chainId = value;
        if (hash != null) { generation++; checking = false; restartPolling(); }
        refreshExplorer();
        return this;
    }
    public TransactionStatus track(String txHash) {
        hash = Objects.requireNonNull(txHash);
        generation++;
        checking = false;
        startedAt = System.nanoTime();
        confirmations = 0;
        error.setText("");
        change(Status.SUBMITTED, 0);
        refreshExplorer();
        restartPolling();
        return this;
    }
    public TransactionStatus setFinality(Finality value) {
        finality = Objects.requireNonNull(value);
        if (hash != null) { generation++; checking = false; restartPolling(); }
        return this;
    }
    public TransactionStatus setPollInterval(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("pollInterval must be positive");
        pollInterval = value; restartPolling(); return this;
    }
    public TransactionStatus setTimeout(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("timeout must be positive");
        timeout = value; return this;
    }
    public TransactionStatus setI18n(TransactionStatusI18n value) { i18n = Objects.requireNonNull(value); render(); return this; }
    public Status getStatus() { return status; }
    public int getConfirmations() { return confirmations; }
    public Registration addStatusChangedListener(ComponentEventListener<StatusChangedEvent> listener) {
        return addListener(StatusChangedEvent.class, listener);
    }

    private void restartPolling() {
        if (polling != null) polling.remove();
        polling = null;
        if (hash == null || !isAttached()) return;
        UI ui = getUI().orElse(null);
        if (ui != null) polling = UiPolling.register(ui, pollInterval, this::poll);
    }
    private void poll() {
        if (hash == null || checking || status == Status.CONFIRMED || status == Status.FAILED || status == Status.UNKNOWN) return;
        if (checkTimeoutAt(System.nanoTime())) return;
        checking = true;
        long current = generation;
        String currentHash = hash;
        long currentChain = chainId;
        UI ui = getUI().orElse(null);
        if (ui == null) { checking = false; return; }
        ChainRegistry registry = ui.getSession().getService().getContext().getAttribute(ChainRegistry.class);
        EthRpcClient client = registry == null ? null : registry.get(currentChain).orElse(null);
        CompletableFuture.supplyAsync(() -> {
            if (client == null) throw new IllegalStateException("No RPC registered for chain " + currentChain);
            return read(client.pinned(), currentHash);
        }).whenComplete((result, failure) -> ui.access(() -> applyPollResult(current, result, failure)));
    }
    Result read(EthRpcClient rpc, String txHash) {
        if (rpc.getTransactionByHash(txHash).isEmpty()) return new Result(Status.PENDING, 0);
        Optional<TransactionReceipt> found = rpc.getTransactionReceipt(txHash);
        if (found.isEmpty()) return new Result(Status.PENDING, 0);
        TransactionReceipt receipt = found.get();
        if (!receipt.status()) return new Result(Status.FAILED, 0);
        EthRpcClient.BlockReference canonical = rpc.getBlockReference("0x" + Long.toHexString(receipt.blockNumber()));
        if (canonical == null || receipt.blockHash() == null || canonical.hash() == null
                || !canonical.hash().equalsIgnoreCase(receipt.blockHash())) return new Result(Status.PENDING, 0);
        int count = Math.toIntExact(Math.max(0, rpc.blockNumber() - receipt.blockNumber() + 1));
        boolean finalised = finality.kind() == Finality.Kind.CONFIRMATIONS
                ? count >= finality.confirmations()
                : rpc.getBlockReference("finalized").number() >= receipt.blockNumber();
        return new Result(finalised ? Status.CONFIRMED : Status.CONFIRMING, count);
    }
    boolean checkTimeoutAt(long now) {
        if (hash != null && status != Status.CONFIRMED && status != Status.FAILED && status != Status.UNKNOWN
                && now - startedAt >= timeout.toNanos()) {
            change(Status.UNKNOWN, confirmations);
            return true;
        }
        return false;
    }
    void applyPollResult(long current, Result result, Throwable failure) {
        if (current != generation) return;
        checking = false;
        if (failure != null) { error.setText(MessageFormat.format(i18n.getError(), rootMessage(failure))); return; }
        error.setText("");
        if (result.status == Status.FAILED) change(Status.FAILED, 0);
        else if (result.status == Status.CONFIRMED) change(Status.CONFIRMED, result.confirmations);
        else change(result.confirmations > 0 ? Status.CONFIRMING : Status.PENDING, result.confirmations);
    }
    private void change(Status next, int count) {
        boolean changed = status != next || confirmations != count;
        status = next;
        confirmations = count;
        if (changed) { render(); fireEvent(new StatusChangedEvent(this, next, count)); }
        // 终态后停止轮询：否则 UI 会一直保持轮询间隔并持续往返服务器
        if (next == Status.CONFIRMED || next == Status.FAILED || next == Status.UNKNOWN) stopPolling();
    }

    private void stopPolling() {
        if (polling != null) polling.remove();
        polling = null;
    }

    boolean isPolling() { return polling != null; }
    private void render() {
        if (status == null) return;
        String message = switch (status) {
            case SUBMITTED -> i18n.getSubmitted(); case PENDING -> i18n.getPending();
            case CONFIRMING -> MessageFormat.format(i18n.getConfirming(), confirmations,
                    finality.kind() == Finality.Kind.CONFIRMATIONS ? finality.confirmations() : i18n.getFinalizedTarget());
            case CONFIRMED -> i18n.getConfirmed(); case FAILED -> i18n.getFailed(); case UNKNOWN -> i18n.getUnknown();
        };
        text.setText(message);
    }
    private void refreshExplorer() {
        Explorers.transactionUrl(chainId, hash).ifPresentOrElse(url -> { explorer.setHref(url); explorer.setText(hash); explorer.setVisible(true); },
                () -> explorer.setVisible(false));
    }
    private static String rootMessage(Throwable error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        // 消息为空时用异常类名，避免界面显示 "null"
        return cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
    }
    @Override protected void onDetach(DetachEvent event) {
        generation++;
        checking = false;
        if (polling != null) polling.remove();
        polling = null;
        super.onDetach(event);
    }
    record Result(Status status, int confirmations) { }
    public static final class StatusChangedEvent extends ComponentEvent<TransactionStatus> {
        private final Status status; private final int confirmations;
        public StatusChangedEvent(TransactionStatus source, Status status, int confirmations) {
            super(source, false); this.status = status; this.confirmations = confirmations;
        }
        public Status getStatus() { return status; }
        public int getConfirmations() { return confirmations; }
    }
}
