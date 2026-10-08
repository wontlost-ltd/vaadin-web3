package com.wontlost.web3.solana;

/** Solana 确认级别：processed（最快、可能回滚）、confirmed（超级多数投票）、finalized（不可回滚）。 */
public enum SolanaCommitment {
    PROCESSED("processed"),
    CONFIRMED("confirmed"),
    FINALIZED("finalized");

    private final String value;

    SolanaCommitment(String value) {
        this.value = value;
    }

    /** JSON-RPC {@code commitment} 参数值。 */
    public String value() {
        return value;
    }
}
