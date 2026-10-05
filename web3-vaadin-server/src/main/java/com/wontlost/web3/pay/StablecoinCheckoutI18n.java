package com.wontlost.web3.pay;

import java.io.Serializable;

/** Localized labels and status messages for stablecoin checkout. */
public class StablecoinCheckoutI18n implements Serializable {
    private String network = "Network";
    private String token = "Token";
    private String pay = "Pay {0} {1}";
    private String waiting = "Waiting for payment.";
    private String unsupportedNetwork = "No supported network is configured.";
    private String reconnecting = "Transaction sent; reconnecting to payment monitor…";
    private String submitted = "Payment submitted: {0}";
    private String blocked = "Payment blocked: {0}";
    private String rejected = "Payment was rejected in the wallet.";
    private String failed = "Payment failed: {0}";
    private String waitingNetwork = "Waiting for the network…";
    private String networkFailure = "Your payment was sent but can't be verified right now. Don't pay again; verification will continue.";
    private String confirmed = "{0} — {1} confirmations";
    private String paid = "Paid";

    public String getNetwork() { return network; }
    public StablecoinCheckoutI18n setNetwork(String value) { network = value; return this; }
    public String getToken() { return token; }
    public StablecoinCheckoutI18n setToken(String value) { token = value; return this; }
    public String getPay() { return pay; }
    public StablecoinCheckoutI18n setPay(String value) { pay = value; return this; }
    public String getWaiting() { return waiting; }
    public StablecoinCheckoutI18n setWaiting(String value) { waiting = value; return this; }
    public String getUnsupportedNetwork() { return unsupportedNetwork; }
    public StablecoinCheckoutI18n setUnsupportedNetwork(String value) { unsupportedNetwork = value; return this; }
    public String getReconnecting() { return reconnecting; }
    public StablecoinCheckoutI18n setReconnecting(String value) { reconnecting = value; return this; }
    public String getSubmitted() { return submitted; }
    public StablecoinCheckoutI18n setSubmitted(String value) { submitted = value; return this; }
    public String getBlocked() { return blocked; }
    public StablecoinCheckoutI18n setBlocked(String value) { blocked = value; return this; }
    public String getRejected() { return rejected; }
    public StablecoinCheckoutI18n setRejected(String value) { rejected = value; return this; }
    public String getFailed() { return failed; }
    public StablecoinCheckoutI18n setFailed(String value) { failed = value; return this; }
    public String getWaitingNetwork() { return waitingNetwork; }
    public StablecoinCheckoutI18n setWaitingNetwork(String value) { waitingNetwork = value; return this; }
    public String getNetworkFailure() { return networkFailure; }
    public StablecoinCheckoutI18n setNetworkFailure(String value) { networkFailure = value; return this; }
    public String getConfirmed() { return confirmed; }
    public StablecoinCheckoutI18n setConfirmed(String value) { confirmed = value; return this; }
    public String getPaid() { return paid; }
    public StablecoinCheckoutI18n setPaid(String value) { paid = value; return this; }
}
