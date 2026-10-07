package com.wontlost.web3.nft;

import java.net.URI;
import java.time.Duration;

/** 可替换的 HTTPS JSON 获取器。 */
public interface NftMetadataFetcher {
    /**
     * 获取指定 URI 的 JSON 响应。
     * <p>
     * 自定义实现必须在实际建立连接时再次解析主机，并对连接使用的全部地址执行
     * {@link com.wontlost.web3.net.PublicAddressPolicy#isPublic(java.net.InetAddress)} 校验。
     * 仅在调用前校验地址不足以防止 DNS 结果变化后连接到私网。
     */
    NftMetadataFetchResponse fetch(URI uri, Duration timeout, int maxBytes);
}
