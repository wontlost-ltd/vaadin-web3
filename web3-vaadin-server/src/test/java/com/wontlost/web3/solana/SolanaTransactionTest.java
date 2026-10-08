package com.wontlost.web3.solana;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;

import com.wontlost.web3.siws.Base58;
import com.wontlost.web3.solana.SolanaInstruction.AccountMeta;

/**
 * 黄金向量来自官方 CLI（Agave 4.3.0 的 {@code solana transfer} 与 {@code spl-token transfer}，
 * {@code --sign-only --dump-transaction-message}），发送方为 RFC 8032 第 7.1 节 TEST 1 的公开测试密钥。
 * 消息逐字节一致、签名与 CLI 输出一致，说明消息编译、指令编码与账户排序都与官方工具相同。
 */
class SolanaTransactionTest {
    private static final byte[] RFC_SEED = HexFormat.of()
            .parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final String SENDER = "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z";
    private static final String RECIPIENT = "3Gq36rTMG9B6NyN5ZbBiRBVBKnzoSrU8DJFCyz4kx9Tr";
    private static final String BLOCKHASH = "5LvHZdKWcQYtpy4JkmyRkyPWkhTEi1sNK1gTWuWvHKRF";
    private static final String TOKEN_MINT = "4xiDjfo4adkCzUmY94uXEvxqp9pdq89pEUY6UPXuptkp";
    private static final String TOKEN_2022_MINT = "24SSRKiEAtszF7poAcuNYTkfsGmo2zvd4gG3UCRQTita";

    private static final String SOL_MESSAGE = "AQABA9damAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1EaIcVmlYJO9XVLLgZf65nI08v07DDd"
            + "++mGDP7DV4voG50AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAECJCINLc3VXaHGLSMJYzE4mUJSprLDMM7ajKi7fHWF2AQICAAEM"
            + "AgAAAICy5g4AAAAA";
    private static final String SOL_SIGNATURE = "3V6s8EvNrJyE6E3aTTLXHim6UHi1eULj6XjW3QXj4g35NTWnKgraKQAdhc3Fh4jyiDDZ"
            + "gqGCEPtVKfAk7P8behjU";
    private static final String TOKEN_MESSAGE = "AQAFCNdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1EaVOswA9SbamP/jVLQQ187"
            + "AtMubG+DmEty73ElDeSUWnZ6oFMZVOctuZWW+52vzwg3FjMQfS81IAibmGJNOOVxaQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "Bt324ddloZPZy+FGzut5rBy0he1fWzeROoz1hX7/AKkhxWaVgk71dUsuBl/rmcjTy/TsMN376YYM/sNXi+gbnTrYnMargcvPC0leywQM2IYX"
            + "SlXjqtmBCnza/adFkTlxjJclj04kifG7PRApFI4NgwtaE5na/xCEBI572Nvp+FlAiQiDS3N1V2hxi0jCWMxOJlCUqaywzDO2oyou3x1hdgIH"
            + "BgACBQYDBAEBBAQBBgIACgxg4xYAAAAAAAY=";
    private static final String TOKEN_SIGNATURE = "2hrCC86eFGrca9u8Am9sSsYmFTXy4NwrwXVw11GVaXZzy3YWDCxs1PnNke5n1xRD"
            + "w4pfSR1GmiJTHtioodfSbiRy";
    private static final String TOKEN_2022_MESSAGE = "AQAFCNdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1EaXo2ShuwLfhAAfZDr"
            + "TnnOkF2zGZ5satbBSSMWdnMwKh3CjDfnEOLMi5ahdB2291Ym/KRm2yv+EwMm4GxEOkxnlgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAABt324e51j94YQl285GzN2rYa/E2DuQ0n/r35KNihi/wPvTFHGtDte9X62iW4y1AQEcvOX8BP1B/x78u7iua+MyHFZpWCTvV1Sy4GX+uZ"
            + "yNPL9Oww3fvphgz+w1eL6BudjJclj04kifG7PRApFI4NgwtaE5na/xCEBI572Nvp+FlAiQiDS3N1V2hxi0jCWMxOJlCUqaywzDO2oyou3x1h"
            + "dgIHBgABBgUDBAEBBAQCBQEACgwQVSIAAAAAAAY=";
    private static final String TOKEN_2022_SIGNATURE = "2qxCfaYt7k3j7y8zqeCTd7S1CTdTSiMki8xr6qSzw7AwYwXKGVsabcszc3iy"
            + "gqt8d6VpK2aaByiLUvsC3wWDd7Ac";

