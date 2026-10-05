package com.wontlost.web3.monitor.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.wontlost.web3.pay.PaymentLedger;

final class JdbcPaymentLedger implements PaymentLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate claimTransaction;
    JdbcPaymentLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.claimTransaction = new TransactionTemplate(transactions);
        this.claimTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public boolean claim(String claimKey, String intentId) {
        try {
            claimTransaction.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO ledger_claims (claim_key, intent_id) VALUES (?, ?)", claimKey, intentId));
            return true;
        } catch (DuplicateKeyException exception) {
            String existing = jdbc.queryForObject("SELECT intent_id FROM ledger_claims WHERE claim_key = ?", String.class, claimKey);
            return intentId.equals(existing);
        }
    }
}
