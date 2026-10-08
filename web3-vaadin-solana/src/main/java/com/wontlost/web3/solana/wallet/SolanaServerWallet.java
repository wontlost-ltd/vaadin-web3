package com.wontlost.web3.solana.wallet;

import java.util.Objects;

import com.vaadin.flow.server.VaadinContext;
import com.wontlost.web3.siws.SolanaCluster;

/**
 * A Solana wallet whose key lives on the server, offered to the browser as an extra Wallet Standard wallet.
 * <p>
 * {@link SolanaConnect} looks up the wallet registered for the application and registers it in the page next to the
 * browser's own wallets. Signing requests made through it are forwarded to the server; the private key never reaches
 * the browser. Intended for development and testing only: {@link SolanaConnect} refuses to attach in Vaadin
 * production mode while a server wallet is registered.
 */
public interface SolanaServerWallet {

    /** Display name shown in the wallet picker, for example {@code "Solana development wallet"}. */
    String name();

    /** The wallet's base58 public key. */
    String address();

    /** The cluster the wallet operates on. */
    SolanaCluster cluster();

    /** Signs {@code message} with the wallet's Ed25519 key and returns the 64-byte signature. */
    byte[] signMessage(byte[] message);

    /**
     * Registers the server wallet for the application. Registering the same instance again is a no-op; registering a
     * different instance throws {@link IllegalStateException}.
     */
    static void register(VaadinContext context, SolanaServerWallet wallet) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(wallet, "wallet");
        SolanaServerWallet current = context.getAttribute(SolanaServerWallet.class);
        if (current != null && current != wallet) {
            throw new IllegalStateException("A different SolanaServerWallet is already registered");
        }
        context.setAttribute(SolanaServerWallet.class, wallet);
    }

    /** Returns the server wallet registered for the application, or {@code null}. */
    static SolanaServerWallet find(VaadinContext context) {
        return context == null ? null : context.getAttribute(SolanaServerWallet.class);
    }
}
