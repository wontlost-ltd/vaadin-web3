package com.wontlost.web3.autoconfigure.security;

import java.io.Serializable;

import com.wontlost.web3.identity.ChainAccount;

/** A verified wallet identity exposed to Spring Security. */
public record Web3Principal(String address, long chainId) implements Serializable {
    /** Chain-neutral CAIP-10 form of this identity ({@code eip155:<chainId>:<address>}). */
    public ChainAccount account() {
        return ChainAccount.eip155(chainId, address);
    }

    @Override public String toString() { return "Web3Principal[chainId=" + chainId + "]"; }
}
