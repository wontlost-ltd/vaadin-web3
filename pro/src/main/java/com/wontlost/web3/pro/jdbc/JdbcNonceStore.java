package com.wontlost.web3.pro.jdbc;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import javax.sql.DataSource;

import com.wontlost.web3.siwe.NonceStore;

/** JDBC nonce store. 每次写操作都在独立短事务中提交，不参与调用方的外部事务；需要参与外部事务时，应用应自行包装。 */
public final class JdbcNonceStore implements NonceStore {
    private static final char[] ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DataSource dataSource;
    private final Duration ttl;
    private final Clock clock;

    /** Creates a store with a five-minute TTL and system UTC clock. */
    public JdbcNonceStore(DataSource dataSource) { this(dataSource, Duration.ofMinutes(5), Clock.systemUTC()); }
    /** Creates a store with a custom TTL and system UTC clock. */
    public JdbcNonceStore(DataSource dataSource, Duration ttl) { this(dataSource, ttl, Clock.systemUTC()); }
    /** Creates a store with an explicit TTL and clock. */
    public JdbcNonceStore(DataSource dataSource, Duration ttl, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public String issue() {
        while (true) {
            String nonce = randomNonce();
            try {
                return JdbcTransactions.execute(dataSource, connection -> {
                    cleanup(connection);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO web3_pro_nonces (nonce, expires_at) VALUES (?, ?)")) {
                        insert.setString(1, nonce);
                        insert.setTimestamp(2, Timestamp.from(clock.instant().plus(ttl)));
                        insert.executeUpdate();
                        return nonce;
                    }
                });
            } catch (SQLException exception) {
                if (isDuplicate(exception)) continue;
                throw new IllegalStateException("Could not issue SIWE nonce", exception);
            }
        }
    }

    @Override public boolean consume(String nonce) {
        if (nonce == null) return false;
        try {
            return JdbcTransactions.execute(dataSource, connection -> {
                cleanup(connection);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM web3_pro_nonces WHERE nonce = ? AND expires_at > ?")) {
                    delete.setString(1, nonce);
                    delete.setTimestamp(2, Timestamp.from(clock.instant()));
                    return delete.executeUpdate() == 1;
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not consume SIWE nonce", exception);
        }
    }

    @Override public boolean isActive(String nonce) {
        if (nonce == null) return false;
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM web3_pro_nonces WHERE nonce = ? AND expires_at > ?")) {
            query.setString(1, nonce);
            query.setTimestamp(2, Timestamp.from(clock.instant()));
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException exception) { throw new IllegalStateException("Could not check SIWE nonce", exception); }
    }

    private void cleanup(Connection connection) throws SQLException {
        Instant cutoff = clock.instant();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT nonce FROM web3_pro_nonces WHERE expires_at <= ? ORDER BY expires_at FETCH FIRST 100 ROWS ONLY")) {
            select.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet rows = select.executeQuery(); PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM web3_pro_nonces WHERE nonce = ? AND expires_at <= ?")) {
                int count = 0;
                while (rows.next()) { delete.setString(1, rows.getString(1)); delete.setTimestamp(2, Timestamp.from(cutoff)); delete.addBatch(); count++; }
                if (count > 0) delete.executeBatch();
            }
        }
    }

    private static String randomNonce() {
        StringBuilder value = new StringBuilder(17);
        for (int i = 0; i < 17; i++) value.append(ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)]);
        return value.toString();
    }
    private static boolean isDuplicate(SQLException exception) {
        return "23505".equals(exception.getSQLState()) || exception.getErrorCode() == 23505;
    }
}
