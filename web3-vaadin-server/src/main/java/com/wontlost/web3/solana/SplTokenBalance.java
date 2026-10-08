package com.wontlost.web3.solana;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** 某所有者在某 SPL 代币上的总余额（最小单位）、小数位数（u8）及查询 slot。 */
public record SplTokenBalance(String mint, BigInteger amount, int decimals, long slot) {
    public SplTokenBalance {
        Objects.requireNonNull(mint);
        if (Objects.requireNonNull(amount).signum() < 0 || decimals < 0 || decimals > 255 || slot < 0) {
            throw new IllegalArgumentException("invalid SPL token balance");
        }
    }

    /** 按小数位数换算后的余额（精确小数）。 */
    public BigDecimal uiAmount() {
        return new BigDecimal(amount, decimals);
    }
}
