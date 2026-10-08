package com.wontlost.web3.solana;

/**
 * The cluster's view of a transaction signature.
 *
 * @param slot               the slot that processed the transaction
 * @param confirmations      blocks since then, or {@code null} once the transaction is rooted (finalized)
 * @param confirmationStatus the commitment the transaction has reached
 * @param error              the transaction error as JSON (for example {@code {"InstructionError":[0,...]}}),
 *                           or {@code null} when it succeeded
 */
public record SignatureStatus(long slot, Long confirmations, SolanaCommitment confirmationStatus, String error) {

    /** Whether the transaction failed on chain. A failed transaction still pays its fee. */
    public boolean failed() {
        return error != null;
    }

    /** Whether the transaction succeeded and reached at least {@code commitment}. */
    public boolean succeededAt(SolanaCommitment commitment) {
        return !failed() && confirmationStatus.ordinal() >= commitment.ordinal();
    }
}
