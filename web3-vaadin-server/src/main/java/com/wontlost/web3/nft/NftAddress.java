package com.wontlost.web3.nft;

import java.util.Locale;

/**
 * EVM 地址规范化工具：NFT 所有权与元数据的比较、缓存键和游标都以小写 20 字节十六进制地址为准。
 * 公开给第三方 {@link NftOwnershipSource} 实现（如商业索引器适配器）复用，保证与内置 RPC 实现同一语义。
 */
public final class NftAddress {
    private NftAddress() {
    }

    /**
     * 校验并规范化 EVM 地址。
     *
     * @param address 形如 {@code 0x} 加 40 个十六进制字符的地址，大小写不限
     * @return 小写地址
     * @throws IllegalArgumentException 地址为空或格式不正确
     */
    public static String normalize(String address) {
        if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Address must contain exactly 20 hexadecimal bytes");
        }
        return address.toLowerCase(Locale.ROOT);
    }
}
