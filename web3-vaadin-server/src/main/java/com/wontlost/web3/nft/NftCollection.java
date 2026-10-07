package com.wontlost.web3.nft;

import java.math.BigInteger;
import java.util.List;

/** 明确配置的 NFT 集合及可选的 token ID 查询范围。 */
public record NftCollection(long chainId, String contract, NftStandard standard, boolean enumerable,
        List<BigInteger> tokenIds, List<NftTokenIdRange> tokenIdRanges) {
    public NftCollection {
        if (chainId < 0) {
            throw new IllegalArgumentException("Chain ID must be non-negative");
        }
        contract = NftAddress.normalize(contract);
        java.util.Objects.requireNonNull(standard);
        tokenIds = tokenIds == null ? List.of() : List.copyOf(tokenIds);
        tokenIdRanges = tokenIdRanges == null ? List.of() : List.copyOf(tokenIdRanges);
        tokenIds.forEach(NftAbi::requireUint256);
        if (standard == NftStandard.ERC1155 && enumerable) {
            throw new IllegalArgumentException("ERC-1155 does not use ERC-721 Enumerable");
        }
        if (enumerable && (!tokenIds.isEmpty() || !tokenIdRanges.isEmpty())) {
            throw new IllegalArgumentException("Enumerable collections cannot also specify token IDs");
        }
    }

    public NftCollection(long chainId, String contract, NftStandard standard, boolean enumerable,
            List<BigInteger> tokenIds) {
        this(chainId, contract, standard, enumerable, tokenIds, List.of());
    }
}
