package com.wontlost.web3;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.shared.Registration;

/** Displays the connected wallet network and offers a switch to an expected chain. */
@Tag("div")
public class NetworkIndicator extends Component {
    private final Web3Connect wallet;
    private final Span status = new Span();
    private final Button switchButton = new Button();
    private NetworkIndicatorI18n i18n = new NetworkIndicatorI18n();
    private long expectedChainId = -1;
    private String errorMessage;
    private boolean switching;
    private String chainName;
    private String rpcUrl;
    private String currencySymbol;
    private Registration chainRegistration;
    private Registration connectedRegistration;
    private Registration disconnectedRegistration;

    /** Creates an indicator bound to a wallet connector. */
    public NetworkIndicator(Web3Connect wallet) {
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        status.getElement().setAttribute("role", "status");
        status.getElement().setAttribute("aria-live", "polite");
        switchButton.addClickListener(event -> switchNetwork());
        getElement().appendChild(status.getElement(), switchButton.getElement());
        render();
    }

    /** Sets the chain id that the connected wallet is expected to use. */
    public void setExpectedChainId(long chainId) {
        if (chainId < 0) throw new IllegalArgumentException("chainId must be non-negative");
        expectedChainId = chainId;
        errorMessage = null;
        render();
    }

    /** Returns the expected chain id, or {@code -1} when no expectation is configured. */
    public long getExpectedChainId() { return expectedChainId; }

    /**
     * Supplies the metadata passed to {@code wallet_addEthereumChain} when the wallet does not know the expected chain
     * (error 4902). Matches what {@link Web3Connect#switchChain(String, String, String, String)} forwards: the chain name,
     * one RPC URL and the native currency symbol (18 decimals).
     */
    public void setAddChainParameters(String chainName, String rpcUrl, String currencySymbol) {
        this.chainName = Objects.requireNonNull(chainName, "chainName");
        this.rpcUrl = Objects.requireNonNull(rpcUrl, "rpcUrl");
        this.currencySymbol = Objects.requireNonNull(currencySymbol, "currencySymbol");
    }

    /** Sets localized status, action, and error messages. */
    public void setI18n(NetworkIndicatorI18n value) {
        i18n = Objects.requireNonNull(value, "i18n");
        render();
    }

    /** Returns this indicator's localized messages. */
    public NetworkIndicatorI18n getI18n() { return i18n; }

    /** Registers a listener for wallet network changes observed by this indicator. */
    public Registration addNetworkChangedListener(ComponentEventListener<NetworkChangedEvent> listener) {
        return addListener(NetworkChangedEvent.class, listener);
    }

    /** Registers a listener for a failed network switch request. */
    public Registration addNetworkSwitchFailedListener(ComponentEventListener<NetworkSwitchFailedEvent> listener) {
        return addListener(NetworkSwitchFailedEvent.class, listener);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        chainRegistration = wallet.addChainChangedListener(event -> updateFromWallet(event.getChainId()));
        connectedRegistration = wallet.addConnectedListener(event -> updateFromWallet(wallet.getChainId()));
        disconnectedRegistration = wallet.addDisconnectedListener(event -> render());
        updateFromWallet(wallet.getChainId());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        removeWalletListeners();
        super.onDetach(detachEvent);
    }

    private void removeWalletListeners() {
        if (chainRegistration != null) chainRegistration.remove();
        if (connectedRegistration != null) connectedRegistration.remove();
        if (disconnectedRegistration != null) disconnectedRegistration.remove();
        chainRegistration = connectedRegistration = disconnectedRegistration = null;
    }

    private void updateFromWallet(String chainIdHex) {
        long chainId = parseChainId(chainIdHex);
        boolean expectedMatches = expectedChainId < 0 || chainId == expectedChainId;
        fireEvent(new NetworkChangedEvent(this, false, chainId, expectedMatches));
        errorMessage = null;
        render();
    }

