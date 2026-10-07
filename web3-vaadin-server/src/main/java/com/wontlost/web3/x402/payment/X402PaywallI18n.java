package com.wontlost.web3.x402.payment;

import java.io.Serializable;
import java.text.MessageFormat;

public class X402PaywallI18n implements Serializable {
    private String title = "Payment required";
    private String price = "Price: {0} {1}";
    private String network = "Network: {0}";
    private String token = "Token: {0}";
    private String connect = "Connect wallet";
    private String connecting = "Connecting…";
    private String pay = "Pay";
    private String signing = "Sign payment in your wallet";
    private String verifying = "Verifying authorization";
    private String settling = "Settling payment";
    private String pending = "Settlement is pending. Do not pay again.";
    private String paid = "Payment complete";
    private String alreadyPaid = "This resource is already paid";
    private String rejected = "Payment was cancelled";
    private String wrongNetwork = "Switch to the required network";
    private String retry = "Try again";
    private String returnToContent = "Return to content";
    private String check = "Check status";
    private String error = "Payment could not be completed";
    private String errorCode = "{0} ({1})";
    private String txHash = "Transaction";
    private String accountMismatch = "Connected wallet does not match the signed-in account";
    private String signInRequired = "Sign in with SIWE before paying";

    public String getTitle() {
        return title;
    }

    public X402PaywallI18n setTitle(String value) {
        title = value;
        return this;
    }

    public String getPrice() {
        return price;
    }

    public X402PaywallI18n setPrice(String value) {
        price = value;
        return this;
    }

    public String getNetwork() {
        return network;
    }

    public X402PaywallI18n setNetwork(String value) {
        network = value;
        return this;
    }

    public String getToken() {
        return token;
    }

    public X402PaywallI18n setToken(String value) {
        token = value;
        return this;
    }

    public String getConnect() {
        return connect;
    }

    public X402PaywallI18n setConnect(String value) {
        connect = value;
        return this;
    }

    public String getConnecting() {
        return connecting;
    }

    public X402PaywallI18n setConnecting(String value) {
        connecting = value;
        return this;
    }

    public String getPay() {
        return pay;
    }

    public X402PaywallI18n setPay(String value) {
        pay = value;
        return this;
    }

    public String getSigning() {
        return signing;
    }

    public X402PaywallI18n setSigning(String value) {
        signing = value;
        return this;
    }

    public String getVerifying() {
        return verifying;
    }

    public X402PaywallI18n setVerifying(String value) {
        verifying = value;
        return this;
    }

    public String getSettling() {
        return settling;
    }

    public X402PaywallI18n setSettling(String value) {
        settling = value;
        return this;
    }

    public String getPending() {
        return pending;
    }

    public X402PaywallI18n setPending(String value) {
        pending = value;
        return this;
    }

    public String getPaid() {
        return paid;
    }

    public X402PaywallI18n setPaid(String value) {
        paid = value;
        return this;
    }

    public String getAlreadyPaid() {
        return alreadyPaid;
    }

    public X402PaywallI18n setAlreadyPaid(String value) {
        alreadyPaid = value;
        return this;
    }

    public String getRejected() {
        return rejected;
    }

    public X402PaywallI18n setRejected(String value) {
        rejected = value;
        return this;
    }

    public String getWrongNetwork() {
        return wrongNetwork;
    }

    public X402PaywallI18n setWrongNetwork(String value) {
        wrongNetwork = value;
        return this;
    }

    public String getRetry() {
        return retry;
    }

    public X402PaywallI18n setRetry(String value) {
        retry = value;
        return this;
    }

    public String getReturnToContent() {
        return returnToContent;
    }

    public X402PaywallI18n setReturnToContent(String value) {
        returnToContent = value;
        return this;
    }

    public String getCheck() {
        return check;
    }

    public X402PaywallI18n setCheck(String value) {
        check = value;
        return this;
    }

    public String getError() {
        return error;
    }

    public X402PaywallI18n setError(String value) {
        error = value;
        return this;
    }

    public String formatError(String code) {
        return formatError(error, code);
    }

    public String formatError(String detail, String code) {
        return MessageFormat.format(errorCode, detail, code);
    }

    public X402PaywallI18n setErrorCode(String value) {
        errorCode = value;
        return this;
    }

    public String getTxHash() {
        return txHash;
    }

    public X402PaywallI18n setTxHash(String value) {
        txHash = value;
        return this;
    }

    public String getAccountMismatch() {
        return accountMismatch;
    }

    public X402PaywallI18n setAccountMismatch(String value) {
        accountMismatch = value;
        return this;
    }

    public String getSignInRequired() {
        return signInRequired;
    }

    public X402PaywallI18n setSignInRequired(String value) {
        signInRequired = value;
        return this;
    }
}
