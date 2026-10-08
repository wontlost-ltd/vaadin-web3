package com.wontlost.web3.solana.wallet;

import java.io.Serializable;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SignatureStatus;
import com.wontlost.web3.solana.SolanaCommitment;
import com.wontlost.web3.solana.SolanaRpcClient;
import com.wontlost.web3.solana.SolanaRpcException;
import com.wontlost.web3.ui.UiPolling;

/**
 * Shows the server-observed progress of a Solana transaction: submitted, processed, confirmed, finalized, failed,
 * or expired (its blockhash ran out before it landed, so it is safe to build and send again).
 * <p>
 * Polling uses the UI's shared poll interval ({@link UiPolling}) and stops once the transaction reaches the target
 * commitment ({@code confirmed} by default) or another final state. {@link Status#EXPIRED} relies on the block height
 * read by the client, so give it a client at {@code confirmed} or {@code finalized} (the default), not {@code processed}.
 */
public class SolanaTransactionStatus extends Composite<Div> {
    /** Progress of the tracked transaction. */
    public enum Status { SUBMITTED, PROCESSED, CONFIRMED, FINALIZED, FAILED, EXPIRED, TIMED_OUT }

    private final Span text = new Span();
    private final Anchor explorer = new Anchor();
    private final SolanaCluster cluster;
    private transient SolanaRpcClient rpc;
    private transient Registration polling;
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
        explorer.setHref(explorerUrl(cluster, transactionSignature));
        explorer.setVisible(cluster != SolanaCluster.LOCALNET);
        change(Status.SUBMITTED);
        restartPolling();
        return this;
    }

    /** The commitment at which tracking stops successfully; {@code confirmed} by default. */
    public SolanaTransactionStatus setTarget(SolanaCommitment value) {
        target = Objects.requireNonNull(value);
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

    /**
     * 读一次状态并推进；到达目标、失败、过期或超时即停止轮询。
     * RPC 暂时失败时保持当前状态，下次继续（超时兜底）。
     */
    void poll() {
        if (signature == null || isFinal(status) || rpc == null) {
            stopPolling();
            return;
        }
        try {
            Optional<SignatureStatus> read = rpc.getSignatureStatuses(List.of(signature), true).getFirst();
            if (read.isPresent()) {
                lastStatus = read.get();
                change(read.get().failed() ? Status.FAILED : progress(read.get().confirmationStatus()));
            } else if (lastValidBlockHeight >= 0 && rpc.getBlockHeight() > lastValidBlockHeight) {
                change(Status.EXPIRED);
            }
        } catch (SolanaRpcException exception) {
            // 节点暂时不可用：不改变状态
        }
        if (!isFinal(status) && System.nanoTime() - startedAt > timeout.toNanos()) change(Status.TIMED_OUT);
        if (isFinal(status)) stopPolling();
    }

    private Status progress(SolanaCommitment commitment) {
        return switch (commitment) {
            case PROCESSED -> Status.PROCESSED;
            case CONFIRMED -> Status.CONFIRMED;
            case FINALIZED -> Status.FINALIZED;
        };
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
                Status.EXPIRED, "The transaction expired before it landed. It is safe to try again.",
                Status.TIMED_OUT, "Still waiting for the transaction. Check the explorer before trying again."));

        public String get(Status status) { return texts.get(status); }

        public I18n set(Status status, String text) {
            texts.put(Objects.requireNonNull(status), Objects.requireNonNull(text));
            return this;
        }
    }
}
