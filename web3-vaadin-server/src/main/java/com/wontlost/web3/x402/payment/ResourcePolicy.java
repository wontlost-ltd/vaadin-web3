package com.wontlost.web3.x402.payment;

import java.math.BigInteger;
import java.util.Objects;

import com.wontlost.web3.x402.protocol.PaymentRequirements;
import com.wontlost.web3.x402.protocol.X402Resource;
import com.wontlost.web3.x402.protocol.X402Validation;

public record ResourcePolicy(String resourceId, String version, X402Resource resource, String network,
        BigInteger amount, String asset, String payTo, int maxTimeoutSeconds, String tokenName,
        String tokenVersion) {
    public ResourcePolicy {
        Objects.requireNonNull(resourceId); Objects.requireNonNull(version); Objects.requireNonNull(resource);
        Objects.requireNonNull(network); Objects.requireNonNull(amount); Objects.requireNonNull(asset);
        Objects.requireNonNull(payTo); Objects.requireNonNull(tokenName); Objects.requireNonNull(tokenVersion);
        if (resourceId.isBlank() || version.isBlank() || amount.signum() <= 0 || amount.bitLength() > 256
                || maxTimeoutSeconds < 1 || maxTimeoutSeconds > 86_400 || tokenName.isBlank() || tokenVersion.isBlank()
                || tokenName.length() > 128 || tokenVersion.length() > 32)
            throw new IllegalArgumentException("invalid resource policy");
        long chainId = X402Validation.chainId(network, java.util.Set.of());
        network = "eip155:" + chainId;
        asset = X402Validation.address(asset);
        payTo = X402Validation.address(payTo);
    }
    public PaymentRequirements requirements() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        return new PaymentRequirements("exact", network, amount.toString(), asset, payTo, maxTimeoutSeconds,
                java.util.Map.of("name", mapper.valueToTree(tokenName), "version", mapper.valueToTree(tokenVersion),
                        "assetTransferMethod", mapper.valueToTree("eip3009"), "paymentFlow", mapper.valueToTree("authorization")));
    }
}
