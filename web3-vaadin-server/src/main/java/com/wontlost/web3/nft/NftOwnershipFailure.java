package com.wontlost.web3.nft;

/** 单个集合的查询失败，不影响其他集合继续读取。 */
public record NftOwnershipFailure(long chainId, String contract, NftFailureCode code, String message) {
    public NftOwnershipFailure(long chainId, String contract, NftFailureCode code) {
        this(chainId, contract, code, code.message());
    }

    public NftOwnershipFailure {
        contract = NftAddress.normalize(contract);
        java.util.Objects.requireNonNull(code);
        message = code.message();
    }
}
