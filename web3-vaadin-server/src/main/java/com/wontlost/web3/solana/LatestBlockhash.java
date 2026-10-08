package com.wontlost.web3.solana;

/**
 * A recent blockhash to bind a transaction to, the last block height at which it is still accepted, and the slot it
 * was read at. A transaction whose blockhash has expired can never land, so it is safe to rebuild and retry.
 */
public record LatestBlockhash(String blockhash, long lastValidBlockHeight, long slot) {
}
