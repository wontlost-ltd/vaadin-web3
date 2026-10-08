package com.wontlost.web3.siws;

import java.time.Instant;
import java.util.Objects;

import com.wontlost.web3.identity.ChainAccount;
import com.wontlost.web3.identity.Web3Identity;

/**
 * 已验证的 Sign-In With Solana 结果。
 * <p>
 * 构造器为包内可见：只有 {@link SiwsVerifier} 在验签成功后才能创建实例，因此
 * {@link com.wontlost.web3.siwe.Web3Session#signIn(VerifiedSolanaSignIn)} 虽为公开方法，也无法被写入伪造身份。
 */
public final class VerifiedSolanaSignIn implements Web3Identity {
    private static final long serialVersionUID = 1L;

    private final ChainAccount account;
    private final SiwsMessage message;
    private final Instant verifiedAt;

    VerifiedSolanaSignIn(ChainAccount account, SiwsMessage message, Instant verifiedAt) {
        this.account = Objects.requireNonNull(account);
        this.message = Objects.requireNonNull(message);
        this.verifiedAt = Objects.requireNonNull(verifiedAt);
    }

    @Override
    public ChainAccount account() {
        return account;
    }

    /** 钱包签名的消息。 */
    public SiwsMessage message() {
        return message;
    }

    @Override
    public Instant verifiedAt() {
        return verifiedAt;
    }

    /** Base58 地址（Solana 公钥），区分大小写。 */
    public String address() {
        return account.address();
    }

    // 反序列化绕过构造器：至少拒绝缺失字段，避免会话中出现不完整身份
    private void readObject(java.io.ObjectInputStream input) throws java.io.IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (account == null || message == null || verifiedAt == null) {
            throw new java.io.InvalidObjectException("Incomplete Solana sign-in");
        }
    }

    @Override
    public String toString() {
        return "VerifiedSolanaSignIn[" + account.chainId() + "]";
    }
}