    @Test void solTransferMatchesTheSolanaCliByteForByte() {
        SolanaTransaction transaction = SolanaTransfers.sol(SENDER, RECIPIENT, BigInteger.valueOf(250_000_000L), BLOCKHASH);

        assertEquals(SOL_MESSAGE, Base64.getEncoder().encodeToString(transaction.message()));
        assertEquals(SOL_SIGNATURE, Base58.encode(sign(transaction.message())));
        assertEquals(List.of(SENDER), transaction.signers());
        assertEquals(List.of(SENDER, RECIPIENT, SolanaPrograms.SYSTEM), transaction.accountKeys());
    }

    @Test void splTransferWithRecipientAccountCreationMatchesSplTokenCli() {
        SolanaTransaction token = SolanaTransfers.spl(SENDER, TOKEN_MINT, RECIPIENT, BigInteger.valueOf(1_500_000L), 6,
                SolanaPrograms.TOKEN, BLOCKHASH, true);
        SolanaTransaction token2022 = SolanaTransfers.spl(SENDER, TOKEN_2022_MINT, RECIPIENT,
                BigInteger.valueOf(2_250_000L), 6, SolanaPrograms.TOKEN_2022, BLOCKHASH, true);

        assertEquals(TOKEN_MESSAGE, Base64.getEncoder().encodeToString(token.message()));
        assertEquals(TOKEN_SIGNATURE, Base58.encode(sign(token.message())));
        assertEquals(TOKEN_2022_MESSAGE, Base64.getEncoder().encodeToString(token2022.message()));
        assertEquals(TOKEN_2022_SIGNATURE, Base58.encode(sign(token2022.message())));
    }

    @Test void splTransferWithoutCreationHasOnlyTransferChecked() {
        SolanaTransaction transaction = SolanaTransfers.spl(SENDER, TOKEN_MINT, RECIPIENT, BigInteger.valueOf(5), 6,
                SolanaPrograms.TOKEN, BLOCKHASH, false);
        byte[] message = transaction.message();

        assertFalse(transaction.accountKeys().contains(SolanaPrograms.ASSOCIATED_TOKEN));
        assertFalse(transaction.accountKeys().contains(SolanaPrograms.SYSTEM));
        assertEquals(1, transaction.requiredSignatures());
        // 消息末尾是唯一一条指令的 TransferChecked 数据：12、amount(u64 LE)、decimals
        byte[] tail = Arrays.copyOfRange(message, message.length - 10, message.length);
        assertArrayEquals(new byte[] {12, 5, 0, 0, 0, 0, 0, 0, 0, 6}, tail);
    }

    @Test void associatedTokenAddressesMatchSplTokenCli() {
        // spl-token address --token <mint> --owner <owner> --verbose（Agave 4.3.0）
        Object[][] vectors = {
            {"71rzSYGQdEwVnQ7W7Khe8KWqbGrmzuHFMMsA8GZzxBwv", TOKEN_MINT, SolanaPrograms.TOKEN, "4auda3R4fp6MMVkREku3evE52YUzQgbmwsHZkoKBKfxw"},
            {"3Gq36rTMG9B6NyN5ZbBiRBVBKnzoSrU8DJFCyz4kx9Tr", TOKEN_MINT, SolanaPrograms.TOKEN, "9FgXoMUgroKZYP6mcyE6ETr1Xaz7baT7NnTswyi46wKe"},
            {"3Cexfn5ZQeFB824RrkFRoy2nDXXek3zLZe5fR8pZcsmQ", TOKEN_MINT, SolanaPrograms.TOKEN, "CLYnvo42dyYBGUswzjfVaqfPEyx9yZse2Xthpixv7N8A"},
            {"71rzSYGQdEwVnQ7W7Khe8KWqbGrmzuHFMMsA8GZzxBwv", TOKEN_2022_MINT, SolanaPrograms.TOKEN_2022, "4gP3qskiogL9pcqeU54AQzgRuXBhtRPBgkQUCSDwbKaV"},
            {"3Gq36rTMG9B6NyN5ZbBiRBVBKnzoSrU8DJFCyz4kx9Tr", TOKEN_2022_MINT, SolanaPrograms.TOKEN_2022, "7N6XnzNjqsnkJniJJFj8DHFTDE6Hb7rQZojUzEUicZJU"},
            {"3Cexfn5ZQeFB824RrkFRoy2nDXXek3zLZe5fR8pZcsmQ", TOKEN_2022_MINT, SolanaPrograms.TOKEN_2022, "36ZdcfwUnkvQ8T6XFgYJfSsdp6FBsNXP4Pe2vQADtVhm"},
            {SENDER, TOKEN_MINT, SolanaPrograms.TOKEN, "6iVFNCWpmDvN2HSPqAULfy3SKDep2kiCuzETn5K3viM3"},
            {SENDER, TOKEN_2022_MINT, SolanaPrograms.TOKEN_2022, "E6S6zB3uu7wrugbh7aFDCgwVoTmRc1TLQsHNgeh3x1cq"},
        };
        for (Object[] vector : vectors) {
            assertEquals(vector[3], SolanaAddresses.associatedTokenAddress((String) vector[0], (String) vector[1],
                    (String) vector[2]), Arrays.toString(vector));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SolanaAddresses.associatedTokenAddress(SENDER, TOKEN_MINT, SolanaPrograms.SYSTEM));
    }

