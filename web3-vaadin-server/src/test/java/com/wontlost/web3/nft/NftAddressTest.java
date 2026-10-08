package com.wontlost.web3.nft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class NftAddressTest {
    @Test
    void normalizesMixedCaseAddressesToLowercase() {
        assertEquals("0xabcdef0123456789abcdef0123456789abcdef01",
                NftAddress.normalize("0xABCDEF0123456789abcdef0123456789ABCDEF01"));
    }

    @Test
    void rejectsNullAndMalformedAddresses() {
        // 缺前缀、长度不足/超长、非十六进制字符与空值都必须拒绝，避免第三方适配器产生不一致的键
        for (String invalid : Arrays.asList(null, "", "abcdef0123456789abcdef0123456789abcdef01",
                "0xabcdef0123456789abcdef0123456789abcdef0", "0xabcdef0123456789abcdef0123456789abcdef012",
                "0xzzcdef0123456789abcdef0123456789abcdef01")) {
            assertThrows(IllegalArgumentException.class, () -> NftAddress.normalize(invalid), String.valueOf(invalid));
        }
    }
}
