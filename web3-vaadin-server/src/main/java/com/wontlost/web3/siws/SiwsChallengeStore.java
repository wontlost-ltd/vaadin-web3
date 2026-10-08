package com.wontlost.web3.siws;

import java.util.Optional;

/**
 * 服务端保存已签发 SIWS 挑战的存储，以 nonce 为键。
 * 验证时只信任这里保存的挑战（域名、URI 等），不接受客户端回传的挑战，防止钓鱼站点替换域名后重放签名。
 * 多节点部署应提供共享实现。
 */
public interface SiwsChallengeStore {
    /** 保存新签发的挑战；nonce 冲突时抛出异常。 */
    void save(SiwsChallenge challenge);

    /** 按 nonce 查找挑战（不消费）；实现可能返回已过期的挑战，由 {@link SiwsVerifier} 校验过期时间。 */
    Optional<SiwsChallenge> find(String nonce);

    /** 原子地移除挑战；仅在挑战存在时返回 {@code true}。并发重放时只有一个调用成功。 */
    boolean consume(String nonce);
}