    @Test void programAddressesAreOffCurveAndKeysAreOnCurve() {
        assertTrue(SolanaAddresses.isOnCurve(Base58.decode(SENDER, 32)), "an Ed25519 public key");
        String ata = SolanaAddresses.associatedTokenAddress(SENDER, TOKEN_MINT, SolanaPrograms.TOKEN);
        assertFalse(SolanaAddresses.isOnCurve(Base58.decode(ata, 32)));
        assertFalse(SolanaAddresses.isOnCurve(new byte[31]));
        SolanaAddresses.ProgramAddress found = SolanaAddresses.findProgramAddress(
                List.of(Base58.decode(SENDER, 32)), SolanaPrograms.ASSOCIATED_TOKEN);
        assertTrue(found.bump() >= 0 && found.bump() <= 255);
        assertThrows(IllegalArgumentException.class,
                () -> SolanaAddresses.findProgramAddress(List.of(new byte[33]), SolanaPrograms.SYSTEM));
        assertThrows(IllegalArgumentException.class, () -> SolanaAddresses.findProgramAddress(
                java.util.Collections.nCopies(16, new byte[1]), SolanaPrograms.SYSTEM));
    }

    @Test void wireFormatPlacesSignaturesInSignerOrder() {
        SolanaTransaction transaction = SolanaTransfers.sol(SENDER, RECIPIENT, BigInteger.ONE, BLOCKHASH);
        byte[] signature = sign(transaction.message());

        byte[] unsigned = transaction.unsignedWire();
        byte[] signed = transaction.wire(Map.of(SENDER, signature));

        assertEquals(1, unsigned[0]);
        assertArrayEquals(new byte[64], Arrays.copyOfRange(unsigned, 1, 65));
        assertArrayEquals(signature, Arrays.copyOfRange(signed, 1, 65));
        assertArrayEquals(transaction.message(), Arrays.copyOfRange(signed, 65, signed.length));
        assertEquals(65, SolanaTransaction.messageOffset(signed));
        assertEquals(Base58.encode(signature), SolanaTransaction.signatureOf(signed));
        assertThrows(IllegalArgumentException.class, () -> transaction.wire(Map.of(RECIPIENT, signature)));
        assertThrows(IllegalArgumentException.class, () -> transaction.wire(Map.of(SENDER, new byte[63])));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.messageOffset(new byte[] {1, 0}));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.signatureOf(new byte[] {0, 1}));
    }

    @Test void multipleSignersAndPermissionsAreMergedAndOrdered() {
        String cosigner = RECIPIENT;
        String readonly = TOKEN_MINT;
        SolanaInstruction first = new SolanaInstruction(SolanaPrograms.SYSTEM, List.of(
                new AccountMeta(readonly, false, false), new AccountMeta(cosigner, true, false)), new byte[] {1});
        SolanaInstruction second = new SolanaInstruction(SolanaPrograms.SYSTEM, List.of(
                new AccountMeta(readonly, false, true)), new byte[0]);

        SolanaTransaction transaction = SolanaTransaction.compile(SENDER, BLOCKHASH, List.of(first, second));
        byte[] message = transaction.message();

        assertEquals(List.of(SENDER, cosigner), transaction.signers());
        assertEquals(List.of(SENDER, cosigner, readonly, SolanaPrograms.SYSTEM), transaction.accountKeys(),
                "writable signer, read-only signer, writable (merged) non-signer, read-only program");
        assertArrayEquals(new byte[] {2, 1, 1}, Arrays.copyOfRange(message, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.compile(SENDER, BLOCKHASH, List.of()));
    }

    @Test void splTransfersRefuseTokenAccountsAsRecipients() {
        String recipientTokenAccount = SolanaAddresses.associatedTokenAddress(RECIPIENT, TOKEN_MINT, SolanaPrograms.TOKEN);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> SolanaTransfers.spl(SENDER,
                TOKEN_MINT, recipientTokenAccount, BigInteger.ONE, 6, SolanaPrograms.TOKEN, BLOCKHASH, true));
        assertTrue(refused.getMessage().contains("not a wallet address"));

        SolanaTransaction toVault = SolanaTransfers.splToProgramAddress(SENDER, TOKEN_MINT, recipientTokenAccount,
                BigInteger.ONE, 6, SolanaPrograms.TOKEN, BLOCKHASH, true);
        assertTrue(toVault.accountKeys().contains(recipientTokenAccount), "explicit opt-in for program-derived wallets");
    }

    @Test void requireWalletRecipientChecksTheAccountOwner() {
        java.util.Map<String, String> owners = new java.util.HashMap<>(java.util.Map.of(
                RECIPIENT, SolanaPrograms.SYSTEM, TOKEN_MINT, SolanaPrograms.TOKEN));
        SolanaRpcClient client = new SolanaRpcClient(request -> {
            var node = new tools.jackson.databind.ObjectMapper().readTree(request);
            String owner = owners.get(node.path("params").get(0).asString());
            String value = owner == null ? "null" : "{\"owner\":\"" + owner + "\",\"lamports\":1}";
            return "{\"jsonrpc\":\"2.0\",\"id\":" + node.path("id").asLong()
                    + ",\"result\":{\"context\":{\"slot\":1},\"value\":" + value + "}}";
        }, SolanaCommitment.CONFIRMED);

        SolanaTransfers.requireWalletRecipient(client, RECIPIENT);
        SolanaTransfers.requireWalletRecipient(client, SENDER);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SolanaTransfers.requireWalletRecipient(client, TOKEN_MINT));
        assertTrue(refused.getMessage().contains(SolanaPrograms.TOKEN));
    }

    @Test void wireRejectsSignaturesFromTheWrongKeyOrOverOtherBytes() {
        SolanaTransaction transaction = SolanaTransfers.sol(SENDER, RECIPIENT, BigInteger.ONE, BLOCKHASH);
        SolanaTransaction other = SolanaTransfers.sol(SENDER, RECIPIENT, BigInteger.TWO, BLOCKHASH);
        byte[] otherKeySignature = signWith(new Ed25519PrivateKeyParameters(new java.security.SecureRandom()),
                transaction.message());

        assertThrows(IllegalArgumentException.class, () -> transaction.wire(Map.of(SENDER, otherKeySignature)));
        assertThrows(IllegalArgumentException.class, () -> transaction.wire(Map.of(SENDER, sign(other.message()))));
        java.util.Map<String, byte[]> withNull = new java.util.HashMap<>();
        withNull.put(SENDER, null);
        assertThrows(IllegalArgumentException.class, () -> transaction.wire(withNull));
    }

    @Test void oversizedTransactionsAreRejectedAtCompileTime() {
        SolanaInstruction large = new SolanaInstruction(SolanaPrograms.SYSTEM,
                List.of(new AccountMeta(RECIPIENT, false, true)), new byte[1100]);
        SolanaInstruction fits = new SolanaInstruction(SolanaPrograms.SYSTEM,
                List.of(new AccountMeta(RECIPIENT, false, true)), new byte[900]);

        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.compile(SENDER, BLOCKHASH, List.of(large)));
        SolanaTransaction compiled = SolanaTransaction.compile(SENDER, BLOCKHASH, List.of(fits));
        assertTrue(compiled.unsignedWire().length <= SolanaTransaction.MAX_SIZE);
    }

    @Test void feePayerAndProgramPermissionsMergeWithInstructionAccounts() {
        SolanaInstruction payerReadOnly = new SolanaInstruction(SolanaPrograms.SYSTEM, List.of(
                new AccountMeta(SENDER, false, false), new AccountMeta(SolanaPrograms.TOKEN, false, true)), new byte[0]);

        SolanaTransaction transaction = SolanaTransaction.compile(SENDER, BLOCKHASH, List.of(payerReadOnly));
        byte[] message = transaction.message();

        assertEquals(List.of(SENDER, SolanaPrograms.TOKEN, SolanaPrograms.SYSTEM), transaction.accountKeys(),
                "payer stays the writable signer; a program passed as writable is a writable non-signer");
        assertArrayEquals(new byte[] {1, 0, 1}, Arrays.copyOfRange(message, 0, 3));
    }

    @Test void selfTransfersAreBuilt() {
        SolanaTransaction sol = SolanaTransfers.sol(SENDER, SENDER, BigInteger.ONE, BLOCKHASH);
        assertEquals(List.of(SENDER, SolanaPrograms.SYSTEM), sol.accountKeys());
        SolanaTransaction spl = SolanaTransfers.spl(SENDER, TOKEN_MINT, SENDER, BigInteger.ONE, 6, SolanaPrograms.TOKEN,
                BLOCKHASH, false);
        assertEquals(1, spl.requiredSignatures());
    }

    @Test void onCurveIsStricterThanSolanaForDegenerateEncodings() {
        byte[] identity = new byte[32];
        identity[0] = 1;
        assertFalse(SolanaAddresses.isOnCurve(identity), "documented divergence: the identity point decompresses in dalek");
    }

    @Test void moreThan256AccountsAreRejected() {
        java.util.List<AccountMeta> accounts = new java.util.ArrayList<>();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 256; i++) {
            byte[] key = new byte[32];
            random.nextBytes(key);
            accounts.add(new AccountMeta(Base58.encode(key), false, false));
        }
        SolanaInstruction wide = new SolanaInstruction(SolanaPrograms.SYSTEM, accounts, new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.compile(SENDER, BLOCKHASH, List.of(wide)),
                "fee payer + 256 accounts + program exceed one-byte account indices");
    }

    @Test void compactU16RoundTripsAndRejectsNonCanonicalEncodings() {
        int[][] cases = {{0, 1}, {0x7F, 1}, {0x80, 2}, {0x3FFF, 2}, {0x4000, 3}, {0xFFFF, 3}};
        for (int[] value : cases) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            SolanaTransaction.writeLength(out, value[0]);
            byte[] encoded = out.toByteArray();
            assertEquals(value[1], encoded.length, "length of " + value[0]);
            assertArrayEquals(new int[] {value[0], value[1]}, SolanaTransaction.readLength(encoded, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.writeLength(new ByteArrayOutputStream(), 0x10000));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.writeLength(new ByteArrayOutputStream(), -1));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.readLength(new byte[] {(byte) 0x80, 0}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SolanaTransaction.readLength(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, 1}, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SolanaTransaction.readLength(new byte[] {(byte) 0xFF, (byte) 0xFF, 0x04}, 0));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransaction.readLength(new byte[] {(byte) 0x80}, 0));
    }

    @Test void transferAmountsAndDecimalsAreValidated() {
        BigInteger tooLarge = BigInteger.ONE.shiftLeft(64);
        assertThrows(IllegalArgumentException.class, () -> SolanaTransfers.sol(SENDER, RECIPIENT, BigInteger.ZERO, BLOCKHASH));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransfers.sol(SENDER, RECIPIENT, tooLarge, BLOCKHASH));
        SolanaTransaction max = SolanaTransfers.sol(SENDER, RECIPIENT, tooLarge.subtract(BigInteger.ONE), BLOCKHASH);
        byte[] message = max.message();
        assertArrayEquals(new byte[] {-1, -1, -1, -1, -1, -1, -1, -1},
                Arrays.copyOfRange(message, message.length - 8, message.length));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransfers.spl(SENDER, TOKEN_MINT, RECIPIENT,
                BigInteger.ONE, 256, SolanaPrograms.TOKEN, BLOCKHASH, false));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransfers.spl(SENDER, TOKEN_MINT, RECIPIENT,
                BigInteger.ONE, -1, SolanaPrograms.TOKEN, BLOCKHASH, false));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransfers.sol(SENDER, "0xabc", BigInteger.ONE, BLOCKHASH));
        assertThrows(IllegalArgumentException.class, () -> SolanaTransfers.sol(SENDER, RECIPIENT, BigInteger.ONE, "short"));
    }

    @Test void instructionsDefensivelyCopyTheirData() {
        byte[] data = {1, 2};
        SolanaInstruction instruction = new SolanaInstruction(SolanaPrograms.SYSTEM, List.of(), data);
        data[0] = 9;
        byte[] read = instruction.data();
        read[1] = 9;
        assertArrayEquals(new byte[] {1, 2}, instruction.data());
    }

    private static byte[] signWith(Ed25519PrivateKeyParameters key, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    private static byte[] sign(byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(RFC_SEED, 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }
}
