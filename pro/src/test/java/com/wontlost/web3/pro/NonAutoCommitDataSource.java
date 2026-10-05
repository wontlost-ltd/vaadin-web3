package com.wontlost.web3.pro;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.sql.DataSource;

public final class NonAutoCommitDataSource implements DataSource {
    private final DataSource delegate;
    private final Consumer<String> beforePrepare;
    private final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();

    /** Number of explicit commits issued through connections from this data source. */
    public int commits() { return commits.get(); }

    public NonAutoCommitDataSource(DataSource delegate) { this(delegate, sql -> { }); }
    public NonAutoCommitDataSource(DataSource delegate, Consumer<String> beforePrepare) {
        this.delegate = delegate;
        this.beforePrepare = beforePrepare;
    }

    @Override public Connection getConnection() throws SQLException {
        Connection connection = delegate.getConnection();
        connection.setAutoCommit(false);
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        if ("commit".equals(method.getName())) commits.incrementAndGet();
                        if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof String sql) {
                            beforePrepare.accept(sql);
                        }
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() { return Logger.getLogger("global"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
}
