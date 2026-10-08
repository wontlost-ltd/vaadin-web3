package com.wontlost.web3.solana;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

import org.bouncycastle.math.ec.rfc8032.Ed25519;

import com.wontlost.web3.siws.Base58;

/** Program-derived addresses (PDAs), including associated token accounts. */
public final class SolanaAddresses {
    private static final byte[] PDA_MARKER = "ProgramDerivedAddress".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_SEED_LENGTH = 32;
    private static final int MAX_SEEDS = 16;

    private SolanaAddresses() {
    }

    /** A program-derived address and the bump seed that produced it. */
    public record ProgramAddress(String address, int bump) {
    }

    /**
     * The associated token account of {@code owner} for {@code mint} under {@code tokenProgram}
     * ({@link SolanaPrograms#TOKEN} or {@link SolanaPrograms#TOKEN_2022}).
     */
    public static String associatedTokenAddress(String owner, String mint, String tokenProgram) {
        if (!SolanaPrograms.isTokenProgram(tokenProgram)) {
            throw new IllegalArgumentException("tokenProgram must be the SPL Token or Token-2022 program");
        }
        return findProgramAddress(List.of(key(owner), key(tokenProgram), key(mint)), SolanaPrograms.ASSOCIATED_TOKEN)
                .address();
    }

    /**
     * Finds the program address for {@code seeds}, trying bump seeds from 255 down to 1 like
     * {@code Pubkey::find_program_address}: the first SHA-256 result that is not an Ed25519 point is the address.
     */
    public static ProgramAddress findProgramAddress(List<byte[]> seeds, String programId) {
        if (seeds.size() >= MAX_SEEDS) throw new IllegalArgumentException("too many seeds");
        for (byte[] seed : seeds) {
            if (Objects.requireNonNull(seed).length > MAX_SEED_LENGTH) throw new IllegalArgumentException("seed too long");
        }
        byte[] program = key(programId);
        for (int bump = 255; bump >= 1; bump--) {
            byte[] candidate = hash(seeds, (byte) bump, program);
            if (!isOnCurve(candidate)) return new ProgramAddress(Base58.encode(candidate), bump);
        }
        throw new IllegalStateException("no viable bump seed");
    }

    /**
     * Whether 32 bytes are a usable Ed25519 public key. PDAs must not be, so no private key can sign for them.
     * <p>
     * Solana's {@code is_on_curve} is curve25519-dalek's {@code decompress}. BouncyCastle's partial validation used
     * here additionally rejects a few dozen encodings that decompress: non-canonical y values, the small-order points
     * (including the identity, {@code 01 00…00}) and "negative zero". No private key can sign for those, and a SHA-256
     * output hits one with negligible probability, so program-address derivation is unaffected; for those inputs this
     * method answers {@code false} where Solana would say {@code true}.
     */
    public static boolean isOnCurve(byte[] key) {
        return key.length == 32 && Ed25519.validatePublicKeyPartial(key, 0);
    }

    private static byte[] hash(List<byte[]> seeds, byte bump, byte[] program) {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        seeds.forEach(input::writeBytes);
        input.write(bump);
        input.writeBytes(program);
        input.writeBytes(PDA_MARKER);
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] key(String address) {
        return Base58.decode(Objects.requireNonNull(address, "address"), 32);
    }
}
