package com.wontlost.web3.demo;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.NetworkIndicator;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.Web3Utils;
import com.wontlost.web3.calls.Call;
import com.wontlost.web3.calls.CallsRequest;
import com.wontlost.web3.calls.CallsStatus;
import com.wontlost.web3.calls.FallbackPolicy;
import com.wontlost.web3.chain.Tokens;
import com.wontlost.web3.pay.Finality;
import com.wontlost.web3.ui.Balance;
import com.wontlost.web3.ui.TransactionStatus;
import com.wontlost.web3.ui.UiPolling;

/** Demonstrates wallet transactions and EIP-5792 batch calls. */
@Route(value = "transactions", layout = MainLayout.class)
@AnonymousAllowed
public class TransactionsView extends VerticalLayout {
    private final Web3Connect wallet = new Web3Connect();
    private final NetworkIndicator network = new NetworkIndicator(wallet);
    private final VerticalLayout balances = new VerticalLayout();
    private final Balance nativeBalance = new Balance();
    private final TransactionStatus transactionStatus = new TransactionStatus();
    private final Span capability = new Span("Connect a wallet to read batch capabilities.");
    private final Span singleResult = new Span();
    private final Span batchResult = new Span();
    private final Span batchStatus = new Span();
    private int finalityConfirmations = 1;
    private Registration batchPolling;
    private boolean batchRequestInFlight;
    private long batchGeneration;

    public TransactionsView(@Value("${web3.demo.expected-chain-id:11155111}") long expectedChainId) {
        setMaxWidth("760px");
        getStyle().set("margin", "0 auto");
        network.setExpectedChainId(expectedChainId);
        transactionStatus.setChainId(expectedChainId);
        transactionStatus.setFinality(Finality.confirmations(1));
        balances.setPadding(false);
        balances.setSpacing(false);
        balances.setVisible(false);
        balances.add(new Paragraph("Native balance"), nativeBalance);
        wallet.addConnectedListener(event -> updateWallet(event.getAccount(), event.getChainId()));
        wallet.addChainChangedListener(event -> updateWallet(wallet.getAccount(), event.getChainId()));
        wallet.addDisconnectedListener(event -> {
            stopBatchPolling();
            balances.removeAll();
            balances.setVisible(false);
            capability.setText("Connect a wallet to read batch capabilities.");
        });
        add(new H1("Transactions"),
                new Paragraph("Send a transaction or submit two ordered calls. The Development wallet executes batches sequentially and does not support atomic batches."),
                wallet, network, new H2("Balance"), balances, singleTransactionSection(), batchSection(), transactionStatus);
        wallet.restore();
    }

    private VerticalLayout singleTransactionSection() {
        TextField recipient = addressField("Recipient address");
        BigDecimalField amount = amountField("Amount (ETH)");
        Button send = new Button("Send transaction", event -> {
            if (!validTransfer(recipient, amount, singleResult)) return;
            long chainId = currentChainId();
            wallet.sendTransaction(recipient.getValue(), Web3Utils.etherToWeiHex(amount.getValue()), null)
                    .thenAccept(hash -> getUI().ifPresent(ui -> ui.access(() -> {
                        transactionStatus.setChainId(chainId)
                                .setFinality(Finality.confirmations(finalityConfirmations)).track(hash);
                    })))
                    .exceptionally(error -> { showFailure(error); return null; });
        });
        Select<Integer> confirmations = new Select<>();
        confirmations.setLabel("Finality");
        confirmations.setItems(1, 2);
        confirmations.setItemLabelGenerator(value -> value + " confirmation" + (value == 1 ? "" : "s"));
        confirmations.setValue(1);
        confirmations.addValueChangeListener(event -> {
            finalityConfirmations = event.getValue() == null ? 1 : event.getValue();
            transactionStatus.setFinality(Finality.confirmations(finalityConfirmations));
        });
        return section("Single transaction", recipient, amount, confirmations, send, singleResult);
    }

    private VerticalLayout batchSection() {
        TextField firstRecipient = addressField("First recipient address");
        BigDecimalField firstAmount = amountField("First amount (ETH)");
        TextField secondRecipient = addressField("Second recipient address");
        BigDecimalField secondAmount = amountField("Second amount (ETH)");
        Select<FallbackPolicy> fallback = new Select<>();
        fallback.setLabel("Unsupported batch policy");
        fallback.setItems(FallbackPolicy.values());
        fallback.setItemLabelGenerator(value -> value == FallbackPolicy.NEVER
                ? "Never fall back" : "Allow sequential non-atomic fallback");
        fallback.setValue(FallbackPolicy.NEVER);
        Button submit = new Button("Submit two calls", event -> submitBatch(firstRecipient, firstAmount,
                secondRecipient, secondAmount, fallback.getValue()));
        return section("Batch calls (EIP-5792)", capability, firstRecipient, firstAmount,
                secondRecipient, secondAmount, fallback, submit, batchResult, batchStatus);
    }

