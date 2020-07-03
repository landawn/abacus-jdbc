/*
 * Copyright (c) 2015, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.EntityId;
import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.SequentialOnly;
import com.landawn.abacus.annotation.Stateful;
import com.landawn.abacus.cache.Cache;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.core.DirtyMarkerUtil;
import com.landawn.abacus.core.RowDataSet;
import com.landawn.abacus.core.Seid;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Columns.ColumnGetter;
import com.landawn.abacus.util.Columns.ColumnOne;
import com.landawn.abacus.util.DaoUtil.NonDBOperation;
import com.landawn.abacus.util.ExceptionalStream.ExceptionalIterator;
import com.landawn.abacus.util.ExceptionalStream.StreamE;
import com.landawn.abacus.util.Fn.BiConsumers;
import com.landawn.abacus.util.Fn.Suppliers;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.SQLTransaction.CreatedBy;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.stream.Collector;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 *
 * @author Haiyang Li
 * @see {@link com.landawn.abacus.condition.ConditionFactory}
 * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
 * @see {@link com.landawn.abacus.annotation.ReadOnly}
 * @see {@link com.landawn.abacus.annotation.ReadOnlyId}
 * @see {@link com.landawn.abacus.annotation.NonUpdatable}
 * @see {@link com.landawn.abacus.annotation.Transient}
 * @see {@link com.landawn.abacus.annotation.Table}
 * @see {@link com.landawn.abacus.annotation.Column}
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/Statement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html">http://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html</a>
 * @see <a href="http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html">http://docs.oracle.com/javase/8/docs/api/java/sql/ResultSet.html</a>
 * @since 0.8
 */
public final class JdbcUtil {

    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    public static final int DEFAULT_BATCH_SIZE = 200;

    // ...
    static final String CURRENT_DIR_PATH = "./";

    static final AsyncExecutor asyncExecutor = new AsyncExecutor(Math.max(8, IOUtil.CPU_CORES), Math.max(64, IOUtil.CPU_CORES), 180L, TimeUnit.SECONDS);

    static final JdbcUtil.BiParametersSetter<? super PreparedStatement, ? super Object[]> DEFAULT_STMT_SETTER = new JdbcUtil.BiParametersSetter<PreparedStatement, Object[]>() {
        @Override
        public void accept(PreparedStatement stmt, Object[] parameters) throws SQLException {
            for (int i = 0, len = parameters.length; i < len; i++) {
                stmt.setObject(i + 1, parameters[i]);
            }
        }
    };

    private static final Set<String> sqlStateForTableNotExists = N.newHashSet();

    static {
        sqlStateForTableNotExists.add("42S02"); // for MySQCF.
        sqlStateForTableNotExists.add("42P01"); // for PostgreSQCF.
        sqlStateForTableNotExists.add("42501"); // for HSQLDB.
    }

    private JdbcUtil() {
        // singleton
    }

