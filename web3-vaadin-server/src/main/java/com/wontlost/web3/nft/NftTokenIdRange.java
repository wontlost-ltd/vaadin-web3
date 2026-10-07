package com.wontlost.web3.nft;

import java.math.BigInteger;

/** 首尾均包含的 token ID 范围。 */
public record NftTokenIdRange(BigInteger first, BigInteger last) {
    public NftTokenIdRange {
        NftAbi.requireUint256(first);
        NftAbi.requireUint256(last);
        if (last.compareTo(first) < 0) {
            throw new IllegalArgumentException("Range end must not be less than its start");
        }
    }

    public BigInteger size() {
        return last.subtract(first).add(BigInteger.ONE);
    }

    public BigInteger at(BigInteger index) {
        return first.add(index);
    }
}
