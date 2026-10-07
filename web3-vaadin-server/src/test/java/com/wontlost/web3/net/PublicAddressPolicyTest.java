package com.wontlost.web3.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.List;

import org.junit.jupiter.api.Test;

class PublicAddressPolicyTest {
    @Test
    void rejectsAllRequiredIpv4SpecialPurposeRanges() throws Exception {
        List<String> blocked = List.of(
                "0.1.2.3",
                "10.1.2.3",
                "100.64.0.1",
                "100.127.255.254",
                "127.0.0.1",
                "169.254.169.254",
                "172.16.0.1",
                "172.31.255.254",
                "192.0.0.1",
                "192.0.2.1",
                "192.88.99.1",
                "192.168.1.1",
                "198.18.0.1",
                "198.19.255.254",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "240.0.0.1",
                "255.255.255.255");

        for (String address : blocked) {
            assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void checks192NetworkRangesAtTheirExactPrefixLength() throws Exception {
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("192.0.0.1")));
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName("192.0.2.1")));
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName("192.0.3.1")));
    }

    @Test
    void allowsPublicIpv4AndGlobalIpv6Addresses() throws Exception {
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName("2001:4860:4860::8888")));
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName("2001:1::1")));
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName("2001:200::1")));
    }

    @Test
    void rejectsIpv6OutsidePublicSpaceAndSpecialPurposeSubnets() throws Exception {
        List<String> blocked = List.of(
                "::",
                "::1",
                "2001:db8::1",
                "2002::1",
                "2001::1",
                "2001:0:1::1",
                "2001:2::1",
                "2001:10::1",
                "2001:20::1",
                "2001:30::1",
                "fc00::1",
                "fd12::1",
                "fe80::1",
                "ff02::1",
                "3fff::1");

        for (String address : blocked) {
            assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName(address)), address);
        }
    }

    @Test
    void rejectsIpv4MappedAndCompatibleIpv6Addresses() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 8;
        mapped[13] = 8;
        mapped[14] = 8;
        mapped[15] = 8;
        byte[] compatible = new byte[16];
        compatible[12] = 8;
        compatible[13] = 8;
        compatible[14] = 8;
        compatible[15] = 8;

        assertFalse(PublicAddressPolicy.isPublic(Inet6Address.getByAddress(null, mapped, -1)));
        assertFalse(PublicAddressPolicy.isPublic(Inet6Address.getByAddress(null, compatible, -1)));
    }
}
