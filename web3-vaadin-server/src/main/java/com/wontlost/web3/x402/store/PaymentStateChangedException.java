package com.wontlost.web3.x402.store;

public final class PaymentStateChangedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public PaymentStateChangedException() { super("payment state changed"); }
}
