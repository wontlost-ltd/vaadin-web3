package com.wontlost.web3.nft;

import java.net.InetAddress;

/** 可注入的主机名解析器，所有返回地址必须整体通过公网地址校验。 */
@FunctionalInterface
public interface NftDnsResolver {
    InetAddress[] resolve(String host) throws Exception;
}
