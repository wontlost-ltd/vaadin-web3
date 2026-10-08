package com.wontlost.web3.x402.siwx;

import com.wontlost.web3.identity.ChainAccount;

public record VerifiedWallet(String address, long chainId, IdentitySource source) {
    /** 链无关的 CAIP-10 账户形式。 */
    public ChainAccount account() {
        return ChainAccount.eip155(chainId, address);
    }
}
