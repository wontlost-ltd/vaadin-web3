package com.wontlost.web3;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.Synchronize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.shared.Registration;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wallet connector component for web3-enabled Vaadin applications.
 * <p>
 * Wraps any EIP-1193 browser wallet (MetaMask, Coinbase Wallet, Brave,
 * Rabby, ...) behind a Java API: connecting, message signing (personal_sign
 * and EIP-712 typed data), sending transactions and switching chains.
 * <p>
 * Example:
 * <pre>{@code
 * Web3Connect wallet = new Web3Connect();
 * wallet.addConnectedListener(e ->
 *     Notification.show("Connected: " + e.getAccount()));
 * wallet.addErrorListener(e ->
 *     Notification.show("Wallet error: " + e.getErrorMessage()));
 * add(wallet);
 * }</pre>
 */
@Tag("web3-connect")
@JsModule("./web3-connect.js")
@NpmPackage(value = "lit", version = "^3.0.0")
public class Web3Connect extends Component {

    private static final String ERROR_MARKER = "WEB3_ERROR:";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private transient List<CompletableFuture<String>> pendingFutures;

    /**
     * Creates a wallet connector rendered as a connect/disconnect button.
     */
    public Web3Connect() {
    }

    /**
     * Creates a wallet connector.
     *
     * @param hideButton when {@code true}, no button is rendered and the
     *        component is driven purely through the Java API
     */
    public Web3Connect(boolean hideButton) {
        setButtonHidden(hideButton);
    }

    /**
     * The connected account address, or an empty string when disconnected.
     */
    @Synchronize(property = "account", value = {"web3-connected", "web3-disconnected"})
    public String getAccount() {
        return getElement().getProperty("account", "");
    }

    /**
     * The current chain id as a hex string (e.g. {@code 0x1}), or an empty
     * string when unknown. See {@link Chains} for common values.
     */
    @Synchronize(property = "chainId", value = {"web3-connected", "web3-chain-changed"})
    public String getChainId() {
        return getElement().getProperty("chainId", "");
    }

    /** Whether a wallet account is currently connected. */
    public boolean isConnected() {
        return !getAccount().isEmpty();
    }

    /** 是否检测到可用的钱包 provider。 */
    @Synchronize(property = "providerAvailable", value = "web3-provider-detected")
    public boolean isProviderAvailable() {
        return getElement().getProperty("providerAvailable", false);
    }

    /** Sets the label of the connect button. */
    public void setConnectText(String text) {
        getElement().setProperty("connectText", Objects.requireNonNull(text));
    }

    /** Sets the label of the disconnect button. */
    public void setDisconnectText(String text) {
        getElement().setProperty("disconnectText", Objects.requireNonNull(text));
    }

    /** Hides or shows the built-in button. */
    public void setButtonHidden(boolean hidden) {
        getElement().setProperty("hideButton", hidden);
    }

    /**
     * Prompts the user to connect their wallet. The result is also reported
     * through {@link #addConnectedListener(ComponentEventListener)}.
     *
     * @return a future completed with the connected account address
     */
    public CompletableFuture<String> connect() {
        return call("return this.connect()");
    }

    /**
     * Forgets the connection app-side (EIP-1193 wallets have no programmatic
     * disconnect). Fires a {@link Web3DisconnectedEvent}.
     */
    public void disconnect() {
        getElement().callJsFunction("disconnect");
    }

    /**
     * Silently restores a previously authorized connection, if the wallet
     * still allows it. Useful on view enter so returning users do not have
     * to click connect again.
     *
     * @return a future completed with the account address, or {@code null}
     *         when nothing was restored
     */
    public CompletableFuture<String> restore() {
        return call("return this.restore()");
    }

    /**
     * Asks the wallet to sign a plain-text message with {@code personal_sign}.
     *
     * @param message the message to sign
     * @return a future completed with the signature as a hex string
     */
    public CompletableFuture<String> signMessage(String message) {
        return call("return this.signMessage($0)", message);
    }

    /**
     * Asks the wallet to sign EIP-712 typed data with
     * {@code eth_signTypedData_v4}.
     *
     * @param typedDataJson the full EIP-712 payload (domain, types, message)
     *        as a JSON string
     * @return a future completed with the signature as a hex string
     */
    public CompletableFuture<String> signTypedData(String typedDataJson) {
        return call("return this.signTypedData($0)", typedDataJson);
    }

    /**
     * Sends a transaction from the connected account.
     *
     * @param to the recipient address
     * @param valueWeiHex the value in wei as a hex string (e.g.
     *        {@code "0xde0b6b3a7640000"} for 1 ETH), or {@code null} for 0
     * @param dataHex optional calldata as a hex string, or {@code null}
     * @return a future completed with the transaction hash
     */
    public CompletableFuture<String> sendTransaction(String to, String valueWeiHex, String dataHex) {
        ObjectNode tx = MAPPER.createObjectNode();
        tx.put("to", Objects.requireNonNull(to, "to is required"));
        if (valueWeiHex != null) {
            tx.put("value", valueWeiHex);
        }
        if (dataHex != null) {
            tx.put("data", dataHex);
        }
        return call("return this.sendTransaction(JSON.parse($0))", tx.toString());
    }