    private void submitBatch(TextField firstRecipient, BigDecimalField firstAmount,
            TextField secondRecipient, BigDecimalField secondAmount, FallbackPolicy policy) {
        if (!wallet.isConnected()) { batchResult.setText("Connect a wallet first."); return; }
        if (!validTransfer(firstRecipient, firstAmount, batchResult)
                || !validTransfer(secondRecipient, secondAmount, batchResult)) return;
        long chainId = currentChainId();
        String id = "vaadin-" + UUID.randomUUID();
        CallsRequest request = new CallsRequest(id, wallet.getAccount(), chainId, false, List.of(
                new Call(firstRecipient.getValue(), null, Web3Utils.etherToWeiHex(firstAmount.getValue())),
                new Call(secondRecipient.getValue(), null, Web3Utils.etherToWeiHex(secondAmount.getValue()))), null);
        stopBatchPolling();
        long generation = ++batchGeneration;
        batchResult.setText("Submitting calls…");
        batchStatus.setText("");
        wallet.sendCalls(request, policy).whenComplete((submission, failure) -> getUI().ifPresent(ui -> ui.access(() -> {
            if (generation != batchGeneration) return;
            if (failure != null) { batchResult.setText("Batch submission failed: " + rootMessage(failure)); return; }
            batchResult.setText("Batch id: " + submission.id() + "; sequential non-atomic fallback: "
                    + submission.nonAtomicFallback() + "; transaction hashes: "
                    + displayList(submission.transactionHashes()) + "; errors: " + displayList(submission.errors()));
            if (submission.nonAtomicFallback()) {
                batchStatus.setText("The wallet sent separate transactions; it does not provide batch status for this fallback.");
            } else if (submission.id() != null) {
                startBatchPolling(ui, submission.id(), generation);
            }
        })));
    }

    private void updateWallet(String account, String chainHex) {
        if (account == null || account.isBlank()) return;
        long chainId = parseChainId(chainHex);
        transactionStatus.setChainId(chainId);
        balances.removeAll();
        balances.setVisible(true);
        nativeBalance.setChainId(chainId).setAddress(account).setRefreshInterval(Duration.ofSeconds(15));
        balances.add(new Paragraph("Native balance"), nativeBalance);
        Tokens.find("USDC", chainId).ifPresent(token -> balances.add(new Paragraph("USDC balance"),
                new Balance().setChainId(chainId).setAddress(account).setToken(token.symbol())
                        .setRefreshInterval(Duration.ofSeconds(15))));
        wallet.getCapabilities(List.of(chainId)).whenComplete((value, failure) -> getUI().ifPresent(ui -> ui.access(() -> {
            if (failure != null) capability.setText("Batch capability unavailable: " + rootMessage(failure));
            else capability.setText("Atomic batch capability for chain " + chainId + ": "
                    + value.atomicByChain().getOrDefault("0x" + Long.toHexString(chainId),
                            com.wontlost.web3.calls.WalletCapabilities.AtomicStatus.ABSENT)
                            + ". Development wallet batches are sequential and non-atomic.");
        })));
    }

    private void startBatchPolling(UI ui, String id, long generation) {
        batchRequestInFlight = false;
        batchPolling = UiPolling.register(ui, Duration.ofSeconds(2), () -> {
            if (generation != batchGeneration || batchRequestInFlight) return;
            batchRequestInFlight = true;
            wallet.getCallsStatus(id).whenComplete((status, failure) -> ui.access(() -> {
                if (generation != batchGeneration) return;
                batchRequestInFlight = false;
                if (failure != null) { batchStatus.setText("Unable to read batch status: " + rootMessage(failure)); return; }
                batchStatus.setText("Status " + status.status() + " (" + statusName(status.statusType())
                        + "); atomic: " + status.atomic() + "; receipts: " + status.receipts().size());
                if (status.status() != 100) stopBatchPolling();
            }));
        });
    }

    private void stopBatchPolling() {
        batchGeneration++;
        if (batchPolling != null) batchPolling.remove();
        batchPolling = null;
        batchRequestInFlight = false;
    }

    private boolean validTransfer(TextField recipient, BigDecimalField amount, Span result) {
        if (!wallet.isConnected()) { result.setText("Connect a wallet first."); return false; }
        if (!Web3Utils.isValidAddress(recipient.getValue())) { result.setText("Enter a valid recipient address."); return false; }
        if (amount.getValue() == null || amount.getValue().signum() <= 0) { result.setText("Enter an amount greater than zero."); return false; }
        return true;
    }

    private void showFailure(Throwable failure) {
        getUI().ifPresent(ui -> ui.access(() -> singleResult.setText("Transaction failed: " + rootMessage(failure))));
    }

    private long currentChainId() { return parseChainId(wallet.getChainId()); }
    private static long parseChainId(String value) { return value == null || value.isBlank() ? 0 : Long.parseLong(value.substring(2), 16); }
    private static String rootMessage(Throwable failure) {
        Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
        return String.valueOf(cause.getMessage());
    }
    private static String displayList(List<String> values) { return values.isEmpty() ? "none" : String.join(", ", values); }
    private static String statusName(CallsStatus.Status status) {
        return switch (status) {
            case PENDING -> "pending"; case CONFIRMED -> "confirmed"; case OFFCHAIN_FAILURE -> "off-chain failure";
            case REVERTED -> "reverted"; case PARTIALLY_REVERTED -> "partially reverted"; case UNKNOWN -> "unknown";
        };
    }
    private static TextField addressField(String label) { TextField field = new TextField(label); field.setWidthFull(); field.setPlaceholder("0x…"); return field; }
    private static BigDecimalField amountField(String label) { BigDecimalField field = new BigDecimalField(label); field.setValue(new BigDecimal("0.001")); return field; }
    private static VerticalLayout section(String title, com.vaadin.flow.component.Component... children) {
        VerticalLayout layout = new VerticalLayout(new H2(title)); layout.setPadding(false); layout.add(children); return layout;
    }
}
