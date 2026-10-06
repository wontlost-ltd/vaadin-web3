package com.wontlost.web3;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.vaadin.flow.server.VaadinContext;

/**
 * A wallet whose keys live on the server, announced to the browser as an extra EIP-6963 wallet.
 * <p>
 * {@link Web3Connect} looks up the wallet registered for the application with {@link #find(VaadinContext)} and offers
 * it in the wallet picker next to the browser's own wallets. Requests the user makes through that wallet are forwarded
 * to {@link #request(String, String)} on the server; the private key never reaches the browser.
 * <p>
 * Intended for development and testing only, for example a local development wallet backed by a well-known test
 * key. Do not register a server wallet that controls real assets.
 */
public interface ServerWallet {

    /** Display name shown in the wallet picker, for example {@code "Development wallet"}. */
    String name();

    /** Reverse-DNS identifier announced through EIP-6963, for example {@code "com.wontlost.development-wallet"}. */
    String rdns();

    /** Checksummed accounts controlled by this wallet; the first account is the active one. */
    List<String> accounts();

    /** The chain this wallet operates on, as a {@code 0x}-prefixed hexadecimal chain ID. */
    String chainId();

    /**
     * Handles an EIP-1193 request.
     *
     * @param method     the JSON-RPC method, for example {@code personal_sign}
     * @param paramsJson the request parameters as a JSON array, never {@code null}
     * @return a future completing with the JSON-encoded result, or completing exceptionally with
     *         {@link ServerWalletException} for wallet errors that should reach the browser with an EIP-1193 code
     */
    CompletableFuture<String> request(String method, String paramsJson);

    /**
     * Registers the server wallet for the application. Registering the same instance again is a no-op; registering a
     * different instance throws {@link IllegalStateException}.
     */
    static void register(VaadinContext context, ServerWallet wallet) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(wallet, "wallet");
        ServerWallet current = context.getAttribute(ServerWallet.class);
        if (current != null && current != wallet) {
            throw new IllegalStateException("A different ServerWallet is already registered");
        }
        context.setAttribute(ServerWallet.class, wallet);
    }

    /** Returns the server wallet registered for the application, or {@code null} when none is registered. */
    static ServerWallet find(VaadinContext context) {
        return context == null ? null : context.getAttribute(ServerWallet.class);
    }

    /** A wallet error carrying an EIP-1193 / JSON-RPC error code, for example 4001 (user rejected) or 4902. */
    final class ServerWalletException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int code;

        public ServerWalletException(int code, String message) {
            super(message);
            this.code = code;
        }

        /** Returns the EIP-1193 / JSON-RPC error code. */
        public int getCode() {
            return code;
        }
    }
}
