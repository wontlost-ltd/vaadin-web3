package com.wontlost.web3.nft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;

class NftAbiTest {
    @Test
    void encodesAndDecodesMaximumUint256() {
        BigInteger maximum = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        String encoded = NftAbi.ownerOfData(maximum);

        assertEquals("0x6352211e", encoded.substring(0, 10));
        assertEquals(maximum, NftAbi.decodeUint("0x" + "f".repeat(64)));
    }

    @Test
    void encodesNormalizedAddressAndEnumerableQueries() {
        String owner = "0x00000000000000000000000000000000000000Aa";

        assertEquals("0x70a08231", NftAbi.balanceOfData(owner).substring(0, 10));
        assertEquals("0x00fdd58e", NftAbi.balanceOfData(owner, BigInteger.ONE).substring(0, 10));
        assertEquals("0x2f745c59", NftAbi.tokenOfOwnerByIndexData(owner, BigInteger.ONE).substring(0, 10));
        assertEquals("0x01ffc9a7", NftAbi.supportsInterfaceData("0x80ac58cd").substring(0, 10));
        assertEquals("0x4e1273f4", NftAbi.balanceOfBatchData(List.of(owner), List.of(BigInteger.ONE)).substring(0, 10));
        String dynamicArray = "0x" + "0".repeat(62) + "20" + "0".repeat(63) + "2"
                + "0".repeat(63) + "1" + "0".repeat(63) + "2";
        assertEquals(List.of(BigInteger.ONE, BigInteger.TWO), NftAbi.decodeUintArray(dynamicArray));
    }

    @Test
    void rejectsOutOfRangeUintAndEmptyReturnData() {
        assertThrows(IllegalArgumentException.class, () -> NftAbi.ownerOfData(BigInteger.ONE.shiftLeft(256)));
        assertThrows(IllegalArgumentException.class, () -> NftAbi.decodeUint("0x"));
    }
}
