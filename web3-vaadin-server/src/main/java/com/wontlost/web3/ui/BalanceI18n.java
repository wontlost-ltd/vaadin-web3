package com.wontlost.web3.ui;

/** English messages used by {@link Balance}. */
public class BalanceI18n implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private String loading = "Loading balance…";
    private String error = "Unable to load balance: {0}";
    public String getLoading() { return loading; }
    public BalanceI18n setLoading(String value) { loading = value; return this; }
    public String getError() { return error; }
    public BalanceI18n setError(String value) { error = value; return this; }
}
