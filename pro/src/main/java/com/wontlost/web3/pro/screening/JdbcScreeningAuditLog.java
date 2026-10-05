package com.wontlost.web3.pro.screening;

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

/** JDBC audit log. 每次写操作都在独立短事务中提交，不参与调用方的外部事务；需要参与外部事务时，应用应自行包装。 */
public final class JdbcScreeningAuditLog implements ScreeningAuditLog {
    private final DataSource dataSource;
    /** Creates a log backed by the supplied data source. */
    public JdbcScreeningAuditLog(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource, "dataSource"); }
    @Override public void record(AuditEntry entry) {
        try {
            JdbcTransactions.execute(dataSource, connection -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO web3_pro_screening_audit (id,address,allowed,reason,source,checked_at) VALUES (?,?,?,?,?,?)")) {
                    insert.setString(1, entry.id()); insert.setString(2, entry.address()); insert.setBoolean(3, entry.allowed());
                    insert.setString(4, entry.reason()); insert.setString(5, entry.source()); insert.setTimestamp(6, Timestamp.from(entry.checkedAt()));
                    insert.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) { throw new IllegalStateException("Could not write screening audit", exception); }
    }
    @Override public List<AuditEntry> find(String address, Instant fromInclusive, Instant toExclusive) {
        String sql = "SELECT id,address,allowed,reason,source,checked_at FROM web3_pro_screening_audit WHERE address=? AND checked_at>=? AND checked_at<? ORDER BY checked_at,id";
        try (var connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, address); query.setTimestamp(2, Timestamp.from(fromInclusive)); query.setTimestamp(3, Timestamp.from(toExclusive));
            try (ResultSet rows = query.executeQuery()) {
                List<AuditEntry> entries = new ArrayList<>();
                while (rows.next()) entries.add(new AuditEntry(rows.getString("id"), rows.getString("address"), rows.getBoolean("allowed"),
                        rows.getString("reason"), rows.getString("source"), rows.getTimestamp("checked_at").toInstant()));
                return List.copyOf(entries);
            }
        } catch (SQLException exception) { throw new IllegalStateException("Could not query screening audit", exception); }
    }
}
