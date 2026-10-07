package com.wontlost.web3.nft;

import java.math.BigInteger;

/** 固定区块快照上的 token 余额。 */
public record NftHolding(long chainId, String contract, BigInteger tokenId, BigInteger amount,
        NftStandard standard) {
    public NftHolding {
        contract = NftAddress.normalize(contract);
        NftAbi.requireUint256(tokenId);
        NftAbi.requireUint256(amount);
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("A holding must have a positive amount");
        }
        java.util.Objects.requireNonNull(standard);
    }
}
