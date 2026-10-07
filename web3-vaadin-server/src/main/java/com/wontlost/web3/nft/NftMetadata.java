package com.wontlost.web3.nft;

import java.util.List;

/** 不包含源 JSON 的标准化 NFT 元数据。 */
public record NftMetadata(String name, String description, String image, String animationUrl,
        String externalUrl, List<NftMetadataAttribute> attributes) {
    public NftMetadata {
        attributes = List.copyOf(attributes);
    }
}
