package com.wontlost.web3.solana.wallet;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.siws.Base58;
import com.wontlost.web3.siws.SiwsChallenge;
import com.wontlost.web3.siws.SiwsMessage;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaRpcClient;
import com.wontlost.web3.solana.SolanaRpcException;
import com.wontlost.web3.solana.SolanaTransaction;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Connects a Solana wallet through the Wallet Standard and asks it to sign.
 * <p>
 * Wallets that support {@code solana:signIn} sign a Sign-In With Solana challenge directly; others sign the rendered
 * message with {@code solana:signMessage}. A {@link SolanaServerWallet} registered for the application is offered in
 * the wallet picker as a development wallet.
 */
@Tag("web3-solana-connect")
@JsModule("./web3-solana-connect.js")
@NpmPackage(value = "lit", version = "^3.0.0")
@NpmPackage(value = "@wallet-standard/app", version = "1.1.1")
public class SolanaConnect extends Component {
    static final String ERROR_MARKER = "SOLANA_WALLET_ERROR:";
    static final int MAX_SERVER_WALLET_PAYLOAD = 16 * 1024;
    /** Warning shown next to a server wallet in the picker unless replaced. */
    public static final String DEFAULT_DEVELOPMENT_WALLET_WARNING = "Development wallet — never use with real assets";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(SolanaConnect.class);
    private static final java.util.concurrent.atomic.AtomicBoolean PRODUCTION_WALLET_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private String account;
    private String walletName;
    private SolanaCluster cluster;
    private transient List<CompletableFuture<String>> pendingFutures;

    /** Creates a connector rendered as a connect/disconnect button. */
    public SolanaConnect() {
        this(false);
    }

    /**
     * Creates a connector.
     *
     * @param hideButton when {@code true}, no button is rendered and the component is driven through the Java API
     */
    public SolanaConnect(boolean hideButton) {
        getElement().setProperty("hideButton", hideButton);
        getElement().setProperty("developmentWalletWarning", DEFAULT_DEVELOPMENT_WALLET_WARNING);
        getElement().addEventListener("solana-account-changed", event -> {
            String next = event.getEventData().path("event.detail.account").asString("");
            String wallet = event.getEventData().path("event.detail.wallet").asString("");
            applyAccount(next.isEmpty() ? null : next, wallet.isEmpty() ? null : wallet);
        }).addEventData("event.detail.account").addEventData("event.detail.wallet");
        getElement().addEventListener("solana-wallet-error", event -> {
            JsonNode data = event.getEventData();
            fireEvent(new SolanaWalletErrorEvent(this, data.path("event.detail.code").asInt(-1),
                    data.path("event.detail.message").asString(""), data.path("event.detail.userRejected").asBoolean()));
        }).addEventData("event.detail.code").addEventData("event.detail.message").addEventData("event.detail.userRejected");
    }

    /** Restricts the picker to wallets and accounts on {@code cluster}; {@code null} accepts any Solana cluster. */
    public SolanaConnect setCluster(SolanaCluster cluster) {
        this.cluster = cluster;
        getElement().setProperty("chain", cluster == null ? "" : chain(cluster));
        return this;
    }

    /** The cluster the picker is restricted to, or {@code null}. */
    public SolanaCluster getCluster() {
        return cluster;
    }

    /** Sets the connect button label. */
    public SolanaConnect setConnectText(String text) {
        getElement().setProperty("connectText", Objects.requireNonNull(text));
        return this;
    }

    /** Sets the disconnect button label. */
    public SolanaConnect setDisconnectText(String text) {
        getElement().setProperty("disconnectText", Objects.requireNonNull(text));
        return this;
    }

    /** Sets the warning shown next to the development wallet in the picker. */
    public SolanaConnect setDevelopmentWalletWarning(String text) {
        getElement().setProperty("developmentWalletWarning", Objects.requireNonNull(text));
        return this;
    }

    /** Connects directly to the wallet with this Wallet Standard name when it is available, skipping the picker. */
    public SolanaConnect setPreferredWallet(String name) {
        getElement().setProperty("preferredWallet", name == null ? "" : name);
        return this;
    }

    /** The connected base58 address, or {@code null}. */
    public String getAccount() {
        return account;
    }

