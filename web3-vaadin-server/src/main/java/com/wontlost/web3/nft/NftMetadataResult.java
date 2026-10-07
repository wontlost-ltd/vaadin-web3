package com.wontlost.web3.nft;

/** 单个 NFT 的元数据解析结果。 */
public record NftMetadataResult(NftHolding holding, NftMetadata metadata, NftMetadataFailureCode failureCode,
        String displayableImageUrl, String imageReasonCode) {
    public boolean successful() {
        return failureCode == null;
    }
}
