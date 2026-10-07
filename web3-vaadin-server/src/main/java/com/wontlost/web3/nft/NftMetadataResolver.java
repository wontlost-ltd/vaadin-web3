package com.wontlost.web3.nft;

import java.util.List;

/** 批量解析链上 NFT 元数据的扩展点。 */
public interface NftMetadataResolver {
    List<NftMetadataResult> resolve(List<NftHolding> holdings);
}
