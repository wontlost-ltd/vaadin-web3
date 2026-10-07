package com.wontlost.web3.x402.payment;

public record VerifyResult(boolean valid, String invalidReason, String payer) { }
