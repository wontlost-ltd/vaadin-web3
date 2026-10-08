package com.wontlost.web3.siwe;

import java.time.Instant;
import java.io.Serializable;

import com.wontlost.web3.identity.ChainAccount;
import com.wontlost.web3.identity.Web3Identity;

/**
 * A successfully verified SIWE authentication result.
 *
 * @param address EIP-55 checksummed wallet address
 * @param chainId verified EVM chain id
 * @param message parsed and verified SIWE message
 * @param verifiedAt time at which verification succeeded
 */
public record VerifiedSignIn(String address, long chainId, SiweMessage message, Instant verifiedAt)
        implements Serializable, Web3Identity {
    /** 链无关的 CAIP-10 账户形式（{@code eip155:<chainId>:<address>}）。 */
    @Override
    public ChainAccount account() {
        return ChainAccount.eip155(chainId, address);
    }
}
