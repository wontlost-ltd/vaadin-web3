package com.wontlost.web3.onramp;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

import org.web3j.crypto.Keys;

import com.wontlost.web3.chain.TokenInfo;

/** Describes the token purchase and destination wallet for an on-ramp session. */
public record OnrampOrder(TokenInfo token, String walletAddress, BigDecimal cryptoAmount,
        BigDecimal fiatAmount, String fiatCurrency, String redirectUrl, String clientIp,
        String partnerOrderId) {
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

    /** Validates the token, wallet address and mutually exclusive amounts. */
    public OnrampOrder {
        Objects.requireNonNull(token, "token");
        if (walletAddress == null || !ADDRESS.matcher(walletAddress).matches())
            throw new IllegalArgumentException("walletAddress must be a 20-byte hexadecimal address");
        walletAddress = Keys.toChecksumAddress(walletAddress);
        if (cryptoAmount != null && fiatAmount != null)
            throw new IllegalArgumentException("Specify at most one of cryptoAmount and fiatAmount");
        if (cryptoAmount != null && cryptoAmount.signum() <= 0 || fiatAmount != null && fiatAmount.signum() <= 0)
            throw new IllegalArgumentException("Purchase amount must be positive");
        if (fiatAmount != null && (fiatCurrency == null || !fiatCurrency.matches("[A-Za-z]{3}")))
            throw new IllegalArgumentException("fiatAmount requires a three-letter fiatCurrency");
        if (redirectUrl != null && !redirectUrl.matches("(?i)https?://[^\\s]+"))
            throw new IllegalArgumentException("redirectUrl must be an absolute http(s) URL");
        // 各服务商对订单引用长度限制不同（Coinbase partnerUserRef < 50）；拒绝而不是静默截断，避免两个订单撞成同一引用
        if (partnerOrderId != null && (partnerOrderId.isBlank() || partnerOrderId.length() > 49))
            throw new IllegalArgumentException("partnerOrderId must be 1-49 characters");
    }
}
