package com.wontlost.web3.test;

import java.nio.charset.StandardCharsets;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/** A local test account that signs personal messages and EIP-712 typed data. */
public final class TestWallet {
    /**
     * Creates a random wallet.
     */
    public static TestWallet random() {
        try {
            return new TestWallet(Keys.createEcKeyPair());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create a random test wallet", exception);
        }
    }

    /** Creates a wallet from a hexadecimal private key. */
    public static TestWallet fromPrivateKey(String hex) {
        String value = hex == null ? "" : hex.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Private key must be 32-byte hexadecimal");
        return new TestWallet(ECKeyPair.create(Numeric.toBigInt(value)));
    }

    /**
     * Returns Anvil's public account #0 or #1. These private keys are publicly known and must only be used for tests
     * on local development chains; never use them with real assets. The private keys are
     * {@code 0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80} and
     * {@code 0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d}.
     */
    public static TestWallet anvil(int index) {
        return switch (index) {
            case 0 -> fromPrivateKey("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
            case 1 -> fromPrivateKey("59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
            default -> throw new IllegalArgumentException("Only Anvil accounts 0 and 1 are supported");
        };
    }

    private final ECKeyPair keyPair;
    private final String address;

    private TestWallet(ECKeyPair keyPair) {
        this.keyPair = keyPair;
        this.address = Keys.toChecksumAddress("0x" + Keys.getAddress(keyPair.getPublicKey()));
    }

    /** Returns the EIP-55 checksummed address. */
    public String address() { return address; }

    /** Signs UTF-8 text using the Ethereum personal-message prefix. */
    public String signPersonalMessage(String message) {
        return signPersonalMessage(message.getBytes(StandardCharsets.UTF_8));
    }

    /** Signs bytes using the Ethereum personal-message prefix. */
    public String signPersonalMessage(byte[] message) {
        return encode(Sign.signPrefixedMessage(message.clone(), keyPair));
    }

    /** Signs an EIP-712 JSON typed-data document. */
    public String signTypedData(String json) {
        try {
            return encode(Sign.signTypedData(json, keyPair));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid EIP-712 typed data", exception);
        }
    }

    private static String encode(Sign.SignatureData signature) {
        byte[] bytes = new byte[65];
        System.arraycopy(signature.getR(), 0, bytes, 0, 32);
        System.arraycopy(signature.getS(), 0, bytes, 32, 32);
        bytes[64] = signature.getV()[0];
        return Numeric.toHexString(bytes);
    }

    @Override
    public String toString() { return "TestWallet[address=" + address + "]"; }
}
