package com.wontlost.web3.nft;

import java.util.Locale;

final class NftAddress {
    private NftAddress() {
    }

    static String normalize(String address) {
        if (address == null || !address.matches("(?i)0x[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Address must contain exactly 20 hexadecimal bytes");
        }
        return address.toLowerCase(Locale.ROOT);
    }
}
