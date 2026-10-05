package com.wontlost.web3.pro.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

import com.wontlost.web3.pay.PaymentLedger;

/** Database-backed transaction ownership ledger. 每次写操作都在独立短事务中提交，不参与调用方的外部事务；需要参与外部事务时，应用应自行包装。 */
public final class JdbcPaymentLedger implements PaymentLedger {
    private final DataSource dataSource;
    /** Creates a ledger backed by the supplied data source. */
    public JdbcPaymentLedger(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource, "dataSource"); }

    @Override public boolean claim(String claimKey, String orderId) {
        Objects.requireNonNull(claimKey, "claimKey");
        Objects.requireNonNull(orderId, "orderId");
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO web3_pro_payment_claims (claim_key, order_id) VALUES (?, ?)")) {
                    insert.setString(1, claimKey);
                    insert.setString(2, orderId);
                    insert.executeUpdate();
                    return true;
                }
            });
        } catch (SQLException exception) {
            if (!isDuplicate(exception)) throw new IllegalStateException("Could not claim payment", exception);
            try {
                return JdbcTransactions.execute(dataSource, connection -> {
                    try (PreparedStatement query = connection.prepareStatement(
                            "SELECT order_id FROM web3_pro_payment_claims WHERE claim_key = ?")) {
                        query.setString(1, claimKey);
                        try (ResultSet rows = query.executeQuery()) {
                            return rows.next() && orderId.equals(rows.getString(1));
                        }
                    }
                });
            } catch (SQLException lookupFailure) {
                lookupFailure.addSuppressed(exception);
                throw new IllegalStateException("Could not read existing payment claim", lookupFailure);
            }
        }
    }

    private static boolean isDuplicate(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if ("23505".equals(current.getSQLState()) || current.getErrorCode() == 23505) return true;
        }
        return false;
    }
}
