package com.wontlost.web3.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** 判断 IP 地址是否属于可公开访问的单播地址空间。 */
public final class PublicAddressPolicy {
    private PublicAddressPolicy() {
    }

    /** 返回地址是否可作为公网连接目标。 */
    public static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return isPublicIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return isPublicIpv6(address.getAddress());
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192 && second == 0 && third == 0) {
            return false;
        }
        if (first == 192 && second == 0 && third == 2) {
            return false;
        }
        if (first == 192 && second == 88 && third == 99) {
            return false;
        }
        if (first == 192 && second == 168) {
            return false;
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return false;
        }
        if (first == 198 && second == 51 && third == 100) {
            return false;
        }
        return first != 203 || second != 0 || third != 113;
    }

    private static boolean isPublicIpv6(byte[] bytes) {
        if (isIpv4Mapped(bytes) || isIpv4Compatible(bytes)) {
            return false;
        }
        if ((Byte.toUnsignedInt(bytes[0]) & 0xe0) != 0x20) {
            return false;
        }
        if (bytes[0] == 0x3f && bytes[1] == (byte) 0xff && (bytes[2] & 0xf0) == 0) {
            return false;
        }
        int secondHextet = Byte.toUnsignedInt(bytes[2]) << 8 | Byte.toUnsignedInt(bytes[3]);
        if (bytes[0] == 0x20 && bytes[1] == 0x02) {
            return false;
        }
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && secondHextet == 0x0db8) {
            return false;
        }
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && secondHextet == 0x0000) {
            return false;
        }
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && secondHextet == 0x0002
                && bytes[4] == 0 && bytes[5] == 0) {
            return false;
        }
        return !(bytes[0] == 0x20 && bytes[1] == 0x01 && isReservedIetfSubnet(secondHextet));
    }

    private static boolean isReservedIetfSubnet(int secondHextet) {
        return matchesPrefix(secondHextet, 0x0010, 28)
                || matchesPrefix(secondHextet, 0x0020, 28)
                || matchesPrefix(secondHextet, 0x0030, 28);
    }

    private static boolean matchesPrefix(int value, int prefix, int prefixLength) {
        int hostBits = 16 - (prefixLength - 16);
        int mask = 0xffff << hostBits & 0xffff;
        return (value & mask) == (prefix & mask);
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean isIpv4Compatible(byte[] bytes) {
        for (int index = 0; index < 12; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }
}
