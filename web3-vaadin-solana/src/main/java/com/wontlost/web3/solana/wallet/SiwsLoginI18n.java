package com.wontlost.web3.solana.wallet;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Localized labels and failure messages for Sign-In With Solana. */
public class SiwsLoginI18n implements Serializable {
    private String button = "Sign in with Solana";
    private String userRejected = "The wallet request was rejected.";
    private String walletError = "The wallet could not complete the request.";
    private String verificationFailed = "The sign-in could not be verified. Please try again.";
    private final Map<String, String> codes = new HashMap<>(Map.of(
            SiwsLogin.ADDRESS_BLOCKED, "This address is not allowed to sign in.",
            SiwsLogin.SCREENING_UNAVAILABLE, "Sign-in is temporarily unavailable. Please try again later.",
            "siws_expired", "The sign-in request expired. Please try again."));

    public String getButton() { return button; }
    public SiwsLoginI18n setButton(String value) { button = Objects.requireNonNull(value); return this; }
    public String getUserRejected() { return userRejected; }
    public SiwsLoginI18n setUserRejected(String value) { userRejected = Objects.requireNonNull(value); return this; }
    public String getWalletError() { return walletError; }
    public SiwsLoginI18n setWalletError(String value) { walletError = Objects.requireNonNull(value); return this; }
    public String getVerificationFailed() { return verificationFailed; }
    public SiwsLoginI18n setVerificationFailed(String value) { verificationFailed = Objects.requireNonNull(value); return this; }

    /** The message for a failure code such as {@code siws_expired}; falls back to the verification-failed text. */
    public String getMessage(String code) {
        return codes.getOrDefault(code, verificationFailed);
    }

    /** Sets the message for a failure code such as {@code siws_nonce_reused}. */
    public SiwsLoginI18n setMessage(String code, String value) {
        codes.put(Objects.requireNonNull(code), Objects.requireNonNull(value));
        return this;
    }
}
