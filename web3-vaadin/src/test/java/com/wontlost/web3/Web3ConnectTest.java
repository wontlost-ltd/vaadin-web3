package com.wontlost.web3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.vaadin.flow.component.ComponentUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import tools.jackson.databind.ObjectMapper;

class Web3ConnectTest {

    @Test void appliesLocalizedConnectorAndPickerLabels() {
        Web3Connect component = new Web3Connect();
        component.setI18n(new Web3ConnectI18n().setConnect("Conectar")
                .setPickerTitle("Elegir cartera").setNoWallets("Sin carteras"));
        assertEquals("Conectar", component.getElement().getProperty("connectText"));
        assertEquals("Elegir cartera", component.getElement().getProperty("pickerTitle"));
        assertEquals("Sin carteras", component.getElement().getProperty("noWalletText"));
    }

    @Test
    void parsesWalletMetadataAndReturnsImmutableList() {
        Web3Connect component = new Web3Connect();
        component.getElement().setProperty("wallets", "[{\"uuid\":\"u1\",\"name\":\"MetaMask\","
                + "\"icon\":\"data:image/png;base64,AA==\",\"rdns\":\"io.metamask\"}]");

        assertEquals(java.util.List.of(new WalletInfo("u1", "MetaMask", "data:image/png;base64,AA==", "io.metamask")),
                component.getWallets());
        assertThrows(UnsupportedOperationException.class, () -> component.getWallets().clear());
    }

    @Test
    void returnsEmptyWalletListWhenPropertyIsMissingOrInvalid() {
        Web3Connect component = new Web3Connect();
        assertTrue(component.getWallets().isEmpty());
        component.getElement().setProperty("wallets", "not-json");
        assertTrue(component.getWallets().isEmpty());
    }

    @Test
    void walletInfoIsSerializable() throws Exception {
        WalletInfo info = new WalletInfo("u1", "MetaMask", "data:image/png;base64,AA==", "io.metamask");
        String json = new ObjectMapper().writeValueAsString(info);
        assertTrue(json.contains("io.metamask"));
    }

    @Test
    void parsesMarkedErrorAfterPrefix() {
        Web3Connect.Web3Exception error = Web3Connect.parseWeb3Exception(
                "Error: WEB3_ERROR:{\"code\":4001,\"message\":\"User rejected\"}");

        assertEquals(4001, error.getCode());
        assertEquals("User rejected", error.getMessage());
    }

    @Test
    void parsesNormalizedJsonError() {
        Web3Connect.Web3Exception error = Web3Connect.parseWeb3Exception(
                "WEB3_ERROR:{\"code\":4902,\"message\":\"Unknown chain\"}");

        assertEquals(4902, error.getCode());
        assertEquals("Unknown chain", error.getMessage());
    }

    @Test
    void leavesUnmarkedErrorAsRawMessage() {
        Web3Connect.Web3Exception error = Web3Connect.parseWeb3Exception("wallet unavailable");

        assertEquals(-1, error.getCode());
        assertEquals("wallet unavailable", error.getMessage());
    }

    @Test
    void identifiesUserRejection() {
        assertTrue(new Web3Connect.Web3Exception(4001, "rejected").isUserRejected());
        assertFalse(new Web3Connect.Web3Exception("other").isUserRejected());
    }

    @Test
    void detachCompletesPendingFutureExceptionally() {
        Web3Connect component = new Web3Connect();
        CompletableFuture<String> future = new CompletableFuture<>();
        component.trackPendingFuture(future);

        // 测试类路径未提供 Servlet API，UI 无法构造；使用 Vaadin 的组件生命周期工具触发同一 detach 回调。
        ComponentUtil.onComponentAttach(component, false);
        ComponentUtil.onComponentDetach(component);

        CompletionException error = assertThrows(CompletionException.class, future::join);
        assertTrue(error.getCause() instanceof Web3Connect.Web3Exception);
        Web3Connect.Web3Exception web3Error = (Web3Connect.Web3Exception) error.getCause();
        assertEquals(-1, web3Error.getCode());
        assertTrue(web3Error.getMessage().contains("detached"));

        assertDoesNotThrow(() -> ComponentUtil.onComponentDetach(component));
    }

