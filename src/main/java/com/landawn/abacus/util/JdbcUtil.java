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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.EntityId;
import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.SequentialOnly;
import com.landawn.abacus.annotation.Stateful;
import com.landawn.abacus.cache.Cache;
import com.landawn.abacus.core.RowDataSet;
import com.landawn.abacus.core.Seid;
import com.landawn.abacus.dao.CrudDao;
import com.landawn.abacus.dao.Dao;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Columns.ColumnGetter;
import com.landawn.abacus.util.ExceptionalStream.ExceptionalIterator;
import com.landawn.abacus.util.Fn.BiConsumers;
import com.landawn.abacus.util.Fn.Fnn;
import com.landawn.abacus.util.Fn.IntFunctions;
import com.landawn.abacus.util.Fn.Suppliers;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.SQLTransaction.CreatedBy;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Holder;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiPredicate;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Consumer;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.IntFunction;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.stream.Collector;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 *
 * Performance Tips:
 * <li>Avoid unnecessary/repeated database calls.</li>
 * <li>Only fetch the columns you need or update the columns you want.</li>
 * <li>Index is the key point in a lot of database performance issues.</li>
 *
 * <br />
 *
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
 * @author Haiyang Li
 */
public final class JdbcUtil {

    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    static final Logger sqlLogger = LoggerFactory.getLogger("com.landawn.abacus.jdbc.SQL");

    public static final int DEFAULT_BATCH_SIZE = 200;

    // static final int MAX_BATCH_SIZE = 1000;

    public static final int DEFAULT_FETCH_SIZE_FOR_BIG_RESULT = 1000;

    public static final Function<Statement, String> DEFAULT_SQL_EXTRACTOR = stmt -> {
        String sql = stmt.toString();

        if (sql.startsWith("Hikari")) {
            String delimitor = "wrapping ";
            int idx = sql.indexOf(delimitor);

            if (idx > 0) {
                sql = sql.substring(idx + delimitor.length());
            }
        }

        return sql;
    };

    static Function<Statement, String> sqlExtractor = DEFAULT_SQL_EXTRACTOR;

    // TODO is it right to do it?
    // static final KeyedObjectPool<Statement, PoolableWrapper<String>> stmtPoolForSql = PoolFactory.createKeyedObjectPool(1000, 3000);

    // ...
    static final String CURRENT_DIR_PATH = "./";

    static final AsyncExecutor asyncExecutor = new AsyncExecutor(Math.max(64, Math.min(IOUtil.CPU_CORES * 8, IOUtil.MAX_MEMORY_IN_MB / 1024) * 32),
            Math.max(256, (IOUtil.MAX_MEMORY_IN_MB / 1024) * 64), 180L, TimeUnit.SECONDS);

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

