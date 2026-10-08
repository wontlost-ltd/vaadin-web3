package com.wontlost.web3.identity;

import java.util.regex.Pattern;

/**
 * CAIP-2 链标识（{@code namespace:reference}）的校验与 EVM 便捷构造。
 * 字符规则依据 CAIP-2：namespace {@code [-a-z0-9]{3,8}}，reference {@code [-_a-zA-Z0-9]{1,32}}。
 */
public final class Caip2 {
    /** EVM 链命名空间。 */
    public static final String EIP155 = "eip155";
    /** Solana 链命名空间。 */
    public static final String SOLANA = "solana";

    private static final Pattern NAMESPACE = Pattern.compile("[-a-z0-9]{3,8}");
    private static final Pattern REFERENCE = Pattern.compile("[-_a-zA-Z0-9]{1,32}");

    private Caip2() {
    }

    /** 构造 EVM 链标识 {@code eip155:<chainId>}。 */
    public static String eip155(long chainId) {
        if (chainId <= 0) {
            throw new IllegalArgumentException("EVM chain id must be positive");
        }
        return EIP155 + ":" + chainId;
    }

    /** 校验并返回 CAIP-2 链标识。 */
    public static String require(String chainId) {
        if (chainId == null) {
            throw new IllegalArgumentException("CAIP-2 chain id is required");
        }
        int colon = chainId.indexOf(':');
        if (colon < 0 || chainId.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("CAIP-2 chain id must be namespace:reference");
        }
        requireNamespace(chainId.substring(0, colon));
        requireReference(chainId.substring(colon + 1));
        return chainId;
    }

    static String requireNamespace(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid CAIP-2 namespace");
        }
        return namespace;
    }

    static String requireReference(String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("Invalid CAIP-2 reference");
        }
        return reference;
    }
}
