package com.wontlost.web3.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExplorersTest {
    @Test void returnsOnlyConfiguredChainsAndSupportsOverrides() {
        assertEquals("https://etherscan.io/tx/0xabc", Explorers.transactionUrl(1, "0xabc").orElseThrow());
        assertEquals("https://amoy.polygonscan.com/tx/0xabc", Explorers.transactionUrl(80002, "0xabc").orElseThrow());
        assertTrue(Explorers.transactionUrl(43114, "0xabc").isEmpty());
        Explorers.register(1, "https://explorer.example/custom/");
        assertEquals("https://explorer.example/custom/tx/0xabc", Explorers.transactionUrl(1, "0xabc").orElseThrow());
        Explorers.register(1, "https://etherscan.io");
    }
}
