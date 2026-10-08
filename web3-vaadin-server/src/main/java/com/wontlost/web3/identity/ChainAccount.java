package com.wontlost.web3.identity;

import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

import org.web3j.crypto.Keys;

/**
 * 链无关的账户标识（CAIP-10：{@code namespace:reference:address}）。
 * <p>
 * 地址比较规则随命名空间而定：EVM（{@code eip155}）地址不区分大小写；其它命名空间（如 Solana 的 base58）
 * 区分大小写，必须原样比较，绝不能小写化。
 *
 * @param namespace CAIP-2 命名空间，如 {@code eip155}、{@code solana}
 * @param reference CAIP-2 链引用，如 EVM 链 ID 或 Solana 创世哈希前缀
 * @param address   账户地址，按命名空间的原生格式保存
 */
public record ChainAccount(String namespace, String reference, String address) implements Serializable {
    // CAIP-10 account_address 字符规则
    private static final Pattern ADDRESS = Pattern.compile("[-.%a-zA-Z0-9]{1,128}");
    private static final Pattern EVM_ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

    public ChainAccount {
        Caip2.requireNamespace(namespace);
        Caip2.requireReference(reference);
        if (address == null || !ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("Invalid CAIP-10 account address");
        }
        if (Caip2.EIP155.equals(namespace)) {
            if (!EVM_ADDRESS.matcher(address).matches()) {
                throw new IllegalArgumentException("EVM account address must contain exactly 20 hexadecimal bytes");
            }
            // EVM 地址统一为 EIP-55 校验和格式，使 equals/hashCode/caip10 与 sameAccount 一致
            address = Keys.toChecksumAddress(address);
        }
    }

    /** 构造 EVM 账户；地址统一为 EIP-55 校验和格式。 */
    public static ChainAccount eip155(long chainId, String address) {
        String chain = Caip2.eip155(chainId);
        if (address == null || !EVM_ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("EVM account address must contain exactly 20 hexadecimal bytes");
        }
        return new ChainAccount(Caip2.EIP155, chain.substring(Caip2.EIP155.length() + 1),
                Keys.toChecksumAddress(address));
    }

    /** 解析 CAIP-10 账户标识。 */
    public static ChainAccount parse(String caip10) {
        if (caip10 == null) {
            throw new IllegalArgumentException("CAIP-10 account id is required");
        }
        String[] parts = caip10.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("CAIP-10 account id must be namespace:reference:address");
        }
        return new ChainAccount(parts[0], parts[1], parts[2]);
    }

    /** CAIP-2 链标识 {@code namespace:reference}。 */
    public String chainId() {
        return namespace + ":" + reference;
    }

    /** CAIP-10 字符串形式。 */
    public String caip10() {
        return chainId() + ":" + address;
    }

    /** 是否为 EVM 账户。 */
    public boolean isEvm() {
        return Caip2.EIP155.equals(namespace);
    }

    /** 同一链上的同一账户：EVM 地址不区分大小写，其它命名空间精确比较。 */
    public boolean sameAccount(ChainAccount other) {
        if (other == null || !namespace.equals(other.namespace) || !reference.equals(other.reference)) {
            return false;
        }
        return isEvm() ? address.toLowerCase(Locale.ROOT).equals(other.address.toLowerCase(Locale.ROOT))
                : address.equals(other.address);
    }

    @Override
    public String toString() {
        return caip10();
    }
}
