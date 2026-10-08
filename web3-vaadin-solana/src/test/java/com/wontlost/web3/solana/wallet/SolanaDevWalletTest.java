package com.wontlost.web3.solana.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;

import com.wontlost.web3.siws.Base58;
import com.wontlost.web3.siws.SolanaCluster;

class SolanaDevWalletTest {
    // RFC 8032 第 7.1 节 TEST 1：私钥种子与公钥
    private static final byte[] SEED = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final byte[] PUBLIC_KEY = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] EMPTY_MESSAGE_SIGNATURE = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015"
            + "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    @Test void derivesTheRfc8032PublicKeyAndSignature() {
        SolanaDevWallet wallet = new SolanaDevWallet(SEED, SolanaCluster.LOCALNET);

        assertEquals(Base58.encode(PUBLIC_KEY), wallet.address());
        assertTrue(Arrays.equals(EMPTY_MESSAGE_SIGNATURE, wallet.signMessage(new byte[0])));
        assertEquals(SolanaCluster.LOCALNET, wallet.cluster());
        assertEquals("Solana development wallet", wallet.name());
    }

    @Test void signaturesVerifyAgainstTheAddress() {
        SolanaDevWallet wallet = SolanaDevWallet.random(SolanaCluster.DEVNET);
        byte[] message = "hello solana".getBytes(StandardCharsets.UTF_8);

        byte[] signature = wallet.signMessage(message);

        assertEquals(64, signature.length);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(Base58.decode(wallet.address(), 32), 0));
        verifier.update(message, 0, message.length);
        assertTrue(verifier.verifySignature(signature));
    }

    @Test void randomWalletsDifferAndSeedIsCopied() {
        assertNotEquals(SolanaDevWallet.random(SolanaCluster.LOCALNET).address(),
                SolanaDevWallet.random(SolanaCluster.LOCALNET).address());
        byte[] seed = SEED.clone();
        SolanaDevWallet wallet = new SolanaDevWallet(seed, SolanaCluster.LOCALNET);
        Arrays.fill(seed, (byte) 0);
        assertEquals(Base58.encode(PUBLIC_KEY), wallet.address());
        assertTrue(Arrays.equals(EMPTY_MESSAGE_SIGNATURE, wallet.signMessage(new byte[0])));
    }

    @Test void rejectsInvalidInputsAndKeepsTheKeyOutOfToString() {
        assertThrows(IllegalArgumentException.class, () -> new SolanaDevWallet(new byte[31], SolanaCluster.LOCALNET));
        assertThrows(IllegalArgumentException.class, () -> new SolanaDevWallet(new byte[33], SolanaCluster.LOCALNET));
        assertThrows(NullPointerException.class, () -> new SolanaDevWallet(null, SolanaCluster.LOCALNET));
        assertThrows(NullPointerException.class, () -> new SolanaDevWallet(SEED, null));
        SolanaDevWallet wallet = new SolanaDevWallet(SEED, SolanaCluster.LOCALNET);
        assertThrows(NullPointerException.class, () -> wallet.signMessage(null));
        assertFalse(wallet.toString().contains(Base58.encode(SEED)));
        assertFalse(wallet.toString().toLowerCase().contains("9d61b19d"));
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }
}
