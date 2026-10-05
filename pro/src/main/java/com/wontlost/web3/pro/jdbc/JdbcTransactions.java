package com.wontlost.web3.pro.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

/** 在独立短事务中执行 JDBC 操作。每个方法会自行提交，不参与调用方的外部事务；需要加入外部事务时，应用应自行包装。 */
public final class JdbcTransactions {
    private JdbcTransactions() { }

    /** 获取连接、执行操作，并在非自动提交连接上提交或回滚，最后关闭连接。 */
    public static <T> T execute(DataSource dataSource, SqlWork<T> work) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            boolean commit = !connection.getAutoCommit();
            try {
                T result = work.execute(connection);
                if (commit) connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                if (commit) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                }
                throw exception;
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
