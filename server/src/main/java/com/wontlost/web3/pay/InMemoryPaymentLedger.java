package com.wontlost.web3.pay;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe process-local payment claim ledger. */
public final class InMemoryPaymentLedger implements PaymentLedger {
    private final ConcurrentMap<String, String> claims = new ConcurrentHashMap<>();
    @Override
    public boolean claim(String transactionKey, String orderId) {
        String existing = claims.putIfAbsent(transactionKey, orderId);
        return existing == null || existing.equals(orderId);
    }
}
