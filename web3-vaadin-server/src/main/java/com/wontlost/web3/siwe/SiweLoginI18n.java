package com.wontlost.web3.siwe;

import java.io.Serializable;
import java.util.EnumMap;
import java.util.Map;

/** Localized labels and failure messages for SIWE sign-in. */
public class SiweLoginI18n implements Serializable {
    private String button = "Sign in with Ethereum";
    private String userRejected = "The wallet request was rejected.";
    private String networkError = "The wallet network request failed.";
    private final Map<SiweException.Reason, String> reasons = new EnumMap<>(SiweException.Reason.class);

    public SiweLoginI18n() {
        for (SiweException.Reason reason : SiweException.Reason.values())
            reasons.put(reason, reason.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
    }

    public String getButton() { return button; }
    public SiweLoginI18n setButton(String value) { button = value; return this; }
    public String getUserRejected() { return userRejected; }
    public SiweLoginI18n setUserRejected(String value) { userRejected = value; return this; }
    public String getNetworkError() { return networkError; }
    public SiweLoginI18n setNetworkError(String value) { networkError = value; return this; }
    public String getMessage(SiweException.Reason reason) { return reasons.get(reason); }
    public SiweLoginI18n setMessage(SiweException.Reason reason, String value) {
        reasons.put(java.util.Objects.requireNonNull(reason), java.util.Objects.requireNonNull(value));
        return this;
    }
}
