package com.wontlost.web3.pay;

/** Atomically associates one chain transaction with one order. */
@FunctionalInterface
public interface PaymentLedger {
    boolean claim(String chainIdAndTxHash, String orderId);
}