    /** The connected wallet's Wallet Standard name, or {@code null}. */
    public String getWalletName() {
        return walletName;
    }

    /** Whether a wallet account is connected. */
    public boolean isConnected() {
        return account != null;
    }

    /** Opens the wallet picker (or the preferred wallet) and completes with the connected base58 address. */
    public CompletableFuture<String> connect() {
        return call("this.connect()").thenApply(this::applyConnected);
    }

    /** Forgets the connected account and asks the wallet to disconnect when it supports it. */
    public void disconnect() {
        applyAccount(null, null);
        getElement().callJsFunction("disconnect");
    }

    /**
     * Asks the connected wallet to sign {@code challenge} for {@code address}. The future completes with the signer's
     * public key and the exact bytes it signed, ready for {@link com.wontlost.web3.siws.SiwsVerifier#verify}.
     */
    public CompletableFuture<SignedSignIn> signIn(SiwsChallenge challenge, String address) {
        Objects.requireNonNull(challenge, "challenge");
        String message = challenge.toMessage(Objects.requireNonNull(address, "address")).toMessage();
        return call("this.signIn($0, $1)", signInInput(challenge).toString(), message)
                .thenApply(SolanaConnect::parseSignedSignIn);
    }

    /** Signs arbitrary bytes with {@code solana:signMessage} and completes with the 64-byte signature. */
    public CompletableFuture<byte[]> signMessage(byte[] message) {
        String encoded = Base64.getEncoder().encodeToString(Objects.requireNonNull(message, "message"));
        return call("this.signMessage($0)", encoded).thenApply(signature -> Base64.getDecoder().decode(signature));
    }

    /**
     * Asks the connected wallet to sign and send {@code transaction} (wire format, as from
     * {@link SolanaTransaction#unsignedWire()}) and completes with the base58 transaction signature.
     * <p>
     * Wallets with {@code solana:signAndSendTransaction} submit it themselves; the signature is the wallet's report,
     * so verify the transaction on chain (for example with {@link SolanaTransactionStatus}) before crediting anything.
     * Wallets that only support {@code solana:signTransaction} return the signed transaction. It must keep the
     * original fee payer and blockhash and carry a valid signature from the connected account; it is then sent
     * through {@code rpc} on a background thread (pass {@code null} to fail instead). The future completes on the
     * UI thread when the component is attached, which needs {@code @Push} or polling to reach the browser promptly.
     */
    public CompletableFuture<String> signAndSendTransaction(byte[] transaction, SolanaRpcClient rpc) {
        byte[] original = Objects.requireNonNull(transaction, "transaction").clone();
        String encoded = Base64.getEncoder().encodeToString(original);
        String expectedSigner = account;
        UI ui = UI.getCurrent() != null ? UI.getCurrent() : getUI().orElse(null);
        return call("this.signAndSendTransaction($0)", encoded).thenCompose(json -> {
            WalletResult result = parseWalletResult(json);
            if (result.signature() != null) return CompletableFuture.completedFuture(result.signature());
            byte[] signed = verifySigned(original, result.signedTransaction(), expectedSigner);
            if (rpc == null) {
                throw new SolanaWalletException(4200, "The wallet can only sign; no RPC client was given to send", false);
            }
            // 发送是阻塞 RPC：放到后台线程，结果回到 UI 线程完成；登记为在途，分离时会被取消而不是永远挂起
            CompletableFuture<String> sent = new CompletableFuture<>();
            trackPending(sent);
            CompletableFuture.supplyAsync(() -> sendSigned(rpc, signed)).whenComplete((signature, error) -> deliver(ui,
                    sent, () -> {
                        if (error == null) sent.complete(signature);
                        else sent.completeExceptionally(error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null ? error.getCause() : error);
                    }));
            return sent;
        });
    }

    /** 钱包返回：已发送时为 base58 签名，仅签名时为签名后的交易字节。 */
    record WalletResult(String signature, byte[] signedTransaction) {
    }

    static WalletResult parseWalletResult(String json) {
        try {
            JsonNode result = MAPPER.readTree(json);
            Base64.Decoder decoder = Base64.getDecoder();
            if (result.path("signature").isString()) {
                byte[] signature = decoder.decode(result.path("signature").asString());
                if (signature.length != 64) throw new IllegalArgumentException("signature length");
                return new WalletResult(Base58.encode(signature), null);
            }
            if (!result.path("signedTransaction").isString()) throw new IllegalArgumentException("no result");
            return new WalletResult(null, decoder.decode(result.path("signedTransaction").asString()));
        } catch (RuntimeException exception) {
            throw new SolanaWalletException(-32603, "The wallet returned an invalid result", false);
        }
    }

