package com.wontlost.web3.solana;

import java.util.List;
import java.util.Objects;

/** One instruction: the program to call, the accounts it touches, and its encoded data. */
public record SolanaInstruction(String programId, List<AccountMeta> accounts, byte[] data) {
    public SolanaInstruction {
        SolanaAddresses.key(programId);
        accounts = List.copyOf(accounts);
        data = Objects.requireNonNull(data).clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    /** An account used by an instruction, with whether it must sign and whether it is written. */
    public record AccountMeta(String address, boolean signer, boolean writable) {
        public AccountMeta {
            SolanaAddresses.key(address);
        }
    }
}
