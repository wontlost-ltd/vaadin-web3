package com.wontlost.web3.siws;

/**
 * SIWS {@code Chain ID} 字段允许的 Solana 集群及其 CAIP-2 引用。
 * 引用为创世哈希 base58 的前 32 个字符（chainagnostic namespaces：solana/caip2）；
 * 本地验证器没有固定创世哈希，使用字面量 {@code localnet}。
 */
public enum SolanaCluster {
    MAINNET("mainnet", "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp"),
    DEVNET("devnet", "EtWTRABZaYq6iMfeYKouRu166VU2xqa1"),
    TESTNET("testnet", "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3z"),
    LOCALNET("localnet", "localnet");

    private final String chainId;
    private final String reference;

    SolanaCluster(String chainId, String reference) {
        this.chainId = chainId;
        this.reference = reference;
    }

    /** SIWS 消息中的 {@code Chain ID} 取值。 */
    public String chainId() {
        return chainId;
    }

    /** CAIP-2 引用（{@code solana:<reference>}）。 */
    public String reference() {
        return reference;
    }
}
