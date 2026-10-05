package com.wontlost.web3.onramp;

import java.io.Serializable;

/** Localized labels and error messages for fiat on-ramp buttons. */
public class FiatOnrampI18n implements Serializable {
    private String button = "Buy with card";
    private String continueTo = "Continue to {0}";
    private String connectWallet = "Connect your wallet first";
    private String providerMissing = "No on-ramp provider named {0} is registered; call OnrampProviders.register at startup";
    private String invalidProviderUrl = "Provider URL must be an absolute HTTP URL";
    private String needToken = "Need {0}? Buy with card";

    public String getButton() { return button; }
    public FiatOnrampI18n setButton(String value) { button = value; return this; }
    public String getContinueTo() { return continueTo; }
    public FiatOnrampI18n setContinueTo(String value) { continueTo = value; return this; }
    public String getConnectWallet() { return connectWallet; }
    public FiatOnrampI18n setConnectWallet(String value) { connectWallet = value; return this; }
    public String getProviderMissing() { return providerMissing; }
    public FiatOnrampI18n setProviderMissing(String value) { providerMissing = value; return this; }
    public String getInvalidProviderUrl() { return invalidProviderUrl; }
    public FiatOnrampI18n setInvalidProviderUrl(String value) { invalidProviderUrl = value; return this; }
    public String getNeedToken() { return needToken; }
    public FiatOnrampI18n setNeedToken(String value) { needToken = value; return this; }
}
