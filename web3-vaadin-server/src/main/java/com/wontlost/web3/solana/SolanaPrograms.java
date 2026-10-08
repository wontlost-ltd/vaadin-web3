package com.wontlost.web3.solana;

/** Well-known Solana program addresses (base58). */
public final class SolanaPrograms {
    /** The System Program, which owns plain SOL accounts and performs SOL transfers. */
    public static final String SYSTEM = "11111111111111111111111111111111";
    /** The original SPL Token program. */
    public static final String TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    /** The SPL Token-2022 program (token extensions). */
    public static final String TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";
    /** The Associated Token Account program. */
    public static final String ASSOCIATED_TOKEN = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";

    private SolanaPrograms() {
    }

    /** Whether {@code programId} is one of the two SPL token programs. */
    public static boolean isTokenProgram(String programId) {
        return TOKEN.equals(programId) || TOKEN_2022.equals(programId);
    }
}
