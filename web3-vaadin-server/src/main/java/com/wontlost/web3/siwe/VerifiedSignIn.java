package com.wontlost.web3.siwe;

import java.time.Instant;
import java.io.Serializable;

/**
 * A successfully verified SIWE authentication result.
 *
 * @param address EIP-55 checksummed wallet address
 * @param chainId verified EVM chain id
 * @param message parsed and verified SIWE message
 * @param verifiedAt time at which verification succeeded
 */
public record VerifiedSignIn(String address, long chainId, SiweMessage message, Instant verifiedAt) implements Serializable {
}
