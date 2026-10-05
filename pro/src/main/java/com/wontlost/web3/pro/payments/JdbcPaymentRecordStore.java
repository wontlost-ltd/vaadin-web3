package com.wontlost.web3.pro.payments;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import com.wontlost.web3.pro.jdbc.JdbcTransactions;

/** JDBC payment record store. 每次写操作都在独立短事务中提交，不参与调用方的外部事务；需要参与外部事务时，应用应自行包装。 */
public final class JdbcPaymentRecordStore implements PaymentRecordStore {
    private final DataSource dataSource;
    /** Creates a store backed by the supplied data source. */
    public JdbcPaymentRecordStore(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource, "dataSource"); }
    @Override public void upsert(PaymentRecord record) {
        String update = "UPDATE web3_pro_payment_records SET chain_id=?,token_symbol=?,token_address=?,amount=?,payer=?,tx_hash=?,status=?,updated_at=? WHERE order_id=?";
        String insert = "INSERT INTO web3_pro_payment_records (order_id,chain_id,token_symbol,token_address,amount,payer,tx_hash,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try {
            JdbcTransactions.execute(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    bindMutable(statement, record, 1);
                    if (statement.executeUpdate() != 0) return null;
                }
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    bindInsert(statement, record);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            if (!isDuplicate(exception)) throw new IllegalStateException("Could not upsert payment record", exception);
            try {
                JdbcTransactions.execute(dataSource, connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(update)) {
                        bindMutable(statement, record, 1);
                        statement.executeUpdate();
                    }
                    return null;
                });
            } catch (SQLException updateFailure) {
                updateFailure.addSuppressed(exception);
                throw new IllegalStateException("Could not update concurrent payment record", updateFailure);
            }
        }
    }

    private static void bindInsert(PreparedStatement statement, PaymentRecord record) throws SQLException {
        statement.setString(1, record.orderId()); statement.setLong(2, record.chainId());
        statement.setString(3, record.tokenSymbol()); statement.setString(4, record.tokenAddress());
        statement.setString(5, record.amount()); statement.setString(6, record.payer());
        statement.setString(7, record.txHash()); statement.setString(8, record.status());
        statement.setTimestamp(9, Timestamp.from(record.createdAt()));
        statement.setTimestamp(10, Timestamp.from(record.updatedAt()));
    }
    private static void bindMutable(PreparedStatement statement, PaymentRecord record, int offset) throws SQLException {
        statement.setLong(offset, record.chainId()); statement.setString(offset + 1, record.tokenSymbol());
        statement.setString(offset + 2, record.tokenAddress()); statement.setString(offset + 3, record.amount());
        statement.setString(offset + 4, record.payer()); statement.setString(offset + 5, record.txHash());
        statement.setString(offset + 6, record.status()); statement.setTimestamp(offset + 7, Timestamp.from(record.updatedAt()));
        statement.setString(offset + 8, record.orderId());
    }
    @Override public List<PaymentRecord> find(Instant from, Instant to, String status, int offset, int limit) {
        validatePage(offset, limit);
        String sql = "SELECT order_id,chain_id,token_symbol,token_address,amount,payer,tx_hash,status,created_at,updated_at FROM web3_pro_payment_records WHERE created_at>=? AND created_at<? AND (? IS NULL OR status=?) ORDER BY created_at DESC,order_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        try (var connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
            bindFilter(query, from, to, status); query.setInt(5, offset); query.setInt(6, limit);
            try (ResultSet rows = query.executeQuery()) {
                List<PaymentRecord> result = new ArrayList<>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        } catch (SQLException exception) { throw new IllegalStateException("Could not query payment records", exception); }
    }
    @Override public long count(Instant from, Instant to, String status) {
        try (var connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM web3_pro_payment_records WHERE created_at>=? AND created_at<? AND (? IS NULL OR status=?)")) {
            bindFilter(query, from, to, status);
            try (ResultSet rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        } catch (SQLException exception) { throw new IllegalStateException("Could not count payment records", exception); }
    }
    private static void bindFilter(PreparedStatement query, Instant from, Instant to, String status) throws SQLException {
        query.setTimestamp(1, Timestamp.from(Objects.requireNonNull(from))); query.setTimestamp(2, Timestamp.from(Objects.requireNonNull(to)));
        query.setString(3, status); query.setString(4, status);
    }
    private static PaymentRecord read(ResultSet row) throws SQLException {
        return new PaymentRecord(row.getString("order_id"), row.getLong("chain_id"), row.getString("token_symbol"),
                row.getString("token_address"), row.getString("amount"), row.getString("payer"), row.getString("tx_hash"),
                row.getString("status"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
    private static void validatePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page window");
    }
    private static boolean isDuplicate(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException())
            if ("23505".equals(current.getSQLState()) || current.getErrorCode() == 23505) return true;
        return false;
    }
}
