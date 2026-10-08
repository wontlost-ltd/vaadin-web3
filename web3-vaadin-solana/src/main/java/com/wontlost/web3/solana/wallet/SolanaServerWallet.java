package com.wontlost.web3.solana.wallet;

import java.util.Objects;

import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.startup.ApplicationConfiguration;
import com.wontlost.web3.siws.SolanaCluster;

/**
 * A Solana wallet whose key lives on the server, offered to the browser as an extra Wallet Standard wallet.
 * <p>
 * {@link SolanaConnect} looks up the wallet registered for the application and registers it in the page next to the
 * browser's own wallets. Signing requests made through it are forwarded to the server; the private key never reaches
 * the browser. Intended for development and testing only: {@link #register(VaadinContext, SolanaServerWallet)}
 * refuses to register one in Vaadin production mode, so the application fails at startup.
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
     * Registers the server wallet for the application, typically from a {@code VaadinServiceInitListener}. Throws
     * {@link IllegalStateException} in Vaadin production mode, or when a different wallet is already registered.
     * Registering the same instance again is a no-op. The context must provide Vaadin's
     * {@code ApplicationConfiguration}, as the context of a running {@code VaadinService} does.
     */
    static void register(VaadinContext context, SolanaServerWallet wallet) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(wallet, "wallet");
        requireDevelopmentMode(wallet, ApplicationConfiguration.get(context).isProductionMode());
        SolanaServerWallet current = context.getAttribute(SolanaServerWallet.class);
        if (current != null && current != wallet) {
            throw new IllegalStateException("A different SolanaServerWallet is already registered");
        }
        context.setAttribute(SolanaServerWallet.class, wallet);
    }

    /** Throws when {@code wallet} is present and the application runs in production mode. */
    static void requireDevelopmentMode(SolanaServerWallet wallet, boolean productionMode) {
        if (wallet != null && productionMode) {
            throw new IllegalStateException("A SolanaServerWallet (" + wallet.getClass().getName()
                    + ") must not be registered in Vaadin production mode; server wallets are for development only");
        }
    }

    /** Returns the server wallet registered for the application, or {@code null}. */
    static SolanaServerWallet find(VaadinContext context) {
        return context == null ? null : context.getAttribute(SolanaServerWallet.class);
    }
}