    private void switchNetwork() {
        if (switching || expectedChainId < 0 || !wallet.isConnected()) return;
        switching = true;
        errorMessage = null;
        render();
        var result = hasAddChainParameters()
                ? wallet.switchChain(Chains.toHex(expectedChainId), chainName, rpcUrl, currencySymbol)
                : wallet.switchChain(Chains.toHex(expectedChainId));
        result.whenComplete((chain, failure) -> {
            switching = false;
            if (failure == null) {
                updateFromWallet(chain == null || chain.isBlank() ? wallet.getChainId() : chain);
                return;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            int code = cause instanceof Web3Connect.Web3Exception web3Error ? web3Error.getCode() : -1;
            String message = cause.getMessage() == null ? "Network switch failed" : cause.getMessage();
            if (code == 4902 && !hasAddChainParameters()) {
                errorMessage = format(i18n.getAddChainUnavailable(), chainLabel(expectedChainId));
            } else {
                errorMessage = format(i18n.getSwitchFailed(), chainLabel(expectedChainId), message);
            }
            fireEvent(new NetworkSwitchFailedEvent(this, false, code, message));
            render();
        });
    }

    private boolean hasAddChainParameters() {
        return chainName != null && rpcUrl != null && currencySymbol != null;
    }

    private void render() {
        if (!wallet.isConnected()) {
            status.setText(i18n.getNotConnected());
            status.getElement().removeAttribute("data-state");
            switchButton.setVisible(false);
            return;
        }
        long actual = parseChainId(wallet.getChainId());
        boolean mismatch = expectedChainId >= 0 && actual != expectedChainId;
        status.getElement().setAttribute("data-state", mismatch || errorMessage != null ? "error" : "connected");
        if (errorMessage != null) status.setText("⚠ " + errorMessage);
        else if (mismatch) status.setText("⚠ " + format(i18n.getWrongNetwork(), chainLabel(actual), chainLabel(expectedChainId)));
        else status.setText(format(i18n.getConnected(), chainLabel(actual)));
        switchButton.setVisible(mismatch);
        switchButton.setEnabled(!switching);
        switchButton.setText(switching ? format(i18n.getSwitching(), chainLabel(expectedChainId))
                : format(i18n.getSwitchTo(), chainLabel(expectedChainId)));
        switchButton.getElement().setAttribute("aria-label", switchButton.getText());
    }

    private static String format(String pattern, Object... values) {
        return new MessageFormat(pattern, Locale.ROOT).format(values);
    }

    private static long parseChainId(String value) {
        if (value == null || value.isBlank()) return -1;
        try { return Chains.toDecimal(value).longValueExact(); }
        catch (RuntimeException exception) { return -1; }
    }

    private static String chainLabel(long chainId) {
        return switch ((int) chainId) {
            case 1 -> "Ethereum";
            case 11155111 -> "Sepolia";
            case 137 -> "Polygon";
            case 80002 -> "Polygon Amoy";
            case 56 -> "BNB Smart Chain";
            case 42161 -> "Arbitrum One";
            case 10 -> "Optimism";
            case 8453 -> "Base";
            case 43114 -> "Avalanche C-Chain";
            case 100 -> "Gnosis";
            default -> "Chain " + chainId;
        };
    }

    /** Event fired when the wallet reports a chain change. */
    public static class NetworkChangedEvent extends ComponentEvent<NetworkIndicator> {
        private final long chainId;
        private final boolean expectedMatches;
        public NetworkChangedEvent(NetworkIndicator source, boolean fromClient, long chainId, boolean expectedMatches) {
            super(source, fromClient);
            this.chainId = chainId;
            this.expectedMatches = expectedMatches;
        }
        public long getChainId() { return chainId; }
        public boolean isExpectedMatches() { return expectedMatches; }
    }

    /** Event fired when the wallet rejects or cannot complete a chain switch. */
    public static class NetworkSwitchFailedEvent extends ComponentEvent<NetworkIndicator> {
        private final int code;
        private final String message;
        public NetworkSwitchFailedEvent(NetworkIndicator source, boolean fromClient, int code, String message) {
            super(source, fromClient);
            this.code = code;
            this.message = message;
        }
        public int getCode() { return code; }
        public String getMessage() { return message; }
    }
}
