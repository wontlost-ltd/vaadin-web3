package com.wontlost.web3.nft;

/** 所有权查询的稳定失败类型。 */
public enum NftFailureCode {
    UNSUPPORTED,
    UNAVAILABLE,
    INVALID_COLLECTION,
    INVALID_RESPONSE,
    LIMIT_EXCEEDED;

    public String message() {
        return switch (this) {
            case UNSUPPORTED -> "The collection does not support this ownership query.";
            case UNAVAILABLE -> "NFT ownership service is temporarily unavailable.";
            case INVALID_COLLECTION -> "The NFT collection configuration is invalid.";
            case INVALID_RESPONSE -> "The collection returned an invalid response.";
            case LIMIT_EXCEEDED -> "The NFT ownership query exceeded its configured limit.";
        };
    }
}
