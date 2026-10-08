package com.wontlost.web3.solana.wallet;

import java.security.SecureRandom;
import java.util.Objects;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import com.wontlost.web3.siws.Base58;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaRpcClient;

/**
 * A server-side Ed25519 development wallet. It signs every request without asking, so use it only on a valueless
 * local or test cluster, never for real assets.
 */
public final class SolanaDevWallet implements SolanaServerWallet {
    private final Ed25519PrivateKeyParameters key;
    private final String address;
    private final SolanaCluster cluster;
    private final SolanaRpcClient rpc;

    /** Creates a wallet from a 32-byte Ed25519 seed. The seed is copied and kept only on this server. */
    public SolanaDevWallet(byte[] seed, SolanaCluster cluster) {
        this(seed, cluster, null);
    }

    /** Creates a wallet that can also send the transactions it signs through {@code rpc} (may be {@code null}). */
    public SolanaDevWallet(byte[] seed, SolanaCluster cluster, SolanaRpcClient rpc) {
        Objects.requireNonNull(seed, "seed");
        if (seed.length != Ed25519PrivateKeyParameters.KEY_SIZE) {
            throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
        }
        this.key = new Ed25519PrivateKeyParameters(seed.clone(), 0);
        this.address = Base58.encode(key.generatePublicKey().getEncoded());
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.rpc = rpc;
    }

    /** Creates a wallet with a fresh random key, valid until the server restarts. */
    public static SolanaDevWallet random(SolanaCluster cluster) {
        return random(cluster, null);
    }

    /** Creates a wallet with a fresh random key that sends its transactions through {@code rpc}. */
    public static SolanaDevWallet random(SolanaCluster cluster, SolanaRpcClient rpc) {
        byte[] seed = new byte[Ed25519PrivateKeyParameters.KEY_SIZE];
        new SecureRandom().nextBytes(seed);
        return new SolanaDevWallet(seed, cluster, rpc);
    }

    @Override
    public String sendTransaction(byte[] signedTransaction) {
        if (rpc == null) return SolanaServerWallet.super.sendTransaction(signedTransaction);
        return rpc.sendTransaction(signedTransaction);
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
