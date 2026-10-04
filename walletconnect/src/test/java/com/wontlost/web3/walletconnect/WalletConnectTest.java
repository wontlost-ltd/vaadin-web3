package com.wontlost.web3.walletconnect;

import java.io.Serializable;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletConnectTest {

    @Test
    void validatesProjectId() {
        assertThrows(IllegalArgumentException.class, () -> new WalletConnect(null));
        assertThrows(IllegalArgumentException.class, () -> new WalletConnect(" \t "));
        assertDoesNotThrow(() -> new WalletConnect("project-id"));
    }

    @Test
    void defaultsToEthereumAndValidatesConfiguredChains() {
        WalletConnect wallet = new WalletConnect("project-id");
        assertEquals(List.of(1L), wallet.getChains());
        assertThrows(IllegalArgumentException.class, () -> wallet.setChains());
        assertThrows(IllegalArgumentException.class, () -> wallet.setChains(1, 0));
        assertThrows(IllegalArgumentException.class, () -> wallet.setChains(-1));
        wallet.setChains(11155111, 84532);
        assertEquals(List.of(11155111L, 84532L), wallet.getChains());
        assertTrue(wallet.getElement().getProperty("chains").contains("11155111"));
    }

    @Test
    void writesMetadataAndThemeAsElementProperties() {
        WalletConnect wallet = new WalletConnect("project-id")
                .setMetadata("Demo", "A demo", "https://example.com", "https://example.com/icon.png")
                .setThemeMode(WalletConnect.ThemeMode.DARK)
                .setRpcUrl(11155111, "https://rpc.example");
        assertEquals("project-id", wallet.getElement().getProperty("projectId"));
        assertEquals("dark", wallet.getElement().getProperty("themeMode"));
        assertEquals("Demo", wallet.getElement().getPropertyBean("metadata", java.util.Map.class).get("name"));
        assertEquals("https://rpc.example", wallet.getElement().getPropertyBean("rpcMap", java.util.Map.class).get("11155111"));
    }

    @Test
    void settersAreFluentAndComponentIsSerializable() {
        WalletConnect wallet = new WalletConnect("project-id");
        assertInstanceOf(WalletConnect.class, wallet.setChains(1).setWalletName("Mobile Wallet")
                .setWalletIcon("data:image/svg+xml,icon").setThemeMode(WalletConnect.ThemeMode.LIGHT));
        assertInstanceOf(Serializable.class, wallet);
    }
}
