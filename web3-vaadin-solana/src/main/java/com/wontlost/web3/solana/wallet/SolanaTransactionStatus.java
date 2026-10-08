package com.wontlost.web3.solana.wallet;

import java.io.Serializable;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SignatureStatus;
import com.wontlost.web3.solana.SolanaCommitment;
import com.wontlost.web3.solana.SolanaRpcClient;
import com.wontlost.web3.ui.UiPolling;

/**
 * Shows the server-observed progress of a Solana transaction: submitted, processed, confirmed, finalized, failed,
 * or expired (its blockhash ran out before it landed, so it is safe to build and send again).
 * <p>
 * Polling uses the UI's shared poll interval ({@link UiPolling}); the RPC reads run off the UI thread and the
 * result is applied with {@code UI.access}, so a slow node never blocks the UI. Polling stops once the transaction
 * reaches the target commitment ({@code confirmed} by default) or another final state. Progress only moves forward,
 * and an on-chain failure is final only once it is at least {@code confirmed}.
 * <p>
 * {@link Status#EXPIRED} needs the transaction's {@code lastValidBlockHeight} and a client at {@code confirmed}
 * (the default) or {@code finalized}. It assumes the wallet kept the blockhash the server built the transaction
 * with; a wallet that replaced it could still land the transaction later, so check the explorer before retrying.
 */
public class SolanaTransactionStatus extends Composite<Div> {
    /** Progress of the tracked transaction. */
    public enum Status { SUBMITTED, PROCESSED, CONFIRMED, FINALIZED, FAILED, EXPIRED, TIMED_OUT }

    private final Span text = new Span();
    private final Anchor explorer = new Anchor();
    private final SolanaCluster cluster;
    private transient SolanaRpcClient rpc;
    private transient Registration polling;
    /** 每次 track/分离递增：迟到的异步结果按代号丢弃。 */
    private long generation;
    private boolean checking;
    private SolanaCommitment target = SolanaCommitment.CONFIRMED;
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration timeout = Duration.ofMinutes(2);
    private String signature;
    private long lastValidBlockHeight = -1;
    private long startedAt;
    private Status status;
    private SignatureStatus lastStatus;
    private I18n i18n = new I18n();

    /** Creates a status display that reads statuses through {@code rpc} for transactions on {@code cluster}. */
    public SolanaTransactionStatus(SolanaRpcClient rpc, SolanaCluster cluster) {
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        text.getElement().setAttribute("role", "status");
        text.getElement().setAttribute("aria-live", "polite");
        explorer.setTarget("_blank");
        explorer.getElement().setAttribute("rel", "noopener noreferrer");
        explorer.setText("View in Solana Explorer");
        explorer.setVisible(false);
        getContent().add(text, explorer);
    }

    /** Starts tracking {@code transactionSignature} (base58). */
    public SolanaTransactionStatus track(String transactionSignature) {
        return track(transactionSignature, -1);
    }

    /**
     * Starts tracking {@code transactionSignature} built with a blockhash valid until {@code lastValidBlockHeight},
     * so the display can report {@link Status#EXPIRED} once the transaction can no longer land.
     */
    public SolanaTransactionStatus track(String transactionSignature, long lastValidBlockHeight) {
        signature = Objects.requireNonNull(transactionSignature, "transactionSignature");
        this.lastValidBlockHeight = lastValidBlockHeight;
        startedAt = System.nanoTime();
        lastStatus = null;
        generation++;
        checking = false;
        // 重新跟踪时从头开始，确保再次触发 SUBMITTED 事件
        status = null;
        explorer.setHref(explorerUrl(cluster, transactionSignature));
        explorer.setVisible(cluster != SolanaCluster.LOCALNET);
        change(Status.SUBMITTED);
        restartPolling();
        return this;
    }

    /** The commitment at which tracking stops successfully; {@code confirmed} by default. */
    public SolanaTransactionStatus setTarget(SolanaCommitment value) {
        target = Objects.requireNonNull(value);
        restartPolling();
        return this;
    }

    /** How often the status is read while tracking; one second by default. */
    public SolanaTransactionStatus setPollInterval(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("pollInterval must be positive");
        pollInterval = value;
        restartPolling();
        return this;
    }

    /** How long to wait before reporting {@link Status#TIMED_OUT}; two minutes by default. */
    public SolanaTransactionStatus setTimeout(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        timeout = value;
        return this;
    }

    /** Reattaches the RPC client after deserialization. */
    public SolanaTransactionStatus setRpc(SolanaRpcClient value) {
        rpc = Objects.requireNonNull(value);
        restartPolling();
        return this;
    }

    /** Localized status texts. */
    public SolanaTransactionStatus setI18n(I18n value) {
        i18n = Objects.requireNonNull(value);
        if (status != null) text.setText(i18n.get(status));
        return this;
    }

    public Optional<Status> getStatus() { return Optional.ofNullable(status); }
    public Optional<String> getSignature() { return Optional.ofNullable(signature); }
    /** The last status read from the cluster, including the on-chain error of a failed transaction. */
    public Optional<SignatureStatus> getLastSignatureStatus() { return Optional.ofNullable(lastStatus); }

    String statusText() {
        return text.getText();
    }

    /** Fired whenever the status changes. */
    public Registration addStatusChangedListener(ComponentEventListener<StatusChangedEvent> listener) {
        return addListener(StatusChangedEvent.class, listener);
    }

    /** 轮询回调（持有 UI 锁）：在后台线程读取，结果经 ui.access 应用；同一时间只有一次读取在途。 */
    private void poll() {
        if (signature == null || isFinal(status) || rpc == null || checking) return;
        UI ui = getUI().orElse(null);
        if (ui == null) return;
        checking = true;
        long current = generation;
        SolanaRpcClient client = rpc;
        String tracked = signature;
        long lastValid = lastValidBlockHeight;
        CompletableFuture.supplyAsync(() -> read(client, tracked, lastValid))
                .whenComplete((reading, failure) -> ui.access(() -> apply(current, reading, failure)));
    }

