package com.wontlost.web3.identity;

import java.io.Serializable;
import java.time.Instant;

/**
 * 已验证的钱包身份（与链无关）。EVM 的 SIWE 登录结果与后续其它链的登录结果都实现本接口，
 * 由 {@link com.wontlost.web3.siwe.Web3Session#currentIdentity()} 统一读取。
 * 只适用于 EVM 的组件应继续使用 {@link com.wontlost.web3.siwe.Web3Session#current()}：
 * 会话中是非 EVM 身份时它返回空，组件因此按“未登录”处理，而不会误用非 EVM 地址。
 */
public interface Web3Identity extends Serializable {
    /** 已验证的账户。 */
    ChainAccount account();

    /** 验证成功的时间。 */
    Instant verifiedAt();
}
