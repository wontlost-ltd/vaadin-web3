package com.wontlost.web3;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.Synchronize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.wontlost.web3.calls.Call;
import com.wontlost.web3.calls.CallsException;
import com.wontlost.web3.calls.CallsRequest;
import com.wontlost.web3.calls.CallsStatus;
import com.wontlost.web3.calls.CallsSubmission;
import com.wontlost.web3.calls.FallbackPolicy;
import com.wontlost.web3.calls.WalletCapabilities;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.shared.Registration;

import tools.jackson.databind.JsonNode;
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
    // 签名类方法 + 只读节点方法（与 DevWallet 的只读透传一致）；其余一律拒绝
    private static final Set<String> SERVER_WALLET_METHODS = Set.of("personal_sign", "eth_signTypedData_v4",
            "eth_sendTransaction", "wallet_switchEthereumChain", "wallet_addEthereumChain",
            "wallet_getCapabilities", "wallet_sendCalls", "wallet_getCallsStatus", "wallet_showCallsStatus",
            "eth_blockNumber", "eth_call", "eth_estimateGas", "eth_feeHistory", "eth_gasPrice", "eth_getBalance", "eth_getBlockByHash", "eth_getBlockByNumber", "eth_getCode", "eth_getLogs", "eth_getStorageAt", "eth_getTransactionByHash", "eth_getTransactionCount", "eth_getTransactionReceipt", "eth_maxPriorityFeePerGas", "net_version");
    static final int MAX_PENDING_SERVER_WALLET_REQUESTS = 8;
    private transient List<CompletableFuture<String>> pendingFutures;
    private transient Map<String, CompletableFuture<String>> serverWalletRequests;
    private transient ServerWallet serverWallet;
    private Web3ConnectI18n i18n = new Web3ConnectI18n();

    /**
     * Creates a wallet connector rendered as a connect/disconnect button.
     */
    public Web3Connect() {
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        VaadinService service = VaadinService.getCurrent();
        serverWallet = ServerWallet.find(service == null ? null : service.getContext());
        if (serverWallet == null) {
            getElement().setProperty("serverWallet", "");
            return;
        }
        ObjectNode info = MAPPER.createObjectNode();
        info.put("uuid", java.util.UUID.randomUUID().toString());
        info.put("name", serverWallet.name());
        info.put("rdns", serverWallet.rdns());
        info.put("chainId", serverWallet.chainId());
        info.set("accounts", MAPPER.valueToTree(serverWallet.accounts()));
        getElement().setProperty("serverWallet", info.toString());
        getElement().setProperty("developmentWalletWarning", i18n.getDevelopmentWalletWarning());
    }

    /** Forwards a browser wallet request to the registered server wallet. */
    @ClientCallable
    public void serverWalletRequest(String requestId, String method, String paramsJson) {
        VaadinService service = VaadinService.getCurrent();
        ServerWallet wallet = ServerWallet.find(service == null ? null : service.getContext());
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            rejectServerWalletRequest(requestId, 4100, "Server wallet is unavailable");
            return;
        }
        Map<String, CompletableFuture<String>> pending = serverWalletRequests();
        // 每个组件最多 MAX_PENDING_SERVER_WALLET_REQUESTS 个未完成请求，防止浏览器堆积请求占用内存与 RPC。
        // @ClientCallable 在 UI 会话锁内串行执行，因此"检查容量再插入"不会与同一组件的其他调用交错
        if (pending.size() >= MAX_PENDING_SERVER_WALLET_REQUESTS) {
            rejectServerWalletRequest(requestId, -32005, "Too many pending server wallet requests");
            return;
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        if (pending.putIfAbsent(requestId, result) != null) return;
        // 超时与异步钱包会在其他线程完成：回写浏览器必须持有会话锁，否则经 ui.access 排队
        UI ui = UI.getCurrent();
        result.orTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((json, error) -> {
                    if (!pending.remove(requestId, result)) return;
                    if (error == null) {
                        deliver(ui, () -> getElement().executeJs("this._resolveServerWalletRequest($0, $1)", requestId,
                                json == null ? "null" : json));
                    } else {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause() : error;
                        int code = serverWalletErrorCode(cause);
                        String message = cause.getMessage() == null ? "Server wallet request failed" : cause.getMessage();
                        deliver(ui, () -> getElement().executeJs("this._rejectServerWalletRequest($0, $1, $2)",
                                requestId, code, message));
                    }
                });
        CompletableFuture<String> walletResult = requestServerWallet(wallet, method, paramsJson);
        walletResult.whenComplete((json, error) -> {
                if (error == null) result.complete(json);
                else result.completeExceptionally(error);
            });
    }

    /**
     * 在请求线程（已持有会话锁）中直接执行；在其他线程中经 {@code ui.access} 排队。
     * 异步完成的服务端钱包需要 {@code @Push} 或轮询，结果才能在下一次往返之前送达浏览器。
     */
    static void deliver(UI ui, Runnable command) {
        if (ui == null) return;
        if (ui.getSession() != null && ui.getSession().hasLock()) command.run();
        else ui.access(command::run);
    }

    static CompletableFuture<String> requestServerWallet(ServerWallet wallet, String method, String paramsJson) {
        ServerWallet.ServerWalletException invalid = validateServerWalletRequest(wallet, method, paramsJson);
        if (invalid != null) return CompletableFuture.failedFuture(invalid);
        try {
            return wallet.request(method, paramsJson);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    static ServerWallet.ServerWalletException validateServerWalletRequest(
            ServerWallet wallet, String method, String paramsJson) {
        if (wallet == null) return new ServerWallet.ServerWalletException(4100, "Server wallet is unavailable");
        if (method == null || !SERVER_WALLET_METHODS.contains(method)) {
            return new ServerWallet.ServerWalletException(4200, "Server wallet method is not allowed");
        }
        if (paramsJson == null || paramsJson.length() > 65536) {
            return new ServerWallet.ServerWalletException(-32600, "Invalid server wallet parameters");
        }
        try {
            if (!MAPPER.readTree(paramsJson).isArray()) {
                return new ServerWallet.ServerWalletException(-32600, "Server wallet parameters must be an array");
            }
        } catch (RuntimeException exception) {
            return new ServerWallet.ServerWalletException(-32600, "Invalid server wallet parameters");
        }
        return null;
    }

    static int serverWalletErrorCode(Throwable error) {
        return error instanceof ServerWallet.ServerWalletException walletError ? walletError.getCode() : -32603;
    }

    private Map<String, CompletableFuture<String>> serverWalletRequests() {
        if (serverWalletRequests == null) serverWalletRequests = new ConcurrentHashMap<>();
        return serverWalletRequests;
    }

    private void rejectServerWalletRequest(String requestId, int code, String message) {
        if (requestId != null) getElement().executeJs("this._rejectServerWalletRequest($0, $1, $2)", requestId, code, message);
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

    /**
     * Returns whether the given wallet is the application's registered {@link ServerWallet}, for example the development
     * wallet. Applications that render their own wallet list should show
     * {@link Web3ConnectI18n#getDevelopmentWalletWarning()} next to such a wallet.
     */
    public boolean isServerWallet(WalletInfo info) {
        if (info == null) return false;
        VaadinService service = VaadinService.getCurrent();
        ServerWallet wallet = ServerWallet.find(service == null ? null : service.getContext());
        return wallet != null && wallet.rdns().equals(info.rdns());
    }

    /** Returns the EIP-6963 wallets currently discovered by the browser. */
    @Synchronize(property = "wallets", value = "web3-wallets-changed")
    public List<WalletInfo> getWallets() {
        String wallets = getElement().getProperty("wallets", "");
        if (wallets.isBlank()) return List.of();
        try {
            var nodes = MAPPER.readTree(wallets);
            if (!nodes.isArray()) return List.of();
            List<WalletInfo> result = new ArrayList<>();
            for (var node : nodes) {
                result.add(new WalletInfo(node.path("uuid").asString(""),
                        node.path("name").asString(""), node.path("icon").asString(""),
                        node.path("rdns").asString("")));
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /** Sets the wallet reverse-DNS identifier preferred when connecting. */
    public void setPreferredWallet(String rdns) {
        getElement().setProperty("preferredWallet", Objects.requireNonNull(rdns));
    }

    /** Returns the preferred wallet reverse-DNS identifier. */
    public String getPreferredWallet() {
        return getElement().getProperty("preferredWallet", "");
    }

    /** Returns the selected wallet reverse-DNS identifier. */
    @Synchronize(property = "selectedWallet", value = "web3-connected")
    public String getSelectedWallet() {
        return getElement().getProperty("selectedWallet", "");
    }

    /** Sets the connect-button label; the most recently called text or i18n setter controls the visible label. */
    public void setConnectText(String text) {
        i18n.setConnect(Objects.requireNonNull(text));
        getElement().setProperty("connectText", Objects.requireNonNull(text));
    }

    /** Sets the disconnect-button label; the most recently called text or i18n setter controls the visible label. */
    public void setDisconnectText(String text) {
        i18n.setDisconnect(Objects.requireNonNull(text));
        getElement().setProperty("disconnectText", Objects.requireNonNull(text));
    }

    /** Sets localized labels for the connector and wallet picker. */
    public void setI18n(Web3ConnectI18n value) {
        i18n = Objects.requireNonNull(value);
        getElement().setProperty("connectText", value.getConnect());
        getElement().setProperty("disconnectText", value.getDisconnect());
        getElement().setProperty("pickerTitle", value.getPickerTitle());
        getElement().setProperty("noWalletText", value.getNoWallets());
        getElement().setProperty("closeLabel", value.getClose());
        getElement().setProperty("developmentWalletWarning", value.getDevelopmentWalletWarning());
    }

    /** Returns this connector's localized labels. */
    public Web3ConnectI18n getI18n() { return i18n; }

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

    /** Prompts the user to connect the wallet identified by its reverse-DNS name. */
    public CompletableFuture<String> connect(String rdns) {
        return call("return this.connect($0)", Objects.requireNonNull(rdns));
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

    /** Returns wallet capabilities for the connected account and current chain. */
    public CompletableFuture<WalletCapabilities> getCapabilities() {
        if (!isConnected()) return CompletableFuture.failedFuture(new CallsException(4100, "No wallet connected"));
        return call("return this.getCapabilities()")
                .thenApply(WalletCapabilities::fromJson).exceptionallyCompose(Web3Connect::callsFailure);
    }

    /** Returns wallet capabilities for the requested chain IDs. */
    public CompletableFuture<WalletCapabilities> getCapabilities(List<Long> chainIds) {
        Objects.requireNonNull(chainIds, "chainIds is required");
        if (!isConnected()) return CompletableFuture.failedFuture(new CallsException(4100, "No wallet connected"));
        String idsJson = MAPPER.writeValueAsString(chainIds.stream().map(Web3Connect::toHexChainId).toList());
        return call("return this.getCapabilities(JSON.parse($0))", idsJson)
                .thenApply(WalletCapabilities::fromJson).exceptionallyCompose(Web3Connect::callsFailure);
    }

    /** Submits an EIP-5792 batch, optionally allowing sequential non-atomic fallback. */
    public CompletableFuture<CallsSubmission> sendCalls(CallsRequest request, FallbackPolicy fallbackPolicy) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(fallbackPolicy, "fallbackPolicy is required");
        if (!isConnected()) return CompletableFuture.failedFuture(new CallsException(4100, "No wallet connected"));
        return call("return this.sendCalls(JSON.parse($0))", request.toProviderJson())
                .thenApply(CallsSubmission::fromJson)
                .exceptionallyCompose(error -> {
                    Throwable cause = unwrap(error);
                    int code = cause instanceof Web3Exception walletError ? walletError.getCode()
                            : cause instanceof CallsException callsError ? callsError.code() : Integer.MIN_VALUE;
                    CallsException refusal = fallbackRefusal(cause, code, fallbackPolicy, request, getChainId(), getAccount());
                    if (refusal != null) return CompletableFuture.failedFuture(refusal);
                    return sendCallsSequentially(request, call -> {
                        Map<String, String> transaction = new java.util.LinkedHashMap<>();
                        if (call.to() != null) transaction.put("to", call.to());
                        if (call.data() != null) transaction.put("data", call.data());
                        if (call.value() != null) transaction.put("value", call.value());
                        return sendTransaction(transaction);
                    });
                });
    }

    /** Returns status for a previously submitted EIP-5792 batch. */
    public CompletableFuture<CallsStatus> getCallsStatus(String id) {
        Objects.requireNonNull(id, "id is required");
        if (!isConnected()) return CompletableFuture.failedFuture(new CallsException(4100, "No wallet connected"));
        return call("return this.getCallsStatus($0)", id).thenApply(CallsStatus::fromJson)
                .exceptionallyCompose(Web3Connect::callsFailure);
    }

    /** Asks the wallet to display status for a previously submitted batch. */
    public CompletableFuture<Void> showCallsStatus(String id) {
        Objects.requireNonNull(id, "id is required");
        if (!isConnected()) return CompletableFuture.failedFuture(new CallsException(4100, "No wallet connected"));
        return call("return this.showCallsStatus($0)", id).thenApply(ignored -> (Void) null)
                .exceptionallyCompose(Web3Connect::callsFailure);
    }

    static CompletableFuture<CallsSubmission> sendCallsSequentially(CallsRequest request,
            java.util.function.Function<Call, CompletableFuture<String>> sender) {
        CompletableFuture<CallsSubmission> result = CompletableFuture.completedFuture(
                new CallsSubmission(request.id(), true, new ArrayList<>(), new ArrayList<>()));
        for (Call call : request.calls()) {
            result = result.thenCompose(submission -> {
                if (!submission.errors().isEmpty()) return CompletableFuture.completedFuture(submission);
                return sender.apply(call).handle((hash, error) -> {
                    List<String> hashes = new ArrayList<>(submission.transactionHashes());
                    List<String> errors = new ArrayList<>(submission.errors());
                    if (error == null) hashes.add(hash);
                    else errors.add(errorMessage(unwrap(error)));
                    return new CallsSubmission(submission.id(), true, hashes, errors);
                });
            });
        }
        return result;
    }

    /**
     * 回退决策唯一入口：返回 null 表示可以顺序发送单笔交易；否则返回应抛出的异常。
     * 只有"方法不支持"（4200/-32601）+ 显式允许 + 非原子要求时才可能回退；
     * 且 eth_sendTransaction 发往钱包当前链与账户，与批次不一致时绝不回退。
     */
    static CallsException fallbackRefusal(Throwable cause, int code, FallbackPolicy policy, CallsRequest request,
            String walletChainIdHex, String walletAccount) {
        if (!shouldFallback(code, policy, request.atomicRequired())) return asCallsException(cause);
        return fallbackMismatch(request, walletChainIdHex, walletAccount);
    }

    static CallsException fallbackMismatch(CallsRequest request, String walletChainIdHex, String walletAccount) {
        String requiredCapability = request.firstRequiredCapability();
        if (requiredCapability != null) {
            // 逐笔 eth_sendTransaction 无法兑现任何能力，必需能力只能拒绝（EIP-5792 5700）
            return new CallsException(5700, "Capability '" + requiredCapability
                    + "' is required and cannot be honoured by individual transactions");
        }
        long walletChain;
        try {
            walletChain = Long.parseLong(walletChainIdHex.startsWith("0x") ? walletChainIdHex.substring(2) : walletChainIdHex,
                    walletChainIdHex.startsWith("0x") ? 16 : 10);
        } catch (RuntimeException exception) {
            return new CallsException(5710, "Wallet chain is unknown; cannot fall back to individual transactions");
        }
        if (walletChain != request.chainId()) {
            return new CallsException(5710, "Wallet is on chain " + walletChain + " but the batch targets chain "
                    + request.chainId() + "; switch chains before falling back to individual transactions");
        }
        if (request.from() != null && (walletAccount == null || !request.from().equalsIgnoreCase(walletAccount))) {
            return new CallsException(4100, "Batch sender is not the connected account");
        }
        return null;
    }

    static boolean shouldFallback(int code, FallbackPolicy policy, boolean atomicRequired) {
        return (code == 4200 || code == -32601) && policy == FallbackPolicy.ALLOW_NON_ATOMIC && !atomicRequired;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static <T> CompletableFuture<T> callsFailure(Throwable error) {
        return CompletableFuture.failedFuture(asCallsException(unwrap(error)));
    }

    private static CallsException asCallsException(Throwable error) {
        if (error instanceof CallsException callsError) return callsError;
        if (error instanceof Web3Exception web3Error) return new CallsException(web3Error.getCode(), web3Error.getMessage());
        return new CallsException(-32603, errorMessage(error));
    }

    private static String toHexChainId(long chainId) {
        if (chainId < 0) throw new IllegalArgumentException("chainId must not be negative");
        return "0x" + Long.toHexString(chainId);
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

    /** Fires when the set of discovered EIP-6963 wallets changes. */
    public Registration addWalletsChangedListener(ComponentEventListener<WalletsChangedEvent> listener) {
        return addListener(WalletsChangedEvent.class, listener);
    }

    private CompletableFuture<String> call(String expression, Serializable... params) {
        CompletableFuture<String> future = new CompletableFuture<>();
        trackPendingFuture(future);
        future.whenComplete((result, error) -> removePendingFuture(future));
        String invocation = expression.startsWith("return ") ? expression.substring(7) : expression;
        String wrappedExpression = "return Promise.resolve(" + invocation + ").catch(e => { throw new Error('"
                + ERROR_MARKER
                + "' + JSON.stringify(this._errorInfo(e))); })";
        // 以通用 JSON 节点读取结果再转成字符串：若直接按 String 反序列化，JS 返回对象时转换会在回调之前失败，
        // future 既不成功也不失败而永远挂起
        getElement().executeJs(wrappedExpression, params)
                .then(JsonNode.class, node -> future.complete(resultText(node)),
                        error -> future.completeExceptionally(parseWeb3Exception(error)));
        return future;
    }

    static String resultText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return node.isString() ? node.asString() : node.toString();
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
        if (serverWalletRequests != null) {
            serverWalletRequests.values().forEach(future -> future.completeExceptionally(
                    new ServerWallet.ServerWalletException(4900, "Server wallet disconnected")));
            serverWalletRequests.clear();
        }
        serverWallet = null;
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

    /** Event fired when EIP-6963 wallet metadata changes. */
    @DomEvent("web3-wallets-changed")
    public static class WalletsChangedEvent extends ComponentEvent<Web3Connect> {
        public WalletsChangedEvent(Web3Connect source, boolean fromClient) {
            super(source, fromClient);
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
