package com.wontlost.web3.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.JsonRpcTransport;

class BalanceTest {
    private static final String ADDRESS = "0x0000000000000000000000000000000000000001";
    @Test void readsNativeAndErc20AmountsAndFormatsWithoutTrailingZeroes() {
        Balance nativeBalance = new Balance().setChainId(1).setAddress(ADDRESS);
        var nativeAmount = nativeBalance.read(new EthRpcClient(new BalanceRpc("0xde0b6b3a7640000", null)));
        assertEquals(new BigInteger("1000000000000000000"), nativeAmount.amount());
        assertEquals(18, nativeAmount.decimals());
        Balance tokenBalance = new Balance().setChainId(1).setAddress(ADDRESS).setToken("USDC");
        var tokenAmount = tokenBalance.read(new EthRpcClient(new BalanceRpc(null, "0x1e240")));
        assertEquals(new BigInteger("123456"), tokenAmount.amount());
        assertEquals(6, tokenAmount.decimals());
        assertEquals("1.234567", tokenBalance.format(new BigInteger("1234567"), 6));
        assertEquals("1.2345", tokenBalance.setMaxFractionDigits(4).format(new BigInteger("1234567"), 6));
        assertEquals("0", tokenBalance.format(BigInteger.ZERO, 6));
    }

    @Test void surfacesRpcErrorsToCaller() {
        Balance balance = new Balance().setChainId(1).setAddress(ADDRESS);
        assertThrows(IllegalStateException.class, () -> balance.read(new EthRpcClient(request -> {
            throw new IOException("RPC unavailable");
        })));
    }

    private static final class BalanceRpc implements JsonRpcTransport {
        private final String nativeAmount;
        private final String tokenAmount;
        private BalanceRpc(String nativeAmount, String tokenAmount) { this.nativeAmount = nativeAmount; this.tokenAmount = tokenAmount; }
        @Override public String send(String request) {
            String id = request.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1");
            String result;
            if (request.contains("eth_getBalance")) result = "\"" + nativeAmount + "\"";
            else if (request.contains("eth_call") && request.contains("70a08231")) result = "\"" + tokenAmount + "\"";
            else if (request.contains("eth_call") && request.contains("313ce567")) result = "\"0x6\"";
            else throw new IllegalArgumentException(request);
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
        }
    }
}