    /**
     * 校验钱包签好的交易：手续费支付者与区块哈希必须与服务端构建的一致（否则过期判定失效），
     * 且已连接账户的签名对消息有效。钱包可以增加指令（例如优先费），这里不比较指令。
     */
    static byte[] verifySigned(byte[] original, byte[] signed, String expectedSigner) {
        try {
            List<String> signers = SolanaTransaction.signersOf(signed);
            if (!signers.getFirst().equals(SolanaTransaction.signersOf(original).getFirst())) {
                throw new SolanaWalletException(-32603, "The wallet changed the transaction's fee payer", false);
            }
            if (!SolanaTransaction.recentBlockhashOf(signed).equals(SolanaTransaction.recentBlockhashOf(original))) {
                throw new SolanaWalletException(-32603, "The wallet changed the transaction's blockhash; build it again",
                        false);
            }
            String signer = expectedSigner == null ? signers.getFirst() : expectedSigner;
            int slot = signers.indexOf(signer);
            if (slot < 0) throw new SolanaWalletException(-32603, "The connected account did not sign", false);
            int signaturesStart = SolanaTransaction.messageOffset(signed) - signers.size() * 64;
            byte[] signature = java.util.Arrays.copyOfRange(signed, signaturesStart + slot * 64,
                    signaturesStart + slot * 64 + 64);
            SolanaTransaction.withSignature(signed, signer, signature);
            return signed;
        } catch (IllegalArgumentException exception) {
            throw new SolanaWalletException(-32603, "The wallet returned an invalid transaction", false);
        }
    }

    /** 经应用的 RPC 发送；节点拒绝统一映射为钱包错误，不透传节点文本。 */
    static String sendSigned(SolanaRpcClient rpc, byte[] signed) {
        try {
            return rpc.sendTransaction(signed);
        } catch (SolanaRpcException exception) {
            throw new SolanaWalletException(-32603, "The transaction was rejected by the node", false);
        } catch (IllegalArgumentException exception) {
            throw new SolanaWalletException(-32603, "The wallet returned an invalid transaction", false);
        }
    }

    /**
     * 持有会话锁时直接执行，否则经 ui.access 排队；没有 UI（例如测试）时直接执行。
     * UI 已分离时 ui.access 会抛出：此时以异常完成 future，避免调用方永远等待。
     */
    static void deliver(UI ui, CompletableFuture<?> future, Runnable command) {
        if (ui == null || ui.getSession() == null || ui.getSession().hasLock()) {
            command.run();
            return;
        }
        try {
            ui.access(command::run);
        } catch (com.vaadin.flow.component.UIDetachedException exception) {
            future.completeExceptionally(new SolanaWalletException(-1, "Component detached; wallet request cancelled",
                    false));
        }
    }