    public static DBVersion getDBVersion(final javax.sql.DataSource ds) throws UncheckedSQLException {
        try (Connection conn = ds.getConnection()) {
            return getDBVersion(conn);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    public static DBVersion getDBVersion(final Connection conn) throws UncheckedSQLException {
        try {
            String dbProudctName = conn.getMetaData().getDatabaseProductName();
            String dbProudctVersion = conn.getMetaData().getDatabaseProductVersion();

            DBVersion dbVersion = DBVersion.OTHERS;

            String upperCaseProductName = dbProudctName.toUpperCase();
            if (upperCaseProductName.contains("H2")) {
                dbVersion = DBVersion.H2;
            } else if (upperCaseProductName.contains("HSQL")) {
                dbVersion = DBVersion.HSQLDB;
            } else if (upperCaseProductName.contains("MYSQL")) {
                if (dbProudctVersion.startsWith("5.5")) {
                    dbVersion = DBVersion.MYSQL_5_5;
                } else if (dbProudctVersion.startsWith("5.6")) {
                    dbVersion = DBVersion.MYSQL_5_6;
                } else if (dbProudctVersion.startsWith("5.7")) {
                    dbVersion = DBVersion.MYSQL_5_7;
                } else if (dbProudctVersion.startsWith("5.8")) {
                    dbVersion = DBVersion.MYSQL_5_8;
                } else if (dbProudctVersion.startsWith("5.9")) {
                    dbVersion = DBVersion.MYSQL_5_9;
                } else if (dbProudctVersion.startsWith("6")) {
                    dbVersion = DBVersion.MYSQL_6;
                } else if (dbProudctVersion.startsWith("7")) {
                    dbVersion = DBVersion.MYSQL_7;
                } else if (dbProudctVersion.startsWith("8")) {
                    dbVersion = DBVersion.MYSQL_8;
                } else if (dbProudctVersion.startsWith("9")) {
                    dbVersion = DBVersion.MYSQL_9;
                } else if (dbProudctVersion.startsWith("10")) {
                    dbVersion = DBVersion.MYSQL_10;
                } else {
                    dbVersion = DBVersion.MYSQL_OTHERS;
                }
            } else if (upperCaseProductName.contains("POSTGRESQL")) {
                if (dbProudctVersion.startsWith("9.2")) {
                    dbVersion = DBVersion.POSTGRESQL_9_2;
                } else if (dbProudctVersion.startsWith("9.3")) {
                    dbVersion = DBVersion.POSTGRESQL_9_3;
                } else if (dbProudctVersion.startsWith("9.4")) {
                    dbVersion = DBVersion.POSTGRESQL_9_4;
                } else if (dbProudctVersion.startsWith("9.5")) {
                    dbVersion = DBVersion.POSTGRESQL_9_5;
                } else if (dbProudctVersion.startsWith("10")) {
                    dbVersion = DBVersion.POSTGRESQL_10;
                } else if (dbProudctVersion.startsWith("11")) {
                    dbVersion = DBVersion.POSTGRESQL_11;
                } else if (dbProudctVersion.startsWith("12")) {
                    dbVersion = DBVersion.POSTGRESQL_12;
                } else {
                    dbVersion = DBVersion.POSTGRESQL_OTHERS;
                }
            } else if (upperCaseProductName.contains("ORACLE")) {
                dbVersion = DBVersion.ORACLE;
            } else if (upperCaseProductName.contains("DB2")) {
                dbVersion = DBVersion.DB2;
            } else if (upperCaseProductName.contains("SQL SERVER")) {
                dbVersion = DBVersion.SQL_SERVER;
            }

            return dbVersion;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates the DataSource.
     *
     * @param url
     * @param user
     * @param password
     * @return 
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password) {
        try {
            final com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);

            return new com.zaxxer.hikari.HikariDataSource(config);
        } catch (Throwable e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     * Creates the DataSource.
     *
     * @param url
     * @param user
     * @param password
     * @return 
     */
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password) {
        try {
            final com.mchange.v2.c3p0.ComboPooledDataSource cpds = new com.mchange.v2.c3p0.ComboPooledDataSource();
            cpds.setJdbcUrl(url);
            cpds.setUser(user);
            cpds.setPassword(password);

            return cpds;
        } catch (Throwable e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     * Creates the connection.
     *
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final String url, final String user, final String password) throws UncheckedSQLException {
        return createConnection(getDriverClasssByUrl(url), url, user, password);
    }

    /**
     * Creates the connection.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final String driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        final Class<? extends Driver> cls = ClassUtil.forClass(driverClass);

        return createConnection(cls, url, user, password);
    }

    /**
     * Creates the connection.
     *
     * @param driverClass
     * @param url
     * @param user
     * @param password
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection createConnection(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        try {
            DriverManager.registerDriver(N.newInstance(driverClass));

            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new UncheckedSQLException("Failed to close create connection", e);
        }
    }

    /**
     * Gets the driver classs by url.
     *
     * @param url
     * @return
     */
    private static Class<? extends Driver> getDriverClasssByUrl(final String url) {
        N.checkArgNotNullOrEmpty(url, "url");

        Class<? extends Driver> driverClass = null;
        // jdbc:mysql://localhost:3306/abacustest
        if (url.indexOf("mysql") > 0 || StringUtil.indexOfIgnoreCase(url, "mysql") > 0) {
            driverClass = ClassUtil.forClass("com.mysql.jdbc.Driver");
            // jdbc:postgresql://localhost:5432/abacustest
        } else if (url.indexOf("postgresql") > 0 || StringUtil.indexOfIgnoreCase(url, "postgresql") > 0) {
            driverClass = ClassUtil.forClass("org.postgresql.Driver");
            // jdbc:h2:hsql://<host>:<port>/<database>
        } else if (url.indexOf("h2") > 0 || StringUtil.indexOfIgnoreCase(url, "h2") > 0) {
            driverClass = ClassUtil.forClass("org.h2.Driver");
            // jdbc:hsqldb:hsql://localhost/abacustest
        } else if (url.indexOf("hsqldb") > 0 || StringUtil.indexOfIgnoreCase(url, "hsqldb") > 0) {
            driverClass = ClassUtil.forClass("org.hsqldb.jdbc.JDBCDriver");
            // jdbc.url=jdbc:oracle:thin:@localhost:1521:abacustest
        } else if (url.indexOf("oracle") > 0 || StringUtil.indexOfIgnoreCase(url, "oracle") > 0) {
            driverClass = ClassUtil.forClass("oracle.jdbc.driver.OracleDriver");
            // jdbc.url=jdbc:sqlserver://localhost:1433;Database=abacustest
        } else if (url.indexOf("sqlserver") > 0 || StringUtil.indexOfIgnoreCase(url, "sqlserver") > 0) {
            driverClass = ClassUtil.forClass("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            // jdbc:db2://localhost:50000/abacustest
        } else if (url.indexOf("db2") > 0 || StringUtil.indexOfIgnoreCase(url, "db2") > 0) {
            driverClass = ClassUtil.forClass("com.ibm.db2.jcc.DB2Driver");
        } else {
            throw new IllegalArgumentException(
                    "Can not identity the driver class by url: " + url + ". Only mysql, postgresql, hsqldb, sqlserver, oracle and db2 are supported currently");
        }
        return driverClass;
    }

    private static boolean isInSpring = true;

    static {
        try {
            isInSpring = ClassUtil.forClass("org.springframework.jdbc.datasource.DataSourceUtils") != null;
        } catch (Throwable e) {
            isInSpring = false;
        }
    }

    /**
     * Spring Transaction is supported and Integrated.
     * If this method is called where a Spring transaction is started with the specified {@code DataSource},
     * the {@code Connection} started the Spring Transaction will be returned. Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be returned.
     *
     * @param ds
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws UncheckedSQLException {
        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            try {
                return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;

                try {
                    return ds.getConnection();
                } catch (SQLException e1) {
                    throw new UncheckedSQLException(e1);
                }
            }
        } else {
            try {
                return ds.getConnection();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Spring Transaction is supported and Integrated.
     * If this method is called where a Spring transaction is started with the specified {@code DataSource},
     * the specified {@code Connection} won't be returned to {@code DataSource}(Connection pool) until the transaction is committed or rolled back. Otherwise the specified {@code Connection} will be directly returned back to {@code DataSource}(Connection pool).
     *
     * @param conn
     * @param ds
     */
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) {
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Creates the close handler.
     *
     * @param conn
     * @param ds
     * @return
     */
    static Runnable createCloseHandler(final Connection conn, final javax.sql.DataSource ds) {
        return () -> releaseConnection(conn, ds);
    }

    /**
     *
     * @param rs
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs) throws UncheckedSQLException {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        close(rs, closeStatement, false);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @param closeConnection
     * @throws IllegalArgumentException if {@code closeStatement = false} while {@code closeConnection = true}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final boolean closeStatement, final boolean closeConnection)
            throws IllegalArgumentException, UncheckedSQLException {
        if (closeConnection && closeStatement == false) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement || closeConnection) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            close(rs, stmt, conn);
        }
    }

    /**
     *
     * @param stmt
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final Statement stmt) throws UncheckedSQLException {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated consider using {@link #releaseConnection(Connection, javax.sql.DataSource)}
     */
    @Deprecated
    public static void close(final Connection conn) throws UncheckedSQLException {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param stmt
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final Statement stmt) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param stmt
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (stmt != null) {
                stmt.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param stmt
     * @param conn
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void close(final ResultSet rs, final Statement stmt, final Connection conn) throws UncheckedSQLException {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        }
    }

    /**
     * Unconditionally close an <code>ResultSet</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     */
    public static void closeQuietly(final ResultSet rs) {
        closeQuietly(rs, null, null);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        closeQuietly(rs, closeStatement, false);
    }

    /**
     *
     * @param rs
     * @param closeStatement
     * @param closeConnection
     * @throws IllegalArgumentException if {@code closeStatement = false} while {@code closeConnection = true}.
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement, final boolean closeConnection) throws IllegalArgumentException {
        if (closeConnection && closeStatement == false) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement || closeConnection) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (SQLException e) {
            logger.error("Failed to get Statement or Connection by ResultSet", e);
        } finally {
            closeQuietly(rs, stmt, conn);
        }
    }

    /**
     * Unconditionally close an <code>Statement</code>.
     * <p>
     * Equivalent to {@link Statement#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param stmt
     */
    public static void closeQuietly(final Statement stmt) {
        closeQuietly(null, stmt, null);
    }

    /**
     * Unconditionally close an <code>Connection</code>.
     * <p>
     * Equivalent to {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param conn
     * @deprecated consider using {@link #releaseConnection(Connection, javax.sql.DataSource)}
     */
    @Deprecated
    public static void closeQuietly(final Connection conn) {
        closeQuietly(null, null, conn);
    }

    /**
     * Unconditionally close the <code>ResultSet, Statement</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, {@link Statement#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     * @param stmt
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt) {
        closeQuietly(rs, stmt, null);
    }

    /**
     * Unconditionally close the <code>Statement, Connection</code>.
     * <p>
     * Equivalent to {@link Statement#close()}, {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param stmt
     * @param conn
     */
    public static void closeQuietly(final Statement stmt, final Connection conn) {
        closeQuietly(null, stmt, conn);
    }

    /**
     * Unconditionally close the <code>ResultSet, Statement, Connection</code>.
     * <p>
     * Equivalent to {@link ResultSet#close()}, {@link Statement#close()}, {@link Connection#close()}, except any exceptions will be ignored.
     * This is typically used in finally blocks.
     *
     * @param rs
     * @param stmt
     * @param conn
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt, final Connection conn) {
        if (rs != null) {
            try {
                rs.close();
            } catch (Exception e) {
                logger.error("Failed to close ResultSet", e);
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (Exception e) {
                logger.error("Failed to close Statement", e);
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                logger.error("Failed to close Connection", e);
            }
        }
    }

    /**
     *
     * @param rs
     * @param n the count of row to move ahead.
     * @return
     * @throws SQLException the SQL exception
     */
    public static int skip(final ResultSet rs, int n) throws SQLException {
        return skip(rs, (long) n);
    }

    /**
     *
     * @param rs
     * @param n the count of row to move ahead.
     * @return
     * @throws SQLException the SQL exception
     * @see {@link ResultSet#absolute(int)}
     */
    public static int skip(final ResultSet rs, long n) throws SQLException {
        if (n <= 0) {
            return 0;
        } else if (n == 1) {
            return rs.next() == true ? 1 : 0;
        } else {
            final int currentRow = rs.getRow();

            if (n <= Integer.MAX_VALUE) {
                try {
                    if (n > Integer.MAX_VALUE - rs.getRow()) {
                        while (n-- > 0L && rs.next()) {
                        }
                    } else {
                        rs.absolute((int) n + rs.getRow());
                    }
                } catch (SQLException e) {
                    while (n-- > 0L && rs.next()) {
                    }
                }
            } else {
                while (n-- > 0L && rs.next()) {
                }
            }

            return rs.getRow() - currentRow;
        }
    }

    /**
     * Gets the column count.
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    public static int getColumnCount(ResultSet rs) throws SQLException {
        return rs.getMetaData().getColumnCount();
    }

    /**
     * Gets the column name list.
     *
     * @param conn
     * @param tableName
     * @return
     * @throws SQLException the SQL exception
     */
    public static List<String> getColumnNameList(final Connection conn, final String tableName) throws SQLException {
        final String query = "SELECT * FROM " + tableName + " WHERE 1 > 2";
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStatement(conn, query);
            rs = executeQuery(stmt);

            final ResultSetMetaData metaData = rs.getMetaData();
            final int columnCount = metaData.getColumnCount();
            final List<String> columnNameList = new ArrayList<>(columnCount);

            for (int i = 1, n = columnCount + 1; i < n; i++) {
                columnNameList.add(metaData.getColumnName(i));
            }

            return columnNameList;
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    public static List<String> getColumnLabelList(ResultSet rs) throws SQLException {
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnCount = metaData.getColumnCount();
        final List<String> labelList = new ArrayList<>(columnCount);

        for (int i = 1, n = columnCount + 1; i < n; i++) {
            labelList.add(getColumnLabel(metaData, i));
        }

        return labelList;
    }

    /**
     * Gets the column label.
     *
     * @param rsmd
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    public static String getColumnLabel(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        final String result = rsmd.getColumnLabel(columnIndex);

        return N.isNullOrEmpty(result) ? rsmd.getColumnName(columnIndex) : result;
    }

    /**
     * Returns the column index starts with from 1, not 0.
     *
     * @param resultSet
     * @param columnName
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    public static int getColumnIndex(final ResultSet resultSet, final String columnName) throws UncheckedSQLException {
        try {
            return getColumnIndex(resultSet.getMetaData(), columnName);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Returns the column index starts with from 1, not 0.
     * 
     * @param rsmd
     * @param columnName
     * @return
     * @throws SQLException
     */
    public static int getColumnIndex(final ResultSetMetaData rsmd, final String columnName) throws SQLException {
        final int columnCount = rsmd.getColumnCount();

        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
            if (getColumnLabel(rsmd, columnIndex).equalsIgnoreCase(columnName)) {
                return columnIndex;
            }
        }

        return -1;
    }

    /**
     * Gets the column value.
     *
     * @param rs
     * @param columnIndex starts with 1, not 0.
     * @return
     * @throws SQLException the SQL exception
     */
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object obj = rs.getObject(columnIndex);

        if (obj == null || obj instanceof String || obj instanceof Number) {
            return obj;
        }

        final String className = obj.getClass().getName();

        if (obj instanceof Blob) {
            Blob blob = (Blob) obj;
            obj = blob.getBytes(1, (int) blob.length());
        } else if (obj instanceof Clob) {
            Clob clob = (Clob) obj;
            obj = clob.getSubString(1, (int) clob.length());
        } else if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className)) {
            obj = rs.getTimestamp(columnIndex);
        } else if (className != null && className.startsWith("oracle.sql.DATE")) {
            final String metaDataClassName = rs.getMetaData().getColumnClassName(columnIndex);

            if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                obj = rs.getTimestamp(columnIndex);
            } else {
                obj = rs.getDate(columnIndex);
            }
        } else if (obj instanceof java.sql.Date) {
            if ("java.sql.Timestamp".equals(rs.getMetaData().getColumnClassName(columnIndex))) {
                obj = rs.getTimestamp(columnIndex);
            }
        }

        return obj;
    }

    /**
     * Gets the column value.
     *
     * @param rs
     * @param columnLabel
     * @return
     * @throws SQLException the SQL exception
     * @deprecated please consider using {@link #getColumnValue(ResultSet, int)}
     */
    @Deprecated
    public static Object getColumnValue(final ResultSet rs, final String columnLabel) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object obj = rs.getObject(columnLabel);

        if (obj == null || obj instanceof String || obj instanceof Number) {
            return obj;
        }

        final String className = obj.getClass().getName();

        if (obj instanceof Blob) {
            Blob blob = (Blob) obj;
            obj = blob.getBytes(1, (int) blob.length());
        } else if (obj instanceof Clob) {
            Clob clob = (Clob) obj;
            obj = clob.getSubString(1, (int) clob.length());
        } else if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className)) {
            obj = rs.getTimestamp(columnLabel);
        } else {
            final ResultSetMetaData metaData = rs.getMetaData();
            final int columnIndex = getColumnIndex(metaData, columnLabel);

            if (className != null && className.startsWith("oracle.sql.DATE")) {
                final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                    obj = rs.getTimestamp(columnLabel);
                } else {
                    obj = rs.getDate(columnLabel);
                }
            } else if (obj instanceof java.sql.Date) {
                if ("java.sql.Timestamp".equals(metaData.getColumnClassName(columnIndex))) {
                    obj = rs.getTimestamp(columnLabel);
                }
            }
        }

        return obj;
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param targetClass
     * @param rs
     * @param columnIndex
     * @return
     * @throws SQLException the SQL exception
     */
    public static <T> T getColumnValue(final Class<T> targetClass, final ResultSet rs, final int columnIndex) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param targetClass
     * @param rs
     * @param columnLabel
     * @return
     * @throws SQLException the SQL exception
     * @deprecated please consider using {@link #getColumnValue(Class, ResultSet, int)}
     */
    @Deprecated
    public static <T> T getColumnValue(final Class<T> targetClass, final ResultSet rs, final String columnLabel) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnLabel);
    }

    //    /** The Constant column2FieldNameMapPool. */
    //    private static final Map<Class<?>, ImmutableMap<String, String>> column2FieldNameMapPool = new ConcurrentHashMap<>();
    //
    //    /**
    //     * Gets the column 2 field name map.
    //     *
    //     * @param entityClass
    //     * @return
    //     */
    //     static ImmutableMap<String, String> getColumn2FieldNameMap(Class<?> entityClass) {
    //        ImmutableMap<String, String> result = column2FieldNameMapPool.get(entityClass);
    //
    //        if (result == null) {
    //            final Map<String, String> map = new HashMap<>();
    //            final EntityInfo entityInfo = ParserUtil.getEntityInfo(entityClass);
    //
    //            for (PropInfo propInfo : entityInfo.propInfoList) {
    //                if (propInfo.columnName.isPresent()) {
    //                    map.put(propInfo.columnName.get(), propInfo.name);
    //                    map.put(propInfo.columnName.get().toLowerCase(), propInfo.name);
    //                    map.put(propInfo.columnName.get().toUpperCase(), propInfo.name);
    //                }
    //            }
    //
    //            result = ImmutableMap.copyOf(map);
    //
    //            column2FieldNameMapPool.put(entityClass, result);
    //        }
    //
    //        return result;
    //    }

    public static ImmutableMap<String, String> getColumn2FieldNameMap(Class<?> entityClass) {
        return ClassUtil.getColumn2PropNameMap(entityClass);
    }

    /**
     * 
     * @param ds
     * @return
     */
    public static boolean isInTransaction(final javax.sql.DataSource ds) {
        if (SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL) != null) {
            return true;
        }

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            Connection conn = null;

            try {
                conn = getConnection(ds);

                return org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(conn, ds);
            } catch (NoClassDefFoundError e) {
                isInSpring = false;
            } finally {
                releaseConnection(conn, ds);
            }
        }

        return false;
    }

    /**
     * Refer to: {@code beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}.
     *
     * @param dataSource
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource) throws UncheckedSQLException {
        return beginTransaction(dataSource, IsolationLevel.DEFAULT);
    }

    /**
     * Refer to: {@code beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}.
     *
     * @param dataSource
     * @param isolationLevel
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)}
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel) throws UncheckedSQLException {
        return beginTransaction(dataSource, isolationLevel, false);
    }

    /**
     * Starts a global transaction which will be shared by all in-line database query with the same {@code DataSource} in the same thread,
     * including methods: {@code JdbcUtil.beginTransaction/prepareQuery/prepareNamedQuery/prepareCallableQuery, SQLExecutor(Mapper).beginTransaction/get/insert/batchInsert/update/batchUpdate/query/list/findFirst/...}
     *
     * <br />
     * Spring Transaction is supported and Integrated.
     * If this method is called at where a Spring transaction is started with the specified {@code DataSource},
     * the {@code Connection} started the Spring Transaction will be used here.
     * That's to say the Spring transaction will have the final control on commit/roll back over the {@code Connection}.
     *
     * <br />
     * <br />
     *
     * Here is the general code pattern to work with {@code SQLTransaction}.
     *
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcUtil.beginTransaction(dataSource1, isolation);
     *
     *     try {
     *         ...
     *         doSomethingB(); // Share the same transaction 'tranA' because they're in the same thread and start transaction with same DataSource 'dataSource1'.
     *         ...
     *         doSomethingC(); // won't share the same transaction 'tranA' although they're in the same thread but start transaction with different DataSource 'dataSource2'.
     *         ...
     *         tranA.commit();
     *     } finally {
     *         tranA.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingB() {
     *     ...
     *     final SQLTransaction tranB = JdbcUtil.beginTransaction(dataSource1, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranB.commit();
     *     } finally {
     *         tranB.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingC() {
     *     ...
     *     final SQLTransaction tranC = JdbcUtil.beginTransaction(dataSource2, isolation);
     *     try {
     *         // do your work with the conn...
     *         ...
     *         tranC.commit();
     *     } finally {
     *         tranC.rollbackIfNotCommitted();
     *     }
     * }
     * </pre>
     * </code>
     *
     * It's incorrect to use flag to identity the transaction should be committed or rolled back.
     * Don't write below code:
     * <pre>
     * <code>
     * public void doSomethingA() {
     *     ...
     *     final SQLTransaction tranA = JdbcUtil.beginTransaction(dataSource1, isolation);
     *     boolean flagToCommit = false;
     *     try {
     *         // do your work with the conn...
     *         ...
     *         flagToCommit = true;
     *     } finally {
     *         if (flagToCommit) {
     *             tranA.commit();
     *         } else {
     *             tranA.rollbackIfNotCommitted();
     *         }
     *     }
     * }
     * </code>
     * </pre>
     *
     * @param dataSource
     * @param isolationLevel
     * @param isForUpdateOnly
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see {@link #getConnection(javax.sql.DataSource)}
     * @see {@link #releaseConnection(Connection, javax.sql.DataSource)}
     * @see SQLExecutor#beginTransaction(IsolationLevel, boolean, JdbcSettings)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel, final boolean isForUpdateOnly)
            throws UncheckedSQLException {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(isolationLevel, "isolationLevel");

        SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try {
                conn = getConnection(dataSource);
                tran = new SQLTransaction(dataSource, conn, isolationLevel, CreatedBy.JDBC_UTIL, true);
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (noException == false) {
                    releaseConnection(conn, dataSource);
                }
            }

            logger.info("Create a new SQLTransaction(id={})", tran.id());
            SQLTransaction.putTransaction(tran);
        } else {
            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
            tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);
        }

        return tran;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Callable<T, E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);
        T result = null;

        try {
            result = cmd.call();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Function<javax.sql.DataSource, T, E> cmd)
            throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);
        T result = null;

        try {
            result = cmd.apply(ds);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);

        try {
            cmd.run();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws E {
        final SQLTransaction tran = JdbcUtil.beginTransaction(ds);

        try {
            cmd.accept(ds);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Callable<T, E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    return cmd.call();
                } else {
                    return tran.callNotInMe(cmd);
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                return cmd.call();
            } else {
                return tran.callNotInMe(cmd);
            }
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource ds,
            final Throwables.Function<javax.sql.DataSource, T, E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    return cmd.apply(ds);
                } else {
                    return tran.callNotInMe(() -> cmd.apply(ds));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                return cmd.apply(ds);
            } else {
                return tran.callNotInMe(() -> cmd.apply(ds));
            }
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd) throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    cmd.run();
                } else {
                    tran.runNotInMe(cmd);
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                cmd.run();
            } else {
                tran.runNotInMe(cmd);
            }
        }
    }

    /**
     *
     * @param <E>
     * @param ds
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd)
            throws E {
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            try {
                if (tran == null) {
                    cmd.accept(ds);
                } else {
                    tran.runNotInMe(() -> cmd.accept(ds));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            if (tran == null) {
                cmd.accept(ds);
            } else {
                tran.runNotInMe(() -> cmd.accept(ds));
            }
        }
    }

    /**
     * Gets the SQL operation.
     *
     * @param sql
     * @return
     */
    static SQLOperation getSQLOperation(String sql) {
        if (StringUtil.startsWithIgnoreCase(sql.trim(), "select ")) {
            return SQLOperation.SELECT;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "update ")) {
            return SQLOperation.UPDATE;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "insert ")) {
            return SQLOperation.INSERT;
        } else if (StringUtil.startsWithIgnoreCase(sql.trim(), "delete ")) {
            return SQLOperation.DELETE;
        } else {
            for (SQLOperation so : SQLOperation.values()) {
                if (StringUtil.startsWithIgnoreCase(sql.trim(), so.name())) {
                    return so;
                }
            }
        }

        return SQLOperation.UNKNOWN;
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, autoGeneratedKeys);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] returnColumnIndexes) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnIndexes);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] returnColumnNames) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, returnColumnNames);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedQuery(prepareStatement(conn, sql));
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedQuery(prepareStatement(conn, sql, autoGeneratedKeys));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnIndexes);
     * </code>
     * </pre>
     *
     * @param conn
     * @param sql
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnIndexes));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnNames);
     * </code>
     * </pre>
     *
     * @param conn
     * @param sql
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnNames));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedQuery(prepareStatement(conn, sql, stmtCreator));
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] returnColumnNames) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql), parsedSql);
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, autoGeneratedKeys), parsedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnIndexes), parsedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnNames), parsedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, stmtCreator), parsedSql);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final boolean autoGeneratedKeys) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final int[] returnColumnIndexes) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final String[] returnColumnNames) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, returnColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, returnColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     *
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        validateNamedSql(namedSql);

        final SQLTransaction tran = getTransaction(ds, namedSql.getParameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql), namedSql);
    }

    /**
     *
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, autoGeneratedKeys);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, autoGeneratedKeys), namedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnIndexes), namedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnNames), namedSql);
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, stmtCreator), namedSql);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql);
        } else {
            PreparedCallableQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareCallableQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedCallableQuery result = null;
            Connection conn = null;

            try {
                conn = getConnection(ds);
                result = prepareCallableQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @return
     * @throws SQLException the SQL exception
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        return new PreparedCallableQuery(prepareCallable(conn, sql));
    }

    /**
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     *
     * @param conn the specified {@code conn} won't be close after this query is executed.
     * @param sql
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/PreparedCallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/list/execute/....
     * @return
     * @throws SQLException the SQL exception
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedCallableQuery(prepareCallable(conn, sql, stmtCreator));
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return stmtCreator.apply(conn, sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql());
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final boolean autoGeneratedKeys) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int[] returnColumnIndexes) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final String[] returnColumnNames) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareStatement(parsedSql.getParameterizedSql(), resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return conn.prepareCall(sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + sql);
        }

        return stmtCreator.apply(conn, sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return conn.prepareCall(parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        if (isSQLLogEnabled_TL.get()) {
            logger.info("[SQL]: " + parsedSql.sql());
        }

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    static PreparedStatement prepareStmt(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        if (N.notNullOrEmpty(parameters)) {
            setParameters(parsedSql, stmt, parameters);
        }

        return stmt;
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    static CallableStatement prepareCall(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        if (N.notNullOrEmpty(parameters)) {
            setParameters(parsedSql, stmt, parameters);
        }

        return stmt;
    }

    /**
     * Batch prepare statement.
     *
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws SQLException the SQL exception
     */
    static PreparedStatement prepareBatchStmt(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        for (Object parameters : parametersList) {
            setParameters(parsedSql, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parametersList
     * @return
     * @throws SQLException the SQL exception
     */
    static CallableStatement prepareBatchCall(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        for (Object parameters : parametersList) {
            setParameters(parsedSql, stmt, N.asArray(parameters));
            stmt.addBatch();
        }

        return stmt;
    }

    /**
     * Creates the named SQL.
     *
     * @param namedSql
     * @return
     */
    private static ParsedSql parseNamedSql(final String namedSql) {
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");

        final ParsedSql parsedSql = ParsedSql.parse(namedSql);

        validateNamedSql(parsedSql);

        return parsedSql;
    }

    private static void validateNamedSql(final ParsedSql namedSql) {
        if (namedSql.getNamedParameters().size() != namedSql.getParameterCount()) {
            throw new IllegalArgumentException("\"" + namedSql.sql() + "\" is not a valid named sql:");
        }
    }

    private static SQLTransaction getTransaction(final javax.sql.DataSource ds, final String sql, final CreatedBy createdBy) {
        final SQLOperation sqlOperation = JdbcUtil.getSQLOperation(sql);
        final SQLTransaction tran = SQLTransaction.getTransaction(ds, createdBy);

        if (tran == null || (tran.isForUpdateOnly() && sqlOperation == SQLOperation.SELECT)) {
            return null;
        } else {
            return tran;
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static DataSet executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeQuery(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return executeQuery(conn, sql, parameters);
            } finally {
                releaseConnection(conn, ds);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static DataSet executeQuery(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

            rs = executeQuery(stmt);

            return extractData(rs);
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    //    /**
    //     *
    //     * @param stmt
    //     * @return
    //     * @throws SQLException the SQL exception
    //     */
    //    public static DataSet executeQuery(final PreparedStatement stmt) throws SQLException {
    //        ResultSet rs = null;
    //
    //        try {
    //            rs = executeQuerry(stmt);
    //
    //            return extractData(rs);
    //        } finally {
    //            closeQuietly(rs);
    //        }
    //    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeUpdate(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return executeUpdate(conn, sql, parameters);
            } finally {
                releaseConnection(conn, ds);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static int executeUpdate(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return executeUpdate(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeBatchUpdate(ds, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");
        N.checkArgPositive(batchSize, "batchSize");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = getConnection(ds);

            try {
                return executeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                releaseConnection(conn, ds);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            int ret = 0;

            try {
                ret = executeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeBatchUpdate(conn, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize) throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, parsedSql);

            final Object[] parameters = new Object[1];
            int res = 0;
            int idx = 0;

            for (Object parameter : listOfParameters) {
                parameters[0] = parameter;

                setParameters(parsedSql, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeLargeBatchUpdate(ds, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ds
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");
        N.checkArgPositive(batchSize, "batchSize");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeLargeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = getConnection(ds);

            try {
                return executeLargeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                releaseConnection(conn, ds);
            }
        } else {
            final SQLTransaction tran2 = JdbcUtil.beginTransaction(ds);
            long ret = 0;

            try {
                ret = executeLargeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } finally {
                tran2.rollbackIfNotCommitted();
            }

            return ret;
        }
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters) throws SQLException {
        return executeLargeBatchUpdate(conn, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     * Execute batch update.
     *
     * @param conn
     * @param sql
     * @param listOfParameters
     * @param batchSize
     * @return
     * @throws SQLException the SQL exception
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;

        try {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                conn.setAutoCommit(false);
            }

            stmt = prepareStatement(conn, parsedSql);

            final Object[] parameters = new Object[1];
            long res = 0;
            int idx = 0;

            for (Object parameter : listOfParameters) {
                parameters[0] = parameter;

                setParameters(parsedSql, stmt, parameters);
                stmt.addBatch();

                if (++idx % batchSize == 0) {
                    res += N.sum(executeLargeBatch(stmt));
                }
            }

            if (idx % batchSize != 0) {
                res += N.sum(executeLargeBatch(stmt));
            }

            noException = true;

            return res;
        } finally {
            if (originalAutoCommit && listOfParameters.size() > batchSize) {
                try {
                    if (noException) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } finally {
                        JdbcUtil.closeQuietly(stmt);
                    }
                }
            } else {
                JdbcUtil.closeQuietly(stmt);
            }
        }
    }

    /**
     *
     * @param ds
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "ds");
        N.checkArgNotNull(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return execute(tran.connection(), sql, parameters);
        } else {
            final Connection conn = getConnection(ds);

            try {
                return execute(conn, sql, parameters);
            } finally {
                releaseConnection(conn, ds);
            }
        }
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return true, if successful
     * @throws SQLException the SQL exception
     */
    @SafeVarargs
    public static boolean execute(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNull(sql, "sql");

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return JdbcUtil.execute(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    static ResultSet executeQuery(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeQuery();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeQuery", e);
                }
            }
        } else {
            try {
                return stmt.executeQuery();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeQuery", e);
                }
            }
        }
    }

    static int executeUpdate(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeUpdate();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeUpdate", e);
                }
            }
        } else {
            try {
                return stmt.executeUpdate();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after executeUpdate", e);
                }
            }
        }
    }

    static int[] executeBatch(Statement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeBatch();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        }
    }

    static long[] executeLargeBatch(Statement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeBatch();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        }
    }

    static boolean execute(PreparedStatement stmt) throws SQLException {
        if (logger.isInfoEnabled() && minExecutionTimeForSQLPerfLog_TL.get() >= 0) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.execute();
            } finally {
                final long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime >= minExecutionTimeForSQLPerfLog_TL.get()) {
                    logger.info("[SQL-PERF]: " + elapsedTime + ", " + stmt.toString());
                }

                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after execute", e);
                }
            }
        } else {
            try {
                return stmt.execute();
            } finally {
                try {
                    stmt.clearParameters();
                } catch (SQLException e) {
                    logger.error("Failed to clear parameters after execute", e);
                }
            }
        }
    }

    static void setParameters(final ParsedSql parsedSql, final PreparedStatement stmt, final Object[] parameters) throws SQLException {
        final int parameterCount = parsedSql.getParameterCount();

        if (parameterCount == 0) {
            return;
        } else if (N.isNullOrEmpty(parameters)) {
            throw new IllegalArgumentException(
                    "The count of parameter in sql is: " + parsedSql.getParameterCount() + ". But the specified parameters is null or empty");
        }

        Object[] parameterValues = null;
        @SuppressWarnings("rawtypes")
        Type[] parameterTypes = null;

        if (isEntityOrMapParameter(parsedSql, parameters)) {
            final List<String> namedParameters = parsedSql.getNamedParameters();
            final Object parameter_0 = parameters[0];

            parameterValues = new Object[parameterCount];

            if (ClassUtil.isEntity(parameter_0.getClass())) {
                final Object entity = parameter_0;
                final Class<?> cls = entity.getClass();
                final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
                parameterTypes = new Type[parameterCount];
                PropInfo propInfo = null;

                for (int i = 0; i < parameterCount; i++) {
                    propInfo = entityInfo.getPropInfo(namedParameters.get(i));

                    if (propInfo == null) {
                        throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                    }

                    parameterValues[i] = propInfo.getPropValue(entity);
                    parameterTypes[i] = propInfo.dbType;
                }
            } else if (parameter_0 instanceof Map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> m = (Map<String, Object>) parameter_0;

                for (int i = 0; i < parameterCount; i++) {
                    parameterValues[i] = m.get(namedParameters.get(i));

                    if ((parameterValues[i] == null) && !m.containsKey(namedParameters.get(i))) {
                        throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                    }
                }
            } else {
                final EntityId entityId = (EntityId) parameter_0;

                for (int i = 0; i < parameterCount; i++) {
                    parameterValues[i] = entityId.get(namedParameters.get(i));

                    if ((parameterValues[i] == null) && !entityId.containsKey(namedParameters.get(i))) {
                        throw new IllegalArgumentException("Parameter for property '" + namedParameters.get(i) + "' is missed");
                    }
                }
            }
        } else {
            parameterValues = getParameterValues(parsedSql, parameters);
        }

        setParameters(stmt, parameterCount, parameterValues, parameterTypes);
    }

    @SuppressWarnings("rawtypes")
    static void setParameters(final PreparedStatement stmt, final int parameterCount, final Object[] parameters, final Type[] parameterTypes)
            throws SQLException {
        if (N.notNullOrEmpty(parameterTypes) && parameterTypes.length >= parameterCount) {
            for (int i = 0; i < parameterCount; i++) {
                parameterTypes[i].set(stmt, i + 1, parameters[i]);
            }
        } else if (N.notNullOrEmpty(parameters) && parameters.length >= parameterCount) {
            for (int i = 0; i < parameterCount; i++) {
                if (parameters[i] == null) {
                    stmt.setObject(i + 1, parameters[i]);
                } else {
                    N.typeOf(parameters[i].getClass()).set(stmt, i + 1, parameters[i]);
                }
            }
        }
    }

    /**
     * Gets the parameter values.
     *
     * @param parsedSql
     * @param parameters
     * @return
     */
    static Object[] getParameterValues(final ParsedSql parsedSql, final Object... parameters) {
        if ((parameters.length == 1) && (parameters[0] != null)) {
            if (parameters[0] instanceof Object[] && ((((Object[]) parameters[0]).length) >= parsedSql.getParameterCount())) {
                return (Object[]) parameters[0];
            } else if (parameters[0] instanceof List && (((List<?>) parameters[0]).size() >= parsedSql.getParameterCount())) {
                final Collection<?> c = (Collection<?>) parameters[0];
                return c.toArray(new Object[c.size()]);
            }
        }

        return parameters;
    }

    static boolean isEntityOrMapParameter(final ParsedSql parsedSql, final Object... parameters) {
        if (N.isNullOrEmpty(parsedSql.getNamedParameters())) {
            return false;
        }

        if (N.isNullOrEmpty(parameters) || (parameters.length != 1) || (parameters[0] == null)) {
            return false;
        }

        if (ClassUtil.isEntity(parameters[0].getClass()) || parameters[0] instanceof Map || parameters[0] instanceof EntityId) {
            return true;
        }

        return false;
    }

    static final RowFilter INTERNAL_DUMMY_ROW_FILTER = RowFilter.ALWAYS_TRUE;

    static final RowExtractor INTERNAL_DUMMY_ROW_EXTRACTOR = (rs, outputRow) -> {
        throw new UnsupportedOperationException("DO NOT CALL ME.");
    };

    /**
     *
     * @param rs
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs) throws SQLException {
        return extractData(rs, false);
    }

    /**
     *
     * @param rs
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final boolean closeResultSet) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count) throws SQLException {
        return extractData(rs, offset, count, false);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param filter
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, int offset, int count, final RowFilter filter, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param rowExtractor
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, int offset, int count, final RowExtractor rowExtractor, final boolean closeResultSet)
            throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, closeResultSet);
    }

    /**
     *
     * @param rs
     * @param offset
     * @param count
     * @param filter
     * @param rowExtractor
     * @param closeResultSet
     * @return
     * @throws SQLException the SQL exception
     */
    public static DataSet extractData(final ResultSet rs, int offset, int count, final RowFilter filter, final RowExtractor rowExtractor,
            final boolean closeResultSet) throws SQLException {
        N.checkArgNotNull(rs, "ResultSet");
        N.checkArgNotNegative(offset, "offset");
        N.checkArgNotNegative(count, "count");
        N.checkArgNotNull(filter, "filter");
        N.checkArgNotNull(rowExtractor, "rowExtractor");

        try {
            // TODO [performance improvement]. it will improve performance a lot if MetaData is cached.
            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            final List<String> columnNameList = new ArrayList<>(columnCount);
            final List<List<Object>> columnList = new ArrayList<>(columnCount);

            for (int i = 0; i < columnCount;) {
                columnNameList.add(JdbcUtil.getColumnLabel(rsmd, ++i));
                columnList.add(new ArrayList<>());
            }

            JdbcUtil.skip(rs, offset);

            if (filter == INTERNAL_DUMMY_ROW_FILTER) {
                if (rowExtractor == INTERNAL_DUMMY_ROW_EXTRACTOR) {
                    while (count > 0 && rs.next()) {
                        for (int i = 0; i < columnCount;) {
                            columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i));
                        }

                        count--;
                    }
                } else {
                    final Object[] outputRow = new Object[columnCount];

                    while (count > 0 && rs.next()) {
                        rowExtractor.accept(rs, outputRow);

                        for (int i = 0; i < columnCount; i++) {
                            columnList.get(i).add(outputRow[i]);
                        }

                        count--;
                    }
                }
            } else {
                if (rowExtractor == INTERNAL_DUMMY_ROW_EXTRACTOR) {
                    while (count > 0 && rs.next()) {
                        if (filter.test(rs)) {
                            for (int i = 0; i < columnCount;) {
                                columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i));
                            }

                            count--;
                        }
                    }
                } else {
                    final Object[] outputRow = new Object[columnCount];

                    while (count > 0 && rs.next()) {
                        if (filter.test(rs)) {
                            rowExtractor.accept(rs, outputRow);

                            for (int i = 0; i < columnCount; i++) {
                                columnList.get(i).add(outputRow[i]);
                            }

                            count--;
                        }
                    }
                }
            }

            // return new RowDataSet(null, entityClass, columnNameList, columnList);
            return new RowDataSet(columnNameList, columnList);
        } finally {
            if (closeResultSet) {
                closeQuietly(rs);
            }
        }
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished.
     *
     * @param resultSet
     * @return
     */
    public static ExceptionalStream<Object[], SQLException> stream(final ResultSet resultSet) {
        return stream(Object[].class, resultSet);
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished.
     *
     * @param <T>
     * @param targetClass Array/List/Map or Entity with getter/setter methods.
     * @param resultSet
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final Class<T> targetClass, final ResultSet resultSet) {
        N.checkArgNotNull(targetClass, "targetClass");
        N.checkArgNotNull(resultSet, "resultSet");

        return stream(resultSet, BiRowMapper.to(targetClass));
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished.
     *
     * @param <T>
     * @param resultSet
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final RowMapper<T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowMapper, "rowMapper");

        final ExceptionalIterator<T, SQLException> iter = new ExceptionalIterator<T, SQLException>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (hasNext == false) {
                    hasNext = resultSet.next();
                }

                return hasNext;
            }

            @Override
            public T next() throws SQLException {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                hasNext = false;

                return rowMapper.apply(resultSet);
            }

            @Override
            public void skip(long n) throws SQLException {
                N.checkArgNotNegative(n, "n");

                final long m = hasNext ? n - 1 : n;

                JdbcUtil.skip(resultSet, m);

                hasNext = false;
            }
        };

        return ExceptionalStream.newStream(iter);
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished.
     *
     * @param <T>
     * @param resultSet
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final BiRowMapper<T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowMapper, "rowMapper");

        final ExceptionalIterator<T, SQLException> iter = new ExceptionalIterator<T, SQLException>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (hasNext == false) {
                    hasNext = resultSet.next();
                }

                return hasNext;
            }

            @Override
            public T next() throws SQLException {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                hasNext = false;

                if (columnLabels == null) {
                    columnLabels = getColumnLabelList(resultSet);
                }

                return rowMapper.apply(resultSet, columnLabels);
            }

            @Override
            public void skip(long n) throws SQLException {
                N.checkArgNotNegative(n, "n");

                final long m = hasNext ? n - 1 : n;

                JdbcUtil.skip(resultSet, m);

                hasNext = false;
            }

            @Override
            public long count() throws SQLException {
                long cnt = 0;

                while (resultSet.next()) {
                    cnt++;
                }

                return cnt;
            }
        };

        return ExceptionalStream.newStream(iter);
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished.
     *
     * @param <T>
     * @param resultSet
     * @param columnIndex starts from 1, not 0.
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final int columnIndex) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgPositive(columnIndex, "columnIndex");

        final RowMapper<T> rowMapper = rs -> (T) getColumnValue(resultSet, columnIndex);

        return stream(resultSet, rowMapper);
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished.
     *
     * @param <T>
     * @param resultSet
     * @param columnName
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final String columnName) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNullOrEmpty(columnName, "columnName");

        final RowMapper<T> rowMapper = new RowMapper<T>() {
            private int columnIndex = -1;

            @Override
            public T apply(ResultSet rs) throws SQLException {
                if (columnIndex == -1) {
                    columnIndex = getColumnIndex(resultSet, columnName);
                }

                return (T) getColumnValue(resultSet, columnIndex);
            }
        };

        return stream(resultSet, rowMapper);
    }

    /**
     * Does table exist.
     *
     * @param conn
     * @param tableName
     * @return true, if successful
     */
    public static boolean doesTableExist(final Connection conn, final String tableName) {
        try {
            executeQuery(conn, "SELECT 1 FROM " + tableName + " WHERE 1 > 2");

            return true;
        } catch (SQLException e) {
            if (isTableNotExistsException(e)) {
                return false;
            }

            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Returns {@code true} if succeed to create table, otherwise {@code false} is returned.
     *
     * @param conn
     * @param tableName
     * @param schema
     * @return true, if successful
     */
    public static boolean createTableIfNotExists(final Connection conn, final String tableName, final String schema) {
        if (doesTableExist(conn, tableName)) {
            return false;
        }

        try {
            execute(conn, schema);

            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Returns {@code true} if succeed to drop table, otherwise {@code false} is returned.
     *
     * @param conn
     * @param tableName
     * @return true, if successful
     */
    public static boolean dropTableIfExists(final Connection conn, final String tableName) {
        try {
            if (doesTableExist(conn, tableName)) {
                execute(conn, "DROP TABLE " + tableName);

                return true;
            }
        } catch (SQLException e) {
            // ignore.
        }

        return false;
    }

    /**
     * Gets the named parameters.
     *
     * @param sql
     * @return
     */
    public static List<String> getNamedParameters(String sql) {
        return ParsedSql.parse(sql).getNamedParameters();
    }

    /**
     * Gets the DB sequence.
     *
     * @param tableName
     * @param seqName
     * @return
     */
    public static DBSequence getDBSequence(final javax.sql.DataSource ds, final String tableName, final String seqName) {
        return new DBSequence(ds, tableName, seqName, 0, 1000);
    }

    /**
     * Supports global sequence by db table.
     *
     * @param tableName
     * @param seqName
     * @param startVal
     * @param seqBufferSize the numbers to allocate/reserve from database table when cached numbers are used up.
     * @return
     */
    public static DBSequence getDBSequence(final javax.sql.DataSource ds, final String tableName, final String seqName, final long startVal,
            final int seqBufferSize) {
        return new DBSequence(ds, tableName, seqName, startVal, seqBufferSize);
    }

    /**
     * Supports global lock by db table.
     *
     * @param tableName
     * @return
     */
    public static DBLock getDBLock(final javax.sql.DataSource ds, final String tableName) {
        return new DBLock(ds, tableName);
    }

    /**
     * Checks if is table not exists exception.
     *
     * @param e
     * @return true, if is table not exists exception
     */
    static boolean isTableNotExistsException(final Throwable e) {
        if (e instanceof SQLException) {
            SQLException sqlException = (SQLException) e;

            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return N.notNullOrEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        } else if (e instanceof UncheckedSQLException) {
            UncheckedSQLException sqlException = (UncheckedSQLException) e;

            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return N.notNullOrEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        }

        return false;
    }

    static final ThreadLocal<Boolean> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Enable/Disable sql log in current thread.
     *
     * @param b {@code true} to enable, {@code false} to disable.
     */
    public static void enableSQLLog(boolean b) {
        // synchronized (isSQLLogEnabled_TL) {
        if (logger.isDebugEnabled() && isSQLLogEnabled_TL.get() != b) {
            if (b) {
                logger.debug("Turn on [SQL] log");
            } else {
                logger.debug("Turn off [SQL] log");
            }
        }

        isSQLLogEnabled_TL.set(b);
        // }
    }

    /**
     * Checks if sql log is enabled or not in current thread.
     *
     * @return {@code true} if it's enabled, otherwise {@code false} is returned.
     */
    public static boolean isSQLLogEnabled() {
        return isSQLLogEnabled_TL.get();
    }

    static final ThreadLocal<Long> minExecutionTimeForSQLPerfLog_TL = ThreadLocal.withInitial(() -> 1000L);

    /**
     * Set minimum execution time to log sql performance in current thread.
     *
     * @param minExecutionTimeForSQLPerfLog Default value is 1000 (milliseconds).
     */
    public static void setMinExecutionTimeForSQLPerfLog(long minExecutionTimeForSQLPerfLog) {
        // synchronized (minExecutionTimeForSQLPerfLog_TL) {
        if (logger.isDebugEnabled() && minExecutionTimeForSQLPerfLog_TL.get() != minExecutionTimeForSQLPerfLog) {
            if (minExecutionTimeForSQLPerfLog >= 0) {
                logger.debug("set 'minExecutionTimeForSQLPerfLog' to: " + minExecutionTimeForSQLPerfLog);
            } else {
                logger.debug("Turn off SQL perfermance log");
            }
        }

        minExecutionTimeForSQLPerfLog_TL.set(minExecutionTimeForSQLPerfLog);
        // }
    }

    /**
     * Return the minimum execution time in milliseconds to log SQL performance. Default value is 1000 (milliseconds).
     *
     * @return
     */
    public static long getMinExecutionTimeForSQLPerfLog() {
        return minExecutionTimeForSQLPerfLog_TL.get();
    }

    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Disable/enable {@code Spring Transactional} in current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to disable, {@code false} to enable it again.
     */
    public static void disableSpringTransactional(boolean b) {
        // synchronized (isSpringTransactionalDisabled_TL) {
        if (isInSpring) {
            if (logger.isWarnEnabled() && isSpringTransactionalDisabled_TL.get() != b) {
                if (b) {
                    logger.warn("Disable Spring Transactional");
                } else {
                    logger.warn("Enable Spring Transactional again");
                }
            }

            isSpringTransactionalDisabled_TL.set(b);
        } else {
            logger.warn("Not in Spring or not able to retrieve Spring Transactional");
        }
        // }
    }

    /**
     * Check if {@code Spring Transactional} is disabled or not in current thread.
     *
     * @return {@code true} if it's disabled, otherwise {@code false} is returned.
     */
    public static boolean isSpringTransactionalDisabled() {
        return isSpringTransactionalDisabled_TL.get();
    }

    /**
     * Checks if is default id prop value.
     *
     * @param propValue
     * @return true, if is default id prop value
     * @deprecated for internal only.
     */
    @Deprecated
    @Internal
    public static boolean isDefaultIdPropValue(final Object propValue) {
        return (propValue == null) || (propValue instanceof Number && (((Number) propValue).longValue() == 0));
    }

    static <ID> boolean isAllNullIds(List<ID> ids) {
        return N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(JdbcUtil::isDefaultIdPropValue);
    }

    @Beta
    public static void run(final Throwables.Runnable<Exception> sqlAction) {
        try {
            sqlAction.run();
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T> void run(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) {
        try {
            sqlAction.accept(t);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T, U> void run(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction) {
        try {
            sqlAction.accept(t, u);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <A, B, C> void run(final A a, final B b, final C c, final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction) {
        try {
            sqlAction.accept(a, b, c);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <R> R call(final Callable<R> sqlAction) {
        try {
            return sqlAction.call();
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T, R> R call(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction) {
        try {
            return sqlAction.apply(t);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T, U, R> R call(final T t, final U u, final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction) {
        try {
            return sqlAction.apply(t, u);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <A, B, C, R> R call(final A a, final B b, final C c,
            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) {
        try {
            return sqlAction.apply(a, b, c);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param sqlAction
     * @return
     */
    @Beta
    public static ContinuableFuture<Void> asyncRun(final Throwables.Runnable<Exception> sqlAction) {
        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param <T>
     * @param t
     * @param sqlAction
     * @return
     */
    @Beta
    public static <T> ContinuableFuture<Void> asyncRun(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) {
        return asyncExecutor.execute(() -> sqlAction.accept(t));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param <T>
     * @param <U>
     * @param t
     * @param u
     * @param sqlAction
     * @return
     */
    @Beta
    public static <T, U> ContinuableFuture<Void> asyncRun(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction) {
        return asyncExecutor.execute(() -> sqlAction.accept(t, u));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param <A>
     * @param <B>
     * @param <C>
     * @param a
     * @param b
     * @param c
     * @param sqlAction
     * @return
     */
    @Beta
    public static <A, B, C> ContinuableFuture<Void> asyncRun(final A a, final B b, final C c,
            final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction) {
        return asyncExecutor.execute(() -> sqlAction.accept(a, b, c));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param <R>
     * @param sqlAction
     * @return
     */
    @Beta
    public static <R> ContinuableFuture<R> asyncCall(final Callable<R> sqlAction) {
        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param <T>
     * @param <R>
     * @param t
     * @param sqlAction
     * @return
     */
    @Beta
    public static <T, R> ContinuableFuture<R> asyncCall(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction) {
        return asyncExecutor.execute(() -> sqlAction.apply(t));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param <T>
     * @param <U>
     * @param <R>
     * @param t
     * @param u
     * @param sqlAction
     * @return
     */
    @Beta
    public static <T, U, R> ContinuableFuture<R> asyncCall(final T t, final U u,
            final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction) {
        return asyncExecutor.execute(() -> sqlAction.apply(t, u));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     * 
     * @param <A>
     * @param <B>
     * @param <C>
     * @param <R>
     * @param a
     * @param b
     * @param c
     * @param sqlAction
     * @return
     */
    @Beta
    public static <A, B, C, R> ContinuableFuture<R> asyncCall(final A a, final B b, final C c,
            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) {
        return asyncExecutor.execute(() -> sqlAction.apply(a, b, c));
    }

    /**
     * The Interface ParametersSetter.
     *
     * @param <QS>
     */
    @FunctionalInterface
    public static interface ParametersSetter<QS> extends Throwables.Consumer<QS, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final ParametersSetter DO_NOTHING = new ParametersSetter<Object>() {
            @Override
            public void accept(Object preparedQuery) throws SQLException {
                // Do nothing.
            }
        };

        @Override
        void accept(QS preparedQuery) throws SQLException;
    }

    /**
     * The Interface BiParametersSetter.
     *
     * @param <QS>
     * @param <T>
     * @see Columns.ColumnOne
     * @see Columns.ColumnTwo
     * @see Columns.ColumnThree
     */
    @FunctionalInterface
    public static interface BiParametersSetter<QS, T> extends Throwables.BiConsumer<QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter DO_NOTHING = new BiParametersSetter<Object, Object>() {
            @Override
            public void accept(Object preparedQuery, Object param) throws SQLException {
                // Do nothing.
            }
        };

        @Override
        void accept(QS preparedQuery, T param) throws SQLException;
    }

    /**
     * The Interface TriParametersSetter.
     *
     * @param <QS>
     * @param <T>
     */
    @FunctionalInterface
    public static interface TriParametersSetter<QS, T> extends Throwables.TriConsumer<ParsedSql, QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        public static final TriParametersSetter DO_NOTHING = new TriParametersSetter<Object, Object>() {
            @Override
            public void accept(ParsedSql parsedSql, Object preparedQuery, Object param) throws SQLException {
                // Do nothing.
            }
        };

        @Override
        void accept(ParsedSql parsedSql, QS preparedQuery, T param) throws SQLException;
    }

    /**
     * The Interface ResultExtractor.
     *
     * @param <T>
     */
    @FunctionalInterface
    public static interface ResultExtractor<T> extends Throwables.Function<ResultSet, T, SQLException> {

        ResultExtractor<DataSet> TO_DATA_SET = new ResultExtractor<DataSet>() {
            @Override
            public DataSet apply(final ResultSet rs) throws SQLException {
                return JdbcUtil.extractData(rs);
            }
        };

        @Override
        T apply(ResultSet rs) throws SQLException;

        default <R> ResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {
                    final M result = supplier.get();

                    while (rs.next()) {
                        Maps.merge(result, keyExtractor.apply(rs), valueExtractor.apply(rs), mergeFunction);
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, A, D> ResultExtractor<Map<K, D>> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, A, D, M extends Map<K, D>> ResultExtractor<M> toMap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs);
                        container = tmp.get(key);

                        if (container == null) {
                            container = downstreamSupplier.get();
                            tmp.put(key, container);
                        }

                        downstreamAccumulator.accept(container, valueExtractor.apply(rs));
                    }

                    for (Map.Entry<K, D> entry : result.entrySet()) {
                        entry.setValue(downstreamFinisher.apply((A) entry.getValue()));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<ListMultimap<K, V>> toMultimap(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> ResultExtractor<M> toMultimap(final RowMapper<K> keyExtractor,
                final RowMapper<V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {
                    final M result = multimapSupplier.get();

                    while (rs.next()) {
                        result.put(keyExtractor.apply(rs), valueExtractor.apply(rs));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor) throws SQLException {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> ResultExtractor<M> groupTo(final RowMapper<K> keyExtractor, final RowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return new ResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs) throws SQLException {

                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs);
                        value = result.get(key);

                        if (value == null) {
                            value = new ArrayList<>();
                            result.put(key, value);
                        }

                        value.add(valueExtractor.apply(rs));
                    }

                    return result;
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs) throws SQLException {
                    return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowFilter, false);
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowExtractor rowExtractor) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs) throws SQLException {
                    return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false);
                }
            };
        }

        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter, final RowExtractor rowExtractor) {
            return new ResultExtractor<DataSet>() {
                @Override
                public DataSet apply(final ResultSet rs) throws SQLException {
                    return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowFilter, rowExtractor, false);
                }
            };
        }

        static <R> ResultExtractor<R> to(final Throwables.Function<DataSet, R, SQLException> after) {
            return rs -> after.apply(TO_DATA_SET.apply(rs));
        }
    }

    /**
     * The Interface BiResultExtractor.
     *
     * @param <T>
     */
    @FunctionalInterface
    public static interface BiResultExtractor<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        default <R> BiResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final M result = supplier.get();

                    while (rs.next()) {
                        Maps.merge(result, keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels), mergeFunction);
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, A, D> BiResultExtractor<Map<K, D>> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <A>
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, A, D, M extends Map<K, D>> BiResultExtractor<M> toMap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Collector<? super V, A, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {

                    final Supplier<A> downstreamSupplier = downstream.supplier();
                    final BiConsumer<A, ? super V> downstreamAccumulator = downstream.accumulator();
                    final Function<A, D> downstreamFinisher = downstream.finisher();

                    final M result = supplier.get();
                    final Map<K, A> tmp = (Map<K, A>) result;
                    K key = null;
                    A container = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs, columnLabels);
                        container = tmp.get(key);

                        if (container == null) {
                            container = downstreamSupplier.get();
                            tmp.put(key, container);
                        }

                        downstreamAccumulator.accept(container, valueExtractor.apply(rs, columnLabels));
                    }

                    for (Map.Entry<K, D> entry : result.entrySet()) {
                        entry.setValue(downstreamFinisher.apply((A) entry.getValue()));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<ListMultimap<K, V>> toMultimap(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> BiResultExtractor<M> toMultimap(final BiRowMapper<K> keyExtractor,
                final BiRowMapper<V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final M result = multimapSupplier.get();

                    while (rs.next()) {
                        result.put(keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels));
                    }

                    return result;
                }
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         * @throws SQLException the SQL exception
         */
        static <K, V> BiResultExtractor<Map<K, List<V>>> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor) throws SQLException {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> BiResultExtractor<M> groupTo(final BiRowMapper<K> keyExtractor, final BiRowMapper<V> valueExtractor,
                final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return new BiResultExtractor<M>() {
                @Override
                public M apply(final ResultSet rs, List<String> columnLabels) throws SQLException {
                    final M result = supplier.get();
                    K key = null;
                    List<V> value = null;

                    while (rs.next()) {
                        key = keyExtractor.apply(rs, columnLabels);
                        value = result.get(key);

                        if (value == null) {
                            value = new ArrayList<>();
                            result.put(key, value);
                        }

                        value.add(valueExtractor.apply(rs, columnLabels));
                    }

                    return result;
                }
            };
        }
    }

    static final RowMapper<Object> NO_GENERATED_KEY_EXTRACTOR = rs -> null;

    static final RowMapper<Object> SINGLE_GENERATED_KEY_EXTRACTOR = rs -> getColumnValue(rs, 1);

    @SuppressWarnings("deprecation")
    static final RowMapper<Object> MULTI_GENERATED_KEY_EXTRACTOR = rs -> {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(N.EMPTY_STRING);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    static final BiRowMapper<Object> NO_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> null;

    static final BiRowMapper<Object> SINGLE_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> getColumnValue(rs, 1);

    @SuppressWarnings("deprecation")
    static final BiRowMapper<Object> MULTI_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> {
        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(N.EMPTY_STRING);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    /**
     * Don't use {@code RowMapper} in {@link PreparedQuery#list(RowMapper)} or any place where multiple records will be retrieved by it, if column labels/count are used in {@link RowMapper#apply(ResultSet)}.
     * Consider using {@code BiRowMapper} instead because it's more efficient to retrieve multiple records when column labels/count are used.
     *
     * @param <T>
     * @see Columns.ColumnOne
     * @see Columns.ColumnTwo
     * @see Columns.ColumnThree
     */
    @FunctionalInterface
    public static interface RowMapper<T> extends Throwables.Function<ResultSet, T, SQLException> {

        @Override
        T apply(ResultSet rs) throws SQLException;

        default <R> RowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        static RowMapperBuilder builder() {
            return builder(Columns.ColumnGetter.GET_OBJECT);
        }

        static RowMapperBuilder builder(final Columns.ColumnGetter<?> defaultColumnGetter) {
            return new RowMapperBuilder(defaultColumnGetter);
        }

        //    static RowMapperBuilder builder(final int columnCount) {
        //        return new RowMapperBuilder(columnCount);
        //    }

        public static class RowMapperBuilder {
            private final Map<Integer, Columns.ColumnGetter<?>> columnGetterMap;

            RowMapperBuilder(final Columns.ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, "defaultColumnGetter");

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            public RowMapperBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_BOOLEAN);
            }

            public RowMapperBuilder getByte(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_BYTE);
            }

            public RowMapperBuilder getShort(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_SHORT);
            }

            public RowMapperBuilder getInt(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_INT);
            }

            public RowMapperBuilder getLong(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_LONG);
            }

            public RowMapperBuilder getFloat(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_FLOAT);
            }

            public RowMapperBuilder getDouble(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_DOUBLE);
            }

            public RowMapperBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_BIG_DECIMAL);
            }

            public RowMapperBuilder getString(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_STRING);
            }

            public RowMapperBuilder getDate(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_DATE);
            }

            public RowMapperBuilder getTime(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_TIME);
            }

            public RowMapperBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_TIMESTAMP);
            }

            public RowMapperBuilder get(final int columnIndex, final Columns.ColumnGetter<?> columnGetter) {
                N.checkArgPositive(columnIndex, "columnIndex");
                N.checkArgNotNull(columnGetter, "columnGetter");

                //        if (columnGetters == null) {
                //            columnGetterMap.put(columnIndex, columnGetter);
                //        } else {
                //            columnGetters[columnIndex] = columnGetter;
                //        }

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            /**
             *
             * Set column getter function for column[columnIndex].
             *
             * @param columnIndex start from 1.
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(int, ColumnGetter)}
             */
            @Deprecated
            public RowMapperBuilder column(final int columnIndex, final Columns.ColumnGetter<?> columnGetter) {
                return get(columnIndex, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(0, columnGetter);
            //        } else {
            //            columnGetters[0] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[columnIndex].
            //     *
            //     * @param columnIndex start from 1.
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final int columnIndex, final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(columnIndex, columnGetter);
            //        } else {
            //            columnGetters[columnIndex] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _1(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(1, columnGetter);
            //        } else {
            //            columnGetters[1] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _2(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(2, columnGetter);
            //        } else {
            //            columnGetters[2] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[3].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _3(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(3, columnGetter);
            //        } else {
            //            columnGetters[3] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[4].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _4(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(4, columnGetter);
            //        } else {
            //            columnGetters[4] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[5].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _5(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(5, columnGetter);
            //        } else {
            //            columnGetters[5] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[6].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _6(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(6, columnGetter);
            //        } else {
            //            columnGetters[6] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[7].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _7(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(7, columnGetter);
            //        } else {
            //            columnGetters[7] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[8].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _8(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(8, columnGetter);
            //        } else {
            //            columnGetters[8] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[9].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _9(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(9, columnGetter);
            //        } else {
            //            columnGetters[9] = columnGetter;
            //        }
            //
            //        return this;
            //    }

            //    void setDefaultColumnGetter() {
            //        if (columnGetters != null) {
            //            for (int i = 1, len = columnGetters.length; i < len; i++) {
            //                if (columnGetters[i] == null) {
            //                    columnGetters[i] = columnGetters[0];
            //                }
            //            }
            //        }
            //    }

            Columns.ColumnGetter<?>[] initColumnGetter(ResultSet rs) throws SQLException {
                return initColumnGetter(rs.getMetaData().getColumnCount());
            }

            Columns.ColumnGetter<?>[] initColumnGetter(final int columnCount) throws SQLException {
                final Columns.ColumnGetter<?>[] rsColumnGetters = new Columns.ColumnGetter<?>[columnCount + 1];
                rsColumnGetters[0] = columnGetterMap.get(0);

                for (int i = 1, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i, rsColumnGetters[0]);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            public RowMapper<Object[]> toArray() {
                // setDefaultColumnGetter();

                return new RowMapper<Object[]>() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public Object[] apply(ResultSet rs) throws SQLException {
                        Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final Object[] row = new Object[rsColumnCount];

                        for (int i = 0; i < rsColumnCount;) {
                            row[i] = rsColumnGetters[++i].apply(i, rs);
                        }

                        return row;
                    }
                };
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            public RowMapper<List<Object>> toList() {
                // setDefaultColumnGetter();

                return new RowMapper<List<Object>>() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public List<Object> apply(ResultSet rs) throws SQLException {
                        Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final List<Object> row = new ArrayList<>(rsColumnCount);

                        for (int i = 0; i < rsColumnCount;) {
                            row.add(rsColumnGetters[++i].apply(i, rs));
                        }

                        return row;
                    }
                };
            }

            public <R> RowMapper<R> to(final Throwables.Function<DisposableObjArray, R, SQLException> finisher) {
                return new RowMapper<R>() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;
                    private Object[] outputRow = null;
                    private DisposableObjArray output;

                    @Override
                    public R apply(ResultSet rs) throws SQLException {
                        Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            this.rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                            this.outputRow = new Object[rsColumnCount];
                            this.output = DisposableObjArray.wrap(outputRow);
                        }

                        for (int i = 0; i < rsColumnCount;) {
                            outputRow[i] = rsColumnGetters[++i].apply(i, rs);
                        }

                        return finisher.apply(output);
                    }
                };
            }
        }
    }

    /**
     * The Interface BiRowMapper.
     *
     * @param <T>
     */
    @FunctionalInterface
    public static interface BiRowMapper<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /** The Constant TO_ARRAY. */
        BiRowMapper<Object[]> TO_ARRAY = new BiRowMapper<Object[]>() {
            @Override
            public Object[] apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Object[] result = new Object[columnCount];

                for (int i = 1; i <= columnCount; i++) {
                    result[i - 1] = JdbcUtil.getColumnValue(rs, i);
                }

                return result;
            }
        };

        /** The Constant TO_LIST. */
        BiRowMapper<List<Object>> TO_LIST = new BiRowMapper<List<Object>>() {
            @Override
            public List<Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final List<Object> result = new ArrayList<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.add(JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        /** The Constant TO_MAP. */
        BiRowMapper<Map<String, Object>> TO_MAP = new BiRowMapper<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = new HashMap<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        /** The Constant TO_LINKED_HASH_MAP. */
        BiRowMapper<Map<String, Object>> TO_LINKED_HASH_MAP = new BiRowMapper<Map<String, Object>>() {
            @Override
            public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = new LinkedHashMap<>(columnCount);

                for (int i = 1; i <= columnCount; i++) {
                    result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return result;
            }
        };

        BiRowMapper<EntityId> TO_ENTITY_ID = new BiRowMapper<EntityId>() {
            @SuppressWarnings("deprecation")
            @Override
            public EntityId apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Seid entityId = Seid.of(N.EMPTY_STRING);

                for (int i = 1; i <= columnCount; i++) {
                    entityId.set(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return entityId;
            }
        };

        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        default <R> BiRowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        /**
         * Don't cache or reuse the returned {@code BiRowMapper} instance. It's stateful.
         *
         * @param <T>
         * @param targetClass
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass) {
            return to(targetClass, false);
        }

        /**
         * Don't cache or reuse the returned {@code BiRowMapper} instance. It's stateful.
         *
         * @param <T>
         * @param targetClass
         * @param ignoreNonMatchedColumns
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
            if (Object[].class.isAssignableFrom(targetClass)) {
                return new BiRowMapper<T>() {
                    @Override
                    public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                        final int columnCount = columnLabelList.size();
                        final Object[] a = Array.newInstance(targetClass.getComponentType(), columnCount);

                        for (int i = 0; i < columnCount; i++) {
                            a[i] = getColumnValue(rs, i + 1);
                        }

                        return (T) a;
                    }
                };
            } else if (List.class.isAssignableFrom(targetClass)) {
                return new BiRowMapper<T>() {
                    private final boolean isListOrArrayList = targetClass.equals(List.class) || targetClass.equals(ArrayList.class);

                    @Override
                    public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                        final int columnCount = columnLabelList.size();
                        final List<Object> c = isListOrArrayList ? new ArrayList<>(columnCount) : (List<Object>) N.newInstance(targetClass);

                        for (int i = 0; i < columnCount; i++) {
                            c.add(getColumnValue(rs, i + 1));
                        }

                        return (T) c;
                    }
                };
            } else if (Map.class.isAssignableFrom(targetClass)) {
                return new BiRowMapper<T>() {
                    private final boolean isMapOrHashMap = targetClass.equals(Map.class) || targetClass.equals(HashMap.class);
                    private final boolean isLinkedHashMap = targetClass.equals(LinkedHashMap.class);
                    private volatile String[] columnLabels = null;

                    @Override
                    public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                        final int columnCount = columnLabelList.size();
                        String[] columnLabels = this.columnLabels;

                        if (columnLabels == null) {
                            columnLabels = columnLabelList.toArray(new String[columnCount]);
                            this.columnLabels = columnLabels;
                        }

                        final Map<String, Object> m = isMapOrHashMap ? new HashMap<>(columnCount)
                                : (isLinkedHashMap ? new LinkedHashMap<>(columnCount) : (Map<String, Object>) N.newInstance(targetClass));

                        for (int i = 0; i < columnCount; i++) {
                            m.put(columnLabels[i], getColumnValue(rs, i + 1));
                        }

                        return (T) m;
                    }
                };
            } else if (ClassUtil.isEntity(targetClass)) {
                return new BiRowMapper<T>() {
                    private final boolean isDirtyMarker = DirtyMarkerUtil.isDirtyMarker(targetClass);
                    private final EntityInfo entityInfo = ParserUtil.getEntityInfo(targetClass);
                    private volatile String[] columnLabels = null;
                    private volatile PropInfo[] propInfos;
                    private volatile Type<?>[] columnTypes = null;

                    @Override
                    public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                        final int columnCount = columnLabelList.size();

                        String[] columnLabels = this.columnLabels;
                        PropInfo[] propInfos = this.propInfos;
                        Type<?>[] columnTypes = this.columnTypes;

                        if (columnLabels == null) {
                            columnLabels = columnLabelList.toArray(new String[columnCount]);
                            this.columnLabels = columnLabels;
                        }

                        if (columnTypes == null || propInfos == null) {
                            final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(targetClass);

                            propInfos = new PropInfo[columnCount];
                            columnTypes = new Type[columnCount];

                            for (int i = 0; i < columnCount; i++) {
                                propInfos[i] = entityInfo.getPropInfo(columnLabels[i]);

                                if (propInfos[i] == null) {
                                    String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                    if (N.isNullOrEmpty(fieldName)) {
                                        fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                    }

                                    if (N.notNullOrEmpty(fieldName)) {
                                        propInfos[i] = entityInfo.getPropInfo(fieldName);
                                    }
                                }

                                if (propInfos[i] == null) {
                                    if (ignoreNonMatchedColumns) {
                                        columnLabels[i] = null;
                                    } else {
                                        throw new IllegalArgumentException("No property in class: " + ClassUtil.getCanonicalClassName(targetClass)
                                                + " mapping to column: " + columnLabels[i]);
                                    }
                                } else {
                                    columnTypes[i] = entityInfo.getPropInfo(columnLabels[i]).dbType;
                                }
                            }

                            this.propInfos = propInfos;
                            this.columnTypes = columnTypes;
                        }

                        final Object entity = N.newInstance(targetClass);

                        for (int i = 0; i < columnCount; i++) {
                            if (columnLabels[i] == null) {
                                continue;
                            }

                            propInfos[i].setPropValue(entity, columnTypes[i].get(rs, i + 1));
                        }

                        if (isDirtyMarker) {
                            DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                        }

                        return (T) entity;
                    }
                };
            } else {
                return new BiRowMapper<T>() {
                    private final Type<? extends T> targetType = N.typeOf(targetClass);
                    private int columnCount = 0;

                    @Override
                    public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                        if (columnCount != 1 && (columnCount = columnLabelList.size()) != 1) {
                            throw new IllegalArgumentException(
                                    "It's not supported to retrieve value from multiple columns: " + columnLabelList + " for type: " + targetClass);
                        }

                        return targetType.get(rs, 1);
                    }
                };
            }
        }

        static BiRowMapperBuilder builder() {
            return builder(Columns.ColumnGetter.GET_OBJECT);
        }

        static BiRowMapperBuilder builder(final Columns.ColumnGetter<?> defaultColumnGetter) {
            return new BiRowMapperBuilder(defaultColumnGetter);
        }

        //    static BiRowMapperBuilder builder(final int columnCount) {
        //        return new BiRowMapperBuilder(columnCount);
        //    }

        public static class BiRowMapperBuilder {
            private final Columns.ColumnGetter<?> defaultColumnGetter;
            private final Map<String, Columns.ColumnGetter<?>> columnGetterMap;

            BiRowMapperBuilder(final Columns.ColumnGetter<?> defaultColumnGetter) {
                this.defaultColumnGetter = defaultColumnGetter;

                columnGetterMap = new HashMap<>(9);
            }

            public BiRowMapperBuilder getBoolean(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_BOOLEAN);
            }

            public BiRowMapperBuilder getByte(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_BYTE);
            }

            public BiRowMapperBuilder getShort(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_SHORT);
            }

            public BiRowMapperBuilder getInt(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_INT);
            }

            public BiRowMapperBuilder getLong(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_LONG);
            }

            public BiRowMapperBuilder getFloat(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_FLOAT);
            }

            public BiRowMapperBuilder getDouble(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_DOUBLE);
            }

            public BiRowMapperBuilder getBigDecimal(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_BIG_DECIMAL);
            }

            public BiRowMapperBuilder getString(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_STRING);
            }

            public BiRowMapperBuilder getDate(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_DATE);
            }

            public BiRowMapperBuilder getTime(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_TIME);
            }

            public BiRowMapperBuilder getTimestamp(final String columnName) {
                return get(columnName, Columns.ColumnGetter.GET_TIMESTAMP);
            }

            public BiRowMapperBuilder get(final String columnName, final Columns.ColumnGetter<?> columnGetter) {
                N.checkArgNotNull(columnName, "columnName");
                N.checkArgNotNull(columnGetter, "columnGetter");

                columnGetterMap.put(columnName, columnGetter);

                return this;
            }

            /**
             *
             * @param columnName
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(String, ColumnGetter)}
             */
            @Deprecated
            public BiRowMapperBuilder column(final String columnName, final Columns.ColumnGetter<?> columnGetter) {
                return get(columnName, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        defaultColumnGetter = columnGetter;
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[columnName].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final String columnName, final ColumnGetter<?> columnGetter) {
            //        columnGetterMap.put(columnName, columnGetter);
            //
            //        return this;
            //    }

            Columns.ColumnGetter<?>[] initColumnGetter(final List<String> columnLabelList) {
                final int rsColumnCount = columnLabelList.size();
                final Columns.ColumnGetter<?>[] rsColumnGetters = new Columns.ColumnGetter<?>[rsColumnCount + 1];
                rsColumnGetters[0] = defaultColumnGetter;

                int cnt = 0;
                Columns.ColumnGetter<?> columnGetter = null;

                for (int i = 0; i < rsColumnCount; i++) {
                    columnGetter = columnGetterMap.get(columnLabelList.get(i));

                    if (columnGetter != null) {
                        cnt++;
                    }

                    rsColumnGetters[i + 1] = columnGetter == null ? defaultColumnGetter : columnGetter;
                }

                if (cnt < columnGetterMap.size()) {
                    final List<String> tmp = new ArrayList<>(columnGetterMap.keySet());
                    tmp.removeAll(columnLabelList);
                    throw new IllegalArgumentException("ColumnGetters for " + tmp + " are not found in ResultSet columns: " + columnLabelList);
                }

                return rsColumnGetters;
            }

            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
                return to(targetClass, false);
            }

            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
                if (Object[].class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final Object[] a = Array.newInstance(targetClass.getComponentType(), rsColumnCount);

                            for (int i = 0; i < rsColumnCount;) {
                                a[i] = rsColumnGetters[++i].apply(i, rs);
                            }

                            return (T) a;
                        }
                    };
                } else if (List.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isListOrArrayList = targetClass.equals(List.class) || targetClass.equals(ArrayList.class);

                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final List<Object> c = isListOrArrayList ? new ArrayList<>(rsColumnCount) : (List<Object>) N.newInstance(targetClass);

                            for (int i = 0; i < rsColumnCount;) {
                                c.add(rsColumnGetters[++i].apply(i, rs));
                            }

                            return (T) c;
                        }
                    };
                } else if (Map.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isMapOrHashMap = targetClass.equals(Map.class) || targetClass.equals(HashMap.class);
                        private final boolean isLinkedHashMap = targetClass.equals(LinkedHashMap.class);

                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;
                        private String[] columnLabels = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                            }

                            final Map<String, Object> m = isMapOrHashMap ? new HashMap<>(rsColumnCount)
                                    : (isLinkedHashMap ? new LinkedHashMap<>(rsColumnCount) : (Map<String, Object>) N.newInstance(targetClass));

                            for (int i = 0; i < rsColumnCount;) {
                                m.put(columnLabels[i], rsColumnGetters[++i].apply(i, rs));
                            }

                            return (T) m;
                        }
                    };
                } else if (ClassUtil.isEntity(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isDirtyMarker = DirtyMarkerUtil.isDirtyMarker(targetClass);
                        private final EntityInfo entityInfo = ParserUtil.getEntityInfo(targetClass);

                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;
                        private volatile String[] columnLabels = null;
                        private volatile PropInfo[] propInfos;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                                final PropInfo[] propInfos = new PropInfo[rsColumnCount];

                                final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(targetClass);

                                for (int i = 0; i < rsColumnCount; i++) {
                                    propInfos[i] = entityInfo.getPropInfo(columnLabels[i]);

                                    if (propInfos[i] == null) {
                                        String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                        if (N.isNullOrEmpty(fieldName)) {
                                            fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                        }

                                        if (N.notNullOrEmpty(fieldName)) {
                                            propInfos[i] = entityInfo.getPropInfo(fieldName);
                                        }
                                    }

                                    if (propInfos[i] == null) {
                                        if (ignoreNonMatchedColumns) {
                                            columnLabels[i] = null;
                                        } else {
                                            throw new IllegalArgumentException("No property in class: " + ClassUtil.getCanonicalClassName(targetClass)
                                                    + " mapping to column: " + columnLabels[i]);
                                        }
                                    } else {
                                        if (rsColumnGetters[i + 1] == Columns.ColumnGetter.GET_OBJECT) {
                                            rsColumnGetters[i + 1] = Columns.ColumnGetter.get(entityInfo.getPropInfo(columnLabels[i]).dbType);
                                        }
                                    }
                                }

                                this.propInfos = propInfos;
                            }

                            final Object entity = N.newInstance(targetClass);

                            for (int i = 0; i < rsColumnCount;) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                propInfos[i].setPropValue(entity, rsColumnGetters[++i].apply(i, rs));
                            }

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return (T) entity;
                        }
                    };
                } else {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);

                                if (rsColumnGetters[1] == Columns.ColumnGetter.GET_OBJECT) {
                                    rsColumnGetters[1] = Columns.ColumnGetter.get(N.typeOf(targetClass));
                                }

                                this.rsColumnGetters = rsColumnGetters;
                            }

                            if (rsColumnCount != 1 && (rsColumnCount = columnLabelList.size()) != 1) {
                                throw new IllegalArgumentException(
                                        "It's not supported to retrieve value from multiple columns: " + columnLabelList + " for type: " + targetClass);
                            }

                            return (T) rsColumnGetters[1].apply(1, rs);
                        }
                    };
                }
            }
        }
    }

    /**
     * Don't use {@code RowConsumer} in {@link PreparedQuery#forEach(RowConsumer)} or any place where multiple records will be consumed by it, if column labels/count are used in {@link RowConsumer#accept(ResultSet)}.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to consume multiple records when column labels/count are used.
     *
     */
    @FunctionalInterface
    public static interface RowConsumer extends Throwables.Consumer<ResultSet, SQLException> {

        static final RowConsumer DO_NOTHING = rs -> {
        };

        @Override
        void accept(ResultSet rs) throws SQLException;

        default RowConsumer andThen(final Throwables.Consumer<? super ResultSet, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> {
                accept(rs);
                after.accept(rs);
            };
        }
    }

    /**
     * The Interface BiRowConsumer.
     */
    @FunctionalInterface
    public static interface BiRowConsumer extends Throwables.BiConsumer<ResultSet, List<String>, SQLException> {

        static final BiRowConsumer DO_NOTHING = (rs, cls) -> {
        };

        @Override
        void accept(ResultSet rs, List<String> columnLabels) throws SQLException;

        default BiRowConsumer andThen(final Throwables.BiConsumer<? super ResultSet, ? super List<String>, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, cls) -> {
                accept(rs, cls);
                after.accept(rs, cls);
            };
        }
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to test multiple records when column labels/count are used.
     *
     */
    @FunctionalInterface
    public static interface RowFilter extends Throwables.Predicate<ResultSet, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        RowFilter ALWAYS_TRUE = new RowFilter() {
            @Override
            public boolean test(ResultSet rs) throws SQLException {
                return true;
            }
        };

        /** The Constant ALWAYS_FALSE. */
        RowFilter ALWAYS_FALSE = new RowFilter() {
            @Override
            public boolean test(final ResultSet rs) throws SQLException {
                return false;
            }
        };

        @Override
        boolean test(final ResultSet rs) throws SQLException;

        default RowFilter negate() {
            return rs -> !test(rs);
        }

        default RowFilter and(final Throwables.Predicate<? super ResultSet, SQLException> other) {
            N.checkArgNotNull(other);

            return rs -> test(rs) && other.test(rs);
        }
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     *
     */
    @FunctionalInterface
    public static interface BiRowFilter extends Throwables.BiPredicate<ResultSet, List<String>, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        BiRowFilter ALWAYS_TRUE = new BiRowFilter() {
            @Override
            public boolean test(ResultSet rs, List<String> columnLabels) throws SQLException {
                return true;
            }
        };

        /** The Constant ALWAYS_FALSE. */
        BiRowFilter ALWAYS_FALSE = new BiRowFilter() {
            @Override
            public boolean test(ResultSet rs, List<String> columnLabels) throws SQLException {
                return false;
            }
        };

        @Override
        boolean test(ResultSet rs, List<String> columnLabels) throws SQLException;

        default BiRowFilter negate() {
            return (rs, cls) -> !test(rs, cls);
        }

        default BiRowFilter and(final Throwables.BiPredicate<? super ResultSet, ? super List<String>, SQLException> other) {
            N.checkArgNotNull(other);

            return (rs, cls) -> test(rs, cls) && other.test(rs, cls);
        }
    }

    static final ObjectPool<Type<?>, Columns.ColumnGetter<?>> COLUMN_GETTER_POOL = new ObjectPool<>(1024);

    static {
        COLUMN_GETTER_POOL.put(N.typeOf(boolean.class), Columns.ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(Boolean.class), Columns.ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(byte.class), Columns.ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(Byte.class), Columns.ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(short.class), Columns.ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(Short.class), Columns.ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(int.class), Columns.ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(Integer.class), Columns.ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(long.class), Columns.ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(Long.class), Columns.ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(float.class), Columns.ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(Float.class), Columns.ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(double.class), Columns.ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(Double.class), Columns.ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(BigDecimal.class), Columns.ColumnGetter.GET_BIG_DECIMAL);
        COLUMN_GETTER_POOL.put(N.typeOf(String.class), Columns.ColumnGetter.GET_STRING);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Date.class), Columns.ColumnGetter.GET_DATE);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Time.class), Columns.ColumnGetter.GET_TIME);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Timestamp.class), Columns.ColumnGetter.GET_TIMESTAMP);
        COLUMN_GETTER_POOL.put(N.typeOf(Object.class), Columns.ColumnGetter.GET_OBJECT);
    }

    @FunctionalInterface
    public static interface RowExtractor extends Throwables.BiConsumer<ResultSet, Object[], SQLException> {
        @Override
        void accept(final ResultSet rs, final Object[] outputRow) throws SQLException;

        static RowExtractorBuilder builder() {
            return builder(Columns.ColumnGetter.GET_OBJECT);
        }

        static RowExtractorBuilder builder(final Columns.ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        public static class RowExtractorBuilder {
            private final Map<Integer, Columns.ColumnGetter<?>> columnGetterMap;

            RowExtractorBuilder(final Columns.ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, "defaultColumnGetter");

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            public RowExtractorBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_BOOLEAN);
            }

            public RowExtractorBuilder getByte(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_BYTE);
            }

            public RowExtractorBuilder getShort(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_SHORT);
            }

            public RowExtractorBuilder getInt(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_INT);
            }

            public RowExtractorBuilder getLong(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_LONG);
            }

            public RowExtractorBuilder getFloat(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_FLOAT);
            }

            public RowExtractorBuilder getDouble(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_DOUBLE);
            }

            public RowExtractorBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_BIG_DECIMAL);
            }

            public RowExtractorBuilder getString(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_STRING);
            }

            public RowExtractorBuilder getDate(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_DATE);
            }

            public RowExtractorBuilder getTime(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_TIME);
            }

            public RowExtractorBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, Columns.ColumnGetter.GET_TIMESTAMP);
            }

            public RowExtractorBuilder get(final int columnIndex, final Columns.ColumnGetter<?> columnGetter) {
                N.checkArgPositive(columnIndex, "columnIndex");
                N.checkArgNotNull(columnGetter, "columnGetter");

                //        if (columnGetters == null) {
                //            columnGetterMap.put(columnIndex, columnGetter);
                //        } else {
                //            columnGetters[columnIndex] = columnGetter;
                //        }

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            Columns.ColumnGetter<?>[] initColumnGetter(ResultSet rs) throws SQLException {
                return initColumnGetter(rs.getMetaData().getColumnCount());
            }

            Columns.ColumnGetter<?>[] initColumnGetter(final int columnCount) throws SQLException {
                final Columns.ColumnGetter<?>[] rsColumnGetters = new Columns.ColumnGetter<?>[columnCount + 1];
                rsColumnGetters[0] = columnGetterMap.get(0);

                for (int i = 1, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i, rsColumnGetters[0]);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowExtractor} instance.
             *
             * @return
             */
            public RowExtractor build() {
                return new RowExtractor() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                    @Override
                    public void accept(final ResultSet rs, final Object[] outputRow) throws SQLException {
                        Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(outputRow.length);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        for (int i = 0; i < rsColumnCount;) {
                            outputRow[i] = rsColumnGetters[++i].apply(i, rs);
                        }
                    }
                };
            }
        }
    }

    public static interface Handler<T> {
        /**
         *
         * @param targetObject
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        default void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }

        /**
         *
         * @param <R>
         * @param result
         * @param targetObject
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        default void afterInvoke(final Result<?, Exception> result, final T targetObject, final Object[] args,
                Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }
    }

    @SuppressWarnings("rawtypes")
    static final class DaoHandler implements JdbcUtil.Handler<Dao> {

    };

    /**
     * This interface is designed to share/manager SQL queries by Java APIs/methods with static parameter types and return type, while hiding the SQL scripts.
     * It's a gift from nature and created by thoughts.
     *
     * <br />
     * Note: Setting parameters by 'ParametersSetter' or Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present.
     *
     * <br />
     *
     * <li>The SQL operations/methods should be annotated with SQL scripts by {@code @Select/@Insert/@Update/@Delete/@NamedSelect/@NamedInsert/@NamedUpdate/@NamedDelete}.</li>
     *
     * <li>The Order of the parameters in the method should be consistent with parameter order in SQL scripts for parameterized SQL.
     * For named parameterized SQL, the parameters must be binded with names through {@code @Bind}, or {@code Map/Entity} with getter/setter methods.</li>
     *
     * <li>SQL parameters can be set through input method parameters(by multiple parameters or a {@code Collection}, or a {@code Map/Entity} for named sql), or by {@code JdbcUtil.ParametersSetter<PreparedQuery/PreparedCallabeQuery...>}.</li>
     *
     * <li>{@code ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper} can be specified by the last parameter of the method.</li>
     *
     * <li>The return type of the method must be same as the return type of {@code ResultExtractor/BiResultExtractor} if it's specified by the last parameter of the method.</li>
     *
     * <li>The return type of update/delete operations only can int/Integer/long/Long/boolean/Boolean/void. If it's long/Long, {@code PreparedQuery#largeUpdate()} will be called,
     * otherwise, {@code PreparedQuery#update()} will be called.</li>
     *
     * <li>Remember declaring {@code throws SQLException} in the method.</li>
     * <br />
     * <li>Which underline {@code PreparedQuery/PreparedCallableQuery} method to call for SQL methods/operations annotated with {@code @Select/@NamedSelect}:
     * <ul>
     *   <li>If {@code ResultExtractor/BiResultExtractor} is specified by the last parameter of the method, {@code PreparedQuery#query(ResultExtractor/BiResultExtractor)} will be called.</li>
     *   <li>Or else if {@code RowMapper/BiRowMapper} is specified by the last parameter of the method:</li>
     *      <ul>
     *          <li>If the return type of the method is {@code List} and one of below conditions is matched, {@code PreparedQuery#list(RowMapper/BiRowMapper)} will be called:</li>
     *          <ul>
     *              <li>The return type of the method is raw {@code List} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}.</li>
     *          </ul>
     *          <ul>
     *              <li>The last parameter type is raw {@code RowMapper/BiRowMapper} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}.</li>
     *          </ul>
     *          <ul>
     *              <li>The return type of the method is generic {@code List} with parameterized type and The last parameter type is generic {@code RowMapper/BiRowMapper} with parameterized types, but They're not same.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(RowMapper/BiRowMapper)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Optional}, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper).orElse(N.defaultValueOf(returnType))} will be called.</li>
     *      </ul>
     *   <li>Or else:</li>
     *      <ul>
     *          <li>If the return type of the method is {@code DataSet}, {@code PreparedQuery#query()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(Class)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Map} or {@code Entity} class with {@code getter/setter} methods, {@code PreparedQuery#findFirst(Class).orNull()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Optional}:</li>
     *          <ul>
     *              <li>If the value type of {@code Optional} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
     *          </ul>
     *          <ul>
     *              <li>Or else, {@code PreparedQuery#queryForSingleNonNull(Class)} will be called.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code Nullable}:</li>
     *          <ul>
     *              <li>If the value type of {@code Nullable} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
     *          </ul>
     *          <ul>
     *              <li>Or else, {@code PreparedQuery#queryForSingleResult(Class)} will be called.</li>
     *          </ul>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code OptionalBoolean/Byte/.../Double}, {@code PreparedQuery#queryForBoolean/Byte/...Double()} will called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code List}, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"}, {@code PreparedQuery#list(Class)} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else if the return type of the method is {@code boolean/Boolean}, and the method name starts with {@code "exist"/"exists"/"has"}, {@code PreparedQuery#exist()} will be called.</li>
     *      </ul>
     *      <ul>
     *          <li>Or else, {@code PreparedQuery#queryForSingleResult(Class).orElse(N.defaultValueOf(returnType)} will be called.</li>
     *      </ul>
     * </ul>
     *
     * <br />
     * <br />
     *
     * Here is a simple {@code UserDao} sample.
     *
     * <pre>
     * <code>
     * public static interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC> {
     *     &#064NamedInsert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
     *     void insertWithId(User user) throws SQLException;
     *
     *     &#064NamedUpdate("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
     *     int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;
     *
     *     &#064NamedSelect("SELECT first_name, last_name FROM user WHERE id = :id")
     *     User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;
     *
     *     &#064NamedSelect("SELECT id, first_name, last_name, email FROM user")
     *     Stream<User> allUsers() throws SQLException;
     * }
     * </code>
     * </pre>
     *
     * Here is the generate way to work with transaction started by {@code SQLExecutor}.
     *
     * <pre>
     * <code>
     * static final UserDao userDao = Dao.newInstance(UserDao.class, dataSource);
     * ...
     *
     * final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
     *
     * try {
     *      userDao.getById(id);
     *      userDao.update(...);
     *      // more...
     *
     *      tran.commit();
     * } finally {
     *      // The connection will be automatically closed after the transaction is committed or rolled back.
     *      tran.rollbackIfNotCommitted();
     * }
     * </code>
     * </pre>
     *
     * @param <T>
     * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
     * @param <TD>
     * @see {@link com.landawn.abacus.condition.ConditionFactory}
     * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
     * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see Dao
     * @See CrudDao
     * @see SQLExecutor.Mapper
     * @see com.landawn.abacus.annotation.AccessFieldByMethod
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     * 
     * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
     */
    public static interface Dao<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {

        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.TYPE })
        static @interface Config {
            /**
             * Single query method includes: queryForSingleXxx/queryForUniqueResult/findFirst/exists/count...
             * 
             * @return
             */
            boolean addLimitForSingleQuery() default false;
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.FIELD)
        public static @interface SqlField {

            /**
             *
             * @return
             */
            String id() default ""; // default will be field name.
        }

        /**
         * The Interface Select.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Select {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            int fetchSize() default -1;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface Insert.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Insert {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;
        }

        /**
         * The Interface Update.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Update {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface Delete.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Delete {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface NamedSelect.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedSelect {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            int fetchSize() default -1;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface NamedInsert.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedInsert {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;
        }

        /**
         * The Interface NamedUpdate.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedUpdate {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface NamedDelete.
         * 
         * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface NamedDelete {

            /**
             *
             * @return
             * @deprecated using sql="SELECT ... FROM ..." for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            boolean isBatch() default false;

            /**
             *
             * @return
             */
            int batchSize() default 0;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            OP op() default OP.DEFAULT;
        }

        /**
         * The Interface Call.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Call {

            /**
             *
             * @return
             * @deprecated using sql="call update_account(?)" for explicit call.
             */
            @Deprecated
            String value() default "";

            /**
             *
             * @return
             */
            String id() default ""; // id defined SqlMapper

            /**
             *
             * @return
             */
            String sql() default "";

            /**
             *
             * @return
             */
            int fetchSize() default -1;

            /**
             * Unit is seconds.
             *
             * @return
             */
            int queryTimeout() default -1;

            /**
             * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
             * 
             * @return
             */
            boolean isSingleParameter() default false;
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @Repeatable(DaoUtil.OutParameterList.class)
        public @interface OutParameter {
            /**
             *
             * @return
             * @see CallableStatement#registerOutParameter(String, int)
             */
            String name() default "";

            /**
             * Starts from 1.
             * @return
             * @see CallableStatement#registerOutParameter(int, int)
             */
            int position() default -1;

            /**
             *
             * @return
             * @see {@code java.sql.Types}
             */
            int sqlType();
        }

        /**
         * It's only for methods with default implementation in {@code Dao} interfaces. Don't use it for the abstract methods.
         * And the last parameter of the method should be {@code String[]: (param1, param2, ..., String ... sqls)}
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Sqls {
            /**
             *
             * @return
             */
            String[] value() default {};
        }

        /**
         * The Interface Bind.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        public static @interface Bind {

            /**
             *
             * @return
             */
            String value() default "";
        }

        /**
         * Replace the parts defined with format <code>{part}</code> in the sql annotated to the method.
         * For example:
         * <p>
         * <code>
         * 
         *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id")
         *  <br />
         *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id) throws SQLException;
         * 
         * <br />
         * <br />
         * <br />
         * OR with customized '{whatever}':
         * <br />
         * 
         *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id ORDER BY {whatever -> orderBy{{P}}")
         *  <br/>
         *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id, @Define("{whatever -> orderBy{{P}}") String orderBy) throws SQLException;
         * 
         * </code>
         * </p>
         * 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.PARAMETER })
        static @interface Define {
            String value() default "";
        }

        /**
         *
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        public static @interface Transactional {
            Propagation propagation() default Propagation.REQUIRED;
        }

        /** 
         * Unsupported operation.
         * 
         * @deprecated won't be implemented. It should be defined and done in DB server side.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD })
        public static @interface OnDelete {
            OnDeleteAction action() default OnDeleteAction.NO_ACTION;
        }

        /**
         *
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        public static @interface SqlLogEnabled {
            /**
             * 
             * @return
             */
            boolean value() default true;
        }

        /**
         *
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        public static @interface PerfLog {
            /**
             * start to log performance for sql if the execution time >= the specified(or default) execution time in milliseconds.
             *
             * @return
             */
            long minExecutionTimeForSql() default 1000;

            /**
             * start to log performance for Dao operation/method if the execution time >= the specified(or default) execution time in milliseconds.
             * @return
             */
            long minExecutionTimeForOperation() default 3000;
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        @Repeatable(DaoUtil.HandlerList.class)
        public static @interface Handler {
            String qualifier() default "";

            @SuppressWarnings("rawtypes")
            Class<? extends JdbcUtil.Handler<? extends Dao>> type() default DaoHandler.class;

            /**
             * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
             * It's only applied if target of annotation {@code Handler} is {@code Type}, and will be ignored if target is method.
             * 
             * @return
             */
            String[] filter() default { ".*" };
        }

        // TODO: First of all, it's bad idea to implement cache in DAL layer?! and how if not?
        /** 
         * Mostly, it's used for static tables.
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.TYPE })
        static @interface Cache {
            int capacity() default 1000;

            long evictDelay() default 3000; // unit milliseconds.
        }

        /** 
         * 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        static @interface CacheResult {
            /**
             * Flag to identity if {@code CacheResult} is disabled.
             * @return
             */
            boolean disabled() default false;

            /**
             * 
             * @return
             */
            long liveTime() default 30 * 60 * 1000; // unit milliseconds.

            /**
             * 
             * @return
             */
            long idleTime() default 3 * 60 * 1000; // unit milliseconds.

            /**
             * Minimum required size to cache query result if the return type is {@code Collection} or {@code DataSet}.
             * This setting will be ignore if the return types are not {@code Collection} or {@code DataSet}.
             * 
             * @return
             */
            int minSize() default 0; // for list/DataSet.

            /**
             * If the query result won't be cached if it's size is bigger than {@code maxSize} if the return type is {@code Collection} or {@code DataSet}.
             * This setting will be ignore if the return types are not {@code Collection} or {@code DataSet}.
             *  
             * @return
             */
            int maxSize() default Integer.MAX_VALUE; // for list/DataSet.

            /**
             * It's used to copy/clone the result when save result to cache or fetch result from cache.
             * It can be set to {@code "none" and "kryo"}.
             * 
             * @return
             * @see https://github.com/EsotericSoftware/kryo
             */
            String transfer() default "none";

            //    /**
            //     * If it's set to true, the cached result won't be removed by method annotated by {@code RefershCache}.
            //     * 
            //     * @return
            //     */
            //    boolean isStaticData() default false;

            /**
             * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
             * It's only applied if target of annotation {@code RefreshCache} is {@code Type}, and will be ignored if target is method.
             * 
             * @return
             */
            String[] filter() default { "query", "queryFor", "list", "get", "find", "findFirst", "exist", "count" };

            // TODO: second, what will key be like?: {methodName=[args]} -> JSON or kryo? 
            // KeyGenerator keyGenerator() default KeyGenerator.JSON; KeyGenerator.JSON/KRYO;
        }

        /** 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.METHOD, ElementType.TYPE })
        static @interface RefreshCache {

            /**
             * Flag to identity if {@code RefreshCache} is disabled.
             * @return
             */
            boolean disabled() default false;

            //    /**
            //     * 
            //     * @return
            //     */
            //    boolean forceRefreshStaticData() default false;

            /**
             * Those conditions(by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
             * It's only applied if target of annotation {@code RefreshCache} is {@code Type}, and will be ignored if target is method.
             * 
             * @return
             */
            String[] filter() default { "save", "insert", "update", "delete", "upsert", "execute" };
        }

        /** 
         */
        @Retention(RetentionPolicy.RUNTIME)
        @Target(value = { ElementType.TYPE })
        static @interface AllowJoiningByNullOrDefaultValue {
            boolean value() default false;
        }

        /**
         * 
         * @see The operations in {@code AbstractPreparedQuery}
         *
         */
        public static enum OP {
            exists,
            get,
            findFirst,
            list,

            /**
             * @deprecated generally it's unnecessary to specify the {@code "op = OP.stream"} in {@code Select/NamedSelect}.
             */
            query,

            /**
             * 
             * @deprecated generally it's unnecessary to specify the {@code "op = OP.stream"} in {@code Select/NamedSelect}.
             */
            stream,

            /**
             * 
             */
            queryForSingle,

            /**
             * 
             */
            queryForUnique,

            /**
             * 
             */
            update,

            /**
             * 
             */
            largeUpdate,

            /* batchUpdate,*/

            /**
             * 
             */
            DEFAULT;
        }

        /**
         *
         * @return
         */
        @NonDBOperation
        Class<T> targetEntityClass();

        /**
         *
         * @return
         */
        @NonDBOperation
        javax.sql.DataSource dataSource();

        // SQLExecutor sqlExecutor();

        @NonDBOperation
        SQLMapper sqlMapper();

        @NonDBOperation
        Executor executor();

        @NonDBOperation
        AsyncExecutor asyncExecutor();

        void cacheSql(String key, String sql);

        void cacheSqls(String key, Collection<String> sqls);

        String getCachedSql(String key);

        ImmutableList<String> getCachedSqls(String key);

        //    /**
        //     *
        //     * @param isolationLevel
        //     * @return
        //     * @throws UncheckedSQLException
        //     */
        //     @NonDBOperation
        //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel) throws UncheckedSQLException {
        //        return beginTransaction(isolationLevel, false);
        //    }
        //
        //    /**
        //     * The connection opened in the transaction will be automatically closed after the transaction is committed or rolled back.
        //     * DON'T close it again by calling the close method.
        //     * <br />
        //     * <br />
        //     * The transaction will be shared cross the instances of {@code SQLExecutor/Dao} by the methods called in the same thread with same {@code DataSource}.
        //     *
        //     * <br />
        //     * <br />
        //     *
        //     * The general programming way with SQLExecutor/Dao is to execute sql scripts(generated by SQLBuilder) with array/list/map/entity by calling (batch)insert/update/delete/query/... methods.
        //     * If Transaction is required, it can be started:
        //     *
        //     * <pre>
        //     * <code>
        //     *   final SQLTransaction tran = someDao.beginTransaction(IsolationLevel.READ_COMMITTED);
        //     *   try {
        //     *       // sqlExecutor.insert(...);
        //     *       // sqlExecutor.update(...);
        //     *       // sqlExecutor.query(...);
        //     *
        //     *       tran.commit();
        //     *   } finally {
        //     *       // The connection will be automatically closed after the transaction is committed or rolled back.
        //     *       tran.rollbackIfNotCommitted();
        //     *   }
        //     * </code>
        //     * </pre>
        //     *
        //     * @param isolationLevel
        //     * @param forUpdateOnly
        //     * @return
        //     * @throws UncheckedSQLException
        //     * @see {@link SQLExecutor#beginTransaction(IsolationLevel, boolean)}
        //     */
        //     @NonDBOperation
        //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel, final boolean forUpdateOnly) throws UncheckedSQLException {
        //        N.checkArgNotNull(isolationLevel, "isolationLevel");
        //
        //        final javax.sql.DataSource ds = dataSource();
        //        SQLTransaction tran = SQLTransaction.getTransaction(ds);
        //
        //        if (tran == null) {
        //            Connection conn = null;
        //            boolean noException = false;
        //
        //            try {
        //                conn = getConnection(ds);
        //                tran = new SQLTransaction(ds, conn, isolationLevel, true, true);
        //                tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
        //
        //                noException = true;
        //            } catch (SQLException e) {
        //                throw new UncheckedSQLException(e);
        //            } finally {
        //                if (noException == false) {
        //                    JdbcUtil.releaseConnection(conn, ds);
        //                }
        //            }
        //
        //            logger.info("Create a new SQLTransaction(id={})", tran.id());
        //            SQLTransaction.putTransaction(ds, tran);
        //        } else {
        //            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
        //            tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
        //        }
        //
        //        return tran;
        //    }

        /**
         *
         * @param query
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query);
        }

        /**
         *
         * @param query
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, generateKeys);
        }

        /**
         *
         * @param query
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
        }

        /**
         *
         * @param query
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), query, returnColumnNames);
        }

        /**
         *
         * @param sql
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedQuery prepareQuery(final String sql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
                throws SQLException {
            return JdbcUtil.prepareQuery(dataSource(), sql, stmtCreator);
        }

        /**
         *
         * @param namedQuery
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
        }

        /**
         *
         * @param namedQuery
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnNames
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
        }

        /**
         *
         * @param namedQuery
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final String namedQuery,
                final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, stmtCreator);
        }

        /**
         *
         * @param namedSql the named query
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedSql) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSql);
        }

        /**
         *
         * @param namedSql the named query
         * @param generateKeys
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final boolean generateKeys) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generateKeys);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnIndexes
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
        }

        /**
         *
         * @param namedQuery
         * @param returnColumnNames
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
        }

        /**
         *
         * @param namedSql the named query
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default NamedQuery prepareNamedQuery(final ParsedSql namedSql,
                final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, stmtCreator);
        }

        /**
         *
         * @param query
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedCallableQuery prepareCallableQuery(final String query) throws SQLException {
            return JdbcUtil.prepareCallableQuery(dataSource(), query);
        }

        /**
         *
         * @param sql
         * @param stmtCreator
         * @return
         * @throws SQLException
         */
        @NonDBOperation
        default PreparedCallableQuery prepareCallableQuery(final String sql,
                final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
            return JdbcUtil.prepareCallableQuery(dataSource(), sql, stmtCreator);
        }

        /**
         *
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final T entityToSave) throws SQLException;

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws SQLException the SQL exception
         */
        void save(final String namedInsertSQL, final T entityToSave) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        default void batchSave(final Collection<? extends T> entitiesToSave) throws SQLException {
            batchSave(entitiesToSave, DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws SQLException {
            batchSave(entitiesToSave, propNamesToSave, DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws SQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Beta
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws SQLException {
            batchSave(namedInsertSQL, entitiesToSave, DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Beta
        void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

        /**
         *
         * @param cond
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        boolean exists(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int count(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Optional<T> findFirst(final Condition cond) throws SQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Condition cond, final RowMapper<R> rowMapper) throws SQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Condition cond, final BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper) throws SQLException;

        /**
         * Query for boolean.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for char.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for byte.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for short.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for int.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for long.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for float.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for double.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for string.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for date.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for time.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for timestamp.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for single non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        <V> Optional<V> queryForSingleNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws SQLException the SQL exception
         */
        <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, SQLException;

        /**
         * Query for unique non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        <V> Optional<V> queryForUniqueNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        DataSet query(final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        DataSet query(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Condition cond, final ResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Collection<String> selectPropNames, final Condition cond, final ResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Condition cond, final BiResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param resultExtrator
         * @return
         * @throws SQLException the SQL exception
         */
        <R> R query(final Collection<String> selectPropNames, final Condition cond, final BiResultExtractor<R> resultExtrator) throws SQLException;

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        List<T> list(final Condition cond) throws SQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        List<T> list(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper) throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper)
                throws SQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper)
                throws SQLException;

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws SQLException {
            final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? ColumnOne.<R> getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

            return list(singleSelectPropName, cond, rowMapper);
        }

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @param rowMapper
         * @return
         * @throws SQLException the SQL exception
         */
        default <R> List<R> list(final String singleSelectPropName, final Condition cond, final RowMapper<R> rowMapper) throws SQLException {
            return list(N.asList(singleSelectPropName), cond, rowMapper);
        }

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @return
         */
        ExceptionalStream<T, SQLException> stream(final Condition cond);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final RowMapper<R> rowMapper);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final BiRowMapper<R> rowMapper);

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper);

        /**
         * lazy-execution, lazy-fetch.
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @return
         */
        ExceptionalStream<T, SQLException> stream(final Collection<String> selectPropNames, final Condition cond);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, RowFilter rowFilter,
                final RowMapper<R> rowMapper);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         */
        <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final BiRowFilter rowFilter,
                final BiRowMapper<R> rowMapper);

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         * @param singleSelectPropName
         * @param cond
         * @return
         */
        default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond) {
            final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? ColumnOne.<R> getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

            return stream(singleSelectPropName, cond, rowMapper);
        }

        // Will it cause confusion if it's called in transaction?
        /**
         * lazy-execution, lazy-fetch.
         *
         * @param singleSelectPropName
         * @param cond
         * @param rowMapper
         * @return
         */
        default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond, final RowMapper<R> rowMapper) {
            return stream(N.asList(singleSelectPropName), cond, rowMapper);
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default int update(final String propName, final Object propValue, final Condition cond) throws SQLException {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, cond);
        }

        /**
         * Update all the records found by specified {@code cond} with all the properties from specified {@code updateProps}.
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final Map<String, Object> updateProps, final Condition cond) throws SQLException;

        /**
         * Update all the records found by specified {@code cond} with all the properties from specified {@code entity}.
         * 
         * @param entity
         * @param cond
         * @return
         * @throws SQLException
         */
        default int update(final T entity, final Condition cond) throws SQLException {
            return update(Maps.entity2Map(entity), cond);
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws SQLException the SQL exception
         */
        default T upsert(final T entity, final Condition cond) throws SQLException {
            N.checkArgNotNull(cond, "cond");

            final T dbEntity = findFirst(cond).orNull();

            if (dbEntity == null) {
                save(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity);
                update(Maps.entity2Map(dbEntity), cond);
                return dbEntity;
            }
        }

        /**
         *
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        int delete(final Condition cond) throws SQLException;

        /**
         * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
         *
         * @param <R>
         * @param sqlAction
         * @return
         */
        @Beta
        @NonDBOperation
        default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<TD, R, SQLException> sqlAction) {
            return asyncCall(sqlAction, executor());
        }

        /**
         * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
         *
         *
         * @param <R>
         * @param sqlAction
         * @param executor
         * @return
         */
        @Beta
        @NonDBOperation
        default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<TD, R, SQLException> sqlAction, final Executor executor) {
            N.checkArgNotNull(sqlAction, "func");
            N.checkArgNotNull(executor, "executor");

            final TD tdao = (TD) this;

            return ContinuableFuture.call(() -> sqlAction.apply(tdao), executor);
        }

        /**
         * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
         *
         *
         * @param sqlAction
         * @return
         */
        @Beta
        @NonDBOperation
        default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<TD, SQLException> sqlAction) {
            return asyncRun(sqlAction, executor());
        }

        /**
         * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
         *
         *
         * @param sqlAction
         * @param executor
         * @return
         */
        @Beta
        @NonDBOperation
        default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<TD, SQLException> sqlAction, final Executor executor) {
            N.checkArgNotNull(sqlAction, "action");
            N.checkArgNotNull(executor, "executor");

            final TD tdao = (TD) this;

            return ContinuableFuture.run(() -> sqlAction.accept(tdao), executor);
        }
    }

    /**
     * The Interface CrudDao.
     *
     * @param <T>
     * @param <ID> use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T}.
     * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
     * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see Dao
     * @see SQLExecutor.Mapper
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     */
    public static interface CrudDao<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends Dao<T, SB, TD> {

        default BiRowMapper<ID> idExtractor() {
            return null;
        }

        /**
         *
         * @param entityToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final T entityToInsert) throws SQLException;

        /**
         *
         * @param entityToInsert
         * @param propNamesToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        ID insert(final String namedInsertSQL, final T entityToInsert) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException {
            return batchInsert(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @return
         * @throws SQLException the SQL exception
         */
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws SQLException {
            return batchInsert(entities, propNamesToInsert, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize) throws SQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws SQLException {
            return batchInsert(namedInsertSQL, entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id) throws SQLException {
            return Optional.ofNullable(gett(id));
        }

        /**
         *
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames));
        }

        /**
         * Gets the t.
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        T gett(final ID id) throws SQLException;

        /**
         * Gets the t.
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         *
         * @return
         * @throws SQLException the SQL exception
         */
        T gett(final ID id, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         *
         * @param ids
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException, SQLException {
            return batchGet(ids, (Collection<String>) null);
        }

        /**
        *
        * @param ids
        * @param batchSize
        * @return
        * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
        * @throws SQLException the SQL exception
        */
        default List<T> batchGet(final Collection<? extends ID> ids, final int batchSize) throws DuplicatedResultException, SQLException {
            return batchGet(ids, (Collection<String>) null, batchSize);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
            return batchGet(ids, selectPropNames, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws SQLException the SQL exception
         */
        List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
                throws DuplicatedResultException, SQLException;

        /**
         *
         * @param id
         * @return true, if successful
         * @throws SQLException the SQL exception
         */
        boolean exists(final ID id) throws SQLException;

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final T entityToUpdate) throws SQLException;

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws SQLException;

        /**
        *
        * @param propName
        * @param propValue
        * @param id
        * @return
        * @throws SQLException the SQL exception
        */
        default int update(final String propName, final Object propValue, final ID id) throws SQLException {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, id);
        }

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        int update(final Map<String, Object> updateProps, final ID id) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchUpdate(final Collection<? extends T> entities) throws SQLException {
            return batchUpdate(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws SQLException {
            return batchUpdate(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws SQLException;

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws SQLException the SQL exception
         */
        @Override
        default T upsert(final T entity, final Condition cond) throws SQLException {
            N.checkArgNotNull(cond, "cond");

            final T dbEntity = findFirst(cond).orNull();

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                final Class<?> cls = entity.getClass();
                @SuppressWarnings("deprecation")
                final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls);
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         * @throws SQLException the SQL exception
         */
        default T upsert(final T entity) throws SQLException {
            final Class<?> cls = entity.getClass();
            @SuppressWarnings("deprecation")
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
            final T dbEntity = gett(JdbcUtil.extractId(entity, cls, idPropNameList, entityInfo));

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         * 
         * @param entities
         * @return
         * @throws SQLException
         */
        default List<T> batchUpsert(final Collection<? extends T> entities) throws SQLException {
            return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * 
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException
         */
        default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws SQLException {
            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(entities)) {
                return new ArrayList<>();
            }

            final T first = N.firstOrNullIfEmpty(entities);
            final Class<?> cls = first.getClass();
            @SuppressWarnings("deprecation")
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            final Function<T, ID> idExtractorFunc = createIdExtractor(cls, idPropNameList, entityInfo);
            final List<ID> ids = N.map(entities, idExtractorFunc);

            final List<T> dbEntities = batchGet(ids, batchSize);

            final Map<ID, T> dbIdEntityMap = StreamEx.of(dbEntities).toMap(idExtractorFunc, Fn.identity(), Fn.ignoringMerger());
            final Map<Boolean, List<T>> map = StreamEx.of(entities).groupTo(it -> dbIdEntityMap.containsKey(idExtractorFunc.apply(it)), Fn.identity());
            final List<T> entitiesToUpdate = map.get(true);
            final List<T> entitiesToInsert = map.get(false);

            if (N.notNullOrEmpty(entitiesToInsert)) {
                batchInsert(entitiesToInsert, batchSize);
            }

            if (N.notNullOrEmpty(entitiesToUpdate)) {
                final Set<String> idPropNameSet = N.newHashSet(idPropNameList);

                final List<T> dbEntitiesToUpdate = StreamEx.of(entitiesToUpdate)
                        .map(it -> N.merge(it, dbIdEntityMap.get(idExtractorFunc.apply(it)), false, idPropNameSet))
                        .toList();

                batchUpdate(dbEntitiesToUpdate);

                entitiesToInsert.addAll(dbEntitiesToUpdate);
            }

            return entitiesToInsert;
        }

        /**
         *
         * @param entity
         * @return true, if successful
         * @throws SQLException
         */
        default boolean refresh(final T entity) throws SQLException {
            final Class<?> cls = entity.getClass();
            final Collection<String> propNamesToRefresh = DirtyMarkerUtil.isDirtyMarker(cls) ? DirtyMarkerUtil.signedPropNames((DirtyMarker) entity)
                    : SQLBuilder.getSelectPropNames(cls, false, null);

            return refresh(entity, propNamesToRefresh);
        }

        /**
         *
         * @param entity
         * @param propNamesToRefresh
         * @return {@code false} if no record found by the ids in the specified {@code entity}.
         * @throws SQLException
         */
        @SuppressWarnings("deprecation")
        default boolean refresh(final T entity, Collection<String> propNamesToRefresh) throws SQLException {
            N.checkArgNotNullOrEmpty(propNamesToRefresh, "propNamesToRefresh");

            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            final ID id = extractId(entity, cls, idPropNameList, entityInfo);
            final Collection<String> selectPropNames = getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

            final T dbEntity = gett(id, selectPropNames);

            if (dbEntity == null) {
                return false;
            } else {
                N.merge(dbEntity, entity, propNamesToRefresh);

                if (DirtyMarkerUtil.isDirtyMarker(cls)) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToRefresh, false);
                }

                return true;
            }
        }

        /**
         *
         * @param entities
         * @return the count of refreshed entities.
         * @throws SQLException
         */
        default int batchRefresh(final Collection<? extends T> entities) throws SQLException {
            return batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * 
         * @param entities
         * @param batchSize
         * @return the count of refreshed entities.
         * @throws SQLException
         */
        default int batchRefresh(final Collection<? extends T> entities, final int batchSize) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final T first = N.firstOrNullIfEmpty(entities);
            final Class<?> cls = first.getClass();
            final Collection<String> propNamesToRefresh = DirtyMarkerUtil.isDirtyMarker(cls) ? DirtyMarkerUtil.signedPropNames((DirtyMarker) first)
                    : SQLBuilder.getSelectPropNames(cls, false, null);

            return batchRefresh(entities, propNamesToRefresh, batchSize);
        }

        /**
         * 
         * @param entities
         * @param propNamesToRefresh
         * @return the count of refreshed entities.
         * @throws SQLException
         */
        default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh) throws SQLException {
            return batchRefresh(entities, propNamesToRefresh, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToRefresh
         * @param batchSize
         * @return the count of refreshed entities.
         * @throws SQLException
         */
        @SuppressWarnings("deprecation")
        default int batchRefresh(final Collection<? extends T> entities, Collection<String> propNamesToRefresh, final int batchSize) throws SQLException {
            N.checkArgNotNullOrEmpty(propNamesToRefresh, "propNamesToRefresh");
            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final T first = N.firstOrNullIfEmpty(entities);
            final Class<?> cls = first.getClass();
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            final Function<T, ID> idExtractorFunc = createIdExtractor(cls, idPropNameList, entityInfo);
            final Map<ID, List<T>> idEntityMap = StreamEx.of(entities).groupTo(idExtractorFunc, Fn.identity());
            final Collection<String> selectPropNames = getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

            final List<T> dbEntities = batchGet(idEntityMap.keySet(), selectPropNames, batchSize);

            if (N.isNullOrEmpty(dbEntities)) {
                return 0;
            } else {
                final boolean isDirtyMarker = DirtyMarkerUtil.isDirtyMarker(cls);

                return dbEntities.stream().mapToInt(dbEntity -> {
                    final ID id = idExtractorFunc.apply(dbEntity);
                    final List<T> tmp = idEntityMap.get(id);

                    if (N.notNullOrEmpty(tmp)) {
                        for (T entity : tmp) {
                            N.merge(dbEntity, entity, propNamesToRefresh);

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToRefresh, false);
                            }
                        }
                    }

                    return N.size(tmp);
                }).sum();
            }
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws SQLException the SQL exception
         */
        int deleteById(final ID id) throws SQLException;

        /**
         *
         * @param entity
         * @return
         * @throws SQLException the SQL exception
         */
        int delete(final T entity) throws SQLException;
        //
        //    /**
        //     *
        //     * @param entity
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    @Beta
        //    int delete(final T entity, final OnDeleteAction onDeleteAction) throws SQLException;

        /**
         *
         * @param entities
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchDelete(final Collection<? extends T> entities) throws SQLException {
            return batchDelete(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchDelete(final Collection<? extends T> entities, final int batchSize) throws SQLException;

        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    @Beta
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction) throws SQLException {
        //        return batchDelete(entities, onDeleteAction, DEFAULT_BATCH_SIZE);
        //    }
        //
        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @param batchSize
        //     * @return
        //     * @throws SQLException the SQL exception
        //     */
        //    @Beta
        //    int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize) throws SQLException;

        /**
         *
         * @param ids
         * @return
         * @throws SQLException the SQL exception
         */
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws SQLException {
            return batchDeleteByIds(ids, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws SQLException the SQL exception
         */
        int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws SQLException;
    }

    /**
     *  
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface CrudDaoL<T, SB extends SQLBuilder, TD extends CrudDaoL<T, SB, TD>> extends CrudDao<T, Long, SB, TD> {

        default Optional<T> get(final long id) throws SQLException {
            return get(Long.valueOf(id));
        }

        default Optional<T> get(final long id, final Collection<String> selectPropNames) throws SQLException {
            return get(Long.valueOf(id), selectPropNames);
        }

        default T gett(final long id) throws SQLException {
            return gett(Long.valueOf(id));
        }

        default T gett(final long id, final Collection<String> selectPropNames) throws SQLException {
            return gett(Long.valueOf(id), selectPropNames);
        }

        default boolean exists(final long id) throws SQLException {
            return exists(Long.valueOf(id));
        }

        default int update(final String propName, final Object propValue, final long id) throws SQLException {
            return update(propName, propValue, Long.valueOf(id));
        }

        default int update(final Map<String, Object> updateProps, final long id) throws SQLException {
            return update(updateProps, Long.valueOf(id));
        }

        default int deleteById(final long id) throws SQLException {
            return deleteById(Long.valueOf(id));
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface NoUpdateDao<T, SB extends SQLBuilder, TD extends NoUpdateDao<T, SB, TD>> extends Dao<T, SB, TD> {

        /**
         *
         * @param propName
         * @param propValue
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default int update(final String propName, final Object propValue, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entity, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface ReadOnlyDao<T, SB extends SQLBuilder, TD extends ReadOnlyDao<T, SB, TD>> extends NoUpdateDao<T, SB, TD> {

        /**
         *
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface NoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends NoUpdateCrudDao<T, ID, SB, TD>>
            extends NoUpdateDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param id
         * @return
         * @throws SQLException the SQL exception
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * 
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default List<T> batchUpsert(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * 
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation 
         */
        @Override
        @Deprecated
        default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteById(final ID id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final T entity) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        //    /**
        //     *
        //     * @param entity
        //     * @param onDeleteAction
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws SQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int delete(final T entity, final OnDeleteAction onDeleteAction) throws UnsupportedOperationException, SQLException {
        //        throw new UnsupportedOperationException();
        //    }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws SQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction)
        //            throws UnsupportedOperationException, SQLException {
        //        throw new UnsupportedOperationException();
        //    }
        //
        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction
        //     * @param batchSize
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws SQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize)
        //            throws UnsupportedOperationException, SQLException {
        //        throw new UnsupportedOperationException();
        //    }

        /**
         *
         * @param ids
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface ReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends ReadOnlyCrudDao<T, ID, SB, TD>>
            extends ReadOnlyDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToInsert) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToInsert
         * @param propNamesToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
                throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    @Beta
    public static interface NoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends NoUpdateCrudDaoL<T, SB, TD>>
            extends NoUpdateCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

        /**
         * 
         * @param propName
         * @param propValue
         * @param id
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final String propName, final Object propValue, final long id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * 
         * @param updateProps
         * @param id
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final long id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * @param id
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteById(final long id) throws UnsupportedOperationException, SQLException {
            throw new UnsupportedOperationException();
        }
    }

    @Beta
    public static interface ReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends ReadOnlyCrudDaoL<T, SB, TD>>
            extends ReadOnlyCrudDao<T, Long, SB, TD>, NoUpdateCrudDaoL<T, SB, TD> {
    }

    public static interface JoinEntityHelper<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {

        /**
         *
         * @return
         * @deprecated internal only
         */
        @Deprecated
        @Internal
        @NonDBOperation
        Class<T> targetEntityClass();

        /**
         *
         * @return
         * @deprecated internal only
         */
        @Deprecated
        @Internal
        @NonDBOperation
        Class<TD> targetDaoInterface();

        /**
         *
         * @return
         * @deprecated internal only
         */
        @Deprecated
        @Internal
        @NonDBOperation
        Executor executor();

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> findFirst(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
            final Optional<T> result = getDao(this).findFirst(selectPropNames, cond);

            if (result.isPresent()) {
                loadJoinEntities(result.get(), joinEntitiesToLoad);
            }

            return result;
        }

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> findFirst(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad, final Condition cond)
                throws SQLException {
            final Optional<T> result = getDao(this).findFirst(selectPropNames, cond);

            if (result.isPresent()) {
                for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result.get(), joinEntityClass);
                }
            }

            return result;
        }

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        default Optional<T> findFirst(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond)
                throws SQLException {
            final Optional<T> result = getDao(this).findFirst(selectPropNames, cond);

            if (includeAllJoinEntities && result.isPresent()) {
                loadAllJoinEntities(result.get());
            }

            return result;
        }

        /** 
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
            final List<T> result = getDao(this).list(selectPropNames, cond);

            if (N.notNullOrEmpty(result)) {
                if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                    StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> loadJoinEntities(it, joinEntitiesToLoad));
                } else {
                    loadJoinEntities(result, joinEntitiesToLoad);
                }
            }

            return result;
        }

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> list(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad, final Condition cond)
                throws SQLException {
            final List<T> result = getDao(this).list(selectPropNames, cond);

            if (N.notNullOrEmpty(result) && N.notNullOrEmpty(joinEntitiesToLoad)) {
                if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                    StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> {
                        for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                            loadJoinEntities(it, joinEntityClass);
                        }
                    });
                } else {
                    for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(result, joinEntityClass);
                    }
                }
            }

            return result;
        }

        /** 
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @param cond
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> list(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws SQLException {
            final List<T> result = getDao(this).list(selectPropNames, cond);

            if (N.notNullOrEmpty(result)) {
                if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                    StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> loadAllJoinEntities(it));
                } else {
                    loadAllJoinEntities(result);
                }
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntities(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntities(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final String joinEntityPropName) throws SQLException {
            loadJoinEntities(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
            loadJoinEntities(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntities(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                loadJoinEntities(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entity
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(T entity) throws SQLException {
            loadJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadAllJoinEntities(entity, executor());
            } else {
                loadAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final T entity, final Executor executor) throws SQLException {
            loadJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadAllJoinEntities(entities, executor());
            } else {
                loadAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntitiesIfNull(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            loadJoinEntitiesIfNull(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            if (joinEntityPropNames.size() == 1) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames.get(0), selectPropNames);
            } else {
                for (String joinEntityPropName : joinEntityPropNames) {
                    loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
                }
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws SQLException {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * ?
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException {
            final Class<?> cls = entity.getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);

            if (propInfo.getPropValue(entity) == null) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
            loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
                throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> cls = N.firstOrNullIfEmpty(entities).getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);
            final List<T> newEntities = N.filter(entities, entity -> propInfo.getPropValue(entity) == null);

            loadJoinEntities(newEntities, joinEntityPropName, selectPropNames);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.complete(futures);
        }

        /**
         *
         * @param entity
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(T entity) throws SQLException {
            loadJoinEntitiesIfNull(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, executor());
            } else {
                loadJoinEntitiesIfNull(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws SQLException {
            loadJoinEntitiesIfNull(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, executor());
            } else {
                loadJoinEntitiesIfNull(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws SQLException the SQL exception
         */
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entity, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entities, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException;

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException;

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entity, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
            if (inParallel) {
                return deleteJoinEntities(entity, joinEntityPropNames, executor());
            } else {
                return deleteJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            return JdbcUtil.completeSum(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entities, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException {
            if (inParallel) {
                return deleteJoinEntities(entities, joinEntityPropNames, executor());
            } else {
                return deleteJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, SQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            return JdbcUtil.completeSum(futures);
        }

        /**
         *
         * @param entity
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(T entity) throws SQLException {
            return deleteJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entity, executor());
            } else {
                return deleteAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException {
            return deleteJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entities, executor());
            } else {
                return deleteAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws SQLException the SQL exception
         */
        default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }
    }

    public static interface CrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws SQLException {
            return Optional.ofNullable(gett(id, joinEntitiesToLoad));
        }

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws SQLException {
            return Optional.ofNullable(gett(id, includeAllJoinEntities));
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        @Beta
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        @Beta
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException
         */
        @Beta
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities) throws SQLException {
            return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws SQLException {
            final T result = getCrudDao(this).gett(id);

            if (result != null) {
                loadJoinEntities(result, joinEntitiesToLoad);
            }

            return result;
        }

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException the SQL exception
         */
        @Beta
        default T gett(final ID id, final boolean includeAllJoinEntities) throws SQLException {
            final T result = getCrudDao(this).gett(id);

            if (result != null && includeAllJoinEntities) {
                loadAllJoinEntities(result);
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        @Beta
        default T gett(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws SQLException {
            final T result = getCrudDao(this).gett(id, selectPropNames);

            if (result != null) {
                loadJoinEntities(result, joinEntitiesToLoad);
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws SQLException
         */
        @Beta
        default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad) throws SQLException {
            final T result = getCrudDao(this).gett(id, selectPropNames);

            if (result != null) {
                for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result, joinEntityClass);
                }
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @return
         * @throws SQLException
         */
        @Beta
        default T gett(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities) throws SQLException {
            final T result = getCrudDao(this).gett(id, selectPropNames);

            if (result != null && includeAllJoinEntities) {
                loadAllJoinEntities(result);
            }

            return result;
        }

        /**
         *
         *
         * @param ids
         * @param joinEntitiesToLoad
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntitiesToLoad) throws SQLException {
            return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
        }

        /**
         *
         *
         * @param ids
         * @param includeAllJoinEntities
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities) throws SQLException {
            return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
                throws SQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames,
                final Collection<? extends Class<?>> joinEntitiesToLoad) throws SQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
                throws SQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @param joinEntitiesToLoad
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
                final Class<?> joinEntitiesToLoad) throws SQLException {
            final List<T> result = getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

            if (N.notNullOrEmpty(result)) {
                if (result.size() > batchSize) {
                    StreamEx.of(result).splitToList(batchSize).forEach(it -> loadJoinEntities(it, joinEntitiesToLoad));
                } else {
                    loadJoinEntities(result, joinEntitiesToLoad);
                }
            }

            return result;
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @param joinEntitiesToLoad
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
                final Collection<? extends Class<?>> joinEntitiesToLoad) throws SQLException {
            final List<T> result = getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

            if (N.notNullOrEmpty(result) && N.notNullOrEmpty(joinEntitiesToLoad)) {
                if (result.size() > batchSize) {
                    StreamEx.of(result).splitToList(batchSize).forEach(it -> {
                        for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                            loadJoinEntities(it, joinEntityClass);
                        }
                    });
                } else {
                    for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(result, joinEntityClass);
                    }
                }
            }

            return result;
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @param includeAllJoinEntities
         * @return 
         * @throws SQLException the SQL exception
         */
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
                final boolean includeAllJoinEntities) throws SQLException {
            final List<T> result = getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

            if (includeAllJoinEntities && N.notNullOrEmpty(result)) {
                if (result.size() > batchSize) {
                    StreamEx.of(result).splitToList(batchSize).forEach(it -> loadAllJoinEntities(it));
                } else {
                    loadAllJoinEntities(result);
                }
            }

            return result;
        }
    }

    public static interface ReadOnlyJoinEntityHelper<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

        /**
         * 
         * @param entity
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(T entity) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws SQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }
    }

    public static interface ReadOnlyCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>>
            extends ReadOnlyJoinEntityHelper<T, SB, TD>, CrudJoinEntityHelper<T, ID, SB, TD> {

    }

    public static interface UncheckedDao<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> extends Dao<T, SB, TD> {
        /**
         *
         * @param entityToSave
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        void save(final T entityToSave) throws UncheckedSQLException;

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        void save(final T entityToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        void save(final String namedInsertSQL, final T entityToSave) throws UncheckedSQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave) throws UncheckedSQLException {
            batchSave(entitiesToSave, DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Override
        void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException {
            batchSave(entitiesToSave, propNamesToSave, DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Override
        void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
                throws UncheckedSQLException;

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Override
        @Beta
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UncheckedSQLException {
            batchSave(namedInsertSQL, entitiesToSave, DEFAULT_BATCH_SIZE);
        }

        /**
         * Insert the specified entities to database by batch.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @see CrudDao#batchInsert(Collection)
         */
        @Override
        @Beta
        void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @return true, if successful
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        boolean exists(final Condition cond) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int count(final Condition cond) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        Optional<T> findFirst(final Condition cond) throws UncheckedSQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> Optional<R> findFirst(final Condition cond, final RowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> Optional<R> findFirst(final Condition cond, final BiRowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         * Query for boolean.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for char.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for byte.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for short.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for int.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for long.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for float.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for double.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for string.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for date.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for time.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for timestamp.
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

        /**
         * Query for single result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws UncheckedSQLException;

        /**
         * Query for single non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <V> Optional<V> queryForSingleNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws UncheckedSQLException;

        /**
         * Query for unique result.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws DuplicatedResultException if more than one record found.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, UncheckedSQLException;

        /**
         * Query for unique non null.
         *
         * @param <V> the value type
         * @param targetValueClass
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <V> Optional<V> queryForUniqueNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
                throws DuplicatedResultException, UncheckedSQLException;

        /**
         *
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        DataSet query(final Condition cond) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        DataSet query(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @param resultExtrator
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> R query(final Condition cond, final ResultExtractor<R> resultExtrator) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param resultExtrator
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> R query(final Collection<String> selectPropNames, final Condition cond, final ResultExtractor<R> resultExtrator) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @param resultExtrator
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> R query(final Condition cond, final BiResultExtractor<R> resultExtrator) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param resultExtrator
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> R query(final Collection<String> selectPropNames, final Condition cond, final BiResultExtractor<R> resultExtrator) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        List<T> list(final Condition cond) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Condition cond, final RowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Condition cond, final BiRowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        List<T> list(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper) throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper)
                throws UncheckedSQLException;

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param cond
         * @param rowFilter
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper)
                throws UncheckedSQLException;

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException {
            final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
            final RowMapper<R> rowMapper = propInfo == null ? ColumnOne.<R> getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

            return list(singleSelectPropName, cond, rowMapper);
        }

        /**
         *
         * @param singleSelectPropName
         * @param cond
         * @param rowMapper
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default <R> List<R> list(final String singleSelectPropName, final Condition cond, final RowMapper<R> rowMapper) throws UncheckedSQLException {
            return list(N.asList(singleSelectPropName), cond, rowMapper);
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int update(final String propName, final Object propValue, final Condition cond) throws UncheckedSQLException {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, cond);
        }

        /**
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int update(final Map<String, Object> updateProps, final Condition cond) throws UncheckedSQLException;

        /**
         * Update all the records found by specified {@code cond} with all the properties from specified {@code entity}.
         * 
         * @param entity
         * @param cond
         * @return
         * @throws UncheckedSQLException
         */
        @Override
        default int update(final T entity, final Condition cond) throws UncheckedSQLException {
            return update(Maps.entity2Map(entity), cond);
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default T upsert(final T entity, final Condition cond) throws UncheckedSQLException {
            N.checkArgNotNull(cond, "cond");

            final T dbEntity = findFirst(cond).orNull();

            if (dbEntity == null) {
                save(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity);
                update(Maps.entity2Map(dbEntity), cond);
                return dbEntity;
            }
        }

        /**
         *
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int delete(final Condition cond) throws UncheckedSQLException;
    }

    /**
     * The Interface CrudDao.
     *
     * @param <T>
     * @param <ID> use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T}.
     * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
     * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
     * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see Dao
     * @see SQLExecutor.Mapper
     * @see com.landawn.abacus.condition.ConditionFactory
     * @see com.landawn.abacus.condition.ConditionFactory.CF
     */
    public static interface UncheckedCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>>
            extends UncheckedDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToInsert
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        ID insert(final T entityToInsert) throws UncheckedSQLException;

        /**
         *
         * @param entityToInsert
         * @param propNamesToInsert
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UncheckedSQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entityToInsert
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        ID insert(final String namedInsertSQL, final T entityToInsert) throws UncheckedSQLException;

        /**
         *
         * @param entities
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities) throws UncheckedSQLException {
            return batchInsert(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws UncheckedSQLException {
            return batchInsert(entities, propNamesToInsert, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
                throws UncheckedSQLException;

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws UncheckedSQLException {
            return batchInsert(namedInsertSQL, entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

        /**
         *
         * @param id
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default Optional<T> get(final ID id) throws UncheckedSQLException {
            return Optional.ofNullable(gett(id));
        }

        /**
         *
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws UncheckedSQLException {
            return Optional.ofNullable(gett(id, selectPropNames));
        }

        /**
         * Gets the t.
         *
         * @param id
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        T gett(final ID id) throws UncheckedSQLException;

        /**
         * Gets the t.
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         *
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        T gett(final ID id, final Collection<String> selectPropNames) throws UncheckedSQLException;

        /**
         *
         *
         * @param ids
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException, UncheckedSQLException {
            return batchGet(ids, (Collection<String>) null);
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default List<T> batchGet(final Collection<? extends ID> ids, final int batchSize) throws DuplicatedResultException, UncheckedSQLException {
            return batchGet(ids, (Collection<String>) null, batchSize);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames)
                throws DuplicatedResultException, UncheckedSQLException {
            return batchGet(ids, selectPropNames, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @return
         * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
                throws DuplicatedResultException, UncheckedSQLException;

        /**
         *
         * @param id
         * @return true, if successful
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        boolean exists(final ID id) throws UncheckedSQLException;

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int update(final T entityToUpdate) throws UncheckedSQLException;

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UncheckedSQLException;

        /**
        *
        * @param propName
        * @param propValue
        * @param id
        * @return
        * @throws UncheckedSQLException the unchecked SQL exception
        */
        @Override
        default int update(final String propName, final Object propValue, final ID id) throws UncheckedSQLException {
            final Map<String, Object> updateProps = new HashMap<>();
            updateProps.put(propName, propValue);

            return update(updateProps, id);
        }

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int update(final Map<String, Object> updateProps, final ID id) throws UncheckedSQLException;

        /**
         *
         * @param entities
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int batchUpdate(final Collection<? extends T> entities) throws UncheckedSQLException {
            return batchUpdate(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws UncheckedSQLException {
            return batchUpdate(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws UncheckedSQLException;

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default T upsert(final T entity, final Condition cond) throws UncheckedSQLException {
            N.checkArgNotNull(cond, "cond");

            final T dbEntity = findFirst(cond).orNull();

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                final Class<?> cls = entity.getClass();
                @SuppressWarnings("deprecation")
                final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls);
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default T upsert(final T entity) throws UncheckedSQLException {
            final Class<?> cls = entity.getClass();
            @SuppressWarnings("deprecation")
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
            final T dbEntity = gett(JdbcUtil.extractId(entity, cls, idPropNameList, entityInfo));

            if (dbEntity == null) {
                insert(entity);
                return entity;
            } else {
                N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
                update(dbEntity);
                return dbEntity;
            }
        }

        /**
         * 
         * @param entities
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default List<T> batchUpsert(final Collection<? extends T> entities) throws UncheckedSQLException {
            return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * 
         * @param entities
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException {
            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(entities)) {
                return new ArrayList<>();
            }

            final T first = N.firstOrNullIfEmpty(entities);
            final Class<?> cls = first.getClass();
            @SuppressWarnings("deprecation")
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            final Function<T, ID> idExtractorFunc = createIdExtractor(cls, idPropNameList, entityInfo);
            final List<ID> ids = N.map(entities, idExtractorFunc);

            final List<T> dbEntities = batchGet(ids, batchSize);

            final Map<ID, T> dbIdEntityMap = StreamEx.of(dbEntities).toMap(idExtractorFunc, Fn.identity(), Fn.ignoringMerger());
            final Map<Boolean, List<T>> map = StreamEx.of(entities).groupTo(it -> dbIdEntityMap.containsKey(idExtractorFunc.apply(it)), Fn.identity());
            final List<T> entitiesToUpdate = map.get(true);
            final List<T> entitiesToInsert = map.get(false);

            if (N.notNullOrEmpty(entitiesToInsert)) {
                batchInsert(entitiesToInsert, batchSize);
            }

            if (N.notNullOrEmpty(entitiesToUpdate)) {
                final Set<String> idPropNameSet = N.newHashSet(idPropNameList);

                final List<T> dbEntitiesToUpdate = StreamEx.of(entitiesToUpdate)
                        .map(it -> N.merge(it, dbIdEntityMap.get(idExtractorFunc.apply(it)), false, idPropNameSet))
                        .toList();

                batchUpdate(dbEntitiesToUpdate);

                entitiesToInsert.addAll(dbEntitiesToUpdate);
            }

            return entitiesToInsert;
        }

        /**
         *
         * @param entity
         * @return true, if successful
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default boolean refresh(final T entity) throws UncheckedSQLException {
            final Class<?> cls = entity.getClass();
            final Collection<String> propNamesToRefresh = DirtyMarkerUtil.isDirtyMarker(cls) ? DirtyMarkerUtil.signedPropNames((DirtyMarker) entity)
                    : SQLBuilder.getSelectPropNames(cls, false, null);

            return refresh(entity, propNamesToRefresh);
        }

        /**
         *
         * @param entity
         * @param propNamesToRefresh
         * @return {@code false} if no record found by the ids in the specified {@code entity}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @SuppressWarnings("deprecation")
        default boolean refresh(final T entity, Collection<String> propNamesToRefresh) throws UncheckedSQLException {
            N.checkArgNotNullOrEmpty(propNamesToRefresh, "propNamesToRefresh");

            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            final ID id = extractId(entity, cls, idPropNameList, entityInfo);
            final Collection<String> selectPropNames = getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

            final T dbEntity = gett(id, selectPropNames);

            if (dbEntity == null) {
                return false;
            } else {
                N.merge(dbEntity, entity, propNamesToRefresh);

                if (DirtyMarkerUtil.isDirtyMarker(cls)) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToRefresh, false);
                }

                return true;
            }
        }

        /**
         *
         * @param entities
         * @return the count of refreshed entities.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int batchRefresh(final Collection<? extends T> entities) throws UncheckedSQLException {
            return batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         * 
         * @param entities
         * @param batchSize
         * @return the count of refreshed entities.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int batchRefresh(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final T first = N.firstOrNullIfEmpty(entities);
            final Class<?> cls = first.getClass();
            final Collection<String> propNamesToRefresh = DirtyMarkerUtil.isDirtyMarker(cls) ? DirtyMarkerUtil.signedPropNames((DirtyMarker) first)
                    : SQLBuilder.getSelectPropNames(cls, false, null);

            return batchRefresh(entities, propNamesToRefresh, batchSize);
        }

        /**
         * 
         * @param entities
         * @param propNamesToRefresh
         * @return the count of refreshed entities.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh) throws UncheckedSQLException {
            return batchRefresh(entities, propNamesToRefresh, JdbcUtil.DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param propNamesToRefresh
         * @param batchSize
         * @return the count of refreshed entities.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @SuppressWarnings("deprecation")
        default int batchRefresh(final Collection<? extends T> entities, Collection<String> propNamesToRefresh, final int batchSize)
                throws UncheckedSQLException {
            N.checkArgNotNullOrEmpty(propNamesToRefresh, "propNamesToRefresh");
            N.checkArgPositive(batchSize, "batchSize");

            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final T first = N.firstOrNullIfEmpty(entities);
            final Class<?> cls = first.getClass();
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(cls); // must not empty.
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);

            final Function<T, ID> idExtractorFunc = createIdExtractor(cls, idPropNameList, entityInfo);
            final Map<ID, List<T>> idEntityMap = StreamEx.of(entities).groupTo(idExtractorFunc, Fn.identity());
            final Collection<String> selectPropNames = getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

            final List<T> dbEntities = batchGet(idEntityMap.keySet(), selectPropNames, batchSize);

            if (N.isNullOrEmpty(dbEntities)) {
                return 0;
            } else {
                final boolean isDirtyMarker = DirtyMarkerUtil.isDirtyMarker(cls);

                return dbEntities.stream().mapToInt(dbEntity -> {
                    final ID id = idExtractorFunc.apply(dbEntity);
                    final List<T> tmp = idEntityMap.get(id);

                    if (N.notNullOrEmpty(tmp)) {
                        for (T entity : tmp) {
                            N.merge(dbEntity, entity, propNamesToRefresh);

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToRefresh, false);
                            }
                        }
                    }

                    return N.size(tmp);
                }).sum();
            }
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int deleteById(final ID id) throws UncheckedSQLException;

        /**
         *
         * @param entity
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int delete(final T entity) throws UncheckedSQLException;
        //
        //    /**
        //     *
        //     * @param entity
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @return
        //     * @throws UncheckedSQLException the unchecked SQL exception
        //     */
        //    @Beta
        //    int delete(final T entity, final OnDeleteAction onDeleteAction) throws UncheckedSQLException;

        /**
         *
         * @param entities
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int batchDelete(final Collection<? extends T> entities) throws UncheckedSQLException {
            return batchDelete(entities, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @return
        //     * @throws UncheckedSQLException the unchecked SQL exception
        //     */
        //    @Beta
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction) throws UncheckedSQLException {
        //        return batchDelete(entities, onDeleteAction, DEFAULT_BATCH_SIZE);
        //    }
        //
        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction It should be defined and done in DB server side.
        //     * @param batchSize
        //     * @return
        //     * @throws UncheckedSQLException the unchecked SQL exception
        //     */
        //    @Beta
        //    int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize) throws UncheckedSQLException;

        /**
         *
         * @param ids
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws UncheckedSQLException {
            return batchDeleteByIds(ids, DEFAULT_BATCH_SIZE);
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UncheckedSQLException;
    }

    /**
     *  
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface UncheckedCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
            extends UncheckedCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

        @Override
        default Optional<T> get(final long id) throws UncheckedSQLException {
            return get(Long.valueOf(id));
        }

        @Override
        default Optional<T> get(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
            return get(Long.valueOf(id), selectPropNames);
        }

        @Override
        default T gett(final long id) throws UncheckedSQLException {
            return gett(Long.valueOf(id));
        }

        @Override
        default T gett(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
            return gett(Long.valueOf(id), selectPropNames);
        }

        @Override
        default boolean exists(final long id) throws UncheckedSQLException {
            return exists(Long.valueOf(id));
        }

        @Override
        default int update(final String propName, final Object propValue, final long id) throws UncheckedSQLException {
            return update(propName, propValue, Long.valueOf(id));
        }

        @Override
        default int update(final Map<String, Object> updateProps, final long id) throws UncheckedSQLException {
            return update(updateProps, Long.valueOf(id));
        }

        @Override
        default int deleteById(final long id) throws UncheckedSQLException {
            return deleteById(Long.valueOf(id));
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface UncheckedNoUpdateDao<T, SB extends SQLBuilder, TD extends UncheckedNoUpdateDao<T, SB, TD>>
            extends UncheckedDao<T, SB, TD>, NoUpdateDao<T, SB, TD> {

        /**
         *
         * @param propName
         * @param propValue
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default int update(final String propName, final Object propValue, final Condition cond) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param updateProps
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final Condition cond) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entity, final Condition cond) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param cond
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final Condition cond) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface UncheckedReadOnlyDao<T, SB extends SQLBuilder, TD extends UncheckedReadOnlyDao<T, SB, TD>>
            extends UncheckedNoUpdateDao<T, SB, TD>, ReadOnlyDao<T, SB, TD> {

        /**
         *
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void save(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param entitiesToSave
         * @param propNamesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Always throws {@code UnsupportedOperationException}.
         *
         * @param namedInsertSQL
         * @param entitiesToSave
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface UncheckedNoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDao<T, ID, SB, TD>>
            extends UncheckedNoUpdateDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD>, UncheckedCrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToUpdate
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param propName
         * @param propValue
         * @param id
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param updateProps
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToUpdate
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @param cond to verify if the record exists or not.
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default T upsert(final T entity) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * 
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Override
        @Deprecated
        default List<T> batchUpsert(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * 
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation 
         */
        @Override
        @Deprecated
        default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * Delete by id.
         *
         * @param id
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteById(final ID id) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int delete(final T entity) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        //    /**
        //     *
        //     * @param entity
        //     * @param onDeleteAction
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws UncheckedSQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int delete(final T entity, final OnDeleteAction onDeleteAction) throws UnsupportedOperationException, UncheckedSQLException {
        //        throw new UnsupportedOperationException();
        //    }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws UncheckedSQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction)
        //            throws UnsupportedOperationException, UncheckedSQLException {
        //        throw new UnsupportedOperationException();
        //    }
        //
        //    /**
        //     *
        //     * @param entities
        //     * @param onDeleteAction
        //     * @param batchSize
        //     * @return
        //     * @throws UnsupportedOperationException
        //     * @throws UncheckedSQLException
        //     * @deprecated unsupported Operation
        //     */
        //    @Deprecated
        //    @Override
        //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize)
        //            throws UnsupportedOperationException, UncheckedSQLException {
        //        throw new UnsupportedOperationException();
        //    }

        /**
         *
         * @param ids
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param ids
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }
    }

    @Beta
    public static interface UncheckedNoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDaoL<T, SB, TD>>
            extends UncheckedNoUpdateCrudDao<T, Long, SB, TD>, UncheckedCrudDaoL<T, SB, TD> {

        /**
         * 
         * @param propName
         * @param propValue
         * @param id
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final String propName, final Object propValue, final long id) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * 
         * @param updateProps
         * @param id
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int update(final Map<String, Object> updateProps, final long id) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         * @param id
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteById(final long id) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }
    }

    @Beta
    public static interface UncheckedReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedReadOnlyCrudDaoL<T, SB, TD>>
            extends UncheckedReadOnlyCrudDao<T, Long, SB, TD>, UncheckedNoUpdateCrudDaoL<T, SB, TD> {
    }

    /**
     * TODO
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     */
    @Beta
    public static interface UncheckedReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedReadOnlyCrudDao<T, ID, SB, TD>>
            extends UncheckedReadOnlyDao<T, SB, TD>, UncheckedNoUpdateCrudDao<T, ID, SB, TD>, ReadOnlyCrudDao<T, ID, SB, TD> {

        /**
         *
         * @param entityToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToInsert) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entityToInsert
         * @param propNamesToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entityToSave
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default ID insert(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param propNamesToInsert
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param namedInsertSQL
         * @param entities
         * @param batchSize
         * @return
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
                throws UnsupportedOperationException, UncheckedSQLException {
            throw new UnsupportedOperationException();
        }
    }

    public static interface UncheckedJoinEntityHelper<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default Optional<T> findFirst(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond)
                throws UncheckedSQLException {
            final Optional<T> result = getDao(this).findFirst(selectPropNames, cond);

            if (result.isPresent()) {
                loadJoinEntities(result.get(), joinEntitiesToLoad);
            }

            return result;
        }

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default Optional<T> findFirst(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad, final Condition cond)
                throws UncheckedSQLException {
            final Optional<T> result = getDao(this).findFirst(selectPropNames, cond);

            if (result.isPresent()) {
                for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result.get(), joinEntityClass);
                }
            }

            return result;
        }

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default Optional<T> findFirst(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond)
                throws UncheckedSQLException {
            final Optional<T> result = getDao(this).findFirst(selectPropNames, cond);

            if (includeAllJoinEntities && result.isPresent()) {
                loadAllJoinEntities(result.get());
            }

            return result;
        }

        /** 
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws UncheckedSQLException {
            final List<T> result = getDao(this).list(selectPropNames, cond);

            if (N.notNullOrEmpty(result)) {
                if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                    StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> loadJoinEntities(it, joinEntitiesToLoad));
                } else {
                    loadJoinEntities(result, joinEntitiesToLoad);
                }
            }

            return result;
        }

        /**
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> list(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad, final Condition cond)
                throws UncheckedSQLException {
            final List<T> result = getDao(this).list(selectPropNames, cond);

            if (N.notNullOrEmpty(result) && N.notNullOrEmpty(joinEntitiesToLoad)) {
                if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                    StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> {
                        for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                            loadJoinEntities(it, joinEntityClass);
                        }
                    });
                } else {
                    for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(result, joinEntityClass);
                    }
                }
            }

            return result;
        }

        /** 
         *
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @param cond
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> list(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond)
                throws UncheckedSQLException {
            final List<T> result = getDao(this).list(selectPropNames, cond);

            if (N.notNullOrEmpty(result)) {
                if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                    StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> loadAllJoinEntities(it));
                } else {
                    loadAllJoinEntities(result);
                }
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
            loadJoinEntities(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws UncheckedSQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
            loadJoinEntities(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException {
            loadJoinEntities(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws UncheckedSQLException;

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException {
            loadJoinEntities(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
                throws UncheckedSQLException;

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                loadJoinEntities(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.uncheckedComplete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntities(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws UncheckedSQLException {
            if (inParallel) {
                loadJoinEntities(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.uncheckedComplete(futures);
        }

        /**
         *
         * @param entity
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadAllJoinEntities(T entity) throws UncheckedSQLException {
            loadJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                loadAllJoinEntities(entity, executor());
            } else {
                loadAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
            loadJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                loadAllJoinEntities(entities, executor());
            } else {
                loadAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
            loadJoinEntitiesIfNull(entity, joinEntityClass, null);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws UncheckedSQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
            loadJoinEntitiesIfNull(entities, joinEntityClass, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            if (joinEntityPropNames.size() == 1) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames.get(0), selectPropNames);
            } else {
                for (String joinEntityPropName : joinEntityPropNames) {
                    loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
                }
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws UncheckedSQLException {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
        }

        /**
         *
         * @param entity
         * ?
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames)
                throws UncheckedSQLException {
            final Class<?> cls = entity.getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);

            if (propInfo.getPropValue(entity) == null) {
                loadJoinEntities(entity, joinEntityPropName, selectPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException {
            loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            final Class<?> cls = N.firstOrNullIfEmpty(entities).getClass();
            final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);
            final List<T> newEntities = N.filter(entities, entity -> propInfo.getPropValue(entity) == null);

            loadJoinEntities(newEntities, joinEntityPropName, selectPropNames);
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entity, joinEntityPropName);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws UncheckedSQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.uncheckedComplete(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws UncheckedSQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames, executor());
            } else {
                loadJoinEntitiesIfNull(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return;
            }

            final List<ContinuableFuture<Void>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                    .toList();

            JdbcUtil.uncheckedComplete(futures);
        }

        /**
         *
         * @param entity
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(T entity) throws UncheckedSQLException {
            loadJoinEntitiesIfNull(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entity, executor());
            } else {
                loadJoinEntitiesIfNull(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws UncheckedSQLException {
            loadJoinEntitiesIfNull(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                loadJoinEntitiesIfNull(entities, executor());
            } else {
                loadJoinEntitiesIfNull(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default void loadJoinEntitiesIfNull(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return;
            }

            loadJoinEntitiesIfNull(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entity
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entity, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            final Class<?> targetEntityClass = targetEntityClass();
            final List<String> joinEntityPropNames = getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
            N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entities, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException;

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException;

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entity, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                return deleteJoinEntities(entity, joinEntityPropNames, executor());
            } else {
                return deleteJoinEntities(entity, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
            if (N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                    .toList();

            return JdbcUtil.uncheckedCompleteSum(futures);
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            int result = 0;

            for (String joinEntityPropName : joinEntityPropNames) {
                result += deleteJoinEntities(entities, joinEntityPropName);
            }

            return result;
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws UncheckedSQLException {
            if (inParallel) {
                return deleteJoinEntities(entities, joinEntityPropNames, executor());
            } else {
                return deleteJoinEntities(entities, joinEntityPropNames);
            }
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
                return 0;
            }

            final List<ContinuableFuture<Integer>> futures = StreamE.of(joinEntityPropNames, UncheckedSQLException.class)
                    .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                    .toList();

            return JdbcUtil.uncheckedCompleteSum(futures);
        }

        /**
         *
         * @param entity
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteAllJoinEntities(T entity) throws UncheckedSQLException {
            return deleteJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entity, executor());
            } else {
                return deleteAllJoinEntities(entity);
            }
        }

        /**
         *
         * @param entity
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
            return deleteJoinEntities(entity, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }

        /**
         *
         * @param entities
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
            if (inParallel) {
                return deleteAllJoinEntities(entities, executor());
            } else {
                return deleteAllJoinEntities(entities);
            }
        }

        /**
         *
         * @param entities
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
            if (N.isNullOrEmpty(entities)) {
                return 0;
            }

            return deleteJoinEntities(entities, getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
        }
    }

    public static interface UncheckedCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>>
            extends UncheckedJoinEntityHelper<T, SB, TD>, CrudJoinEntityHelper<T, ID, SB, TD> {
        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Beta
        @Override
        default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws UncheckedSQLException {
            return Optional.ofNullable(gett(id, joinEntitiesToLoad));
        }

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Beta
        @Override
        default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws UncheckedSQLException {
            return Optional.ofNullable(gett(id, includeAllJoinEntities));
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws UncheckedSQLException
         */
        @Beta
        @Override
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws UncheckedSQLException {
            return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws UncheckedSQLException
         */
        @Beta
        @Override
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
                throws UncheckedSQLException {
            return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @return
         * @throws UncheckedSQLException
         */
        @Beta
        @Override
        default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities) throws UncheckedSQLException {
            return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
        }

        /**
         *
         * @param id
         * @param joinEntitiesToLoad
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Beta
        @Override
        default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws UncheckedSQLException {
            final T result = getCrudDao(this).gett(id);

            if (result != null) {
                loadJoinEntities(result, joinEntitiesToLoad);
            }

            return result;
        }

        /**
         *
         * @param id
         * @param includeAllJoinEntities
         * @return
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Beta
        @Override
        default T gett(final ID id, final boolean includeAllJoinEntities) throws UncheckedSQLException {
            final T result = getCrudDao(this).gett(id);

            if (result != null && includeAllJoinEntities) {
                loadAllJoinEntities(result);
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws UncheckedSQLException
         */
        @Beta
        @Override
        default T gett(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws UncheckedSQLException {
            final T result = getCrudDao(this).gett(id, selectPropNames);

            if (result != null) {
                loadJoinEntities(result, joinEntitiesToLoad);
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return
         * @throws UncheckedSQLException
         */
        @Beta
        @Override
        default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad) throws UncheckedSQLException {
            final T result = getCrudDao(this).gett(id, selectPropNames);

            if (result != null) {
                for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result, joinEntityClass);
                }
            }

            return result;
        }

        /**
         * 
         * @param id
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @return
         * @throws UncheckedSQLException
         */
        @Beta
        @Override
        default T gett(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities) throws UncheckedSQLException {
            final T result = getCrudDao(this).gett(id, selectPropNames);

            if (result != null && includeAllJoinEntities) {
                loadAllJoinEntities(result);
            }

            return result;
        }

        /**
         *
         * @param ids
         * @param joinEntitiesToLoad
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntitiesToLoad) throws UncheckedSQLException {
            return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
        }

        /**
         *
         *
         * @param ids
         * @param includeAllJoinEntities
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities) throws UncheckedSQLException {
            return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
                throws UncheckedSQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param joinEntitiesToLoad
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames,
                final Collection<? extends Class<?>> joinEntitiesToLoad) throws UncheckedSQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param includeAllJoinEntities
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
                throws UncheckedSQLException {
            return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @param joinEntitiesToLoad
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
                final Class<?> joinEntitiesToLoad) throws UncheckedSQLException {
            final List<T> result = getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

            if (N.notNullOrEmpty(result)) {
                if (result.size() > batchSize) {
                    StreamEx.of(result).splitToList(batchSize).forEach(it -> loadJoinEntities(it, joinEntitiesToLoad));
                } else {
                    loadJoinEntities(result, joinEntitiesToLoad);
                }
            }

            return result;
        }

        /**
         *
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @param joinEntitiesToLoad
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
                final Collection<? extends Class<?>> joinEntitiesToLoad) throws UncheckedSQLException {
            final List<T> result = getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

            if (N.notNullOrEmpty(result) && N.notNullOrEmpty(joinEntitiesToLoad)) {
                if (result.size() > batchSize) {
                    StreamEx.of(result).splitToList(batchSize).forEach(it -> {
                        for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                            loadJoinEntities(it, joinEntityClass);
                        }
                    });
                } else {
                    for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(result, joinEntityClass);
                    }
                }
            }

            return result;
        }

        /**
         *
         * @param ids
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
         * @param batchSize
         * @param includeAllJoinEntities
         * @return 
         * @throws UncheckedSQLException the unchecked SQL exception
         */
        @Override
        @Beta
        default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
                final boolean includeAllJoinEntities) throws UncheckedSQLException {
            final List<T> result = getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

            if (includeAllJoinEntities && N.notNullOrEmpty(result)) {
                if (result.size() > batchSize) {
                    StreamEx.of(result).splitToList(batchSize).forEach(it -> loadAllJoinEntities(it));
                } else {
                    loadAllJoinEntities(result);
                }
            }

            return result;
        }
    }

    public static interface UncheckedReadOnlyJoinEntityHelper<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>>
            extends UncheckedJoinEntityHelper<T, SB, TD>, ReadOnlyJoinEntityHelper<T, SB, TD> {

        /**
         * 
         * @param entity
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityClass
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param joinEntityPropNames
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param joinEntityPropName
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
                throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(T entity) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entity
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param inParallel
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }

        /**
         *
         * @param entities
         * @param executor
         * @return the total count of updated/deleted records.
         * @throws UnsupportedOperationException
         * @throws UncheckedSQLException
         * @deprecated unsupported Operation
         */
        @Deprecated
        @Override
        default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException, UnsupportedOperationException {
            throw new UnsupportedOperationException();
        }
    }

    public static interface UnckeckedReadOnlyCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>>
            extends UncheckedReadOnlyJoinEntityHelper<T, SB, TD>, UncheckedCrudJoinEntityHelper<T, ID, SB, TD> {
    }

    static Object[] getParameterArray(final SP sp) {
        return N.isNullOrEmpty(sp.parameters) ? N.EMPTY_OBJECT_ARRAY : sp.parameters.toArray();
    }

    static Collection<String> getRefreshSelectPropNames(Collection<String> propNamesToRefresh, final List<String> idPropNameList) {
        if (propNamesToRefresh.containsAll(idPropNameList)) {
            return propNamesToRefresh;
        } else {
            final Collection<String> selectPropNames = new HashSet<>(propNamesToRefresh);
            selectPropNames.addAll(idPropNameList);
            return selectPropNames;
        }
    }

    @SuppressWarnings("deprecation")
    static <T, ID> ID extractId(final T entity, final Class<?> cls, final List<String> idPropNameList, final EntityInfo entityInfo) {
        if (idPropNameList.size() == 1) {
            return entityInfo.getPropInfo(idPropNameList.get(0)).getPropValue(entity);
        } else {
            Seid entityId = Seid.of(ClassUtil.getSimpleClassName(cls));

            for (String idPropName : idPropNameList) {
                entityId.set(idPropName, entityInfo.getPropInfo(idPropName).getPropValue(entity));
            }

            return (ID) entityId;
        }
    }

    @SuppressWarnings("deprecation")
    static <T, ID> Function<T, ID> createIdExtractor(final Class<?> cls, final List<String> idPropNameList, final EntityInfo entityInfo) {
        if (idPropNameList.size() == 1) {
            final PropInfo idPropInfo = entityInfo.getPropInfo(idPropNameList.get(0));

            return it -> idPropInfo.getPropValue(it);
        } else {
            final List<PropInfo> idPropInfos = N.map(idPropNameList, idPropName -> entityInfo.getPropInfo(idPropName));

            return it -> {
                final Seid entityId = Seid.of(ClassUtil.getSimpleClassName(cls));

                for (PropInfo propInfo : idPropInfos) {
                    entityId.set(propInfo.name, propInfo.getPropValue(it));
                }

                return (ID) entityId;
            };
        }
    }

    static <R> BiRowMapper<R> toBiRowMapper(final RowMapper<R> rowMapper) {
        return (rs, columnLabels) -> rowMapper.apply(rs);
    }

    static final Throwables.Consumer<? super Exception, SQLException> throwSQLExceptionAction = e -> {
        if (e instanceof SQLException) {
            throw (SQLException) e;
        } else if (e.getCause() != null && e.getCause() instanceof SQLException) {
            throw (SQLException) e.getCause();
        } else {
            throw N.toRuntimeException(e);
        }
    };

    static void complete(final List<ContinuableFuture<Void>> futures) throws SQLException {
        for (ContinuableFuture<Void> f : futures) {
            f.gett().ifFailure(throwSQLExceptionAction);
        }
    }

    static int completeSum(final List<ContinuableFuture<Integer>> futures) throws SQLException {
        int result = 0;
        Result<Integer, Exception> ret = null;

        for (ContinuableFuture<Integer> f : futures) {
            ret = f.gett();

            if (ret.isFailure()) {
                throwSQLExceptionAction.accept(ret.getExceptionIfPresent());
            }

            result += ret.orElse(0);
        }

        return result;
    }

    static final Throwables.Consumer<? super Exception, UncheckedSQLException> throwUncheckedSQLException = e -> {
        if (e instanceof SQLException) {
            throw new UncheckedSQLException((SQLException) e);
        } else if (e.getCause() != null && e.getCause() instanceof SQLException) {
            throw new UncheckedSQLException((SQLException) e.getCause());
        } else {
            throw N.toRuntimeException(e);
        }
    };

    static void uncheckedComplete(final List<ContinuableFuture<Void>> futures) throws UncheckedSQLException {
        for (ContinuableFuture<Void> f : futures) {
            f.gett().ifFailure(throwUncheckedSQLException);
        }
    }

    static int uncheckedCompleteSum(final List<ContinuableFuture<Integer>> futures) throws UncheckedSQLException {
        int result = 0;
        Result<Integer, Exception> ret = null;

        for (ContinuableFuture<Integer> f : futures) {
            ret = f.gett();

            if (ret.isFailure()) {
                throwUncheckedSQLException.accept(ret.getExceptionIfPresent());
            }

            result += ret.orElse(0);
        }

        return result;
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Tuple2<Class<?>, Class<?>>, Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, Function, BiConsumer> noIdGeneratorGetterSetter = Tuple.of(NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null,
            BiConsumers.doNothing());

    @SuppressWarnings({ "rawtypes", "deprecation" })
    static <ID> Tuple3<BiRowMapper<ID>, Function<Object, ID>, BiConsumer<ID, Object>> getIdGeneratorGetterSetter(final Class<? extends Dao> daoInterface,
            final Class<?> entityClass, final NamingPolicy namingPolicy, final Class<?> idType) {
        if (entityClass == null || !ClassUtil.isEntity(entityClass)) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        final Tuple2<Class<?>, Class<?>> key = Tuple.of(entityClass, idType);

        Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>> map = idGeneratorGetterSetterPool.get(key);

        if (map == null) {
            final List<String> idPropNameList = ClassUtil.getIdFieldNames(entityClass);
            final boolean isNoId = N.isNullOrEmpty(idPropNameList) || ClassUtil.isFakeId(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final EntityInfo entityInfo = isNoId ? null : ParserUtil.getEntityInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = isNoId ? false : idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);

            final Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? entity -> idPropInfo.getPropValue(entity) //
                            : (isEntityId ? entity -> {
                                final Seid ret = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (PropInfo propInfo : idPropInfoList) {
                                    ret.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            } : entity -> {
                                final Object ret = N.newInstance(idType);

                                for (PropInfo propInfo : idPropInfoList) {
                                    ClassUtil.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            }));

            final BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (isEntityId ? (id, entity) -> {
                                if (id instanceof EntityId) {
                                    final EntityId entityId = (EntityId) id;
                                    PropInfo propInfo = null;

                                    for (String propName : entityId.keySet()) {
                                        propInfo = entityInfo.getPropInfo(propName);

                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null) {
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                        }
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            } : (id, entity) -> {
                                if (id != null && ClassUtil.isEntity(id.getClass())) {
                                    final Object entityId = id;

                                    for (PropInfo propInfo : idPropInfoList) {
                                        propInfo.setPropValue(entity, ClassUtil.getPropValue(entityId, propInfo.name));
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            }));

            map = new EnumMap<>(NamingPolicy.class);

            for (NamingPolicy np : NamingPolicy.values()) {
                final ImmutableMap<String, String> propColumnNameMap = ClassUtil.getProp2ColumnNameMap(entityClass, namingPolicy);

                final ImmutableMap<String, String> columnPropNameMap = EntryStream.of(propColumnNameMap)
                        .inversed()
                        .flattMapKey(e -> N.asList(e, e.toLowerCase(), e.toUpperCase()))
                        .distinctByKey()
                        .toImmutableMap();

                final BiRowMapper<Object> keyExtractor = isNoId ? noIdGeneratorGetterSetter._1
                        : (idExtractorPool.containsKey(daoInterface) ? (BiRowMapper<Object>) idExtractorPool.get(daoInterface) //
                                : (isOneId ? (rs, columnLabels) -> idPropInfo.dbType.get(rs, 1) //
                                        : (rs, columnLabels) -> {
                                            if (columnLabels.size() == 1) {
                                                return idPropInfo.dbType.get(rs, 1);
                                            } else if (isEntityId) {
                                                final int columnCount = columnLabels.size();
                                                final Seid id = Seid.of(ClassUtil.getSimpleClassName(entityClass));
                                                String columnName = null;
                                                String propName = null;
                                                PropInfo propInfo = null;

                                                for (int i = 1; i <= columnCount; i++) {
                                                    columnName = columnLabels.get(i - 1);

                                                    if ((propName = columnPropNameMap.get(columnName)) == null
                                                            || (propInfo = entityInfo.getPropInfo(propName)) == null) {
                                                        id.set(columnName, getColumnValue(rs, i));
                                                    } else {
                                                        id.set(propInfo.name, propInfo.dbType.get(rs, i));
                                                    }
                                                }

                                                return id;
                                            } else {
                                                final EntityInfo idEntityInfo = ParserUtil.getEntityInfo(idType);
                                                final List<Tuple2<String, PropInfo>> tpList = StreamEx.of(columnLabels)
                                                        .filter(it -> idEntityInfo.getPropInfo(it) != null)
                                                        .map(it -> Tuple.of(it, idEntityInfo.getPropInfo(it)))
                                                        .toList();
                                                final Object id = N.newInstance(idType);

                                                for (Tuple2<String, PropInfo> tp : tpList) {
                                                    tp._2.setPropValue(id, tp._2.dbType.get(rs, tp._1));
                                                }

                                                return id;
                                            }
                                        }));

                map.put(np, Tuple.of(keyExtractor, idGetter, idSetter));
            }

            idGeneratorGetterSetterPool.put(key, map);
        }

        return (Tuple3) map.get(namingPolicy);
    }

    private static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD getDao(final JoinEntityHelper<T, SB, TD> dao) {
        if (dao instanceof Dao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extends interface JoinEntityHelper");
        }
    }

    private static <T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> TD getDao(final UncheckedJoinEntityHelper<T, SB, TD> dao) {
        if (dao instanceof UncheckedDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extends interface JoinEntityHelper");
        }
    }

    private static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> TD getCrudDao(final CrudJoinEntityHelper<T, ID, SB, TD> dao) {
        if (dao instanceof CrudDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extends interface JoinEntityHelper");
        }
    }

    private static <T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>> TD getCrudDao(
            final UncheckedCrudJoinEntityHelper<T, ID, SB, TD> dao) {
        if (dao instanceof UncheckedCrudDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extends interface JoinEntityHelper");
        }
    }

    private static List<String> getJoinEntityPropNamesByType(final Class<?> targetDaoInterface, final Class<?> targetEntityClass,
            final Class<?> joinEntityClass) {
        return JoinInfo.getJoinEntityPropNamesByType(targetDaoInterface, targetEntityClass, joinEntityClass);
    }

    private static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> targetDaoInterface, final Class<?> targetEntityClass) {
        return JoinInfo.getEntityJoinInfo(targetDaoInterface, targetEntityClass);
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Class<? extends Dao>, BiRowMapper<?>> idExtractorPool = new ConcurrentHashMap<>();

    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final RowMapper<ID> idExtractor) {
        N.checkArgNotNull(daoInterface, "daoInterface");
        N.checkArgNotNull(idExtractor, "idExtractor");

        idExtractorPool.put(daoInterface, (rs, cls) -> idExtractor.apply(rs));
    }

    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final BiRowMapper<ID> idExtractor) {
        N.checkArgNotNull(daoInterface, "daoInterface");
        N.checkArgNotNull(idExtractor, "idExtractor");

        idExtractorPool.put(daoInterface, idExtractor);
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) {
        return createDao(daoInterface, ds, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper) {
        return createDao(daoInterface, ds, sqlMapper, asyncExecutor.getExecutor());
    }

    /**
     * 
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param cache Don't share cache between Dao.
     * @return
     * @deprecated
     */
    @Deprecated
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Cache<String, Object> cache) {
        return createDao(daoInterface, ds, sqlMapper, cache, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param executor
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final Executor executor) {
        return createDao(daoInterface, ds, null, executor);
    }

    /**
     *
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param executor
     * @return
     */
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Executor executor) {
        return createDao(daoInterface, ds, sqlMapper, null, executor);
    }

    /**
     * 
     * @param <T>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param cache Don't share cache between Dao.
     * @param executor
     * @return
     * @deprecated
     */
    @Deprecated
    public static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Cache<String, Object> cache, final Executor executor) {
        return DaoUtil.createDao(daoInterface, ds, sqlMapper, cache, executor);
    }
}
