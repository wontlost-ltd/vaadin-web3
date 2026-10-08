package com.wontlost.web3.siws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.Test;

class Base58Test {
    // 比特币 base58 编码测试向量（bitcoin/src/test/data/base58_encode_decode.json）
    private static final Map<String, String> VECTORS = Map.ofEntries(
            Map.entry("", ""),
            Map.entry("61", "2g"),
            Map.entry("626262", "a3gV"),
            Map.entry("636363", "aPEr"),
            Map.entry("73696d706c792061206c6f6e6720737472696e67", "2cFupjhnEsSn59qHXstmK2ffpLv2"),
            Map.entry("00eb15231dfceb60925886b67d065299925915aeb172c06647", "1NS17iag9jJgTHD1VXjvLCEnZuQ3rJDE9L"),
            Map.entry("516b6fcd0f", "ABnLTmg"),
            Map.entry("bf4f89001e670274dd", "3SEo3LWLoPntC"),
            Map.entry("572e4794", "3EFU7m"),
            Map.entry("ecac89cad93923c02321", "EJDM8drfXA6uyA"),
            Map.entry("10c8511e", "Rt5zm"),
            Map.entry("00000000000000000000", "1111111111"));

    @Test
    void encodesAndDecodesPublishedVectors() {
        VECTORS.forEach((hex, base58) -> {
            byte[] bytes = HexFormat.of().parseHex(hex);
            assertEquals(base58, Base58.encode(bytes), hex);
            assertArrayEquals(bytes, Base58.decode(base58), base58);
        });
    }

    @Test
    void rejectsCharactersOutsideTheAlphabetAndWrongLengths() {
        for (String invalid : new String[] {"0", "O", "I", "l", "abc+", "é"}) {
            assertThrows(IllegalArgumentException.class, () -> Base58.decode(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("2g", 32));
        assertEquals(32, Base58.decode("11111111111111111111111111111111", 32).length);
        // 超长输入在解码前即被拒绝（性能防护），而不是先做大数运算再因长度不符失败
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> Base58.decode("1".repeat(10_000), 32));
        assertEquals("Base58 value is too long", tooLong.getMessage());
        assertEquals(32, Base58.decode(Base58.encode(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}), 32).length);
    }
}