    /** Handles a signing request from the development wallet registered in the browser. */
    @ClientCallable
    public void solanaServerWalletRequest(String requestId, String method, String payloadJson) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) return;
        ServerWalletResponse response = respond(currentServerWallet(), method, payloadJson);
        if (response.result() != null) {
            getElement().executeJs("this._resolveServerWalletRequest($0, $1)", requestId, response.result());
        } else {
            getElement().executeJs("this._rejectServerWalletRequest($0, $1, $2)", requestId, response.code(),
                    response.message());
        }
    }

    /** Fired when the connected account changes, including connect and disconnect. */
    public Registration addAccountChangedListener(ComponentEventListener<AccountChangedEvent> listener) {
        return addListener(AccountChangedEvent.class, listener);
    }

    /** Fired on wallet errors, such as a rejected request or a missing wallet. */
    public Registration addWalletErrorListener(ComponentEventListener<SolanaWalletErrorEvent> listener) {
        return addListener(SolanaWalletErrorEvent.class, listener);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        SolanaServerWallet wallet = currentServerWallet();
        getElement().setProperty("serverWallet", wallet == null ? "" : serverWalletInfo(wallet).toString());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        cancelPending();
        super.onDetach(detachEvent);
    }

    void cancelPending() {
        List<CompletableFuture<String>> pending;
        synchronized (this) {
            pending = pendingFutures;
            pendingFutures = null;
        }
        if (pending != null) {
            SolanaWalletException error = new SolanaWalletException(-1, "Component detached; wallet request cancelled",
                    false);
            pending.forEach(future -> future.completeExceptionally(error));
        }
    }

    /**
     * 每次按当前服务查找服务端钱包（反序列化后无需重新挂载）。生产模式下即使绕过注册检查放入了钱包，
     * 也不向浏览器暴露，只记录错误。
     */
    private static SolanaServerWallet currentServerWallet() {
        VaadinService service = VaadinService.getCurrent();
        SolanaServerWallet wallet = SolanaServerWallet.find(service == null ? null : service.getContext());
        return exposableWallet(wallet, service != null && service.getDeploymentConfiguration().isProductionMode());
    }

    static SolanaServerWallet exposableWallet(SolanaServerWallet wallet, boolean productionMode) {
        if (wallet == null || !productionMode) return wallet;
        // 每次挂载与请求都会查找：只记录一次，避免刷屏
        if (PRODUCTION_WALLET_LOGGED.compareAndSet(false, true)) LOGGER.error("A SolanaServerWallet ({}) is registered in Vaadin production mode; it is not offered to browsers",
                wallet.getClass().getName());
        return null;
    }

    static String chain(SolanaCluster cluster) {
        return "solana:" + cluster.chainId();
    }

    static ObjectNode serverWalletInfo(SolanaServerWallet wallet) {
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", wallet.name());
        info.put("address", wallet.address());
        info.put("publicKey", Base64.getEncoder().encodeToString(Base58.decode(wallet.address(), 32)));
        info.put("chain", chain(wallet.cluster()));
        info.put("canSend", wallet.canSendTransactions());
        return info;
    }

    /** solana:signIn 的输入：与服务端挑战逐字段一致；空字段省略，避免钱包把 null 渲染进消息。 */
    static ObjectNode signInInput(SiwsChallenge challenge) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("domain", challenge.domain());
        if (challenge.statement() != null) input.put("statement", challenge.statement());
        input.put("uri", challenge.uri());
        input.put("version", "1");
        input.put("chainId", challenge.cluster().chainId());
        input.put("nonce", challenge.nonce());
        input.put("issuedAt", challenge.issuedAt());
        input.put("expirationTime", challenge.expirationTime());
        if (!challenge.resources().isEmpty()) input.set("resources", MAPPER.valueToTree(challenge.resources()));
        return input;
    }

    static SignedSignIn parseSignedSignIn(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] publicKey = decoder.decode(node.path("publicKey").asString());
            byte[] signedMessage = decoder.decode(node.path("signedMessage").asString());
            byte[] signature = decoder.decode(node.path("signature").asString());
            // Ed25519：公钥 32 字节、签名 64 字节；格式不符直接视为钱包返回无效结果
            if (publicKey.length != 32 || signature.length != 64 || signedMessage.length == 0) {
                throw new IllegalArgumentException("invalid sign-in result");
            }
            return new SignedSignIn(Base58.encode(publicKey), signedMessage, signature);
        } catch (RuntimeException exception) {
            throw new SolanaWalletException(-32603, "The wallet returned an invalid sign-in result", false);
        }
    }

    /** 对浏览器的答复：成功时 result 为 JSON，失败时为 Wallet Standard 错误码与消息。 */
    record ServerWalletResponse(String result, int code, String message) {
    }

    /** 总是给出答复：自定义钱包的意外异常也映射为 -32603，否则浏览器端的钱包请求永远不结束。 */
    static ServerWalletResponse respond(SolanaServerWallet wallet, String method, String payloadJson) {
        try {
            return new ServerWalletResponse(handleServerWalletRequest(wallet, method, payloadJson), 0, null);
        } catch (SolanaWalletException exception) {
            return new ServerWalletResponse(null, exception.getCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.warn("Solana server wallet request failed", exception);
            return new ServerWalletResponse(null, -32603, "Server wallet request failed");
        }
    }

    /**
     * 开发钱包请求：signMessage 签名任意字节；signIn 用钱包自身地址渲染 SIWS 消息后签名，
     * 与真实钱包的 solana:signIn 行为一致。返回 JSON 结果，失败抛出带 Wallet Standard 错误码的异常。
     */
    static String handleServerWalletRequest(SolanaServerWallet wallet, String method, String payloadJson) {
        if (wallet == null) throw new SolanaWalletException(4100, "Server wallet is unavailable", false);
        if (payloadJson == null || payloadJson.length() > MAX_SERVER_WALLET_PAYLOAD) {
            throw new SolanaWalletException(-32602, "Invalid server wallet parameters", false);
        }
        JsonNode payload;
        try {
            payload = MAPPER.readTree(payloadJson);
        } catch (RuntimeException exception) {
            throw new SolanaWalletException(-32602, "Invalid server wallet parameters", false);
        }
        if (!payload.isObject()) throw new SolanaWalletException(-32602, "Invalid server wallet parameters", false);
        return switch (method == null ? "" : method) {
            case "signMessage" -> {
                byte[] message = decode(payload.path("message"));
                ObjectNode result = MAPPER.createObjectNode();
                result.put("signature", Base64.getEncoder().encodeToString(wallet.signMessage(message)));
                yield result.toString();
            }
            case "signIn" -> {
                byte[] message = renderSignIn(wallet, payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ObjectNode result = MAPPER.createObjectNode();
                result.put("signedMessage", Base64.getEncoder().encodeToString(message));
                result.put("signature", Base64.getEncoder().encodeToString(wallet.signMessage(message)));
                yield result.toString();
            }
            case "signTransaction" -> {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("signedTransaction", Base64.getEncoder().encodeToString(signTransaction(wallet, payload)));
                yield result.toString();
            }
            case "signAndSendTransaction" -> {
                String signature = send(wallet, signTransaction(wallet, payload));
                ObjectNode result = MAPPER.createObjectNode();
                result.put("signature", Base64.getEncoder().encodeToString(Base58.decode(signature, 64)));
                yield result.toString();
            }
            default -> throw new SolanaWalletException(4200, "Server wallet method is not allowed", false);
        };
    }

    /** 用钱包密钥签名浏览器传来的交易：钱包必须是交易的签名者，签名放入其槽位（其余槽位保留）。 */
    private static byte[] signTransaction(SolanaServerWallet wallet, JsonNode payload) {
        byte[] transaction = decode(payload.path("transaction"));
        try {
            byte[] signature = wallet.signMessage(SolanaTransaction.messageOf(transaction));
            return SolanaTransaction.withSignature(transaction, wallet.address(), signature);
        } catch (IllegalArgumentException exception) {
            throw new SolanaWalletException(-32602, "Invalid transaction for this wallet", false);
        }
    }

    // 节点拒绝（例如预检失败）以通用消息返回浏览器，不透传节点文本
    private static String send(SolanaServerWallet wallet, byte[] signed) {
        try {
            return wallet.sendTransaction(signed);
        } catch (SolanaRpcException exception) {
            throw new SolanaWalletException(-32603, "The transaction was rejected by the node", false);
        }
    }

    private static String renderSignIn(SolanaServerWallet wallet, JsonNode input) {
        String address = text(input, "address");
        if (address != null && !address.equals(wallet.address())) {
            throw new SolanaWalletException(4100, "The requested account is not controlled by this wallet", false);
        }
        List<String> resources = new ArrayList<>();
        input.path("resources").forEach(resource -> resources.add(resource.asString()));
        try {
            return new SiwsMessage(text(input, "domain"), wallet.address(), text(input, "statement"), text(input, "uri"),
                    text(input, "version"), text(input, "chainId"), text(input, "nonce"), text(input, "issuedAt"),
                    text(input, "expirationTime"), text(input, "notBefore"), text(input, "requestId"), resources)
                    .toMessage();
        } catch (RuntimeException exception) {
            throw new SolanaWalletException(-32602, "Invalid sign-in input", false);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static byte[] decode(JsonNode value) {
        try {
            if (!value.isString()) throw new IllegalArgumentException();
            return Base64.getDecoder().decode(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new SolanaWalletException(-32602, "Invalid server wallet parameters", false);
        }
    }

    /** 应用 JS connect() 的结果 {address, wallet}；与此前到达的账户变化事件一致时不重复触发事件。 */
    String applyConnected(String json) {
        JsonNode result = MAPPER.readTree(json);
        String address = result.path("address").asString();
        applyAccount(address, result.path("wallet").asString(null));
        return address;
    }

    void applyAccount(String nextAccount, String nextWallet) {
        if (Objects.equals(account, nextAccount) && Objects.equals(walletName, nextWallet)) return;
        account = nextAccount;
        walletName = nextWallet;
        fireEvent(new AccountChangedEvent(this, nextAccount, nextWallet));
    }

    /** 登记在途 future：完成时移除；组件分离时统一以异常完成。 */
    void trackPending(CompletableFuture<String> future) {
        synchronized (this) {
            if (pendingFutures == null) pendingFutures = new ArrayList<>();
            pendingFutures.add(future);
        }
        future.whenComplete((result, error) -> {
            synchronized (this) {
                if (pendingFutures != null) pendingFutures.remove(future);
            }
        });
    }

    private CompletableFuture<String> call(String expression, Serializable... params) {
        CompletableFuture<String> future = new CompletableFuture<>();
        trackPending(future);
        String invocation = "return Promise.resolve(" + expression + ").catch(e => { throw new Error('" + ERROR_MARKER
                + "' + JSON.stringify(this._errorInfo(e))); })";
        // 以通用 JSON 节点接收：JS 返回对象时按 String 反序列化会在回调前失败，future 永远挂起
        getElement().executeJs(invocation, params).then(JsonNode.class,
                node -> future.complete(node == null || node.isNull() ? null : node.isString() ? node.asString()
                        : node.toString()),
                error -> future.completeExceptionally(parseError(error)));
        return future;
    }

    static SolanaWalletException parseError(String error) {
        String message = error == null ? "" : error;
        int marker = message.indexOf(ERROR_MARKER);
        if (marker < 0) return new SolanaWalletException(-1, message, false);
        try {
            JsonNode info = MAPPER.readTree(message.substring(marker + ERROR_MARKER.length()));
            return new SolanaWalletException(info.path("code").asInt(-1), info.path("message").asString(message),
                    info.path("userRejected").asBoolean());
        } catch (RuntimeException exception) {
            return new SolanaWalletException(-1, message, false);
        }
    }

    /** The result of a wallet sign-in: the signer's base58 public key, the signed bytes and the signature. */
    public record SignedSignIn(String publicKey, byte[] signedMessage, byte[] signature) implements Serializable {
    }

    /** A wallet failure carrying the wallet's error code. */
    public static final class SolanaWalletException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int code;
        private final boolean userRejected;

        public SolanaWalletException(int code, String message, boolean userRejected) {
            super(message);
            this.code = code;
            this.userRejected = userRejected;
        }

        /** The wallet error code, for example 4001 for a rejected request, or -1 when unknown. */
        public int getCode() {
            return code;
        }

        /** Whether the user rejected the request in the wallet. */
        public boolean isUserRejected() {
            return userRejected;
        }
    }

    /** Fired when the connected account changes. */
    public static class AccountChangedEvent extends ComponentEvent<SolanaConnect> {
        private final String account;
        private final String walletName;

        public AccountChangedEvent(SolanaConnect source, String account, String walletName) {
            super(source, true);
            this.account = account;
            this.walletName = walletName;
        }

        /** The new base58 address, or {@code null} after a disconnect. */
        public String getAccount() {
            return account;
        }

        /** The wallet's name, or {@code null} after a disconnect. */
        public String getWalletName() {
            return walletName;
        }
    }

    /** Fired on a wallet error. */
    public static class SolanaWalletErrorEvent extends ComponentEvent<SolanaConnect> {
        private final int code;
        private final String message;
        private final boolean userRejected;

        public SolanaWalletErrorEvent(SolanaConnect source, int code, String message, boolean userRejected) {
            super(source, true);
            this.code = code;
            this.message = message;
            this.userRejected = userRejected;
        }

        /** The wallet error code, or -1 when unknown. */
        public int getCode() {
            return code;
        }

        /** The wallet's error message. */
        public String getMessage() {
            return message;
        }

        /** Whether the user rejected the request. */
        public boolean isUserRejected() {
            return userRejected;
        }
    }
}
