package com.landawn.abacus.util;

import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import com.landawn.abacus.DataSource;
import com.landawn.abacus.SliceSelector;
import com.landawn.abacus.dataSource.NonSliceSelector;
import com.landawn.abacus.exception.UncheckedSQLException;

class SimpleDataSource implements DataSource {

    static final String PRIMARY = "primary";

    private final javax.sql.DataSource sqlDataSource;

    private final Properties<String, String> props = new Properties<>();

    private final SliceSelector sliceSelector = new NonSliceSelector();

    private final Method closeMethod;

    private boolean isClosed = false;

    public SimpleDataSource(final javax.sql.DataSource sqlDataSource) {
        this.sqlDataSource = sqlDataSource;

        Method method = null;

        try {
            method = ClassUtil.getDeclaredMethod(sqlDataSource.getClass(), "close");
        } catch (Exception e) {

        }

        closeMethod = method != null && Modifier.isPublic(method.getModifiers()) ? method : null;
    }

    /**
     * Gets the connection.
     *
     * @param username
     * @param password
     * @return
     * @throws SQLException the SQL exception
     */
    @Override
    public Connection getConnection(final String username, final String password) throws SQLException {
        return sqlDataSource.getConnection(username, password);
    }

    /**
     * Gets the log writer.
     *
     * @return
     * @throws SQLException the SQL exception
     */
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return sqlDataSource.getLogWriter();
    }

    /**
     * Sets the log writer.
     *
     * @param out the new log writer
     * @throws SQLException the SQL exception
     */
    @Override
    public void setLogWriter(final PrintWriter out) throws SQLException {
        sqlDataSource.setLogWriter(out);
    }

    /**
     * Sets the login timeout.
     *
     * @param seconds the new login timeout
     * @throws SQLException the SQL exception
     */
    @Override
    public void setLoginTimeout(final int seconds) throws SQLException {
        sqlDataSource.setLoginTimeout(seconds);
    }

    /**
     * Gets the login timeout.
     *
     * @return
     * @throws SQLException the SQL exception
     */
    @Override
    public int getLoginTimeout() throws SQLException {
        return sqlDataSource.getLoginTimeout();
    }

    /**
     * Gets the parent logger.
     *
     * @return
     * @throws SQLFeatureNotSupportedException the SQL feature not supported exception
     */
    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return sqlDataSource.getParentLogger();
    }

    /**
     *
     * @param <T>
     * @param iface
     * @return
     * @throws SQLException the SQL exception
     */
    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        return sqlDataSource.unwrap(iface);
    }

    /**
     * Checks if is wrapper for.
     *
     * @param iface
     * @return true, if is wrapper for
     * @throws SQLException the SQL exception
     */
    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return sqlDataSource.isWrapperFor(iface);
    }

    /**
     * Gets the connection.
     *
     * @return
     */
    @Override
    public Connection getConnection() {
        try {
            return sqlDataSource.getConnection();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Gets the read only connection.
     *
     * @return
     */
    @Override
    public Connection getReadOnlyConnection() {
        try {
            return sqlDataSource.getConnection();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Gets the slice selector.
     *
     * @return
     */
    @Override
    public SliceSelector getSliceSelector() {
        return sliceSelector;
    }

    /**
     * Gets the name.
     *
     * @return
     */
    @Override
    public String getName() {
        return PRIMARY;
    }

    /**
     * Gets the properties.
     *
     * @return
     */
    @Override
    public Properties<String, String> getProperties() {
        return props;
    }

    /**
     * Gets the max active.
     *
     * @return
     */
    @Override
    public int getMaxActive() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the current active.
     *
     * @return
     */
    @Override
    public int getCurrentActive() {
        throw new UnsupportedOperationException();
    }

    /**
     * Close.
     */
    @Override
    public void close() {
        if (isClosed) {
            return;
        }

        if (closeMethod != null) {
            try {
                ClassUtil.invokeMethod(sqlDataSource, closeMethod);
            } catch (Exception e) {
                // ignore.
            }
        }

        isClosed = true;
    }

    /**
     * Checks if is closed.
     *
     * @return true, if is closed
     */
    @Override
    public boolean isClosed() {
        return isClosed;
    }
}