    /** 同步读取并应用一次（测试与无 UI 场景）。 */
    void pollNow() {
        if (signature == null || isFinal(status) || rpc == null) return;
        long current = generation;
        Reading reading = null;
        Throwable failure = null;
        try {
            reading = read(rpc, signature, lastValidBlockHeight);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        apply(current, reading, failure);
    }

    /**
     * 后台读取（不碰 UI）。过期判定先确认区块高度已超过 lastValidBlockHeight，再最后读一次签名状态，
     * 仍不存在才算过期：避免交易恰好在两次读取之间落块而被误报为过期。
     */
    static Reading read(SolanaRpcClient client, String signature, long lastValidBlockHeight) {
        Optional<SignatureStatus> first = status(client, signature);
        if (first.isPresent() || lastValidBlockHeight < 0) return new Reading(first, false);
        if (client.getBlockHeight() <= lastValidBlockHeight) return new Reading(Optional.empty(), false);
        Optional<SignatureStatus> again = status(client, signature);
        return new Reading(again, again.isEmpty());
    }

    private static Optional<SignatureStatus> status(SolanaRpcClient client, String signature) {
        return client.getSignatureStatuses(List.of(signature), true).getFirst();
    }

    /** 在 UI 线程应用读取结果；节点暂时失败时保持状态，超时兜底。 */
    void apply(long current, Reading reading, Throwable failure) {
        if (current != generation) return;
        checking = false;
        if (failure == null && reading.expired()) {
            change(Status.EXPIRED);
        } else if (failure == null && reading.status().isPresent()) {
            lastStatus = reading.status().get();
            advance(next(lastStatus));
        }
        if (!isFinal(status) && System.nanoTime() - startedAt > timeout.toNanos()) change(Status.TIMED_OUT);
        if (isFinal(status)) stopPolling();
    }

    /** processed 级别的失败可能随分叉被丢弃，只有达到 confirmed 才视为最终失败。 */
    private static Status next(SignatureStatus read) {
        boolean settled = read.confirmationStatus().ordinal() >= SolanaCommitment.CONFIRMED.ordinal();
        if (read.failed()) return settled ? Status.FAILED : Status.PROCESSED;
        return switch (read.confirmationStatus()) {
            case PROCESSED -> Status.PROCESSED;
            case CONFIRMED -> Status.CONFIRMED;
            case FINALIZED -> Status.FINALIZED;
        };
    }

    /** 状态只前进：负载均衡下落后的节点可能返回较低的确认级别，忽略之。 */
    private void advance(Status next) {
        if (status == null || next.ordinal() > status.ordinal()) change(next);
    }

    /** 一次读取的结果：签名状态（可能不存在）与是否已确定过期。 */
    record Reading(Optional<SignatureStatus> status, boolean expired) {
    }

    /** 失败、过期、超时为终态；成功则在达到目标确认级别时视为终态。 */
    private boolean isFinal(Status value) {
        if (value == null) return false;
        return switch (value) {
            case FAILED, EXPIRED, TIMED_OUT, FINALIZED -> true;
            case CONFIRMED -> target != SolanaCommitment.FINALIZED;
            case PROCESSED -> target == SolanaCommitment.PROCESSED;
            case SUBMITTED -> false;
        };
    }

    private void change(Status next) {
        if (next == status) return;
        status = next;
        text.setText(i18n.get(next));
        fireEvent(new StatusChangedEvent(this, next));
    }

    static String explorerUrl(SolanaCluster cluster, String transactionSignature) {
        String base = "https://explorer.solana.com/tx/" + transactionSignature;
        return switch (cluster) {
            case MAINNET -> base;
            case DEVNET -> base + "?cluster=devnet";
            case TESTNET -> base + "?cluster=testnet";
            case LOCALNET -> base + "?cluster=custom";
        };
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        restartPolling();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        generation++;
        checking = false;
        stopPolling();
        super.onDetach(detachEvent);
    }

    private void restartPolling() {
        stopPolling();
        if (signature == null || isFinal(status)) return;
        getUI().ifPresent(ui -> polling = UiPolling.register(ui, pollInterval, this::poll));
    }

    private void stopPolling() {
        if (polling != null) {
            polling.remove();
            polling = null;
        }
    }

    /** Fired when the tracked transaction's status changes. */
    public static class StatusChangedEvent extends ComponentEvent<SolanaTransactionStatus> {
        private final Status status;

        public StatusChangedEvent(SolanaTransactionStatus source, Status status) {
            super(source, false);
            this.status = status;
        }

        public Status getStatus() { return status; }
    }

    /** Status texts, replaceable per status. */
    public static class I18n implements Serializable {
        private final Map<Status, String> texts = new EnumMap<>(Map.of(
                Status.SUBMITTED, "Transaction submitted…",
                Status.PROCESSED, "Processed by the cluster, waiting for confirmation…",
                Status.CONFIRMED, "Confirmed.",
                Status.FINALIZED, "Finalized.",
                Status.FAILED, "The transaction failed on chain.",
                Status.EXPIRED, "The transaction did not land in time and can no longer be processed. Check the "
                        + "explorer, then try again.",
                Status.TIMED_OUT, "Still waiting for the transaction. Check the explorer before trying again."));

        public String get(Status status) { return texts.get(status); }

        public I18n set(Status status, String text) {
            texts.put(Objects.requireNonNull(status), Objects.requireNonNull(text));
            return this;
        }
    }
}
