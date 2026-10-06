package com.wontlost.web3.ui;

/** English messages used by {@link TransactionStatus}. */
public class TransactionStatusI18n implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private String submitted = "Transaction submitted";
    private String pending = "Waiting for transaction to be mined";
    private String confirming = "Confirming ({0}/{1})";
    private String confirmed = "Transaction confirmed";
    private String failed = "Transaction failed";
    private String unknown = "Transaction status unknown";
    private String error = "Unable to check transaction; retrying: {0}";
    private String finalizedTarget = "finalized";
    public String getSubmitted() { return submitted; }
    public TransactionStatusI18n setSubmitted(String value) { submitted = value; return this; }
    public String getPending() { return pending; }
    public TransactionStatusI18n setPending(String value) { pending = value; return this; }
    public String getConfirming() { return confirming; }
    public TransactionStatusI18n setConfirming(String value) { confirming = value; return this; }
    public String getConfirmed() { return confirmed; }
    public TransactionStatusI18n setConfirmed(String value) { confirmed = value; return this; }
    public String getFailed() { return failed; }
    public TransactionStatusI18n setFailed(String value) { failed = value; return this; }
    /** Target shown in the confirming message when {@code Finality.finalized()} is used instead of a count. */
    public String getFinalizedTarget() { return finalizedTarget; }
    public TransactionStatusI18n setFinalizedTarget(String value) { finalizedTarget = value; return this; }
    public String getUnknown() { return unknown; }
    public TransactionStatusI18n setUnknown(String value) { unknown = value; return this; }
    public String getError() { return error; }
    public TransactionStatusI18n setError(String value) { error = value; return this; }
}
