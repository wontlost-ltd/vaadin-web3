package com.wontlost.web3;

import java.io.Serializable;

/** Metadata announced by an EIP-6963 browser wallet. */
public record WalletInfo(String uuid, String name, String icon, String rdns) implements Serializable {
}
