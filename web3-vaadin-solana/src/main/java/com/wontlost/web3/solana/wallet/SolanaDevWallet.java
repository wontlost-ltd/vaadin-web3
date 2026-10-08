package com.wontlost.web3.solana.wallet;

import java.security.SecureRandom;
import java.util.Objects;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import com.wontlost.web3.siws.Base58;
import com.wontlost.web3.siws.SolanaCluster;

/**
 * A server-side Ed25519 development wallet. It signs every request without asking, so use it only on a valueless
 * local or test cluster, never for real assets.
 */
public final class SolanaDevWallet implements SolanaServerWallet {
    private final Ed25519PrivateKeyParameters key;
    private final String address;
    private final SolanaCluster cluster;

    /** Creates a wallet from a 32-byte Ed25519 seed. The seed is copied and kept only on this server. */
    public SolanaDevWallet(byte[] seed, SolanaCluster cluster) {
        Objects.requireNonNull(seed, "seed");
        if (seed.length != Ed25519PrivateKeyParameters.KEY_SIZE) {
            throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
        }
        this.key = new Ed25519PrivateKeyParameters(seed.clone(), 0);
        this.address = Base58.encode(key.generatePublicKey().getEncoded());
        this.cluster = Objects.requireNonNull(cluster, "cluster");
    }

    /** Creates a wallet with a fresh random key, valid until the server restarts. */
    public static SolanaDevWallet random(SolanaCluster cluster) {
        byte[] seed = new byte[Ed25519PrivateKeyParameters.KEY_SIZE];
        new SecureRandom().nextBytes(seed);
        return new SolanaDevWallet(seed, cluster);
    }

    @Override public String name() { return "Solana development wallet"; }
    @Override public String address() { return address; }
    @Override public SolanaCluster cluster() { return cluster; }

    @Override
    public byte[] signMessage(byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        byte[] bytes = Objects.requireNonNull(message, "message");
        signer.update(bytes, 0, bytes.length);
        return signer.generateSignature();
    }

    @Override
    public String toString() {
        return "SolanaDevWallet[" + address + ", " + cluster + "]";
    }
}
