package com.wontlost.web3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.vaadin.flow.component.ComponentUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import tools.jackson.databind.ObjectMapper;

class Web3ConnectTest {

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
}
