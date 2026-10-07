package com.wontlost.web3.nft;

/** 单个 NFT 的元数据解析结果。 */
public record NftMetadataResult(NftHolding holding, NftMetadata metadata, NftMetadataFailureCode failureCode,
        String displayableImageUrl, String imageReasonCode, String sourceUri) {
    public NftMetadataResult(NftHolding holding, NftMetadata metadata, NftMetadataFailureCode failureCode,
            String displayableImageUrl, String imageReasonCode) {
        this(holding, metadata, failureCode, displayableImageUrl, imageReasonCode, null);
    }

    public boolean successful() {
        return failureCode == null;
    }
}