    @Test
    void sendTransactionRequiresRecipient() {
        Web3Connect component = new Web3Connect();

        assertThrows(NullPointerException.class,
                () -> component.sendTransaction(null, null, null));
    }

    @Test
    void doesNotExposeWalletWhenNoVaadinServiceIsAvailable() {
        Web3Connect component = new Web3Connect();
        ComponentUtil.onComponentAttach(component, false);
        assertEquals("", component.getElement().getProperty("serverWallet", ""));
        ComponentUtil.onComponentDetach(component);
    }

    @Test
    void forwardsWhitelistedRequestAndPreservesWalletErrorCode() {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedParams = new AtomicReference<>();
        ServerWallet wallet = wallet((method, params) -> {
            receivedMethod.set(method);
            receivedParams.set(params);
            return CompletableFuture.failedFuture(new ServerWallet.ServerWalletException(4001, "Rejected"));
        });
        CompletableFuture<String> result = Web3Connect.requestServerWallet(wallet, "personal_sign", "[\"0x01\"]");
        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertEquals("personal_sign", receivedMethod.get());
        assertEquals("[\"0x01\"]", receivedParams.get());
        assertEquals(4001, Web3Connect.serverWalletErrorCode(failure.getCause()));
    }

    @Test
    void rejectsUnregisteredWalletNonWhitelistedMethodAndOversizedParams() {
        assertEquals(4100, Web3Connect.validateServerWalletRequest(null, "personal_sign", "[]").getCode());
        ServerWallet wallet = wallet((method, params) -> CompletableFuture.completedFuture("null"));
        assertEquals(4200, Web3Connect.validateServerWalletRequest(wallet, "eth_accounts", "[]").getCode());
        assertEquals(-32600, Web3Connect.validateServerWalletRequest(wallet, "personal_sign", "x".repeat(65537))
                .getCode());
    }

    private static ServerWallet wallet(RequestHandler requestHandler) {
        return new ServerWallet() {
            @Override public String name() { return "Development wallet"; }
            @Override public String rdns() { return "com.example.development"; }
            @Override public List<String> accounts() { return List.of("0xabc"); }
            @Override public String chainId() { return "0x7a69"; }
            @Override public CompletableFuture<String> request(String method, String paramsJson) {
                return requestHandler.request(method, paramsJson);
            }
        };
    }

    @FunctionalInterface
    private interface RequestHandler {
        CompletableFuture<String> request(String method, String paramsJson);
    }

    @Test void serverWalletRepliesRunInlineWithLockAndAreQueuedWithoutIt() {
        java.util.List<String> queued = new java.util.ArrayList<>();
        boolean[] locked = { true };
        com.vaadin.flow.server.VaadinSession session = new com.vaadin.flow.server.VaadinSession(null) {
            @Override public boolean hasLock() { return locked[0]; }
        };
        com.vaadin.flow.component.UI ui = new com.vaadin.flow.component.UI() {
            @Override public com.vaadin.flow.server.VaadinSession getSession() { return session; }
            @Override public java.util.concurrent.Future<Void> access(com.vaadin.flow.server.Command command) {
                queued.add("queued");
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        java.util.List<String> ran = new java.util.ArrayList<>();

        Web3Connect.deliver(ui, () -> ran.add("inline"));
        assertEquals(java.util.List.of("inline"), ran);

        locked[0] = false;
        Web3Connect.deliver(ui, () -> ran.add("off-thread"));
        assertEquals(java.util.List.of("inline"), ran, "must not touch the component without the session lock");
        assertEquals(java.util.List.of("queued"), queued);

        Web3Connect.deliver(null, () -> ran.add("no-ui"));
        assertEquals(java.util.List.of("inline"), ran);
    }
}
