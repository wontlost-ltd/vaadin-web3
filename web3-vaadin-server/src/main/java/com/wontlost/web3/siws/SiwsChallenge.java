package com.wontlost.web3.siws;

import java.util.List;

/**
 * 服务端签发的 SIWS 挑战，即传给钱包 {@code solana:signIn} 的输入（地址由钱包填入）。
 * 验证时要求钱包签名的文本与本挑战加上签名者地址渲染出的文本逐字节一致。
 */
public record SiwsChallenge(String domain, String statement, String uri, SolanaCluster cluster, String nonce,
        String issuedAt, String expirationTime, List<String> resources) {
    public SiwsChallenge {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    /** 以签名者地址渲染期望的消息。 */
    public SiwsMessage toMessage(String address) {
        return new SiwsMessage(domain, address, statement, uri, "1", cluster.chainId(), nonce, issuedAt,
                expirationTime, null, null, resources);
    }
}
