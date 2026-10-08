package com.wontlost.web3.solana;

/**
 * A recent blockhash to bind a transaction to, the last block height at which it is still accepted, and the slot it
 * was read at. Read it at {@code confirmed}: a {@code processed} blockhash may belong to a fork that never lands.
 * <p>
 * Only rebuild and resend a transaction once {@code getBlockHeight()} at {@code confirmed} (or {@code finalized})
 * exceeds {@link #lastValidBlockHeight()} <em>and</em> {@code getSignatureStatuses(…, true)} still has no status for
 * the old signature. Before that, the old transaction may still land and the retry would pay twice.
 */
public record LatestBlockhash(String blockhash, long lastValidBlockHeight, long slot) {
}