    public static DBProductInfo getDBProductInfo(final javax.sql.DataSource ds) throws UncheckedSQLException {
        try (Connection conn = ds.getConnection()) {
            return getDBProductInfo(conn);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    public static DBProductInfo getDBProductInfo(final Connection conn) throws UncheckedSQLException {
        try {
            final DatabaseMetaData metaData = conn.getMetaData();

            final String dbProudctName = metaData.getDatabaseProductName();
            final String dbProudctVersion = metaData.getDatabaseProductVersion();
            final String upperCaseProductName = dbProudctName.toUpperCase();

            DBVersion dbVersion = DBVersion.OTHERS;

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

            return new DBProductInfo(dbProudctName, dbProudctName, dbVersion);
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
     * @throws SQLException
     */
    public static int skip(final ResultSet rs, int n) throws SQLException {
        return skip(rs, (long) n);
    }

    /**
     *
     * @param rs
     * @param n the count of row to move ahead.
     * @return
     * @throws SQLException
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
                if (n > Integer.MAX_VALUE - currentRow) {
                    while (n-- > 0L && rs.next()) {
                        // continue.
                    }
                } else {
                    try {
                        rs.absolute((int) n + currentRow);
                    } catch (SQLException e) {
                        while (n-- > 0L && rs.next()) {
                            // continue.
                        }
                    }
                }
            } else {
                while (n-- > 0L && rs.next()) {
                    // continue.
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
        } else if (obj instanceof NClob) {
            NClob nclob = (NClob) obj;
            obj = nclob.getSubString(1, (int) nclob.length());
        } else if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className)) {
            obj = rs.getTimestamp(columnIndex);
        } else if (className != null && className.startsWith("oracle.sql.DATE")) {
            final String columnClassName = rs.getMetaData().getColumnClassName(columnIndex);

            if ("java.sql.Timestamp".equals(columnClassName) || "oracle.sql.TIMESTAMP".equals(columnClassName)) {
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
     * @throws SQLException
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
        } else if (obj instanceof NClob) {
            NClob nclob = (NClob) obj;
            obj = nclob.getSubString(1, (int) nclob.length());
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
     * @throws SQLException
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
     * @throws SQLException
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
        return QueryUtil.getColumn2PropNameMap(entityClass);
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
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
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
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Function<javax.sql.DataSource, T, E> cmd)
            throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
        T result = null;

        try {
            result = cmd.apply(dataSource);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     * @param <E>
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

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
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Consumer<javax.sql.DataSource, E> cmd)
            throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            cmd.accept(dataSource);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     *
     * @param <T>
     * @param <E>
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

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
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

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
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Function<javax.sql.DataSource, T, E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    return cmd.apply(dataSource);
                } else {
                    return tran.callNotInMe(() -> cmd.apply(dataSource));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.apply(dataSource);
            } else {
                return tran.callNotInMe(() -> cmd.apply(dataSource));
            }
        }
    }

    /**
     *
     * @param <E>
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

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
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

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
     * @param dataSource
     * @param cmd
     * @return
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) {
            JdbcUtil.disableSpringTransactional(true);

            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    cmd.accept(dataSource);
                } else {
                    tran.runNotInMe(() -> cmd.accept(dataSource));
                }
            } finally {
                JdbcUtil.disableSpringTransactional(false);
            }
        } else {
            final SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.accept(dataSource);
            } else {
                tran.runNotInMe(() -> cmd.accept(dataSource));
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
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

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
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

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
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");
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
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");
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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedQuery(prepareStatement(conn, sql, stmtCreator));
    }

    /**
     * Prepare {@code select} query for big result set. Fetch direction will be set to {@code FetchDirection.FORWARD}
     * and fetch size will be set to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException
     */
    @Beta
    public static PreparedQuery prepareQueryForBigResult(final javax.sql.DataSource ds, final String sql) throws SQLException {
        return prepareQuery(ds, sql).setFetchDirectionToForward().setFetchSize(DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    /**
     * Prepare {@code select} query for big result set. Fetch direction will be set to {@code FetchDirection.FORWARD}
     * and fetch size will be set to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     *
     * @param conn
     * @param sql
     * @return
     * @throws SQLException
     */
    @Beta
    public static PreparedQuery prepareQueryForBigResult(final Connection conn, final String sql) throws SQLException {
        return prepareQuery(conn, sql).setFetchDirectionToForward().setFetchSize(DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param namedSql for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");

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
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");

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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");

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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");

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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");

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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");

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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");
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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");
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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(namedSql, "namedSql");
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
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNull(namedSql, "namedSql");
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
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final boolean autoGeneratedKeys) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNull(namedSql, "namedSql");
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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final int[] returnColumnIndexes) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnIndexes, "returnColumnIndexes");
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
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final String[] returnColumnNames) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNullOrEmpty(returnColumnNames, "returnColumnNames");
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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNull(namedSql, "namedSql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
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
     * Prepare {@code select} query for big result set. Fetch direction will be set to {@code FetchDirection.FORWARD}
     * and fetch size will be set to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final javax.sql.DataSource ds, final String sql) throws SQLException {
        return prepareNamedQuery(ds, sql).setFetchDirectionToForward().setFetchSize(DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    /**
     * Prepare {@code select} query for big result set. Fetch direction will be set to {@code FetchDirection.FORWARD}
     * and fetch size will be set to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     *
     * @param conn
     * @param sql
     * @return
     * @throws SQLException
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final Connection conn, final String sql) throws SQLException {
        return prepareNamedQuery(conn, sql).setFetchDirectionToForward().setFetchSize(DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    /**
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise a {@code Connection} directly from the specified {@code DataSource}(Connection pool) will be borrowed and used.
     *
     * @param ds
     * @param sql
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

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
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     */
    public static PreparedCallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new PreparedCallableQuery(prepareCallable(conn, sql, stmtCreator));
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql) throws SQLException {
        logSql(sql);

        return conn.prepareStatement(sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        logSql(sql);

        return conn.prepareStatement(sql, autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int[] returnColumnIndexes) throws SQLException {
        logSql(sql);

        return conn.prepareStatement(sql, returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final String[] returnColumnNames) throws SQLException {
        logSql(sql);

        return conn.prepareStatement(sql, returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        logSql(sql);

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        logSql(sql);

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    static PreparedStatement prepareStatement(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        logSql(sql);

        return stmtCreator.apply(conn, sql);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql());
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final boolean autoGeneratedKeys) throws SQLException {
        logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int[] returnColumnIndexes) throws SQLException {
        logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnIndexes);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final String[] returnColumnNames) throws SQLException {
        logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), returnColumnNames);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), resultSetType, resultSetConcurrency);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        logSql(parsedSql.sql());

        return conn.prepareStatement(parsedSql.getParameterizedSql(), resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        logSql(parsedSql.sql());

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql) throws SQLException {
        logSql(sql);

        return conn.prepareCall(sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        logSql(sql);

        return stmtCreator.apply(conn, sql);
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        logSql(parsedSql.sql());

        return conn.prepareCall(parsedSql.getParameterizedSql());
    }

    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        logSql(parsedSql.sql());

        return stmtCreator.apply(conn, parsedSql.getParameterizedSql());
    }

    /**
     *
     * @param conn
     * @param sql
     * @param parameters
     * @return
     * @throws SQLException
     */
    @SafeVarargs
    static PreparedStatement prepareStmt(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    @SafeVarargs
    static CallableStatement prepareCall(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    static PreparedStatement prepareBatchStmt(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    static CallableStatement prepareBatchCall(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    @SafeVarargs
    public static DataSet executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    @SafeVarargs
    public static DataSet executeQuery(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
    //     * @throws SQLException
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
     * @throws SQLException
     */
    @SafeVarargs
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    @SafeVarargs
    public static int executeUpdate(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
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
     * @throws SQLException
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
     */
    @SafeVarargs
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotNullOrEmpty(sql, "sql");

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
     * @throws SQLException
     */
    @SafeVarargs
    public static boolean execute(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotNullOrEmpty(sql, "sql");

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return JdbcUtil.execute(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    static ResultSet executeQuery(PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled()) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeQuery();
            } finally {
                logSqlPerf(stmt, sqlLogConfig, startTime);

                clearParameters(stmt);
            }
        } else {
            try {
                return stmt.executeQuery();
            } finally {
                clearParameters(stmt);
            }
        }
    }

    static int executeUpdate(PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled()) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeUpdate();
            } finally {
                logSqlPerf(stmt, sqlLogConfig, startTime);

                clearParameters(stmt);
            }
        } else {
            try {
                return stmt.executeUpdate();
            } finally {
                clearParameters(stmt);
            }
        }
    }

    static int[] executeBatch(Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled()) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeBatch();
            } finally {
                logSqlPerf(stmt, sqlLogConfig, startTime);

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

    static long executeLargeUpdate(PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled()) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeUpdate();
            } finally {
                logSqlPerf(stmt, sqlLogConfig, startTime);

                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeUpdate", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeUpdate();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeUpdate", e);
                }
            }
        }
    }

    static long[] executeLargeBatch(Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled()) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeBatch();
            } finally {
                logSqlPerf(stmt, sqlLogConfig, startTime);

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
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled()) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.execute();
            } finally {
                logSqlPerf(stmt, sqlLogConfig, startTime);

                clearParameters(stmt);
            }
        } else {
            try {
                return stmt.execute();
            } finally {
                clearParameters(stmt);
            }
        }
    }

    static void clearParameters(final PreparedStatement stmt) {
        if (stmt == null || stmt instanceof CallableStatement) {
            // no
        } else {
            try {
                stmt.clearParameters();
            } catch (SQLException e) {
                logger.error("Failed to clear parameters after executeUpdate", e);
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

        @SuppressWarnings("rawtypes")
        Type[] parameterTypes = null;
        Object[] parameterValues = null;

        if (isEntityOrMapParameter(parsedSql, parameters)) {
            final List<String> namedParameters = parsedSql.getNamedParameters();
            final Object parameter_0 = parameters[0];
            final Class<?> cls = parameter_0.getClass();

            parameterValues = new Object[parameterCount];

            if (ClassUtil.isEntity(cls)) {
                final Object entity = parameter_0;
                final EntityInfo entityInfo = ParserUtil.getEntityInfo(cls);
                parameterTypes = new Type[parameterCount];
                PropInfo propInfo = null;

                for (int i = 0; i < parameterCount; i++) {
                    propInfo = entityInfo.getPropInfo(namedParameters.get(i));

                    if (propInfo == null) {
                        throw new IllegalArgumentException(
                                "No property found with name: " + namedParameters.get(i) + " in class: " + ClassUtil.getCanonicalClassName(cls));
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

        final Class<? extends Object> cls = parameters[0].getClass();

        return ClassUtil.isEntity(cls) || ClassUtil.isRecord(cls) || Map.class.isAssignableFrom(cls) || EntityId.class.isAssignableFrom(cls);
    }

    static final RowFilter INTERNAL_DUMMY_ROW_FILTER = RowFilter.ALWAYS_TRUE;

    static final RowExtractor INTERNAL_DUMMY_ROW_EXTRACTOR = (rs, outputRow) -> {
        throw new UnsupportedOperationException("DO NOT CALL ME.");
    };

    /**
     *
     * @param rs
     * @return
     * @throws SQLException
     */
    public static DataSet extractData(final ResultSet rs) throws SQLException {
        return extractData(rs, false);
    }

    /**
     *
     * @param rs
     * @param closeResultSet
     * @return
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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
     * @throws SQLException
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

    static <R> R extractAndCloseResultSet(ResultSet rs, final ResultExtractor<R> resultExtrator) throws SQLException {
        try {
            return checkNotResultSet(resultExtrator.apply(rs));
        } finally {
            closeQuietly(rs);
        }
    }

    static <R> R extractAndCloseResultSet(ResultSet rs, final BiResultExtractor<R> resultExtrator) throws SQLException {
        try {
            return checkNotResultSet(resultExtrator.apply(rs, getColumnLabelList(rs)));
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
     *
     * @param resultSet
     * @return
     */
    public static ExceptionalStream<Object[], SQLException> stream(final ResultSet resultSet) {
        return stream(Object[].class, resultSet);
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
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
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
     *
     * @param <T>
     * @param resultSet
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final RowMapper<T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return ExceptionalStream.newStream(iterate(resultSet, rowMapper, null));
    }

    static <T> ExceptionalIterator<T, SQLException> iterate(final ResultSet resultSet, final RowMapper<T> rowMapper,
            final Throwables.Runnable<SQLException> onClose) {
        return new ExceptionalIterator<T, SQLException>() {
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
            public void advance(long n) throws SQLException {
                N.checkArgNotNegative(n, "n");

                final long m = hasNext ? n - 1 : n;

                JdbcUtil.skip(resultSet, m);

                hasNext = false;
            }

            @Override
            public long count() throws SQLException {
                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                while (resultSet.next()) {
                    cnt++;
                }

                return cnt;
            }

            @Override
            public void close() throws SQLException {
                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
     *
     * @param <T>
     * @param resultSet
     * @param rowFilter
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return ExceptionalStream.newStream(iterate(resultSet, rowFilter, rowMapper, null));
    }

    static <T> ExceptionalIterator<T, SQLException> iterate(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<T> rowMapper,
            final Throwables.Runnable<SQLException> onClose) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return new ExceptionalIterator<T, SQLException>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (hasNext == false) {
                    while (resultSet.next()) {
                        if (rowFilter.test(resultSet)) {
                            hasNext = true;
                            break;
                        }
                    }
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
            public void close() throws SQLException {
                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
     *
     * @param <T>
     * @param resultSet
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final BiRowMapper<T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return ExceptionalStream.newStream(iterate(resultSet, rowMapper, null));
    }

    static <T> ExceptionalIterator<T, SQLException> iterate(final ResultSet resultSet, final BiRowMapper<T> rowMapper,
            final Throwables.Runnable<SQLException> onClose) {
        return new ExceptionalIterator<T, SQLException>() {
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
            public void advance(long n) throws SQLException {
                N.checkArgNotNegative(n, "n");

                final long m = hasNext ? n - 1 : n;

                JdbcUtil.skip(resultSet, m);

                hasNext = false;
            }

            @Override
            public long count() throws SQLException {
                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                while (resultSet.next()) {
                    cnt++;
                }

                return cnt;
            }

            @Override
            public void close() throws SQLException {
                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
     *
     * @param <T>
     * @param resultSet
     * @param rowFilter
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> stream(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return ExceptionalStream.newStream(iterate(resultSet, rowFilter, rowMapper));
    }

    static <T> ExceptionalIterator<T, SQLException> iterate(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) {
        return new ExceptionalIterator<T, SQLException>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (columnLabels == null) {
                    columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                }

                if (hasNext == false) {
                    while (resultSet.next()) {
                        if (rowFilter.test(resultSet, columnLabels)) {
                            hasNext = true;
                            break;
                        }
                    }
                }

                return hasNext;
            }

            @Override
            public T next() throws SQLException {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                hasNext = false;

                return rowMapper.apply(resultSet, columnLabels);
            }
        };
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
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
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
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
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.streamAllResultSets(stmt, rowMapper).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param <T>
     * @param stmt
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> streamAllResultSets(final Statement stmt, final RowMapper<T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowMapper, "rowMapper");

        final Throwables.Supplier<ExceptionalIterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return ExceptionalStream.just(supplier, SQLException.class)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> ExceptionalStream.newStream(it.get()))
                .flatMap(rs -> JdbcUtil.stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    }

    /**
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.streamAllResultSets(stmt, rowFilter, rowMapper).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param <T>
     * @param stmt
     * @param rowFilter
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> streamAllResultSets(final Statement stmt, final RowFilter rowFilter, final RowMapper<T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        final Throwables.Supplier<ExceptionalIterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return ExceptionalStream.just(supplier, SQLException.class)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> ExceptionalStream.newStream(it.get()))
                .flatMap(rs -> JdbcUtil.stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    }

    /**
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.streamAllResultSets(stmt, rowMapper).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param <T>
     * @param stmt
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> streamAllResultSets(final Statement stmt, final BiRowMapper<T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowMapper, "rowMapper");

        final Throwables.Supplier<ExceptionalIterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return ExceptionalStream.just(supplier, SQLException.class)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> ExceptionalStream.newStream(it.get()))
                .flatMap(rs -> JdbcUtil.stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    }

    /**
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.streamAllResultSets(stmt, rowFilter, rowMapper).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param <T>
     * @param stmt
     * @param rowFilter
     * @param rowMapper
     * @return
     */
    public static <T> ExceptionalStream<T, SQLException> streamAllResultSets(final Statement stmt, final BiRowFilter rowFilter,
            final BiRowMapper<T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        final Throwables.Supplier<ExceptionalIterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return ExceptionalStream.just(supplier, SQLException.class)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> ExceptionalStream.newStream(it.get()))
                .flatMap(rs -> JdbcUtil.stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    }

    static ExceptionalIterator<ResultSet, SQLException> iterateAllResultSets(final Statement stmt) throws SQLException {
        return new ExceptionalIterator<ResultSet, SQLException>() {
            private final Holder<ResultSet> resultSetHolder = new Holder<>();
            private int updateCount = stmt.getUpdateCount();
            private boolean isNextResultSet = updateCount == -1 ? true : false;

            @Override
            public boolean hasNext() throws SQLException {
                if (resultSetHolder.isNull()) {
                    while (isNextResultSet || updateCount != -1) {
                        if (isNextResultSet) {
                            resultSetHolder.setValue(stmt.getResultSet());
                            isNextResultSet = false;
                            updateCount = 0; // for next loop.

                            break;
                        } else {
                            isNextResultSet = stmt.getMoreResults();
                            updateCount = stmt.getUpdateCount();
                        }
                    }
                }

                return resultSetHolder.isNotNull();
            }

            @Override
            public ResultSet next() throws SQLException {
                if (hasNext() == false) {
                    throw new NoSuchElementException();
                }

                return resultSetHolder.getAndSet(null);
            }

            @Override
            public void close() throws SQLException {
                if (resultSetHolder.isNotNull()) {
                    JdbcUtil.closeQuietly(resultSetHolder.getAndSet(null));
                }
            }
        };
    }

    static <R> R checkNotResultSet(R result) {
        if (result instanceof ResultSet) {
            throw new UnsupportedOperationException("The result value of ResultExtractor/BiResultExtractor.apply can't be ResultSet");
        }

        return result;
    }

    interface OutParameterGetter {

        Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException;

        Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException;
    }

    private static final Map<Integer, OutParameterGetter> sqlTypeGetterMap = new HashMap<>(Types.class.getDeclaredFields().length * 2);

    static {
        sqlTypeGetterMap.put(Types.BOOLEAN, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getBoolean(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getBoolean(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.BIT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getByte(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getByte(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.TINYINT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getByte(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getByte(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.SMALLINT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getShort(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getShort(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.INTEGER, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getInt(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getInt(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.BIGINT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getLong(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getLong(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.FLOAT, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getFloat(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getFloat(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.DOUBLE, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getDouble(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getDouble(outParameterName);
            }
        });

        sqlTypeGetterMap.put(Types.REAL, sqlTypeGetterMap.get(Types.DOUBLE));

        sqlTypeGetterMap.put(Types.NUMERIC, sqlTypeGetterMap.get(Types.BIGINT));

        sqlTypeGetterMap.put(Types.DECIMAL, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getBigDecimal(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getBigDecimal(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.CHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.VARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.LONGVARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.NCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.NVARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.LONGNVARCHAR, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNString(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNString(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.DATE, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getDate(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getDate(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.TIME, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getTime(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getTime(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.TIMESTAMP, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getTimestamp(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getTimestamp(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.BLOB, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getBlob(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getBlob(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.CLOB, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getClob(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getClob(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.NCLOB, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getNClob(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getNClob(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.ARRAY, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getArray(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getArray(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.REF, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getRef(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getRef(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.ROWID, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getRowId(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getRowId(outParameterName);
            }
        });
        sqlTypeGetterMap.put(Types.SQLXML, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getSQLXML(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getSQLXML(outParameterName);
            }
        });
    }

    private static final OutParameterGetter objOutParameterGetter = new OutParameterGetter() {
        @Override
        public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
            return stmt.getObject(outParameterIndex);
        }

        @Override
        public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
            return stmt.getObject(outParameterName);
        }
    };

    /**
     *
     * @param stmt
     * @param outParams
     * @return
     * @throws SQLException
     */
    public static OutParamResult getOutParameters(final CallableStatement stmt, final List<OutParam> outParams) throws SQLException {
        N.checkArgNotNull(stmt, "stmt");

        if (N.isNullOrEmpty(outParams)) {
            return new OutParamResult(N.<OutParam> emptyList(), N.<Object, Object> emptyMap());
        }

        final Map<Object, Object> outParamValues = new LinkedHashMap<>(outParams.size());
        OutParameterGetter outParameterGetter = null;
        Object key = null;
        Object value = null;

        for (OutParam outParam : outParams) {
            outParameterGetter = sqlTypeGetterMap.getOrDefault(outParam.getSqlType(), objOutParameterGetter);

            key = outParam.getParameterIndex() > 0 ? outParam.getParameterIndex() : outParam.getParameterName();

            value = outParam.getParameterIndex() > 0 ? outParameterGetter.getOutParameter(stmt, outParam.getParameterIndex())
                    : outParameterGetter.getOutParameter(stmt, outParam.getParameterName());

            if (value instanceof ResultSet) {
                final ResultSet rs = (ResultSet) value;

                try {
                    value = JdbcUtil.extractData(rs);
                } finally {
                    JdbcUtil.closeQuietly(rs);
                }
            } else if (value instanceof Blob) {
                Blob blob = (Blob) value;
                value = blob.getBytes(1, (int) blob.length());
            } else if (value instanceof Clob) {
                Clob clob = (Clob) value;
                value = clob.getSubString(1, (int) clob.length());
            } else if (value instanceof NClob) {
                NClob nclob = (NClob) value;
                value = nclob.getSubString(1, (int) nclob.length());
            }

            outParamValues.put(key, value);
        }

        return new OutParamResult(outParams, outParamValues);
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

    static boolean isSqlLogAllowed = true;

    public static void turnOffSqlLogGlobally() {
        isSqlLogAllowed = false;
    }

    static boolean isSqlPerfLogAllowed = true;

    public static void turnOffSqlPerfLogGlobally() {
        isSqlPerfLogAllowed = false;
    }

    static boolean isDaoMethodPerfLogAllowed = true;

    public static void turnOffDaoMethodPerfLogGlobally() {
        isDaoMethodPerfLogAllowed = false;
    }

    public static final int DEFAULT_MAX_SQL_LOG_LENGTH = 1024;

    static final ThreadLocal<SqlLogConfig> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> new SqlLogConfig(false, DEFAULT_MAX_SQL_LOG_LENGTH));

    /**
     * Enable/Disable sql log in current thread.
     *
     * @param b {@code true} to enable, {@code false} to disable.
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    public static void enableSqlLog(final boolean b) {
        enableSqlLog(b, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enable/Disable sql log in current thread.
     *
     * @param b {@code true} to enable, {@code false} to disable.
     * @param maxSqlLogLength default value is 1024
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    public static void enableSqlLog(final boolean b, final int maxSqlLogLength) {
        // synchronized (isSQLLogEnabled_TL) {
        if (logger.isDebugEnabled() && isSQLLogEnabled_TL.get().isEnabled != b) {
            if (b) {
                logger.debug("Turn on [SQL] log");
            } else {
                logger.debug("Turn off [SQL] log");
            }
        }

        isSQLLogEnabled_TL.get().set(b, maxSqlLogLength);
        // }
    }

    /**
     * Enable sql log in current thread.
     *
     */
    public static void enableSqlLog() {
        enableSqlLog(DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enable sql log in current thread.
     *
     * @param maxSqlLogLength default value is 1024
     */
    public static void enableSqlLog(final int maxSqlLogLength) {
        enableSqlLog(true, maxSqlLogLength);
    }

    /**
     * Disable sql log in current thread.
     *
     */
    public static void disableSqlLog() {
        enableSqlLog(false, isSQLLogEnabled_TL.get().maxSqlLogLength);
    }

    /**
     * Checks if sql log is enabled or not in current thread.
     *
     * @return {@code true} if it's enabled, otherwise {@code false} is returned.
     */
    public static boolean isSqlLogEnabled() {
        return isSQLLogEnabled_TL.get().isEnabled;
    }

    static void logSql(final String sql) {
        if (isSqlLogAllowed == false || sqlLogger.isDebugEnabled() == false) {
            return;
        }

        final SqlLogConfig sqlLogConfig = isSQLLogEnabled_TL.get();

        if (sqlLogConfig.isEnabled) {
            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.debug("[SQL]: " + sql);
            } else {
                sqlLogger.debug("[SQL]: " + sql.substring(0, sqlLogConfig.maxSqlLogLength));
            }
        }
    }

    // TODO is it right to do it?
    //    static <ST extends Statement> ST checkStatement(ST stmt, String sql) {
    //        if (isSqlPerfLogAllowed && N.notNullOrEmpty(sql)) {
    //            stmtPoolForSql.put(stmt, Poolable.wrap(sql, 3000, 3000));
    //        }
    //
    //        return stmt;
    //    }

    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG = 3000L;

    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG = 1000L;

    static final ThreadLocal<SqlLogConfig> minExecutionTimeForSqlPerfLog_TL = ThreadLocal
            .withInitial(() -> new SqlLogConfig(DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG, DEFAULT_MAX_SQL_LOG_LENGTH));

    /**
     * Set minimum execution time to log sql performance in current thread.
     *
     * @param minExecutionTimeForSqlPerfLog
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog) {
        setMinExecutionTimeForSqlPerfLog(minExecutionTimeForSqlPerfLog, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Set minimum execution time to log sql performance in current thread.
     *
     * @param minExecutionTimeForSqlPerfLog Default value is 1000 (milliseconds).
     * @param maxSqlLogLength default value is 1024
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        // synchronized (minExecutionTimeForSqlPerfLog_TL) {
        if (logger.isDebugEnabled() && minExecutionTimeForSqlPerfLog_TL.get().minExecutionTimeForSqlPerfLog != minExecutionTimeForSqlPerfLog) {
            if (minExecutionTimeForSqlPerfLog >= 0) {
                logger.debug("set 'minExecutionTimeForSqlPerfLog' to: " + minExecutionTimeForSqlPerfLog);
            } else {
                logger.debug("Turn off SQL perfermance log");
            }
        }

        minExecutionTimeForSqlPerfLog_TL.get().set(minExecutionTimeForSqlPerfLog, maxSqlLogLength);
        // }
    }

    /**
     * Return the minimum execution time in milliseconds to log SQL performance in current thread. Default value is 1000 (milliseconds).
     *
     * @return
     */
    public static long getMinExecutionTimeForSqlPerfLog() {
        return minExecutionTimeForSqlPerfLog_TL.get().minExecutionTimeForSqlPerfLog;
    }

    static void logSqlPerf(final Statement stmt, final SqlLogConfig sqlLogConfig, final long startTime) {
        if (isSqlPerfLogAllowed == false || sqlLogger.isInfoEnabled() == false) {
            return;
        }

        final long elapsedTime = System.currentTimeMillis() - startTime;

        if (elapsedTime >= sqlLogConfig.minExecutionTimeForSqlPerfLog) {
            final Function<Statement, String> sqlExtractor = N.defaultIfNull(JdbcUtil.sqlExtractor, JdbcUtil.DEFAULT_SQL_EXTRACTOR);
            final String sql = sqlExtractor.apply(stmt);

            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.info(StringUtil.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", sql));
            } else {
                sqlLogger.info(StringUtil.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", sql.substring(0, sqlLogConfig.maxSqlLogLength)));
            }
        }
    }

    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Don't share or share {@code Spring Transactional} in current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to not share, {@code false} to share it again.
     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
     */
    @Deprecated
    public static void disableSpringTransactional(boolean b) {
        doNotUseSpringTransactional(b);
    }

    /**
     * Don't share or share {@code Spring Transactional} in current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to not share, {@code false} to share it again.
     */
    public static void doNotUseSpringTransactional(boolean b) {
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
     * Check if {@code Spring Transactional} is shared or not in current thread.
     *
     * @return {@code true} if it's not share, otherwise {@code false} is returned.
     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
     */
    @Deprecated
    public static boolean isSpringTransactionalDisabled() {
        return isSpringTransactionalDisabled_TL.get();
    }

    /**
     * Check if {@code Spring Transactional} is shared or not in current thread.
     *
     * @return {@code true} if it's not share, otherwise {@code false} is returned.
     */
    public static boolean isSpringTransactionalNotUsed() {
        return isSpringTransactionalDisabled_TL.get();
    }

    /**
     * Since enable/disable sql log flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <E>
     * @param sqlAction
     * @throws E
     */
    public static <E extends Exception> void runWithSqlLogDisabled(final Throwables.Runnable<E> sqlAction) throws E {
        if (isSqlLogEnabled()) {
            disableSqlLog();

            try {
                sqlAction.run();
            } finally {
                enableSqlLog();
            }
        } else {
            sqlAction.run();
        }
    }

    /**
     * Since enable/disable sql log flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <R>
     * @param <E>
     * @param sqlAction
     * @return
     * @throws E
     */
    public static <R, E extends Exception> R callWithSqlLogDisabled(final Throwables.Callable<R, E> sqlAction) throws E {
        if (isSqlLogEnabled()) {
            disableSqlLog();

            try {
                return sqlAction.call();
            } finally {
                enableSqlLog();
            }
        } else {
            return sqlAction.call();
        }
    }

    /**
     * Since using or not using Spring transaction flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <E>
     * @param sqlAction
     * @throws E
     */
    public static <E extends Exception> void runWithoutUsingSpringTransaction(final Throwables.Runnable<E> sqlAction) throws E {
        if (isSpringTransactionalNotUsed()) {
            sqlAction.run();
        } else {
            doNotUseSpringTransactional(true);

            try {
                sqlAction.run();
            } finally {
                doNotUseSpringTransactional(false);
            }
        }
    }

    /**
     * Since using or not using Spring transaction flag is attached with current thread, so don't execute the specified {@code sqlAction} in another thread.
     *
     * @param <R>
     * @param <E>
     * @param sqlAction
     * @return
     * @throws E
     */
    public static <R, E extends Exception> R callWithoutUsingSpringTransaction(final Throwables.Callable<R, E> sqlAction) throws E {
        if (isSpringTransactionalNotUsed()) {
            return sqlAction.call();
        } else {
            doNotUseSpringTransactional(true);

            try {
                return sqlAction.call();
            } finally {
                doNotUseSpringTransactional(false);
            }
        }
    }

    static final Predicate<Object> defaultIdTester = JdbcUtil::isDefaultIdPropValue;

    /**
     * Checks if is default id prop value.
     *
     * @param value
     * @return true, if is default id prop value
     * @deprecated for internal only.
     */
    @Deprecated
    @Internal
    public static boolean isDefaultIdPropValue(final Object value) {
        if ((value == null) || (value instanceof Number && (((Number) value).longValue() == 0))) {
            return true;
        } else if (value instanceof EntityId) {
            return N.allMatch(((EntityId) value).entrySet(), it -> JdbcUtil.isDefaultIdPropValue(it.getValue()));
        } else if (ClassUtil.isEntity(value.getClass())) {
            final Class<?> entityClass = value.getClass();
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(entityClass);

            if (N.isNullOrEmpty(idPropNameList)) {
                return true;
            } else {
                final EntityInfo idEntityInfo = ParserUtil.getEntityInfo(entityClass);
                return N.allMatch(idPropNameList, idName -> JdbcUtil.isDefaultIdPropValue(idEntityInfo.getPropValue(value, idName)));
            }
        }

        return false;
    }

    static <ID> boolean isAllNullIds(final List<ID> ids) {
        return isAllNullIds(ids, defaultIdTester);
    }

    static <ID> boolean isAllNullIds(final List<ID> ids, final Predicate<Object> isDefaultIdTester) {
        return N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(isDefaultIdTester);
    }

    public static Collection<String> getInsertPropNames(final Object entity) {
        return getInsertPropNames(entity, null);
    }

    public static Collection<String> getInsertPropNames(final Object entity, final Set<String> excludedPropNames) {
        return QueryUtil.getInsertPropNames(entity, excludedPropNames);
    }

    public static Collection<String> getInsertPropNames(final Class<?> entityClass) {
        return getInsertPropNames(entityClass, null);
    }

    public static Collection<String> getInsertPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.getInsertPropNames(entityClass, excludedPropNames);
    }

    public static Collection<String> getSelectPropNames(final Class<?> entityClass) {
        return getSelectPropNames(entityClass, null);
    }

    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return getSelectPropNames(entityClass, false, excludedPropNames);
    }

    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final boolean includeSubEntityProperties,
            final Set<String> excludedPropNames) {
        return QueryUtil.getSelectPropNames(entityClass, includeSubEntityProperties, excludedPropNames);
    }

    public static Collection<String> getUpdatePropNames(final Class<?> entityClass) {
        return getUpdatePropNames(entityClass, null);
    }

    public static Collection<String> getUpdatePropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.getUpdatePropNames(entityClass, excludedPropNames);
    }

    public static Function<Statement, String> getSqlExtractor() {
        return sqlExtractor;
    }

    public static void setSqlExtractor(final Function<Statement, String> sqlExtractor) {
        JdbcUtil.sqlExtractor = sqlExtractor;
    }

    @Beta
    public static void run(final Throwables.Runnable<Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.run();
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T> void run(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.accept(t);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T, U> void run(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.accept(t, u);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <A, B, C> void run(final A a, final B b, final C c, final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.accept(a, b, c);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <R> R call(final Callable<R> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            return sqlAction.call();
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T, R> R call(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            return sqlAction.apply(t);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <T, U, R> R call(final T t, final U u, final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            return sqlAction.apply(t, u);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    @Beta
    public static <A, B, C, R> R call(final A a, final B b, final C c,
            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

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
        N.checkArgNotNull(sqlAction, "sqlAction");

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param sqlAction1
     * @param sqlAction2
     * @return
     */
    @Beta
    public static Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> asyncRun(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2) {
        N.checkArgNotNull(sqlAction1, "sqlAction1");
        N.checkArgNotNull(sqlAction2, "sqlAction2");

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param sqlAction1
     * @param sqlAction2
     * @param sqlAction3
     * @return
     */
    @Beta
    public static Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> asyncRun(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2, final Throwables.Runnable<Exception> sqlAction3) {
        N.checkArgNotNull(sqlAction1, "sqlAction1");
        N.checkArgNotNull(sqlAction2, "sqlAction2");
        N.checkArgNotNull(sqlAction3, "sqlAction3");

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
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
        N.checkArgNotNull(sqlAction, "sqlAction");

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
        N.checkArgNotNull(sqlAction, "sqlAction");

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
        N.checkArgNotNull(sqlAction, "sqlAction");

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
        N.checkArgNotNull(sqlAction, "sqlAction");

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R1>
     * @param <R2>
     * @param sqlAction1
     * @param sqlAction2
     * @return
     */
    @Beta
    public static <R1, R2> Tuple2<ContinuableFuture<R1>, ContinuableFuture<R2>> asyncCall(final Callable<R1> sqlAction1, final Callable<R2> sqlAction2) {
        N.checkArgNotNull(sqlAction1, "sqlAction1");
        N.checkArgNotNull(sqlAction2, "sqlAction2");

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R1>
     * @param <R2>
     * @param <R3>
     * @param sqlAction1
     * @param sqlAction2
     * @param sqlAction3
     * @return
     */
    @Beta
    public static <R1, R2, R3> Tuple3<ContinuableFuture<R1>, ContinuableFuture<R2>, ContinuableFuture<R3>> asyncCall(final Callable<R1> sqlAction1,
            final Callable<R2> sqlAction2, final Callable<R3> sqlAction3) {
        N.checkArgNotNull(sqlAction1, "sqlAction1");
        N.checkArgNotNull(sqlAction2, "sqlAction2");
        N.checkArgNotNull(sqlAction3, "sqlAction3");

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
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
        N.checkArgNotNull(sqlAction, "sqlAction");

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
        N.checkArgNotNull(sqlAction, "sqlAction");

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
        N.checkArgNotNull(sqlAction, "sqlAction");

        return asyncExecutor.execute(() -> sqlAction.apply(a, b, c));
    }

    static Map<String, Object> newRowHashMap(int columnCount) {
        return N.newHashMap(columnCount);
    }

    static Map<String, Object> newRowLinkedHashMap(int columnCount) {
        return N.newLinkedHashMap(columnCount);
    }

    /**
     * The Interface ParametersSetter.
     *
     * @param <QS>
     */
    @FunctionalInterface
    public interface ParametersSetter<QS> extends Throwables.Consumer<QS, SQLException> {
        @SuppressWarnings("rawtypes")
        ParametersSetter DO_NOTHING = new ParametersSetter<Object>() {
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
    public interface BiParametersSetter<QS, T> extends Throwables.BiConsumer<QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        BiParametersSetter DO_NOTHING = new BiParametersSetter<Object, Object>() {
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
    public interface TriParametersSetter<QS, T> extends Throwables.TriConsumer<ParsedSql, QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        TriParametersSetter DO_NOTHING = new TriParametersSetter<Object, Object>() {
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
    public interface ResultExtractor<T> extends Throwables.Function<ResultSet, T, SQLException> {

        ResultExtractor<DataSet> TO_DATA_SET = new ResultExtractor<DataSet>() {
            @Override
            public DataSet apply(final ResultSet rs) throws SQLException {
                if (rs == null) {
                    return N.newEmptyDataSet();
                }

                return JdbcUtil.extractData(rs);
            }
        };

        /**
         * In a lot of scenarios, including PreparedQuery/Dao/SQLExecutor, the input {@code ResultSet} will be closed after {@code apply(rs)} call. So don't save/return the input {@code ResultSet}.
         *
         * @param rs
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        default <R> ResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        default BiResultExtractor<T> toBiResultExtractor() {
            return BiResultExtractor.from(this);
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
         * @throws SQLException
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

        static <T> ResultExtractor<List<T>> toList(final RowMapper<T> rowMapper) {
            return toList(RowFilter.ALWAYS_TRUE, rowMapper);
        }

        static <T> ResultExtractor<List<T>> toList(final RowFilter rowFilter, RowMapper<T> rowMapper) {
            return new ResultExtractor<List<T>>() {
                @Override
                public List<T> apply(final ResultSet rs) throws SQLException {
                    final List<T> result = new ArrayList<>();

                    while (rs.next()) {
                        if (rowFilter.test(rs)) {
                            result.add(rowMapper.apply(rs));
                        }
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
    public interface BiResultExtractor<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        ResultExtractor<DataSet> TO_DATA_SET = new ResultExtractor<DataSet>() {
            @Override
            public DataSet apply(final ResultSet rs) throws SQLException {
                if (rs == null) {
                    return N.newEmptyDataSet();
                }

                return JdbcUtil.extractData(rs);
            }
        };

        /**
         * In a lot of scenarios, including PreparedQuery/Dao/SQLExecutor, the input {@code ResultSet} will be closed after {@code apply(rs)} call. So don't save/return the input {@code ResultSet}.
         *
         * @param rs
         * @param columnLabels
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        default <R> BiResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        static <R> BiResultExtractor<R> from(final ResultExtractor<R> resultExtractor) {
            N.checkArgNotNull(resultExtractor);

            return (rs, columnLabels) -> resultExtractor.apply(rs);
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
         * @throws SQLException
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
                public M apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
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

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiResultExtractor<List<T>> toList(final Class<T> targetClass) {
            return toList(BiRowMapper.to(targetClass));
        }

        static <T> BiResultExtractor<List<T>> toList(final BiRowMapper<T> rowMapper) {
            return toList(BiRowFilter.ALWAYS_TRUE, rowMapper);
        }

        static <T> BiResultExtractor<List<T>> toList(final BiRowFilter rowFilter, final BiRowMapper<T> rowMapper) {
            return new BiResultExtractor<List<T>>() {
                @Override
                public List<T> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final List<T> result = new ArrayList<>();

                    while (rs.next()) {
                        if (rowFilter.test(rs, columnLabels)) {
                            result.add(rowMapper.apply(rs, columnLabels));
                        }
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
    public interface RowMapper<T> extends Throwables.Function<ResultSet, T, SQLException> {

        @Override
        T apply(ResultSet rs) throws SQLException;

        default <R> RowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        default BiRowMapper<T> toBiRowMapper() {
            return BiRowMapper.from(this);
        }

        static <T, U> RowMapper<Tuple2<T, U>> combine(final RowMapper<T> rowMapper1, final RowMapper<U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs));
        }

        static <A, B, C> RowMapper<Tuple3<A, B, C>> combine(final RowMapper<A> rowMapper1, final RowMapper<B> rowMapper2, final RowMapper<C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");
            N.checkArgNotNull(rowMapper3, "rowMapper3");

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs), rowMapper3.apply(rs));
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param biRowMapper
         * @return
         * @deprecated because it's stateful and may be misused easily&frequently
         */
        @Deprecated
        @SequentialOnly
        @Stateful
        static <T> RowMapper<T> from(final BiRowMapper<T> biRowMapper) {
            N.checkArgNotNull(biRowMapper, "biRowMapper");

            return new RowMapper<T>() {
                private List<String> cls = null;

                @Override
                public T apply(ResultSet rs) throws SQLException {
                    if (cls == null) {
                        cls = JdbcUtil.getColumnLabelList(rs);
                    }

                    return biRowMapper.apply(rs, cls);
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<DisposableObjArray> toDisposableObjArray() {
            return new RowMapper<DisposableObjArray>() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    return disposable;
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<DisposableObjArray> toDisposableObjArray(final Class<?> entityClass) {
            N.checkArgNotNull(entityClass, "entityClass");

            return new RowMapper<DisposableObjArray>() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final EntityInfo entityInfo = ParserUtil.getEntityInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (N.isNullOrEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (N.notNullOrEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    return disposable;
                }
            };
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

        @SequentialOnly
        @Stateful
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

            Columns.ColumnGetter<?>[] initColumnGetter(final int columnCount) {
                final Columns.ColumnGetter<?>[] rsColumnGetters = new Columns.ColumnGetter<?>[columnCount];
                final Columns.ColumnGetter<?> defaultColumnGetter = columnGetterMap.get(0);

                for (int i = 0, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i + 1, defaultColumnGetter);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            @SequentialOnly
            @Stateful
            public RowMapper<Object[]> toArray() {
                // setDefaultColumnGetter();

                return new RowMapper<Object[]>() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                    @SuppressWarnings("hiding")
                    @Override
                    public Object[] apply(ResultSet rs) throws SQLException {
                        Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final Object[] row = new Object[rsColumnCount];

                        for (int i = 0; i < rsColumnCount; i++) {
                            row[i] = rsColumnGetters[i].apply(i + 1, rs);
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
            @SequentialOnly
            @Stateful
            public RowMapper<List<Object>> toList() {
                // setDefaultColumnGetter();

                return new RowMapper<List<Object>>() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                    @SuppressWarnings("hiding")
                    @Override
                    public List<Object> apply(ResultSet rs) throws SQLException {
                        Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        final List<Object> row = new ArrayList<>(rsColumnCount);

                        for (int i = 0; i < rsColumnCount; i++) {
                            row.add(rsColumnGetters[i].apply(i + 1, rs));
                        }

                        return row;
                    }
                };
            }

            /**
             * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
             *
             * @param <R>
             * @param finisher
             * @return
             */
            @SequentialOnly
            @Stateful
            public <R> RowMapper<R> to(final Throwables.Function<DisposableObjArray, R, SQLException> finisher) {
                return new RowMapper<R>() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;
                    private Object[] outputRow = null;
                    private DisposableObjArray output;

                    @SuppressWarnings("hiding")
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

                        for (int i = 0; i < rsColumnCount; i++) {
                            outputRow[i] = rsColumnGetters[i].apply(i + 1, rs);
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
    public interface BiRowMapper<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

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
                final Map<String, Object> result = JdbcUtil.newRowHashMap(columnCount);

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
                final Map<String, Object> result = JdbcUtil.newRowLinkedHashMap(columnCount);

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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @return
         * @deprecated because it's stateful and may be misused easily&frequently
         */
        @Deprecated
        @Stateful
        default RowMapper<T> toRowMapper() {
            return RowMapper.from(this);
        }

        static <T> BiRowMapper<T> from(final RowMapper<T> rowMapper) {
            N.checkArgNotNull(rowMapper, "rowMapper");

            return (rs, columnLabels) -> rowMapper.apply(rs);
        }

        static <T, U> BiRowMapper<Tuple2<T, U>> combine(final BiRowMapper<T> rowMapper1, final BiRowMapper<U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls));
        }

        static <A, B, C> BiRowMapper<Tuple3<A, B, C>> combine(final BiRowMapper<A> rowMapper1, final BiRowMapper<B> rowMapper2,
                final BiRowMapper<C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");
            N.checkArgNotNull(rowMapper3, "rowMapper3");

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls), rowMapper3.apply(rs, cls));
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
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
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
            return to(targetClass, Fn.alwaysTrue(), Fn.identity(), ignoreNonMatchedColumns);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @param columnNameFilter
         * @param columnNameConverter
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass, final Predicate<? super String> columnNameFilter,
                final Function<? super String, String> columnNameConverter) {
            return to(targetClass, columnNameFilter, columnNameConverter, false);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @param columnNameFilter
         * @param columnNameConverter
         * @param ignoreNonMatchedColumns
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(Class<? extends T> targetClass, final Predicate<? super String> columnNameFilter,
                final Function<? super String, String> columnNameConverter, final boolean ignoreNonMatchedColumns) {
            N.checkArgNotNull(targetClass, "targetClass");

            final Predicate<? super String> columnNameFilterToBeUsed = columnNameFilter == null ? Fn.alwaysTrue() : columnNameFilter;
            final Function<? super String, String> columnNameConverterToBeUsed = columnNameConverter == null ? Fn.identity() : columnNameConverter;

            if (Object[].class.isAssignableFrom(targetClass)) {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
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
                } else {
                    return new BiRowMapper<T>() {
                        private volatile String[] columnLabels = null;

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            final int columnCount = columnLabelList.size();
                            String[] columnLabels = this.columnLabels;

                            if (columnLabels == null) {
                                columnLabels = columnLabelList.toArray(new String[columnCount]);

                                for (int i = 0; i < columnCount; i++) {
                                    if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                        columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);
                                    } else {
                                        columnLabels[i] = null;
                                    }
                                }

                                this.columnLabels = columnLabels;
                            }

                            final Object[] a = Array.newInstance(targetClass.getComponentType(), columnCount);

                            for (int i = 0; i < columnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                a[i] = getColumnValue(rs, i + 1);
                            }

                            return (T) a;
                        }
                    };
                }
            } else if (List.class.isAssignableFrom(targetClass)) {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
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
                } else {
                    return new BiRowMapper<T>() {
                        private final boolean isListOrArrayList = targetClass.equals(List.class) || targetClass.equals(ArrayList.class);
                        private volatile String[] columnLabels = null;

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            final int columnCount = columnLabelList.size();
                            String[] columnLabels = this.columnLabels;

                            if (columnLabels == null) {
                                columnLabels = columnLabelList.toArray(new String[columnCount]);

                                for (int i = 0; i < columnCount; i++) {
                                    if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                        columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);
                                    } else {
                                        columnLabels[i] = null;
                                    }
                                }

                                this.columnLabels = columnLabels;
                            }

                            final List<Object> c = isListOrArrayList ? new ArrayList<>(columnCount) : (List<Object>) N.newInstance(targetClass);

                            for (int i = 0; i < columnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                c.add(getColumnValue(rs, i + 1));
                            }

                            return (T) c;
                        }
                    };
                }
            } else if (Map.class.isAssignableFrom(targetClass)) {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
                    return new BiRowMapper<T>() {
                        private final boolean isMapOrHashMap = targetClass.equals(Map.class) || targetClass.equals(HashMap.class);
                        private final boolean isLinkedHashMap = targetClass.equals(LinkedHashMap.class);
                        private volatile String[] columnLabels = null;

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            final int columnCount = columnLabelList.size();
                            String[] columnLabels = this.columnLabels;

                            if (columnLabels == null) {
                                columnLabels = columnLabelList.toArray(new String[columnCount]);
                                this.columnLabels = columnLabels;
                            }

                            final Map<String, Object> m = isMapOrHashMap ? JdbcUtil.newRowHashMap(columnCount)
                                    : (isLinkedHashMap ? JdbcUtil.newRowLinkedHashMap(columnCount) : (Map<String, Object>) N.newInstance(targetClass));

                            for (int i = 0; i < columnCount; i++) {
                                m.put(columnLabels[i], getColumnValue(rs, i + 1));
                            }

                            return (T) m;
                        }
                    };
                } else {
                    return new BiRowMapper<T>() {
                        private final boolean isMapOrHashMap = targetClass.equals(Map.class) || targetClass.equals(HashMap.class);
                        private final boolean isLinkedHashMap = targetClass.equals(LinkedHashMap.class);
                        private volatile String[] columnLabels = null;

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            final int columnCount = columnLabelList.size();
                            String[] columnLabels = this.columnLabels;

                            if (columnLabels == null) {
                                columnLabels = columnLabelList.toArray(new String[columnCount]);

                                for (int i = 0; i < columnCount; i++) {
                                    if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                        columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);
                                    } else {
                                        columnLabels[i] = null;
                                    }
                                }

                                this.columnLabels = columnLabels;
                            }

                            final Map<String, Object> m = isMapOrHashMap ? JdbcUtil.newRowHashMap(columnCount)
                                    : (isLinkedHashMap ? JdbcUtil.newRowLinkedHashMap(columnCount) : (Map<String, Object>) N.newInstance(targetClass));

                            for (int i = 0; i < columnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                m.put(columnLabels[i], getColumnValue(rs, i + 1));
                            }

                            return (T) m;
                        }
                    };
                }
            } else if (ClassUtil.isEntity(targetClass)) {
                return new BiRowMapper<T>() {
                    private final EntityInfo entityInfo = ParserUtil.getEntityInfo(targetClass);
                    private volatile String[] columnLabels = null;
                    private volatile PropInfo[] propInfos;
                    private volatile Type<?>[] columnTypes = null;

                    @SuppressWarnings("hiding")
                    @Override
                    public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                        final int columnCount = columnLabelList.size();

                        String[] columnLabels = this.columnLabels;
                        PropInfo[] propInfos = this.propInfos;
                        Type<?>[] columnTypes = this.columnTypes;

                        if (columnLabels == null) {
                            final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(targetClass);

                            columnLabels = columnLabelList.toArray(new String[columnCount]);
                            propInfos = new PropInfo[columnCount];
                            columnTypes = new Type[columnCount];

                            for (int i = 0; i < columnCount; i++) {
                                if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                    columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);

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
                                        columnTypes[i] = propInfos[i].dbType;
                                    }
                                } else {
                                    columnLabels[i] = null;
                                    propInfos[i] = null;
                                    columnTypes[i] = null;
                                }
                            }

                            this.columnLabels = columnLabels;
                            this.propInfos = propInfos;
                            this.columnTypes = columnTypes;
                        }

                        final Object result = entityInfo.createEntityResult();

                        for (int i = 0; i < columnCount; i++) {
                            if (columnLabels[i] == null) {
                                continue;
                            }

                            propInfos[i].setPropValue(result, columnTypes[i].get(rs, i + 1));
                        }

                        return entityInfo.finishEntityResult(result);
                    }
                };
            } else {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
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
                } else {
                    throw new IllegalArgumentException(
                            "'columnNameFilter' and 'columnNameConverter' are not supported to convert single column to target type: " + targetClass);
                }
            }
        }

        static BiRowMapper<Map<String, Object>> toMap(final Predicate<Object> valueFilter) {
            return new BiRowMapper<Map<String, Object>>() {
                @Override
                public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();
                    final Map<String, Object> result = JdbcUtil.newRowHashMap(columnCount);

                    Object value = null;

                    for (int i = 1; i <= columnCount; i++) {
                        value = JdbcUtil.getColumnValue(rs, i);

                        if (valueFilter.test(value)) {
                            result.put(columnLabels.get(i - 1), value);
                        }
                    }

                    return result;
                }
            };
        }

        static BiRowMapper<Map<String, Object>> toMap(final BiPredicate<String, Object> valueFilter, final IntFunction<Map<String, Object>> mapSupplier) {
            return new BiRowMapper<Map<String, Object>>() {
                @Override
                public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();
                    final Map<String, Object> result = mapSupplier.apply(columnCount);

                    String columnName = null;
                    Object value = null;

                    for (int i = 1; i <= columnCount; i++) {
                        columnName = columnLabels.get(i - 1);
                        value = JdbcUtil.getColumnValue(rs, i);

                        if (valueFilter.test(columnName, value)) {
                            result.put(columnName, value);
                        }
                    }

                    return result;
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param columnNameConverter
         * @return
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final Function<? super String, String> columnNameConverter) {
            return toMap(columnNameConverter, IntFunctions.<String, Object> ofMap());
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param columnNameConverter
         * @param mapSupplier
         * @return
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final Function<? super String, String> columnNameConverter,
                final IntFunction<Map<String, Object>> mapSupplier) {
            return new BiRowMapper<Map<String, Object>>() {
                private String[] keyNames = null;

                @Override
                public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (keyNames == null) {
                        keyNames = new String[columnLabels.size()];

                        for (int i = 0, size = columnLabels.size(); i < size; i++) {
                            keyNames[i] = columnNameConverter.apply(columnLabels.get(i));
                        }
                    }

                    final int columnCount = keyNames.length;
                    final Map<String, Object> result = mapSupplier.apply(columnCount);

                    for (int i = 1; i <= columnCount; i++) {
                        result.put(keyNames[i - 1], JdbcUtil.getColumnValue(rs, i));
                    }

                    return result;
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowMapper<DisposableObjArray> toDisposableObjArray() {
            return new BiRowMapper<DisposableObjArray>() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    return disposable;
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowMapper<DisposableObjArray> toDisposableObjArray(final Class<?> entityClass) {
            N.checkArgNotNull(entityClass, "entityClass");

            return new BiRowMapper<DisposableObjArray>() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final EntityInfo entityInfo = ParserUtil.getEntityInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (N.isNullOrEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (N.notNullOrEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    return disposable;
                }
            };
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

        @SequentialOnly
        @Stateful
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
                final Columns.ColumnGetter<?>[] rsColumnGetters = new Columns.ColumnGetter<?>[rsColumnCount];

                int cnt = 0;
                Columns.ColumnGetter<?> columnGetter = null;

                for (int i = 0; i < rsColumnCount; i++) {
                    columnGetter = columnGetterMap.get(columnLabelList.get(i));

                    if (columnGetter != null) {
                        cnt++;
                    }

                    rsColumnGetters[i] = columnGetter == null ? defaultColumnGetter : columnGetter;
                }

                if (cnt < columnGetterMap.size()) {
                    final List<String> tmp = new ArrayList<>(columnGetterMap.keySet());
                    tmp.removeAll(columnLabelList);
                    throw new IllegalArgumentException("ColumnGetters for " + tmp + " are not found in ResultSet columns: " + columnLabelList);
                }

                return rsColumnGetters;
            }

            /**
             * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
             *
             * @param <T>
             * @param targetClass
             * @return
             */
            @SequentialOnly
            @Stateful
            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
                return to(targetClass, false);
            }

            /**
             * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
             *
             * @param <T>
             * @param targetClass
             * @param ignoreNonMatchedColumns
             * @return
             */
            @SequentialOnly
            @Stateful
            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
                if (Object[].class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final Object[] a = Array.newInstance(targetClass.getComponentType(), rsColumnCount);

                            for (int i = 0; i < rsColumnCount; i++) {
                                a[i] = rsColumnGetters[i].apply(i + 1, rs);
                            }

                            return (T) a;
                        }
                    };
                } else if (List.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final boolean isListOrArrayList = targetClass.equals(List.class) || targetClass.equals(ArrayList.class);

                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;
                            }

                            final List<Object> c = isListOrArrayList ? new ArrayList<>(rsColumnCount) : (List<Object>) N.newInstance(targetClass);

                            for (int i = 0; i < rsColumnCount; i++) {
                                c.add(rsColumnGetters[i].apply(i + 1, rs));
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

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                                this.rsColumnGetters = rsColumnGetters;

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                            }

                            final Map<String, Object> m = isMapOrHashMap ? JdbcUtil.newRowHashMap(rsColumnCount)
                                    : (isLinkedHashMap ? JdbcUtil.newRowLinkedHashMap(rsColumnCount) : (Map<String, Object>) N.newInstance(targetClass));

                            for (int i = 0; i < rsColumnCount; i++) {
                                m.put(columnLabels[i], rsColumnGetters[i].apply(i + 1, rs));
                            }

                            return (T) m;
                        }
                    };
                } else if (ClassUtil.isEntity(targetClass)) {
                    return new BiRowMapper<T>() {
                        private final EntityInfo entityInfo = ParserUtil.getEntityInfo(targetClass);

                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;
                        private volatile String[] columnLabels = null;
                        private volatile PropInfo[] propInfos;

                        @SuppressWarnings("hiding")
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
                                        if (rsColumnGetters[i] == Columns.ColumnGetter.GET_OBJECT) {
                                            rsColumnGetters[i] = Columns.ColumnGetter.get(propInfos[i].dbType);
                                        }
                                    }
                                }

                                this.propInfos = propInfos;
                            }

                            final Object result = entityInfo.createEntityResult();

                            for (int i = 0; i < rsColumnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                propInfos[i].setPropValue(result, rsColumnGetters[i].apply(i + 1, rs));
                            }

                            return entityInfo.finishEntityResult(result);
                        }
                    };
                } else {
                    return new BiRowMapper<T>() {
                        private volatile int rsColumnCount = -1;
                        private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                        @SuppressWarnings("hiding")
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);

                                if (rsColumnGetters[0] == Columns.ColumnGetter.GET_OBJECT) {
                                    rsColumnGetters[0] = Columns.ColumnGetter.get(N.typeOf(targetClass));
                                }

                                this.rsColumnGetters = rsColumnGetters;
                            }

                            if (rsColumnCount != 1 && (rsColumnCount = columnLabelList.size()) != 1) {
                                throw new IllegalArgumentException(
                                        "It's not supported to retrieve value from multiple columns: " + columnLabelList + " for type: " + targetClass);
                            }

                            return (T) rsColumnGetters[0].apply(1, rs);
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
    public interface RowConsumer extends Throwables.Consumer<ResultSet, SQLException> {

        RowConsumer DO_NOTHING = rs -> {
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

        default BiRowConsumer toBiRowConsumer() {
            return BiRowConsumer.from(this);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer oneOff(final Consumer<DisposableObjArray> consumer) {
            N.checkArgNotNull(consumer, "consumer");

            return new RowConsumer() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                @Override
                public void accept(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    consumer.accept(disposable);
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer oneOff(final Class<?> entityClass, final Consumer<DisposableObjArray> consumer) {
            N.checkArgNotNull(entityClass, "entityClass");
            N.checkArgNotNull(consumer, "consumer");

            return new RowConsumer() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public void accept(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final EntityInfo entityInfo = ParserUtil.getEntityInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (N.isNullOrEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (N.notNullOrEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    consumer.accept(disposable);
                }
            };
        }
    }

    /**
     * The Interface BiRowConsumer.
     */
    @FunctionalInterface
    public interface BiRowConsumer extends Throwables.BiConsumer<ResultSet, List<String>, SQLException> {

        BiRowConsumer DO_NOTHING = (rs, cls) -> {
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

        static BiRowConsumer from(final RowConsumer rowConsumer) {
            N.checkArgNotNull(rowConsumer, "rowConsumer");

            return (rs, columnLabels) -> rowConsumer.accept(rs);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowConsumer oneOff(final BiConsumer<List<String>, DisposableObjArray> consumer) {
            N.checkArgNotNull(consumer, "consumer");

            return new BiRowConsumer() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                @Override
                public void accept(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = columnLabels.size();
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    consumer.accept(columnLabels, disposable);
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowConsumer oneOff(final Class<?> entityClass, final BiConsumer<List<String>, DisposableObjArray> consumer) {
            N.checkArgNotNull(entityClass, "entityClass");
            N.checkArgNotNull(consumer, "consumer");

            return new BiRowConsumer() {
                private volatile DisposableObjArray disposable = null;
                private int columnCount = 0;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public void accept(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final EntityInfo entityInfo = ParserUtil.getEntityInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (N.isNullOrEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (N.notNullOrEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    consumer.accept(columnLabels, disposable);
                }
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
    public interface RowFilter extends Throwables.Predicate<ResultSet, SQLException> {

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

        default BiRowFilter toBiRowFilter() {
            return BiRowFilter.from(this);
        }
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     *
     */
    @FunctionalInterface
    public interface BiRowFilter extends Throwables.BiPredicate<ResultSet, List<String>, SQLException> {

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

        static BiRowFilter from(final RowFilter rowFilter) {
            N.checkArgNotNull(rowFilter, "rowFilter");

            return (rs, columnLabels) -> rowFilter.test(rs);
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
    public interface RowExtractor extends Throwables.BiConsumer<ResultSet, Object[], SQLException> {
        @Override
        void accept(final ResultSet rs, final Object[] outputRow) throws SQLException;

        static RowExtractorBuilder builder() {
            return builder(Columns.ColumnGetter.GET_OBJECT);
        }

        static RowExtractorBuilder builder(final Columns.ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        @SequentialOnly
        @Stateful
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

            Columns.ColumnGetter<?>[] initColumnGetter(final int columnCount) {
                final Columns.ColumnGetter<?>[] rsColumnGetters = new Columns.ColumnGetter<?>[columnCount];
                final Columns.ColumnGetter<?> defaultColumnGetter = columnGetterMap.get(0);

                for (int i = 0, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i + 1, defaultColumnGetter);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowExtractor} instance.
             *
             * @return
             */
            @SequentialOnly
            @Stateful
            public RowExtractor build() {
                return new RowExtractor() {
                    private volatile int rsColumnCount = -1;
                    private volatile Columns.ColumnGetter<?>[] rsColumnGetters = null;

                    @SuppressWarnings("hiding")
                    @Override
                    public void accept(final ResultSet rs, final Object[] outputRow) throws SQLException {
                        Columns.ColumnGetter<?>[] rsColumnGetters = this.rsColumnGetters;

                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(outputRow.length);
                            rsColumnCount = rsColumnGetters.length - 1;
                            this.rsColumnGetters = rsColumnGetters;
                        }

                        for (int i = 0; i < rsColumnCount; i++) {
                            outputRow[i] = rsColumnGetters[i].apply(i + 1, rs);
                        }
                    }
                };
            }
        }
    }

    @Beta
    public interface Handler<P> {
        /**
         *
         * @param proxy
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        @SuppressWarnings("unused")
        default void beforeInvoke(final P proxy, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }

        /**
         *
         * @param <R>
         * @param result
         * @param proxy
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        @SuppressWarnings("unused")
        default void afterInvoke(final Object result, final P proxy, final Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Data
    public static class OutParam {
        private int parameterIndex;
        private String parameterName;
        private int sqlType;
        private String typeName;
        private int scale;
    }

    @EqualsAndHashCode
    @ToString
    public static class OutParamResult {
        private final List<OutParam> outParams;
        private final Map<Object, Object> outParamValues;

        OutParamResult(final List<OutParam> outParams, final Map<Object, Object> outParamValues) {
            this.outParams = outParams;
            this.outParamValues = outParamValues;
        }

        public <T> T getOutParamValue(final int parameterIndex) {
            return (T) outParamValues.get(parameterIndex);
        }

        public <T> T getOutParamValue(final String parameterName) {
            return (T) outParamValues.get(parameterName);
        }

        public Map<Object, Object> getOutParamValues() {
            return outParamValues;
        }

        public List<OutParam> getOutParams() {
            return outParams;
        }
    }

    static Object[] getParameterArray(final SP sp) {
        return N.isNullOrEmpty(sp.parameters) ? N.EMPTY_OBJECT_ARRAY : sp.parameters.toArray();
    }

    static <R> BiRowMapper<R> toBiRowMapper(final RowMapper<R> rowMapper) {
        return (rs, columnLabels) -> rowMapper.apply(rs);
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Tuple2<Class<?>, Class<?>>, Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, Function, BiConsumer> noIdGeneratorGetterSetter = Tuple.of(NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null,
            BiConsumers.doNothing());

    @SuppressWarnings({ "rawtypes", "deprecation", "null" })
    static <ID> Tuple3<BiRowMapper<ID>, Function<Object, ID>, BiConsumer<ID, Object>> getIdGeneratorGetterSetter(final Class<? extends Dao> daoInterface,
            final Class<?> entityClass, final NamingPolicy namingPolicy, final Class<?> idType) {
        if (entityClass == null || ClassUtil.isEntity(entityClass) == false) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        final Tuple2<Class<?>, Class<?>> key = Tuple.of(entityClass, idType);

        Map<NamingPolicy, Tuple3<BiRowMapper, Function, BiConsumer>> map = idGeneratorGetterSetterPool.get(key);

        if (map == null) {
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(entityClass);
            final boolean isNoId = N.isNullOrEmpty(idPropNameList) || QueryUtil.isFakeId(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final EntityInfo entityInfo = isNoId ? null : ParserUtil.getEntityInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = isNoId ? false : idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);
            final EntityInfo idEntityInfo = idType != null && ClassUtil.isEntity(idType) ? ParserUtil.getEntityInfo(idType) : null;

            final Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? entity -> idPropInfo.getPropValue(entity) //
                            : (isEntityId ? entity -> {
                                final Seid ret = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (PropInfo propInfo : idPropInfoList) {
                                    ret.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            } : entity -> {
                                final Object ret = idEntityInfo.createEntityResult();

                                for (PropInfo propInfo : idPropInfoList) {
                                    ClassUtil.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) idEntityInfo.finishEntityResult(ret);
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
                final ImmutableMap<String, String> propColumnNameMap = QueryUtil.getProp2ColumnNameMap(entityClass, namingPolicy);

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

                                                for (int i = 0; i < columnCount; i++) {
                                                    columnName = columnLabels.get(i);

                                                    if ((propName = columnPropNameMap.get(columnName)) == null
                                                            || (propInfo = entityInfo.getPropInfo(propName)) == null) {
                                                        id.set(columnName, getColumnValue(rs, i + 1));
                                                    } else {
                                                        id.set(propInfo.name, propInfo.dbType.get(rs, i + 1));
                                                    }
                                                }

                                                return id;
                                            } else {
                                                final List<Tuple2<String, PropInfo>> tpList = StreamEx.of(columnLabels)
                                                        .filter(it -> idEntityInfo.getPropInfo(it) != null)
                                                        .map(it -> Tuple.of(it, idEntityInfo.getPropInfo(it)))
                                                        .toList();
                                                final Object id = idEntityInfo.createEntityResult();

                                                for (Tuple2<String, PropInfo> tp : tpList) {
                                                    tp._2.setPropValue(id, tp._2.dbType.get(rs, tp._1));
                                                }

                                                return idEntityInfo.finishEntityResult(id);
                                            }
                                        }));

                map.put(np, Tuple.of(keyExtractor, idGetter, idSetter));
            }

            idGeneratorGetterSetterPool.put(key, map);
        }

        return (Tuple3) map.get(namingPolicy);
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

    //    @SuppressWarnings("rawtypes")
    //    static Class<?> getTargetEntityClass(final Class<? extends Dao> daoInterface) {
    //        if (N.notNullOrEmpty(daoInterface.getGenericInterfaces()) && daoInterface.getGenericInterfaces()[0] instanceof ParameterizedType) {
    //            final ParameterizedType parameterizedType = (ParameterizedType) daoInterface.getGenericInterfaces()[0];
    //            java.lang.reflect.Type[] typeArguments = parameterizedType.getActualTypeArguments();
    //
    //            if (typeArguments.length >= 1 && typeArguments[0] instanceof Class) {
    //                if (!ClassUtil.isEntity((Class) typeArguments[0])) {
    //                    throw new IllegalArgumentException(
    //                            "Entity Type parameter of Dao interface must be: Object.class or entity class with getter/setter methods. Can't be: "
    //                                    + typeArguments[0]);
    //                }
    //
    //                return (Class) typeArguments[0];
    //            }
    //        }
    //
    //        throw new IllegalArgumentException("Invalid Dao interface: " + daoInterface + ". No entity class found by type parameter");
    //    }
    //
    //    @SuppressWarnings("rawtypes")
    //    static final Map<javax.sql.DataSource, Map<Class<?>, Dao>> dsEntityDaoPool = new IdentityHashMap<>();

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) {
        return createDao(daoInterface, ds, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper) {
        return createDao(daoInterface, ds, sqlMapper, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param cache don't share cache between Dao instances.
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Cache<String, Object> cache) {
        return createDao(daoInterface, ds, sqlMapper, cache, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final Executor executor) {
        return createDao(daoInterface, ds, null, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Executor executor) {
        return createDao(daoInterface, ds, sqlMapper, null, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param ds
     * @param sqlMapper
     * @param cache don't share cache between Dao instances.
     * @param executor
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Cache<String, Object> cache, final Executor executor) {
        final TD dao = DaoImpl.createDao(daoInterface, ds, sqlMapper, cache, executor);

        //    synchronized (dsEntityDaoPool) {
        //        @SuppressWarnings("rawtypes")
        //        Map<Class<?>, Dao> entityDaoPool = dsEntityDaoPool.get(ds);
        //
        //        if (entityDaoPool == null) {
        //            entityDaoPool = new HashMap<>();
        //            dsEntityDaoPool.put(ds, entityDaoPool);
        //        }
        //
        //        entityDaoPool.put(getTargetEntityClass(daoInterface), dao);
        //    }

        return dao;
    }

    //    /**
    //     *
    //     * @param ds
    //     * @param targetEntityOrDaoClass
    //     */
    //    public static void removeCachedDao(final javax.sql.DataSource ds, final Class<?> targetEntityOrDaoClass) {
    //        N.checkArgNotNull(ds, "dataSource");
    //        N.checkArgNotNull(targetEntityOrDaoClass, "targetEntityOrDaoClass");
    //
    //        @SuppressWarnings("rawtypes")
    //        final Class<?> targetEntityClass = Dao.class.isAssignableFrom(targetEntityOrDaoClass)
    //                ? getTargetEntityClass((Class<? extends Dao>) targetEntityOrDaoClass)
    //                : targetEntityOrDaoClass;
    //
    //        synchronized (dsEntityDaoPool) {
    //            @SuppressWarnings("rawtypes")
    //            Map<Class<?>, Dao> entityDaoPool = dsEntityDaoPool.get(ds);
    //
    //            if (entityDaoPool != null) {
    //                entityDaoPool.remove(targetEntityClass);
    //
    //                if (N.isNullOrEmpty(entityDaoPool)) {
    //                    dsEntityDaoPool.remove(ds);
    //                }
    //            }
    //        }
    //    }

    public static String generateEntityClass(final DataSource ds, final String tableName) {
        return CodeGenerationUtil.generateEntityClass(ds, tableName);
    }

    public static String generateEntityClass(final Connection conn, final String tableName) {
        return CodeGenerationUtil.generateEntityClass(conn, tableName);
    }

    public static String generateEntityClass(final DataSource ds, final String tableName, final EntityCodeConfig config) {
        return CodeGenerationUtil.generateEntityClass(ds, tableName, config);
    }

    public static String generateEntityClass(final Connection conn, final String tableName, final EntityCodeConfig config) {
        return CodeGenerationUtil.generateEntityClass(conn, tableName, config);
    }

    public static boolean isNullOrDefault(final Object value) {
        return (value == null) || N.equals(value, N.defaultValueOf(value.getClass()));
    }
}
