package com.wontlost.web3.solana;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.wontlost.web3.solana.SolanaInstruction.AccountMeta;

/**
 * Builds unsigned SOL and SPL token transfers. The sender signs them, for example with a browser wallet's
 * {@code solana:signAndSendTransaction}, so the server never holds the sender's key.
 */
public final class SolanaTransfers {
    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    /** System Program {@code Transfer} 指令序号（u32 小端）。 */
    private static final int SYSTEM_TRANSFER = 2;
    /** SPL Token {@code TransferChecked} 指令序号：校验 mint 与小数位，Token 与 Token-2022 通用。 */
    private static final byte TRANSFER_CHECKED = 12;
    /** Associated Token Account 程序 {@code CreateIdempotent}：账户已存在时不报错。 */
    private static final byte CREATE_IDEMPOTENT = 1;

    private SolanaTransfers() {
    }

    /**
     * A SOL transfer of {@code lamports} from {@code from} (the fee payer and signer) to the wallet {@code to}.
     * An off-curve recipient (a token account or other program-derived address) is rejected, because no key can
     * move SOL out of it; use {@link #solToProgramAddress} to fund a program-controlled address on purpose.
     */
    public static SolanaTransaction sol(String from, String to, BigInteger lamports, String recentBlockhash) {
        requireOnCurve(to);
        return solToProgramAddress(from, to, lamports, recentBlockhash);
    }

    /** Like {@link #sol} for a program-derived recipient, such as a program's vault. */
    public static SolanaTransaction solToProgramAddress(String from, String to, BigInteger lamports,
            String recentBlockhash) {
        ByteBuffer data = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(SYSTEM_TRANSFER).putLong(u64(lamports));
        SolanaInstruction transfer = new SolanaInstruction(SolanaPrograms.SYSTEM,
                List.of(new AccountMeta(from, true, true), new AccountMeta(to, false, true)), data.array());
        return SolanaTransaction.compile(from, recentBlockhash, List.of(transfer));
    }

    /**
     * An SPL token transfer of {@code amount} base units between the associated token accounts of {@code owner}
     * (the fee payer and signer) and the wallet {@code recipient}. {@code decimals} must match the mint, which the
     * token program checks. With {@code createRecipientAccount}, the transaction first creates the recipient's
     * associated token account if it does not exist yet; the sender pays its rent (about 0.002 SOL).
     * <p>
     * {@code recipient} must be a wallet address, not a token account. An off-curve address (a token account or
     * other program-derived address) is rejected, because tokens sent to an associated account owned by it could be
     * stranded. Also confirm with {@link #requireWalletRecipient} that the recipient is not a program-owned account.
     * To pay a program-derived wallet on purpose, use {@link #splToProgramAddress}.
     */
    public static SolanaTransaction spl(String owner, String mint, String recipient, BigInteger amount, int decimals,
            String tokenProgram, String recentBlockhash, boolean createRecipientAccount) {
        requireOnCurve(recipient);
        return splTransfer(owner, mint, recipient, amount, decimals, tokenProgram, recentBlockhash, createRecipientAccount);
    }

    /**
     * Like {@link #spl} for a recipient that is a program-derived address controlled by a program, such as a
     * multisig vault. Only use it when the recipient program can move tokens out of its associated token account.
     */
    public static SolanaTransaction splToProgramAddress(String owner, String mint, String recipient, BigInteger amount,
            int decimals, String tokenProgram, String recentBlockhash, boolean createRecipientAccount) {
        return splTransfer(owner, mint, recipient, amount, decimals, tokenProgram, recentBlockhash, createRecipientAccount);
    }

    /**
     * Throws when {@code recipient} exists and is owned by a program other than the System Program, for example a
     * token account pasted instead of a wallet address. A missing account is a valid new wallet. System-owned
     * accounts that hold data, such as durable nonce accounts, still pass.
     */
    public static void requireWalletRecipient(SolanaRpcClient client, String recipient) {
        String owner = client.getAccountOwner(recipient).orElse(SolanaPrograms.SYSTEM);
        if (!SolanaPrograms.SYSTEM.equals(owner)) {
            throw new IllegalArgumentException("recipient is an account owned by " + owner
                    + ", not a wallet; send to the owner's wallet address instead");
        }
    }

    private static SolanaTransaction splTransfer(String owner, String mint, String recipient, BigInteger amount,
            int decimals, String tokenProgram, String recentBlockhash, boolean createRecipientAccount) {
        if (decimals < 0 || decimals > 255) throw new IllegalArgumentException("decimals must be 0-255");
        String source = SolanaAddresses.associatedTokenAddress(owner, mint, tokenProgram);
        String destination = SolanaAddresses.associatedTokenAddress(recipient, mint, tokenProgram);
        List<SolanaInstruction> instructions = new ArrayList<>();
        if (createRecipientAccount) {
            instructions.add(new SolanaInstruction(SolanaPrograms.ASSOCIATED_TOKEN, List.of(
                    new AccountMeta(owner, true, true),
                    new AccountMeta(destination, false, true),
                    new AccountMeta(recipient, false, false),
                    new AccountMeta(mint, false, false),
                    new AccountMeta(SolanaPrograms.SYSTEM, false, false),
                    new AccountMeta(tokenProgram, false, false)), new byte[] {CREATE_IDEMPOTENT}));
        }
        ByteBuffer data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        data.put(TRANSFER_CHECKED).putLong(u64(amount)).put((byte) decimals);
        instructions.add(new SolanaInstruction(tokenProgram, List.of(
                new AccountMeta(source, false, true),
                new AccountMeta(mint, false, false),
                new AccountMeta(destination, false, true),
                new AccountMeta(owner, true, false)), data.array()));
        return SolanaTransaction.compile(owner, recentBlockhash, instructions);
    }

    private static void requireOnCurve(String recipient) {
        if (!SolanaAddresses.isOnCurve(SolanaAddresses.key(recipient))) {
            throw new IllegalArgumentException("recipient is not a wallet address (it is off the Ed25519 curve, like a "
                    + "token account); send to the owner's wallet address instead");
        }
    }

    private static long u64(BigInteger value) {
        if (value.signum() <= 0 || value.compareTo(U64_MAX) > 0) {
            throw new IllegalArgumentException("amount must be between 1 and 2^64-1 base units");
        }
        return value.longValue();
    }
}
