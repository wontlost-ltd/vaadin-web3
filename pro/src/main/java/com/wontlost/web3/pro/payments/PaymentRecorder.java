package com.wontlost.web3.pro.payments;

import java.time.Instant;
import java.util.Objects;

import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.pay.PaymentResult;
import com.wontlost.web3.pay.PaymentRequest;
import com.wontlost.web3.pay.StablecoinCheckout;

/** Records checkout lifecycle events in a supplied store. */
public final class PaymentRecorder {
    private PaymentRecorder() { }
    /** Subscribes to submitted, confirmed and failed checkout events. Call {@link Registration#remove()} to stop recording. */
    public static Registration attach(StablecoinCheckout checkout, PaymentRecordStore store) {
        Objects.requireNonNull(checkout, "checkout"); Objects.requireNonNull(store, "store");
        Registration submitted = checkout.addPaymentSubmittedListener(event -> {
            TokenInfo token = event.getToken();
            if (token != null) write(store, token, event.getHash(), "SUBMITTED", null, event.getRequest(), event.getOrderId());
        });
        Registration confirmed = checkout.addPaymentConfirmedListener(event -> {
            TokenInfo token = event.getToken();
            if (token != null) write(store, token, event.getResult().txHash(), event.getResult().status().name(), event.getResult(), event.getRequest(), event.getOrderId());
        });
        Registration failed = checkout.addPaymentFailedListener(event -> {
            TokenInfo token = event.getToken();
            if (token != null) write(store, token, event.getResult().txHash(), event.getResult().status().name(), event.getResult(), event.getRequest(), event.getOrderId());
        });
        return () -> { submitted.remove(); confirmed.remove(); failed.remove(); };
    }
    private static void write(PaymentRecordStore store, TokenInfo token, String hash, String status,
            PaymentResult result, PaymentRequest request, String orderId) {
        if (request == null) return;
        Instant now = Instant.now();
        String payer = result != null && result.payer() != null ? result.payer() : request.payer();
        // 有链上结果时记实付金额（超额或少付都如实记账），否则记订单请求金额
        java.math.BigInteger units = result != null && result.amountPaid() != null && result.amountPaid().signum() > 0
                ? result.amountPaid() : request.minAmount();
        String amount = new java.math.BigDecimal(units, token.decimals()).toPlainString();
        store.upsert(new PaymentRecord(orderId, request.chainId(), token.symbol(), token.address(),
                amount, payer, hash, status, now, now));
    }
}
