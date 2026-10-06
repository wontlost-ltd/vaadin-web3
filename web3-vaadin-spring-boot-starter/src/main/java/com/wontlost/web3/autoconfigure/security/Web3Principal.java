package com.wontlost.web3.autoconfigure.security;

import java.io.Serializable;

/** A verified wallet identity exposed to Spring Security. */
public record Web3Principal(String address, long chainId) implements Serializable {
    @Override public String toString() { return "Web3Principal[chainId=" + chainId + "]"; }
}
