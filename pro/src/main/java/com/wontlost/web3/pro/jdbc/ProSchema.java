package com.wontlost.web3.pro.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import javax.sql.DataSource;

/** Initializes the Pro tables on H2 or PostgreSQL. Production applications should include {@code com/wontlost/web3/pro/schema.sql} (inside this jar) in their own migration tool. */
public final class ProSchema {
    private ProSchema() { }

    /** Creates all Pro tables if they do not already exist. */
    public static void create(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        String sql;
        // 放在包路径下：类路径根目录的 schema.sql 会被 Spring Boot 对嵌入式数据库自动执行，并与应用自己的 schema.sql 冲突
        try (InputStream input = ProSchema.class.getResourceAsStream("/com/wontlost/web3/pro/schema.sql")) {
            if (input == null) throw new IllegalStateException("Missing schema.sql");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read Pro schema", exception);
        }
        try {
            // PostgreSQL 的 DDL 也是事务性的：非自动提交连接上必须显式提交，否则关闭连接即回滚建表
            JdbcTransactions.execute(dataSource, connection -> {
                try (Statement statement = connection.createStatement()) {
                    for (String command : sql.split(";")) {
                        if (!command.isBlank()) statement.execute(command.trim());
                    }
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create Pro schema", exception);
        }
    }
}
