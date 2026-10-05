package com.wontlost.web3;

import java.math.BigInteger;

/**
 * Hex chain ids of commonly used EVM networks, for use with
 * {@link Web3Connect#switchChain(String)}.
 */
public final class Chains {

    public static final String ETHEREUM_MAINNET = "0x1";
    public static final String SEPOLIA = "0xaa36a7";
    public static final String POLYGON = "0x89";
    public static final String POLYGON_AMOY = "0x13882";
    public static final String BSC = "0x38";
    public static final String ARBITRUM_ONE = "0xa4b1";
    public static final String OPTIMISM = "0xa";
    public static final String BASE = "0x2105";
    public static final String AVALANCHE_C = "0xa86a";
    public static final String GNOSIS = "0x64";

    private Chains() {
    }

    /** Converts a decimal chain id to the hex form wallets expect. */
    public static String toHex(long chainId) {
        if (chainId < 0) {
            throw new IllegalArgumentException("chainId must be non-negative");
        }
        return "0x" + Long.toHexString(chainId);
    }

    /** Converts a hex chain id (e.g. {@code 0x89}) to its decimal value. */
    public static BigInteger toDecimal(String chainIdHex) {
        String hex = chainIdHex.startsWith("0x") || chainIdHex.startsWith("0X")
                ? chainIdHex.substring(2) : chainIdHex;
        return new BigInteger(hex, 16);
    }
}
