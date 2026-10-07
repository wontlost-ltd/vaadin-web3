package com.wontlost.web3.x402.protocol;

public record SettlementResponse(boolean success, String errorReason, String errorMessage,
        String payer, String transaction) { }
