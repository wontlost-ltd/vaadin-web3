package com.wontlost.web3.x402.payment;

public record SettlementResult(SettlementState state, String txHash, String errorCode, String message) { }
