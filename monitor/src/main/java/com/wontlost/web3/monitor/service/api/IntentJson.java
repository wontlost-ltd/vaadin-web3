package com.wontlost.web3.monitor.service.api;

import java.math.BigDecimal;
import java.time.Instant;
import com.wontlost.web3.monitor.MonitoredPayment;
import com.wontlost.web3.monitor.service.db.PaymentIntent;

public final class IntentJson {
    private IntentJson() { }
    public static MonitoredPayment from(PaymentIntent p) {
        return new MonitoredPayment(p.id(),p.orderId(),p.chainId(),new MonitoredPayment.Token(p.tokenSymbol(),p.tokenAddress(),p.tokenDecimals()),
                p.recipient(),decimal(p.amountUnits(),p.tokenDecimals()),p.payer(),p.minConfirmations(),p.status(),
                p.txHash(),decimal(p.paidAmountUnits(),p.tokenDecimals()),p.confirmations(),p.notBefore(),p.expiresAt(),p.createdAt(),p.updatedAt());
    }
    private static String decimal(java.math.BigInteger units,int decimals) {
        return new BigDecimal(units,decimals).stripTrailingZeros().toPlainString();
    }
}
