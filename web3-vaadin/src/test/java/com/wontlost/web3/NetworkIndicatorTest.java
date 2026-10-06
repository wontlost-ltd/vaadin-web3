package com.wontlost.web3;

import java.util.concurrent.CompletableFuture;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkIndicatorTest {
    @Test
    void showsDisconnectedConnectedAndWrongChainStates() {
        FakeWallet wallet = new FakeWallet();
        NetworkIndicator indicator = new NetworkIndicator(wallet);
        assertEquals("Not connected", status(indicator));

        wallet.setConnectedChain("0x1");
        indicator.setExpectedChainId(1);
        assertTrue(status(indicator).contains("Ethereum"));
        assertFalse(status(indicator).contains("Wrong network"));

        wallet.chainId = "0x89";
        wallet.getElement().setProperty("chainId", "0x89");
        indicator.setExpectedChainId(1);
        assertTrue(status(indicator).contains("Wrong network"));
        assertTrue(status(indicator).contains("Polygon"));
        assertTrue(indicator.getChildren().filter(Button.class::isInstance).map(Button.class::cast)
                .findFirst().orElseThrow().isVisible());
    }

    @Test
    void switchButtonCallsWalletAndRemainsDisabledUntilFutureCompletes() {
        FakeWallet wallet = connectedWallet("0x89");
        CompletableFuture<String> pending = new CompletableFuture<>();
        wallet.switchResult = pending;
        NetworkIndicator indicator = new NetworkIndicator(wallet);
        indicator.setExpectedChainId(1);
        Button button = button(indicator);
        button.click();

        assertEquals(Chains.ETHEREUM_MAINNET, wallet.requestedChain);
        assertFalse(button.isEnabled());
        pending.complete("0x1");
        assertTrue(button.isEnabled());
        assertTrue(status(indicator).contains("Ethereum"));
    }

    @Test
    void reportsUserRejectionAndMissingChain() {
        FakeWallet wallet = connectedWallet("0x89");
        NetworkIndicator indicator = new NetworkIndicator(wallet);
        indicator.setExpectedChainId(1);
        int[] failureCode = { 0 };
        indicator.addNetworkSwitchFailedListener(event -> failureCode[0] = event.getCode());
        wallet.switchResult = CompletableFuture.failedFuture(new Web3Connect.Web3Exception(4001, "Rejected"));
        button(indicator).click();
        assertTrue(status(indicator).contains("Could not switch"));
        assertEquals(4001, failureCode[0]);

        wallet.switchResult = CompletableFuture.failedFuture(new Web3Connect.Web3Exception(4902, "Unknown chain"));
        button(indicator).click();
        assertTrue(status(indicator).contains("not available"));
    }

    @Test
    void firesNetworkEventsAndRemovesWalletListenerOnDetach() {
        FakeWallet wallet = connectedWallet("0x89");
        NetworkIndicator indicator = new NetworkIndicator(wallet);
        indicator.setExpectedChainId(1);
        int[] changes = { 0 };
        indicator.addNetworkChangedListener(event -> {
            assertEquals(137, event.getChainId());
            assertFalse(event.isExpectedMatches());
            changes[0]++;
        });
        ComponentUtil.onComponentAttach(indicator, false);
        changes[0] = 0;
        ComponentUtil.fireEvent(wallet, new Web3Connect.ChainChangedEvent(wallet, true, "0x89"));
        assertEquals(1, changes[0]);
        ComponentUtil.onComponentDetach(indicator);
        ComponentUtil.fireEvent(wallet, new Web3Connect.ChainChangedEvent(wallet, true, "0x1"));
        assertEquals(1, changes[0]);
    }

    @Test
    void appliesLocalizedMessagesAndAccessibleStatusAttributes() {
        FakeWallet wallet = connectedWallet("0x89");
        NetworkIndicator indicator = new NetworkIndicator(wallet);
        indicator.setI18n(new NetworkIndicatorI18n().setWrongNetwork("Expected {1}, got {0}")
                .setSwitchTo("Go to {0}"));
        indicator.setExpectedChainId(1);
        assertTrue(status(indicator).contains("Expected Ethereum, got Polygon"));
        Span status = indicator.getChildren().filter(Span.class::isInstance).map(Span.class::cast)
                .findFirst().orElseThrow();
        assertEquals("status", status.getElement().getAttribute("role"));
        assertEquals("polite", status.getElement().getAttribute("aria-live"));
        assertEquals("Go to Ethereum", button(indicator).getElement().getAttribute("aria-label"));
    }

    private static FakeWallet connectedWallet(String chainId) {
        FakeWallet wallet = new FakeWallet();
        wallet.setConnectedChain(chainId);
        return wallet;
    }

    private static String status(NetworkIndicator indicator) {
        return indicator.getChildren().filter(Span.class::isInstance).map(Span.class::cast)
                .findFirst().orElseThrow().getText();
    }

    private static Button button(NetworkIndicator indicator) {
        return indicator.getChildren().filter(Button.class::isInstance).map(Button.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void addChainParametersAreForwardedExactlyAndOnlyWhenConfigured() {
        FakeWallet plain = connectedWallet("0x89");
        NetworkIndicator withoutParams = new NetworkIndicator(plain);
        withoutParams.setExpectedChainId(84532);
        button(withoutParams).click();
        assertEquals(null, plain.addChainArgs, "without add-chain metadata the plain switch must be used");

        FakeWallet wallet = connectedWallet("0x89");
        NetworkIndicator indicator = new NetworkIndicator(wallet);
        indicator.setExpectedChainId(84532);
        indicator.setAddChainParameters("Base Sepolia", "https://sepolia.base.org", "ETH");
        button(indicator).click();
        assertEquals("0x14a34", wallet.requestedChain);
        assertEquals(java.util.List.of("Base Sepolia", "https://sepolia.base.org", "ETH"), wallet.addChainArgs);
    }

    private static class FakeWallet extends Web3Connect {
        private String chainId = "";
        private CompletableFuture<String> switchResult = CompletableFuture.completedFuture("0x1");
        private String requestedChain;
        private java.util.List<String> addChainArgs;
        void setConnectedChain(String value) {
            chainId = value;
            getElement().setProperty("account", "0xabc");
            getElement().setProperty("chainId", value);
        }
        @Override public String getChainId() { return chainId; }
        @Override public boolean isConnected() { return !chainId.isEmpty(); }
        @Override public CompletableFuture<String> switchChain(String chainIdHex) {
            requestedChain = chainIdHex;
            return switchResult;
        }
        @Override public CompletableFuture<String> switchChain(String chainIdHex, String name, String rpcUrl,
                String currencySymbol) {
            requestedChain = chainIdHex;
            addChainArgs = java.util.List.of(name, rpcUrl, currencySymbol);
            return switchResult;
        }
    }
}
