package com.wontlost.web3.autoconfigure;

import java.util.List;

import com.wontlost.web3.nft.NftCollection;

/** 已配置的 NFT 合约集合。 */
public record NftCollections(List<NftCollection> collections) {
    public NftCollections {
        collections = List.copyOf(collections);
    }
}
