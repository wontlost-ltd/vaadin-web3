package com.wontlost.web3.nft;

import java.util.List;

/** 与供应商无关的 NFT 只读所有权数据源。 */
public interface NftOwnershipSource {
    NftOwnershipPage find(long chainId, String owner, List<NftCollection> collections, String cursor, int pageSize);
}
