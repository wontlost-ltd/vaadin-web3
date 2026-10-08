package com.wontlost.web3.solana;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bouncycastle.math.ec.rfc8032.Ed25519;

import com.wontlost.web3.siws.Base58;

/**
 * A legacy-format Solana transaction: the compiled message plus one signature slot per required signer.
 * <p>
 * The message lists every account once, ordered as the runtime requires: writable signers (fee payer first),
 * read-only signers, writable non-signers, then read-only non-signers; within each group accounts are sorted by
 * public key, as Agave does, so the bytes match the official Solana tooling. The fee payer signs and pays; every other
 * signer must also sign before the transaction is sent.
 */
public final class SolanaTransaction {
    /** Maximum serialized transaction size accepted by the network (an IPv6 MTU minus headers). */
    public static final int MAX_SIZE = 1232;

    private final List<String> accountKeys;
    private final int requiredSignatures;
    private final byte[] message;

    private SolanaTransaction(List<String> accountKeys, int requiredSignatures, byte[] message) {
        this.accountKeys = List.copyOf(accountKeys);
        this.requiredSignatures = requiredSignatures;
        this.message = message;
    }

    /** Compiles {@code instructions} into a message paid by {@code feePayer} and bound to {@code recentBlockhash}. */
    public static SolanaTransaction compile(String feePayer, String recentBlockhash, List<SolanaInstruction> instructions) {
        if (instructions.isEmpty()) throw new IllegalArgumentException("at least one instruction is required");
        byte[] blockhash = SolanaAddresses.key(recentBlockhash);
        // 账户去重并合并权限
        Map<String, int[]> flags = new LinkedHashMap<>();
        merge(flags, feePayer, true, true);
        for (SolanaInstruction instruction : instructions) {
            for (SolanaInstruction.AccountMeta meta : instruction.accounts()) {
                merge(flags, meta.address(), meta.signer(), meta.writable());
            }
            merge(flags, instruction.programId(), false, false);
        }
        // 与 Agave 的 CompiledKeys 一致：手续费支付者在首位，其余账户在各权限组内按公钥字节升序，
        // 因此与官方 CLI 生成的消息逐字节相同
        List<String> keys = new ArrayList<>(List.of(feePayer));
        for (int group = 0; group < 4; group++) {
            final int current = group;
            flags.entrySet().stream()
                    .filter(entry -> group(entry.getValue()) == current && !entry.getKey().equals(feePayer))
                    .map(Map.Entry::getKey)
                    .sorted((left, right) -> Arrays.compareUnsigned(SolanaAddresses.key(left), SolanaAddresses.key(right)))
                    .forEach(keys::add);
        }
        // 指令中的账户索引是单字节
        if (keys.size() > 256) throw new IllegalArgumentException("too many accounts for a legacy transaction");
        int signers = count(flags, 0) + count(flags, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(signers);
        out.write(count(flags, 1));
        out.write(count(flags, 3));
        writeLength(out, keys.size());
        keys.forEach(key -> out.writeBytes(SolanaAddresses.key(key)));
        out.writeBytes(blockhash);
        writeLength(out, instructions.size());
        for (SolanaInstruction instruction : instructions) {
            out.write(keys.indexOf(instruction.programId()));
            writeLength(out, instruction.accounts().size());
            instruction.accounts().forEach(meta -> out.write(keys.indexOf(meta.address())));
            byte[] data = instruction.data();
            writeLength(out, data.length);
            out.writeBytes(data);
        }
        byte[] message = out.toByteArray();
        // 在编译时就拒绝永远发不出去的交易；1232 字节上限意味着签名者不超过十余个，签名数的 compact-u16 只占 1 字节
        if (1 + signers * 64 + message.length > MAX_SIZE) {
            throw new IllegalArgumentException("transaction exceeds " + MAX_SIZE + " bytes");
        }
        return new SolanaTransaction(keys, signers, message);
    }

    /** The account keys in message order; the first {@link #requiredSignatures()} must sign. */
    public List<String> accountKeys() {
        return accountKeys;
    }

    /** The number of signatures the transaction needs. */
    public int requiredSignatures() {
        return requiredSignatures;
    }

    /** The signers in signature-slot order, fee payer first. */
    public List<String> signers() {
        return accountKeys.subList(0, requiredSignatures);
    }

    /** The serialized message: the exact bytes each signer signs with Ed25519. */
    public byte[] message() {
        return message.clone();
    }

    /** The wire format with zeroed signature slots, as passed to a wallet's {@code signAndSendTransaction}. */
    public byte[] unsignedWire() {
        return wire(Map.of());
    }

    /**
     * The wire format with the given signatures (keyed by signer address) in their slots; missing signers keep a
     * zeroed slot. Each signature is verified against its signer and this message, so a wrong key or a signature over
     * other bytes fails here rather than at the node.
     */
    public byte[] wire(Map<String, byte[]> signatures) {
        for (Map.Entry<String, byte[]> entry : signatures.entrySet()) {
            if (!signers().contains(entry.getKey())) throw new IllegalArgumentException("not a signer of this transaction");
            byte[] signature = entry.getValue();
            if (signature == null || signature.length != 64) {
                throw new IllegalArgumentException("Ed25519 signatures are 64 bytes");
            }
            if (!Ed25519.verify(signature, 0, SolanaAddresses.key(entry.getKey()), 0, message, 0, message.length)) {
                throw new IllegalArgumentException("signature does not match the signer and this message");
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLength(out, requiredSignatures);
        for (String signer : signers()) {
            out.writeBytes(signatures.getOrDefault(signer, new byte[64]));
        }
        out.writeBytes(message);
        byte[] wire = out.toByteArray();
        // 防御性断言：compile 已保证不超限
        if (wire.length > MAX_SIZE) throw new IllegalArgumentException("transaction exceeds " + MAX_SIZE + " bytes");
        return wire;
    }

    /** The transaction id: the base58 fee-payer signature taken from a signed wire transaction. */
    public static String signatureOf(byte[] wire) {
        int[] header = readLength(wire, 0);
        if (header[0] < 1 || wire.length < header[1] + 64) throw new IllegalArgumentException("unsigned transaction");
        return Base58.encode(Arrays.copyOfRange(wire, header[1], header[1] + 64));
    }

    /** The offset of the message within a wire transaction (after the signature slots). */
    public static int messageOffset(byte[] wire) {
        int[] header = readLength(wire, 0);
        int offset = header[1] + header[0] * 64;
        if (offset >= wire.length) throw new IllegalArgumentException("truncated transaction");
        return offset;
    }

    /** Solana's compact-u16 length encoding: 7 bits per byte, low bits first, high bit set when more follow. */
    static void writeLength(ByteArrayOutputStream out, int value) {
        if (value < 0 || value > 0xFFFF) throw new IllegalArgumentException("compact-u16 out of range");
        int remaining = value;
        while (remaining >= 0x80) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    /** 解码 compact-u16，返回 {值, 读取后的偏移}；最多 3 字节且必须是最短编码。 */
    static int[] readLength(byte[] input, int offset) {
        int value = 0;
        for (int i = 0; i < 3; i++) {
            if (offset + i >= input.length) throw new IllegalArgumentException("truncated compact-u16");
            int b = input[offset + i] & 0xFF;
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                if (i > 0 && b == 0) throw new IllegalArgumentException("non-canonical compact-u16");
                if (value > 0xFFFF) throw new IllegalArgumentException("compact-u16 out of range");
                return new int[] {value, offset + i + 1};
            }
        }
        throw new IllegalArgumentException("compact-u16 too long");
    }

    private static void merge(Map<String, int[]> flags, String address, boolean signer, boolean writable) {
        int[] current = flags.computeIfAbsent(Objects.requireNonNull(address), key -> new int[2]);
        current[0] |= signer ? 1 : 0;
        current[1] |= writable ? 1 : 0;
    }

    /** 0 可写签名者、1 只读签名者、2 可写非签名者、3 只读非签名者。 */
    private static int group(int[] flag) {
        return (flag[0] == 1 ? 0 : 2) + (flag[1] == 1 ? 0 : 1);
    }

    private static int count(Map<String, int[]> flags, int group) {
        return (int) flags.values().stream().filter(flag -> group(flag) == group).count();
    }
}
