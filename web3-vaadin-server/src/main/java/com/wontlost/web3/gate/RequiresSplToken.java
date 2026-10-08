package com.wontlost.web3.gate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.wontlost.web3.siws.SolanaCluster;

/**
 * Requires a Solana sign-in on {@link #cluster()} whose wallet holds at least {@link #minBalance()} of the SPL token
 * {@link #mint()} (Token or Token-2022) to enter a routed view. Visitors who are not signed in are sent to
 * {@link #redirectTo()} with a {@code continue} link; others are shown the access-denied page, or a temporary-error
 * page when the balance cannot be read.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresSplToken {
    /** The token mint address (base58). */
    String mint();

    /** The minimum balance in whole tokens, for example {@code "1"} or {@code "0.5"}. */
    String minBalance() default "1";

    /** The cluster the wallet must be signed in on and the balance is read from. */
    SolanaCluster cluster() default SolanaCluster.MAINNET;

    /** A display name for the token in the access-denied message, for example {@code "USDC"}. */
    String symbol() default "tokens";

    /** The route of the sign-in view. */
    String redirectTo() default "login";
}
