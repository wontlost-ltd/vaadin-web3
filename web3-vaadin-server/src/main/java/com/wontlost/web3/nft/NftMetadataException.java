package com.wontlost.web3.nft;

/** 内部流程使用的稳定失败类型，不保留异常文本。 */
final class NftMetadataException extends RuntimeException {
    private final NftMetadataFailureCode code;

    NftMetadataException(NftMetadataFailureCode code) {
        super(code.name());
        this.code = code;
    }

    NftMetadataFailureCode code() {
        return code;
    }
}
