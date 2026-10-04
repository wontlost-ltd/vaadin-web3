package com.wontlost.web3;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Web3UtilsTest {

    private static final String ADDR = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045";

    @Test
    void validAddress() {
        assertTrue(Web3Utils.isValidAddress(ADDR));
        assertFalse(Web3Utils.isValidAddress(null));
        assertFalse(Web3Utils.isValidAddress("0x123"));
        assertFalse(Web3Utils.isValidAddress(ADDR + "ff"));
        assertFalse(Web3Utils.isValidAddress(ADDR.substring(2)));
    }

    @Test
    void abbreviate() {
        assertEquals("0xd8dA…6045", Web3Utils.abbreviate(ADDR));
        assertEquals("", Web3Utils.abbreviate(null));
        assertEquals("0x123", Web3Utils.abbreviate("0x123"));
    }

    @Test
    void etherWeiRoundTrip() {
        assertEquals("0xde0b6b3a7640000", Web3Utils.etherToWeiHex(BigDecimal.ONE));
        assertEquals(0, Web3Utils.weiHexToEther("0xde0b6b3a7640000")
                .compareTo(BigDecimal.ONE));
        assertEquals("0x0", Web3Utils.etherToWeiHex(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> Web3Utils.etherToWeiHex(new BigDecimal("-1")));
    }

    @Test
    void chainIdConversions() {
        assertEquals("0x89", Chains.toHex(137));
        assertEquals(137, Chains.toDecimal("0x89").intValueExact());
        assertEquals(1, Chains.toDecimal(Chains.ETHEREUM_MAINNET).intValueExact());
        assertThrows(IllegalArgumentException.class, () -> Chains.toHex(-1));
    }
}