    /**
     * Sends a transaction built from arbitrary EIP-1193 transaction fields
     * ({@code to}, {@code value}, {@code data}, {@code gas}, ...). Values
     * must already be hex-encoded where the RPC expects hex.
     *
     * @param txFields transaction fields; {@code from} is filled in with the
     *        connected account automatically
     * @return a future completed with the transaction hash
     */
    public CompletableFuture<String> sendTransaction(Map<String, String> txFields) {
        ObjectNode tx = MAPPER.createObjectNode();
        txFields.forEach(tx::put);
        return call("return this.sendTransaction(JSON.parse($0))", tx.toString());
    }

    /**
     * Returns the balance of the connected account in wei, as a hex string.
     */
    public CompletableFuture<String> getBalance() {
        return call("return this.getBalance()");
    }

    /**
     * Asks the wallet to switch to the given chain.
     *
     * @param chainIdHex the chain id as a hex string, see {@link Chains}
     * @return a future completed with the new chain id
     */
    public CompletableFuture<String> switchChain(String chainIdHex) {
        return call("return this.switchChain($0)", chainIdHex);
    }

    /**
     * Asks the wallet to switch to the given chain, adding it first
     * (wallet_addEthereumChain) if the wallet does not know it.
     *
     * @param chainIdHex the chain id as a hex string
     * @param chainName human readable chain name
     * @param rpcUrl JSON-RPC endpoint of the chain
     * @param currencySymbol native currency symbol (e.g. "ETH")
     * @return a future completed with the new chain id
     */
    public CompletableFuture<String> switchChain(String chainIdHex, String chainName,
            String rpcUrl, String currencySymbol) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("chainName", chainName);
        params.putArray("rpcUrls").add(rpcUrl);
        ObjectNode currency = params.putObject("nativeCurrency");
        currency.put("name", currencySymbol);
        currency.put("symbol", currencySymbol);
        currency.put("decimals", 18);
        return call("return this.switchChain($0, JSON.parse($1))", chainIdHex, params.toString());
    }

    /** Fired when a wallet account has been connected or changed. */
    public Registration addConnectedListener(ComponentEventListener<Web3ConnectedEvent> listener) {
        return addListener(Web3ConnectedEvent.class, listener);
    }

    /** Fired when the wallet has been disconnected. */
    public Registration addDisconnectedListener(ComponentEventListener<Web3DisconnectedEvent> listener) {
        return addListener(Web3DisconnectedEvent.class, listener);
    }

    /** Fired when the wallet switched to a different chain. */
    public Registration addChainChangedListener(ComponentEventListener<ChainChangedEvent> listener) {
        return addListener(ChainChangedEvent.class, listener);
    }

    /** Fired when a transaction has been submitted by the wallet. */
    public Registration addTransactionSentListener(ComponentEventListener<TransactionSentEvent> listener) {
        return addListener(TransactionSentEvent.class, listener);
    }

    /** Fired when a message or typed data has been signed. */
    public Registration addMessageSignedListener(ComponentEventListener<MessageSignedEvent> listener) {
        return addListener(MessageSignedEvent.class, listener);
    }

    /** Fired on any wallet error (user rejection, missing provider, RPC error). */
    public Registration addErrorListener(ComponentEventListener<Web3ErrorEvent> listener) {
        return addListener(Web3ErrorEvent.class, listener);
    }

    /** 浏览器检测到钱包 provider 可用性变化时触发。 */
    public Registration addProviderDetectedListener(
            ComponentEventListener<ProviderDetectedEvent> listener) {
        return addListener(ProviderDetectedEvent.class, listener);
    }

    private CompletableFuture<String> call(String expression, Serializable... params) {
        CompletableFuture<String> future = new CompletableFuture<>();
        trackPendingFuture(future);
        future.whenComplete((result, error) -> removePendingFuture(future));
        String invocation = expression.startsWith("return ") ? expression.substring(7) : expression;
        String wrappedExpression = "return Promise.resolve(" + invocation + ").catch(e => { throw new Error('"
                + ERROR_MARKER
                + "' + JSON.stringify(this._errorInfo(e))); })";
        getElement().executeJs(wrappedExpression, params)
                .then(String.class, future::complete,
                        error -> future.completeExceptionally(parseWeb3Exception(error)));
        return future;
    }

    void trackPendingFuture(CompletableFuture<String> future) {
        synchronized (this) {
            if (pendingFutures == null) {
                pendingFutures = new ArrayList<>();
            }
            pendingFutures.add(future);
        }
    }

    private synchronized void removePendingFuture(CompletableFuture<String> future) {
        if (pendingFutures != null) {
            pendingFutures.remove(future);
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        completePendingFuturesOnDetach();
        super.onDetach(detachEvent);
    }

    void completePendingFuturesOnDetach() {
        List<CompletableFuture<String>> pending;
        synchronized (this) {
            pending = pendingFutures;
            pendingFutures = null;
        }
        if (pending != null) {
            Web3Exception error = new Web3Exception(-1, "Component detached; wallet request cancelled");
            pending.forEach(future -> future.completeExceptionally(error));
        }
    }

    static Web3Exception parseWeb3Exception(String error) {
        String message = error == null ? "" : error;
        int markerIndex = message.indexOf(ERROR_MARKER);
        if (markerIndex < 0) {
            return new Web3Exception(-1, message);
        }
        try {
            var errorInfo = MAPPER.readTree(message.substring(markerIndex + ERROR_MARKER.length()));
            int code = errorInfo.path("code").asInt(-1);
            String errorMessage = errorInfo.path("message").asString(message);
            return new Web3Exception(code, errorMessage);
        } catch (RuntimeException exception) {
            return new Web3Exception(-1, message);
        }
    }

    /** Thrown when a client-side wallet operation fails. */
    public static class Web3Exception extends RuntimeException {
        private final int code;

        public Web3Exception(String message) {
            this(-1, message);
        }

        public Web3Exception(int code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * The EIP-1193 / EIP-1474 error code reported by the wallet, or
         * {@code -1} when unknown (e.g. the component was detached).
         */
        public int getCode() {
            return code;
        }

        /** Whether the user rejected the request in the wallet (code 4001). */
        public boolean isUserRejected() {
            return code == 4001;
        }
    }

    /** 钱包 provider 变为可用或不可用时触发。 */
    @DomEvent("web3-provider-detected")
    public static class ProviderDetectedEvent extends ComponentEvent<Web3Connect> {
        private final boolean available;

        public ProviderDetectedEvent(Web3Connect source, boolean fromClient,
                @EventData("event.detail.available") boolean available) {
            super(source, fromClient);
            this.available = available;
        }

        public boolean isAvailable() {
            return available;
        }
    }

    /** Event fired when a wallet account connects. */
    @DomEvent("web3-connected")
    public static class Web3ConnectedEvent extends ComponentEvent<Web3Connect> {
        private final String account;
        private final String chainId;

        public Web3ConnectedEvent(Web3Connect source, boolean fromClient,
                @EventData("event.detail.account") String account,
                @EventData("event.detail.chainId") String chainId) {
            super(source, fromClient);
            this.account = account;
            this.chainId = chainId;
        }

        public String getAccount() {
            return account;
        }

        public String getChainId() {
            return chainId;
        }
    }

    /** Event fired when the wallet disconnects. */
    @DomEvent("web3-disconnected")
    public static class Web3DisconnectedEvent extends ComponentEvent<Web3Connect> {
        private final String previousAccount;

        public Web3DisconnectedEvent(Web3Connect source, boolean fromClient,
                @EventData("event.detail.previousAccount") String previousAccount) {
            super(source, fromClient);
            this.previousAccount = previousAccount;
        }

        public String getPreviousAccount() {
            return previousAccount;
        }
    }

    /** Event fired when the active chain changes. */
    @DomEvent("web3-chain-changed")
    public static class ChainChangedEvent extends ComponentEvent<Web3Connect> {
        private final String chainId;

        public ChainChangedEvent(Web3Connect source, boolean fromClient,
                @EventData("event.detail.chainId") String chainId) {
            super(source, fromClient);
            this.chainId = chainId;
        }

        public String getChainId() {
            return chainId;
        }
    }

    /** Event fired when a transaction has been submitted. */
    @DomEvent("web3-transaction-sent")
    public static class TransactionSentEvent extends ComponentEvent<Web3Connect> {
        private final String hash;
        private final String account;

        public TransactionSentEvent(Web3Connect source, boolean fromClient,
                @EventData("event.detail.hash") String hash,
                @EventData("event.detail.account") String account) {
            super(source, fromClient);
            this.hash = hash;
            this.account = account;
        }

        public String getHash() {
            return hash;
        }

        public String getAccount() {
            return account;
        }
    }

    /** Event fired when a message has been signed. */
    @DomEvent("web3-message-signed")
    public static class MessageSignedEvent extends ComponentEvent<Web3Connect> {
        private final String message;
        private final String signature;
        private final String account;

        public MessageSignedEvent(Web3Connect source, boolean fromClient,
                @EventData("event.detail.message") String message,
                @EventData("event.detail.signature") String signature,
                @EventData("event.detail.account") String account) {
            super(source, fromClient);
            this.message = message;
            this.signature = signature;
            this.account = account;
        }

        public String getMessage() {
            return message;
        }

        public String getSignature() {
            return signature;
        }

        public String getAccount() {
            return account;
        }
    }

    /** Event fired when a wallet operation fails. */
    @DomEvent("web3-error")
    public static class Web3ErrorEvent extends ComponentEvent<Web3Connect> {
        private final int code;
        private final String errorMessage;

        public Web3ErrorEvent(Web3Connect source, boolean fromClient,
                @EventData("event.detail.code") int code,
                @EventData("event.detail.message") String errorMessage) {
            super(source, fromClient);
            this.code = code;
            this.errorMessage = errorMessage;
        }

        /** EIP-1193/EIP-1474 error code; 4001 is "user rejected". */
        public int getCode() {
            return code;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        /** Whether the user rejected the request in the wallet. */
        public boolean isUserRejected() {
            return code == 4001;
        }
    }
}
