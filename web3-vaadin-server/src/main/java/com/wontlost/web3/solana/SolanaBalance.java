package com.wontlost.web3.solana;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** SOL 余额（lamports）及查询所对应的 slot。 */
public record SolanaBalance(BigInteger lamports, long slot) {
    /** 1 SOL = 10^9 lamports。 */
    public static final int DECIMALS = 9;

    public SolanaBalance {
        if (Objects.requireNonNull(lamports).signum() < 0 || slot < 0) {
            throw new IllegalArgumentException("lamports and slot must not be negative");
        }
    }

    /** 以 SOL 表示的余额（精确小数）。 */
    public BigDecimal sol() {
        return new BigDecimal(lamports, DECIMALS);
    }
}
