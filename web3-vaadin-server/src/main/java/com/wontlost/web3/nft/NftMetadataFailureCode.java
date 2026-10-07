package com.wontlost.web3.nft;

/** 对外稳定的 NFT 元数据失败码。 */
public enum NftMetadataFailureCode {
    NOT_FOUND,
    INVALID_COLLECTION,
    UNSUPPORTED_URI,
    INVALID_URI,
    URI_TOO_LONG,
    UNSAFE_TARGET,
    TOO_MANY_REDIRECTS,
    RESPONSE_TOO_LARGE,
    TIMEOUT,
    UNSUPPORTED_CONTENT_TYPE,
    INVALID_JSON,
    UNAVAILABLE
}
