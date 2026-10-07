package com.wontlost.web3.nft;

/** 单跳 HTTP 响应；body 在读取时已受字节上限约束。 */
public record NftMetadataFetchResponse(int status, String contentType, String location, byte[] body) {
    public NftMetadataFetchResponse {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
