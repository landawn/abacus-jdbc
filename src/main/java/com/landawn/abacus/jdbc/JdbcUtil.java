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
package com.landawn.abacus.jdbc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.cache.Cache;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiParametersSetter;
import com.landawn.abacus.jdbc.Jdbc.BiResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.BiRowFilter;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.OutParam;
import com.landawn.abacus.jdbc.Jdbc.OutParamResult;
import com.landawn.abacus.jdbc.Jdbc.ResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowExtractor;
import com.landawn.abacus.jdbc.Jdbc.RowFilter;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.jdbc.SQLTransaction.CreatedBy;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.Charsets;
import com.landawn.abacus.util.CheckedStream;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.BiConsumers;
import com.landawn.abacus.util.Fn.Fnn;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.InternalUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.ObjectPool;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.QueryUtil;
import com.landawn.abacus.util.RowDataSet;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.SQLMapper;
import com.landawn.abacus.util.SQLOperation;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Splitter;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.function.TriConsumer;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 *
 * Performance Tips:
 * <li>Avoid unnecessary/repeated database calls.</li>
 * <li>Only fetch the columns you need or update the columns you want.</li>
 * <li>Index is the key point in a lot of database performance issues.</li>
 *
 * <br />
 *
 * @see {@link com.landawn.abacus.condition .ConditionFactory}
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
@SuppressWarnings({ "java:S1192", "java:S6539" })
public final class JdbcUtil {

    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    static final Logger sqlLogger = LoggerFactory.getLogger("com.landawn.abacus.SQL");

    static final char CHAR_ZERO = 0;

    public static final int DEFAULT_BATCH_SIZE = 200;

    // static final int MAX_BATCH_SIZE = 1000;

    public static final int DEFAULT_FETCH_SIZE_FOR_BIG_RESULT = 1000;

    public static final int DEFAULT_FETCH_SIZE_FOR_STREAM = 100;

    public static final Throwables.Function<Statement, String, SQLException> DEFAULT_SQL_EXTRACTOR = stmt -> {
        Statement stmtToUse = stmt;
        String clsName = stmtToUse.getClass().getName();

        if ((clsName.startsWith("com.zaxxer.hikari") || clsName.startsWith("com.mchange.v2.c3p0")) && stmt.isWrapperFor(Statement.class)) {
            stmtToUse = stmt.unwrap(Statement.class);
            clsName = stmtToUse.getClass().getName();
        }

        if (clsName.startsWith("oracle.jdbc")) {
            if (stmtToUse instanceof oracle.jdbc.internal.OraclePreparedStatement) { //NOSONAR
                try {
                    return ((oracle.jdbc.internal.OraclePreparedStatement) stmtToUse).getOriginalSql();
                } catch (SQLException e) {
                    // ignore.
                }
            }
        }

        return stmtToUse.toString();
    };

    static Throwables.Function<Statement, String, SQLException> _sqlExtractor = DEFAULT_SQL_EXTRACTOR; //NOSONAR

    // TODO is it right to do it?
    // static final KeyedObjectPool<Statement, PoolableWrapper<String>> stmtPoolForSql = PoolFactory.createKeyedObjectPool(1000, 3000);

    // ...
    static final String CURRENT_DIR_PATH = "./";

    static final AsyncExecutor asyncExecutor = new AsyncExecutor(//
            N.max(64, IOUtil.CPU_CORES * 8), // coreThreadPoolSize
            N.max(128, IOUtil.CPU_CORES * 16), // maxThreadPoolSize
            180L, TimeUnit.SECONDS);

    static final BiParametersSetter<? super PreparedQuery, ? super Object[]> DEFAULT_STMT_SETTER = (stmt, parameters) -> {
        for (int i = 0, len = parameters.length; i < len; i++) {
            stmt.setObject(i + 1, parameters[i]);
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

    /**
     *
     *
     * @param ds
     * @return
     * @throws UncheckedSQLException
     */
    public static DBProductInfo getDBProductInfo(final javax.sql.DataSource ds) throws UncheckedSQLException {
        Connection conn = null;

        try {
            conn = ds.getConnection();
            return getDBProductInfo(conn);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }
    }

    /**
     *
     *
     * @param conn
     * @return
     * @throws UncheckedSQLException
     */
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
        } catch (Exception e) {
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
        } catch (Exception e) {
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
        N.checkArgNotEmpty(url, "url");

        Class<? extends Driver> driverClass = null;
        // jdbc:mysql://localhost:3306/abacustest
        if (Strings.indexOfIgnoreCase(url, "mysql") >= 0) {
            driverClass = ClassUtil.forClass("com.mysql.Driver");
            // jdbc:postgresql://localhost:5432/abacustest
        } else if (Strings.indexOfIgnoreCase(url, "postgresql") >= 0) {
            driverClass = ClassUtil.forClass("org.postgresql.Driver");
            // jdbc:h2:hsql://<host>:<port>/<database>
        } else if (Strings.indexOfIgnoreCase(url, "h2") >= 0) {
            driverClass = ClassUtil.forClass("org.h2.Driver");
            // jdbc:hsqldb:hsql://localhost/abacustest
        } else if (Strings.indexOfIgnoreCase(url, "hsqldb") >= 0) {
            driverClass = ClassUtil.forClass("org.hsqldb.JDBCDriver");
            // url=jdbc:oracle:thin:@localhost:1521:abacustest
        } else if (Strings.indexOfIgnoreCase(url, "oracle") >= 0) {
            driverClass = ClassUtil.forClass("oracle.driver.OracleDriver");
            // url=jdbc:sqlserver://localhost:1433;Database=abacustest
        } else if (Strings.indexOfIgnoreCase(url, "sqlserver") >= 0) {
            driverClass = ClassUtil.forClass("com.microsoft.sqlserver.SQLServerDriver");
            // jdbc:db2://localhost:50000/abacustest
        } else if (Strings.indexOfIgnoreCase(url, "db2") >= 0) {
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
            isInSpring = ClassUtil.forClass("org.springframework.datasource.DataSourceUtils") != null;
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
        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
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

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
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
        if (closeConnection && !closeStatement) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement) {
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
                throw new UncheckedSQLException(e); //NOSONAR
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
                throw new UncheckedSQLException(e); //NOSONAR
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
                throw new UncheckedSQLException(e); //NOSONAR
            } finally {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e); //NOSONAR
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
        if (closeConnection && !closeStatement) {
            throw new IllegalArgumentException("'closeStatement' can't be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;

        try {
            if (closeStatement) {
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

    private static final Set<Class<?>> resultSetClassNotSupportAbsolute = ConcurrentHashMap.newKeySet();

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
            return rs.next() ? 1 : 0;
        } else {
            final int currentRow = rs.getRow();

            if (n <= Integer.MAX_VALUE) {
                if (n > Integer.MAX_VALUE - currentRow
                        || (resultSetClassNotSupportAbsolute.size() > 0 && resultSetClassNotSupportAbsolute.contains(rs.getClass()))) {
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

                        resultSetClassNotSupportAbsolute.add(rs.getClass());
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

    /**
     *
     *
     * @param rs
     * @return
     * @throws SQLException
     */
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

        return Strings.isEmpty(result) ? rsmd.getColumnName(columnIndex) : result;
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

        String columnLabel = null;

        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
            columnLabel = rsmd.getColumnLabel(columnIndex);

            if (columnLabel != null && columnLabel.equalsIgnoreCase(columnName)) {
                return columnIndex;
            }

            columnLabel = rsmd.getColumnName(columnIndex);

            if (columnLabel != null && columnLabel.equalsIgnoreCase(columnName)) {
                return columnIndex;
            }
        }

        return -1;
    }

    @FunctionalInterface
    interface ColumnConverterByIndex {
        Object apply(ResultSet rs, int columnIndex, Object columnValue) throws SQLException;
    }

    @FunctionalInterface
    interface ColumnConverterByLabel {
        Object apply(ResultSet rs, String columnLabel, Object columnValue) throws SQLException;
    }

    private static final Throwables.Function<Object, java.sql.Timestamp, SQLException> oracleTimestampToJavaTimestamp = obj -> ((oracle.sql.Datum) obj)
            .timestampValue();

    private static final Throwables.Function<Object, java.sql.Date, SQLException> oracleTimestampToJavaDate = obj -> ((oracle.sql.Datum) obj).dateValue();

    private static final ObjectPool<Class<?>, Tuple2<ColumnConverterByIndex, ColumnConverterByLabel>> columnConverterPool = new ObjectPool<>(128);

    private static final Function<Object, Tuple2<ColumnConverterByIndex, ColumnConverterByLabel>> columnConverterGetter = ret -> {
        Tuple2<ColumnConverterByIndex, ColumnConverterByLabel> converterTP = columnConverterPool.get(ret.getClass());

        if (converterTP == null) {
            final Class<?> cls = ret.getClass();
            final String className = cls.getName();

            if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className)) {
                converterTP = Tuple.of((rs, columnIndex, val) -> ((oracle.sql.Datum) val).timestampValue(),
                        (rs, columnLabel, val) -> ((oracle.sql.Datum) val).timestampValue());
            } else if (className.startsWith("oracle.sql.DATE")) {
                converterTP = Tuple.of((rs, columnIndex, val) -> {
                    final String metaDataClassName = rs.getMetaData().getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return ((oracle.sql.Datum) val).timestampValue();
                    } else {
                        return ((oracle.sql.Datum) val).dateValue();
                    }
                }, (rs, columnLabel, val) -> {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final int columnIndex = getColumnIndex(metaData, columnLabel);
                    final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return ((oracle.sql.Datum) val).timestampValue();
                    } else {
                        return ((oracle.sql.Datum) val).dateValue();
                    }
                });
            } else if ((ret instanceof java.sql.Date)) {
                converterTP = Tuple.of((rs, columnIndex, val) -> {
                    if ("java.sql.Timestamp".equals(rs.getMetaData().getColumnClassName(columnIndex))) {
                        return rs.getTimestamp(columnIndex);
                    } else {
                        return val;
                    }
                }, (rs, columnLabel, val) -> {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final int columnIndex = getColumnIndex(metaData, columnLabel);

                    if ("java.sql.Timestamp".equals(metaData.getColumnClassName(columnIndex))) {
                        return rs.getTimestamp(columnIndex);
                    } else {
                        return val;
                    }
                });
            } else {
                converterTP = Tuple.of((rs, columnIndex, val) -> val, (rs, columnLabel, val) -> val);
            }

            columnConverterPool.put(cls, converterTP);
        }

        return converterTP;
    };

    private static final ColumnConverterByIndex columnConverterByIndex = (rs, columnIndex, val) -> columnConverterGetter.apply(val)._1.apply(rs, columnIndex,
            val);

    private static final ColumnConverterByLabel columnConverterByLabel = (rs, columnLabel, val) -> columnConverterGetter.apply(val)._2.apply(rs, columnLabel,
            val);

    /**
     * Gets the column value.
     *
     * @param rs
     * @param columnIndex starts with 1, not 0.
     * @return
     * @throws SQLException
     */
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws SQLException {
        return getColumnValue(rs, columnIndex, true);
    }

    /**
     *
     * @param rs
     * @param columnIndex starts with 1, not 0.
     * @param checkDateType
     * @return
     * @throws SQLException
     */
    static Object getColumnValue(final ResultSet rs, final int columnIndex, final boolean checkDateType) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object ret = rs.getObject(columnIndex);

        if (ret == null || ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
            return ret;
        }

        if (ret instanceof Blob blob) {
            ret = blob.getBytes(1, (int) blob.length());
        } else if (ret instanceof Clob clob) {
            ret = clob.getSubString(1, (int) clob.length());
        } else if (checkDateType) {
            ret = columnConverterByIndex.apply(rs, columnIndex, ret);
        }

        return ret;
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
        return getColumnValue(rs, columnLabel, true);
    }

    /**
     *
     * @param rs
     * @param columnLabel
     * @param checkDateType
     * @return
     * @throws SQLException
     * @deprecated use {@link #getColumnValue(ResultSet, int, boolean)}
     */
    @Deprecated
    static Object getColumnValue(final ResultSet rs, final String columnLabel, final boolean checkDateType) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object ret = rs.getObject(columnLabel);

        if (ret == null || ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
            return ret;
        }

        if (ret instanceof Blob blob) {
            ret = blob.getBytes(1, (int) blob.length());
        } else if (ret instanceof Clob clob) {
            ret = clob.getSubString(1, (int) clob.length());
        } else if (checkDateType) {
            ret = columnConverterByLabel.apply(rs, columnLabel, ret);
        }

        return ret;
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param rs
     * @param columnIndex starts with 1, not 0.
     * @return
     * @throws SQLException
     */
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final int columnIndex) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        final List<Object> result = new ArrayList<>();
        Object val = null;

        while (rs.next()) {
            val = result.get(columnIndex);

            if (val == null) {
                result.add(val);
            } else if (val instanceof String || val instanceof Number || val instanceof java.sql.Timestamp || val instanceof Boolean) {
                result.add(val);

                while (rs.next()) {
                    result.add(result.get(columnIndex));
                }
            } else if (val instanceof Blob blob) {
                result.add(blob.getBytes(1, (int) blob.length()));

                while (rs.next()) {
                    blob = (Blob) result.get(columnIndex);
                    result.add(blob.getBytes(1, (int) blob.length()));
                }
            } else if (val instanceof Clob clob) {
                result.add(clob.getSubString(1, (int) clob.length()));

                while (rs.next()) {
                    clob = (Clob) result.get(columnIndex);
                    result.add(clob.getSubString(1, (int) clob.length()));
                }
            } else {
                final String className = val.getClass().getName();

                if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className)) {
                    result.add(oracleTimestampToJavaTimestamp.apply(val));

                    while (rs.next()) {
                        result.add(oracleTimestampToJavaTimestamp.apply(result.get(columnIndex)));
                    }
                } else if (className.startsWith("oracle.sql.DATE")) {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        result.add(oracleTimestampToJavaTimestamp.apply(val));

                        while (rs.next()) {
                            result.add(oracleTimestampToJavaTimestamp.apply(result.get(columnIndex)));
                        }
                    } else {
                        result.add(oracleTimestampToJavaDate.apply(val));

                        while (rs.next()) {
                            result.add(oracleTimestampToJavaDate.apply(result.get(columnIndex)));
                        }
                    }
                } else if ((val instanceof java.sql.Date)) {
                    final ResultSetMetaData metaData = rs.getMetaData();

                    if ("java.sql.Timestamp".equals(metaData.getColumnClassName(columnIndex))) {
                        result.add(rs.getTimestamp(columnIndex));

                        while (rs.next()) {
                            result.add(rs.getTimestamp(columnIndex));
                        }
                    } else {
                        result.add(val);

                        while (rs.next()) {
                            result.add(result.get(columnIndex));
                        }
                    }
                } else {
                    result.add(val);

                    while (rs.next()) {
                        result.add(result.get(columnIndex));
                    }
                }
            }
        }

        return (List<T>) result;
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param rs
     * @param columnLabel
     * @return
     * @throws SQLException
     */
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final String columnLabel) throws SQLException {
        final int columnIndex = JdbcUtil.getColumnIndex(rs, columnLabel);

        if (columnIndex < 1) {
            throw new IllegalArgumentException("No column found by name: " + columnLabel + " in result set: " + JdbcUtil.getColumnLabelList(rs));
        }

        return getAllColumnValues(rs, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param rs
     * @param columnIndex
     * @param targetClass
     * @return
     * @throws SQLException
     */
    public static <T> T getColumnValue(final ResultSet rs, final int columnIndex, final Class<? extends T> targetClass) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnIndex);
    }

    /**
     * Gets the column value.
     *
     * @param <T>
     * @param rs
     * @param columnLabel
     * @param targetClass
     * @return
     * @throws SQLException
     * @deprecated please consider using {@link #getColumnValue(ResultSet, int, Class)}
     */
    @Deprecated
    public static <T> T getColumnValue(final ResultSet rs, final String columnLabel, final Class<? extends T> targetClass) throws SQLException {
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
    //            final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
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

    /**
     *
     *
     * @param entityClass
     * @return
     */
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

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
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

            try { //NOSONAR
                conn = getConnection(dataSource);
                tran = new SQLTransaction(dataSource, conn, isolationLevel, CreatedBy.JDBC_UTIL, true); //NOSONAR
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (!noException) {
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
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Function<Connection, T, E> cmd)
            throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
        T result = null;

        try {
            result = cmd.apply(tran.connection());
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        return result;
    }

    /**
     *
     *
     * @param <E>
     * @param dataSource
     * @param cmd
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
     *
     * @param <E>
     * @param dataSource
     * @param cmd
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Consumer<Connection, E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            cmd.accept(tran.connection());
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

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
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

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
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
     *
     * @param <E>
     * @param dataSource
     * @param cmd
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
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
     *
     * @param <E>
     * @param dataSource
     * @param cmd
     * @throws E
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws E {
        N.checkArgNotNull(dataSource, "dataSource");
        N.checkArgNotNull(cmd, "cmd");

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
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
        if (Strings.startsWithIgnoreCase(sql.trim(), "select ")) {
            return SQLOperation.SELECT;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "update ")) {
            return SQLOperation.UPDATE;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "insert ")) {
            return SQLOperation.INSERT;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "delete ")) {
            return SQLOperation.DELETE;
        } else if (Strings.startsWithIgnoreCase(sql.trim(), "merge ")) {
            return SQLOperation.MERGE;
        } else {
            for (SQLOperation so : SQLOperation.values()) {
                if (Strings.startsWithIgnoreCase(sql.trim(), so.name())) {
                    return so;
                }
            }
        }

        return SQLOperation.UNKNOWN;
    }

    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        // stmt.setFetchDirectionToForward().setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
        }
    };

    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForStream = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM);
        }
    };

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");
        N.checkArgNotEmpty(returnColumnIndexes, "returnColumnIndexes");

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
        N.checkArgNotEmpty(sql, "sql");
        N.checkArgNotEmpty(returnColumnNames, "returnColumnNames");

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
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/CallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotEmpty(sql, "sql");
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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");
        N.checkArgNotEmpty(returnColumnIndexes, "returnColumnIndexes");

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
        N.checkArgNotEmpty(sql, "sql");
        N.checkArgNotEmpty(returnColumnNames, "returnColumnNames");

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
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code PreparedQuery/CallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotEmpty(sql, "sql");
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
        return prepareQuery(ds, sql).configStmt(stmtSetterForBigQueryResult);
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
        return prepareQuery(conn, sql).configStmt(stmtSetterForBigQueryResult);
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
        N.checkArgNotEmpty(namedSql, "namedSql");

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
        N.checkArgNotEmpty(namedSql, "namedSql");

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
        N.checkArgNotEmpty(namedSql, "namedSql");
        N.checkArgNotEmpty(returnColumnIndexes, "returnColumnIndexes");

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
        N.checkArgNotEmpty(namedSql, "namedSql");
        N.checkArgNotEmpty(returnColumnNames, "returnColumnNames");

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
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/CallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotEmpty(namedSql, "namedSql");
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
        N.checkArgNotEmpty(namedSql, "namedSql");

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
        N.checkArgNotEmpty(namedSql, "namedSql");

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
        N.checkArgNotEmpty(namedSql, "namedSql");
        N.checkArgNotEmpty(returnColumnIndexes, "returnColumnIndexes");

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
        N.checkArgNotEmpty(namedSql, "namedSql");
        N.checkArgNotEmpty(returnColumnNames, "returnColumnNames");

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
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/CallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotEmpty(namedSql, "namedSql");
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
        N.checkArgNotEmpty(returnColumnIndexes, "returnColumnIndexes");
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
        N.checkArgNotEmpty(returnColumnNames, "returnColumnNames");
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
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/CallableQuery} is called.
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
        N.checkArgNotEmpty(returnColumnIndexes, "returnColumnIndexes");
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
        N.checkArgNotEmpty(returnColumnNames, "returnColumnNames");
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
     * @param stmtCreator the created {@code PreparedStatement} will be closed after any execution methods in {@code NamedQuery/CallableQuery} is called.
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
        return prepareNamedQuery(ds, sql).configStmt(stmtSetterForBigQueryResult);
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
        return prepareNamedQuery(conn, sql).configStmt(stmtSetterForBigQueryResult);
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
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotEmpty(sql, "sql");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql);
        } else {
            CallableQuery result = null;
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
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/CallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(ds, "dataSource");
        N.checkArgNotEmpty(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        final SQLTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql, stmtCreator);
        } else {
            CallableQuery result = null;
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
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotEmpty(sql, "sql");

        return new CallableQuery(prepareCallable(conn, sql));
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
     * @param stmtCreator the created {@code CallableStatement} will be closed after any execution methods in {@code PreparedQuery/CallableQuery} is called.
     * An execution method is a method which will trigger the backed {@code PreparedStatement/CallableStatement} to be executed, for example: get/query/queryForInt/Long/../findFirst/findOnlyOne/list/execute/....
     * @return
     * @throws SQLException
     */
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        N.checkArgNotNull(conn, "conn");
        N.checkArgNotEmpty(sql, "sql");
        N.checkArgNotNull(stmtCreator, "stmtCreator");

        return new CallableQuery(prepareCallable(conn, sql, stmtCreator));
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
        N.checkArgNotEmpty(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        if (N.notEmpty(parameters)) {
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
        N.checkArgNotEmpty(sql, "sql");

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        if (N.notEmpty(parameters)) {
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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(namedSql, "namedSql");

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");
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

        if (N.isEmpty(listOfParameters)) {
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
        N.checkArgNotEmpty(sql, "sql");
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

        if (N.isEmpty(listOfParameters)) {
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
        N.checkArgNotEmpty(sql, "sql");

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
        N.checkArgNotEmpty(sql, "sql");

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

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeQuery();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

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

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeUpdate();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

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

    static long executeLargeUpdate(PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeUpdate();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

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

    static int[] executeBatch(Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeBatch();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

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
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeBatch();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

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

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.execute();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

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

    private static boolean isToHandleSqlLog(final SqlLogConfig sqlLogConfig) {
        return _sqlLogHandler != null || (isSqlPerfLogAllowed && sqlLogConfig.minExecutionTimeForSqlPerfLog >= 0 && sqlLogger.isInfoEnabled());
    }

    static void clearParameters(final PreparedStatement stmt) {
        // calling clearParameters() will impact/remove registered out parameters in CallableStatement.
        if (stmt == null || stmt instanceof CallableStatement) {
            // no
        } else {
            try {
                stmt.clearParameters();
            } catch (SQLException e) {
                logger.error("Failed to clear parameters after execution", e);
            }
        }
    }

    static void setParameters(final ParsedSql parsedSql, final PreparedStatement stmt, final Object[] parameters) throws SQLException {
        final int parameterCount = parsedSql.getParameterCount();

        if (parameterCount == 0) {
            return;
        } else if (N.isEmpty(parameters)) {
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

            if (ClassUtil.isBeanClass(cls)) {
                final Object entity = parameter_0;
                final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
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
        if (N.notEmpty(parameterTypes) && parameterTypes.length >= parameterCount) {
            for (int i = 0; i < parameterCount; i++) {
                parameterTypes[i].set(stmt, i + 1, parameters[i]);
            }
        } else if (N.notEmpty(parameters) && parameters.length >= parameterCount) {
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
        if (N.isEmpty(parsedSql.getNamedParameters()) || N.isEmpty(parameters) || (parameters.length != 1) || (parameters[0] == null)) {
            return false;
        }

        final Class<? extends Object> cls = parameters[0].getClass();

        return ClassUtil.isBeanClass(cls) || ClassUtil.isRecordClass(cls) || Map.class.isAssignableFrom(cls) || EntityId.class.isAssignableFrom(cls);
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
     *
     * @param rs
     * @param filter
     * @return
     * @throws SQLException
     */
    public static DataSet extractData(final ResultSet rs, final RowFilter filter) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, false);
    }

    /**
     *
     * @param rs
     * @param rowExtractor
     * @return
     * @throws SQLException
     */
    public static DataSet extractData(final ResultSet rs, final RowExtractor rowExtractor) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, false);
    }

    /**
     *
     * @param rs
     * @param filter
     * @param rowExtractor
     * @return
     * @throws SQLException
     */
    public static DataSet extractData(final ResultSet rs, final RowFilter filter, final RowExtractor rowExtractor) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, filter, rowExtractor, false);
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
        final boolean checkDateType = checkDateType(rs);

        try {
            return JdbcUtil.extractResultSetToDataSet(rs, offset, count, filter, rowExtractor, checkDateType);
        } finally {
            if (closeResultSet) {
                closeQuietly(rs);
            }
        }
    }

    static DataSet extractResultSetToDataSet(final ResultSet rs, int offset, int count, final RowFilter filter, final RowExtractor rowExtractor,
            final boolean checkDateType) throws SQLException {
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
                        columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i, checkDateType));
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
                            columnList.get(i).add(JdbcUtil.getColumnValue(rs, ++i, checkDateType));
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
    }

    static <R> R extractAndCloseResultSet(ResultSet rs, final ResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs));
        } finally {
            closeQuietly(rs);
        }
    }

    static <R> R extractAndCloseResultSet(ResultSet rs, final BiResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs, getColumnLabelList(rs)));
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.extractAllResultSets(stmt).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param stmt
     * @return
     */
    public static CheckedStream<DataSet, SQLException> extractAllResultSets(final Statement stmt) {
        return extractAllResultSets(stmt, ResultExtractor.TO_DATA_SET);
    }

    /**
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.extractAllResultSets(stmt, resultExtractor).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param <T>
     * @param stmt
     * @param resultExtractor
     * @return
     */
    @SuppressWarnings("resource")
    public static <T> CheckedStream<T, SQLException> extractAllResultSets(final Statement stmt, final ResultExtractor<T> resultExtractor) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(resultExtractor, "resultExtractor");

        final Throwables.Supplier<Throwables.Iterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return CheckedStream.just(supplier, SQLException.class)
                .onClose(Fn.rr(() -> supplier.get().close()))
                .flatMap(it -> CheckedStream.of(it.get()))
                .map(rs -> extractAndCloseResultSet(rs, resultExtractor));
    }

    /**
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.extractAllResultSets(stmt, resultExtractor).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param <T>
     * @param stmt
     * @param resultExtractor
     * @return
     */
    @SuppressWarnings("resource")
    public static <T> CheckedStream<T, SQLException> extractAllResultSets(final Statement stmt, final BiResultExtractor<T> resultExtractor) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(resultExtractor, "resultExtractor");

        final Throwables.Supplier<Throwables.Iterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return CheckedStream.just(supplier, SQLException.class)
                .onClose(Fn.rr(() -> supplier.get().close()))
                .flatMap(it -> CheckedStream.of(it.get()))
                .map(rs -> extractAndCloseResultSet(rs, resultExtractor));
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
     *
     * @param resultSet
     * @return
     */
    public static CheckedStream<Object[], SQLException> stream(final ResultSet resultSet) {
        return stream(resultSet, Object[].class);
    }

    /**
     * It's user's responsibility to close the input <code>resultSet</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultset).onClose(Fn.closeQuietly(resultSet))...}
     *
     * @param <T>
     * @param resultSet
     * @param targetClass Array/List/Map or Entity with getter/setter methods.
     * @return
     */
    public static <T> CheckedStream<T, SQLException> stream(final ResultSet resultSet, final Class<? extends T> targetClass) {
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
    public static <T> CheckedStream<T, SQLException> stream(final ResultSet resultSet, final RowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return CheckedStream.of(iterate(resultSet, rowMapper, null));
    }

    static <T> Throwables.Iterator<T, SQLException> iterate(final ResultSet resultSet, final RowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new Throwables.Iterator<>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (!hasNext) {
                    hasNext = resultSet.next();
                }

                return hasNext;
            }

            @Override
            public T next() throws SQLException {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
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
            protected void closeResource() {
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
    public static <T> CheckedStream<T, SQLException> stream(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return CheckedStream.of(iterate(resultSet, rowFilter, rowMapper, null));
    }

    static <T> Throwables.Iterator<T, SQLException> iterate(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper,
            final Runnable onClose) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return new Throwables.Iterator<>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (!hasNext) {
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
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                return rowMapper.apply(resultSet);
            }

            @Override
            protected void closeResource() {
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
    public static <T> CheckedStream<T, SQLException> stream(final ResultSet resultSet, final BiRowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return CheckedStream.of(iterate(resultSet, rowMapper, null));
    }

    static <T> Throwables.Iterator<T, SQLException> iterate(final ResultSet resultSet, final BiRowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new Throwables.Iterator<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (!hasNext) {
                    hasNext = resultSet.next();
                }

                return hasNext;
            }

            @Override
            public T next() throws SQLException {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
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
            protected void closeResource() {
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
    public static <T> CheckedStream<T, SQLException> stream(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        return CheckedStream.of(iterate(resultSet, rowFilter, rowMapper));
    }

    static <T> Throwables.Iterator<T, SQLException> iterate(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper) {
        return new Throwables.Iterator<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() throws SQLException {
                if (columnLabels == null) {
                    columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                }

                if (!hasNext) {
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
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
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
    public static <T> CheckedStream<T, SQLException> stream(final ResultSet resultSet, final int columnIndex) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgPositive(columnIndex, "columnIndex");

        final boolean checkDateType = JdbcUtil.checkDateType(resultSet);
        final RowMapper<? extends T> rowMapper = rs -> (T) getColumnValue(resultSet, columnIndex, checkDateType);

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
    public static <T> CheckedStream<T, SQLException> stream(final ResultSet resultSet, final String columnName) {
        N.checkArgNotNull(resultSet, "resultSet");
        N.checkArgNotEmpty(columnName, "columnName");

        final RowMapper<? extends T> rowMapper = new RowMapper<>() {
            private int columnIndex = -1;
            private boolean checkDateType = true;

            @Override
            public T apply(ResultSet rs) throws SQLException {
                if (columnIndex == -1) {
                    columnIndex = getColumnIndex(resultSet, columnName);
                    checkDateType = JdbcUtil.checkDateType(resultSet);
                }

                return (T) getColumnValue(resultSet, columnIndex, checkDateType);
            }
        };

        return stream(resultSet, rowMapper);
    }

    /**
     * It's user's responsibility to close the input <code>stmt</code> after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.streamAllResultSets(stmt, targetClass).onClose(Fn.closeQuietly(stmt))...}
     *
     * @param <T>
     * @param stmt
     * @param targetClass
     * @return
     */
    @SuppressWarnings("resource")
    public static <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamAllResultSets(final Statement stmt,
            final Class<? extends T> targetClass) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(targetClass, "targetClass");

        JdbcUtil.checkDateType(stmt);

        final Throwables.Supplier<Throwables.Iterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return CheckedStream.just(supplier, SQLException.class)
                .onClose(Fn.rr(() -> supplier.get().close()))
                .flatMap(it -> CheckedStream.of(it.get()))
                .map(rs -> {
                    final BiRowMapper<T> rowMapper = BiRowMapper.to(targetClass);

                    return JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs));
                });
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
    @SuppressWarnings("resource")
    public static <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamAllResultSets(final Statement stmt,
            final RowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowMapper, "rowMapper");

        JdbcUtil.checkDateType(stmt);

        final Throwables.Supplier<Throwables.Iterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return CheckedStream.just(supplier, SQLException.class)
                .onClose(Fn.rr(() -> supplier.get().close()))
                .flatMap(it -> CheckedStream.of(it.get()))
                .map(rs -> JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
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
    @SuppressWarnings("resource")
    public static <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamAllResultSets(final Statement stmt, final RowFilter rowFilter,
            final RowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        JdbcUtil.checkDateType(stmt);

        final Throwables.Supplier<Throwables.Iterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return CheckedStream.just(supplier, SQLException.class)
                .onClose(Fn.rr(() -> supplier.get().close()))
                .flatMap(it -> CheckedStream.of(it.get()))
                .map(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
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
    @SuppressWarnings("resource")
    public static <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamAllResultSets(final Statement stmt,
            final BiRowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowMapper, "rowMapper");

        JdbcUtil.checkDateType(stmt);

        final Throwables.Supplier<Throwables.Iterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return CheckedStream.just(supplier, SQLException.class)
                .onClose(Fn.rr(() -> supplier.get().close()))
                .flatMap(it -> CheckedStream.of(it.get()))
                .map(rs -> JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
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
    @SuppressWarnings("resource")
    public static <T> CheckedStream<CheckedStream<T, SQLException>, SQLException> streamAllResultSets(final Statement stmt, final BiRowFilter rowFilter,
            final BiRowMapper<? extends T> rowMapper) {
        N.checkArgNotNull(stmt, "stmt");
        N.checkArgNotNull(rowFilter, "rowFilter");
        N.checkArgNotNull(rowMapper, "rowMapper");

        JdbcUtil.checkDateType(stmt);

        final Throwables.Supplier<Throwables.Iterator<ResultSet, SQLException>, SQLException> supplier = Fnn.memoize(() -> iterateAllResultSets(stmt));

        return CheckedStream.just(supplier, SQLException.class)
                .onClose(Fn.rr(() -> supplier.get().close()))
                .flatMap(it -> CheckedStream.of(it.get()))
                .map(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    }

    static Throwables.Iterator<ResultSet, SQLException> iterateAllResultSets(final Statement stmt) throws SQLException { //NOSONAR
        return new Throwables.Iterator<>() {
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
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                return resultSetHolder.getAndSet(null);
            }

            @Override
            protected void closeResource() {
                if (resultSetHolder.isNotNull()) {
                    JdbcUtil.closeQuietly(resultSetHolder.getAndSet(null));
                }
            }
        };
    }

    /**
     * Runs a {@code Stream} with each element(page) is loaded from database table by running sql {@code query}.
     *
     * @param ds
     * @param query this query must be ordered by at least one key/id and has the result size limitation: for example {@code LIMIT pageSize}, {@code ROWS FETCH NEXT pageSize ROWS ONLY}
     * @param pageSize
     * @param paramSetter the second parameter is the result set for previous page. it's {@code null} for first page.
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static CheckedStream<DataSet, SQLException> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, DataSet> paramSetter) {
        return queryByPage(ds, query, pageSize, paramSetter, Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Runs a {@code Stream} with each element(page) is loaded from database table by running sql {@code query}.
     *
     * @param <R>
     * @param ds
     * @param query this query must be ordered by at least one key/id and has the result size limitation: for example {@code LIMIT pageSize}, {@code ROWS FETCH NEXT pageSize ROWS ONLY}
     * @param pageSize
     * @param paramSetter the second parameter is the result set for previous page. it's {@code null} for first page.
     * @param resultExtractor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <R> CheckedStream<R, SQLException> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.ResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return CheckedStream.<Holder<R>, SQLException> of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, query) : JdbcUtil.prepareQuery(ds, query)) //
                            .setFetchDirectionToForward()
                            .setFetchSize(pageSize)
                            .settParameters(it.value(), paramSetter)
                            .query(resultExtractor);

                    it.setValue(ret);

                    return ret;
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element(page) is loaded from database table by running sql {@code query}.
     *
     * @param <R>
     * @param ds
     * @param query this query must be ordered by at least one key/id and has the result size limitation: for example {@code LIMIT pageSize}, {@code ROWS FETCH NEXT pageSize ROWS ONLY}
     * @param pageSize
     * @param paramSetter the second parameter is the result set for previous page. it's {@code null} for first page.
     * @param resultExtractor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <R> CheckedStream<R, SQLException> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return CheckedStream.<Holder<R>, SQLException> of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, query) : JdbcUtil.prepareQuery(ds, query)) //
                            .setFetchDirectionToForward()
                            .setFetchSize(pageSize)
                            .settParameters(it.value(), paramSetter)
                            .query(resultExtractor);

                    it.setValue(ret);

                    return ret;
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element(page) is loaded from database table by running sql {@code query}.
     *
     * @param conn
     * @param query this query must be ordered by at least one key/id and has the result size limitation: for example {@code LIMIT pageSize}, {@code ROWS FETCH NEXT pageSize ROWS ONLY}
     * @param pageSize
     * @param paramSetter the second parameter is the result set for previous page. it's {@code null} for first page.
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static CheckedStream<DataSet, SQLException> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, DataSet> paramSetter) {
        return queryByPage(conn, query, pageSize, paramSetter, Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Runs a {@code Stream} with each element(page) is loaded from database table by running sql {@code query}.
     *
     * @param <R>
     * @param conn
     * @param query this query must be ordered by at least one key/id and has the result size limitation: for example {@code LIMIT pageSize}, {@code ROWS FETCH NEXT pageSize ROWS ONLY}
     * @param pageSize
     * @param paramSetter the second parameter is the result set for previous page. it's {@code null} for first page.
     * @param resultExtractor the second parameter is the result set for previous page. it's {@code null} for first page.
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <R> CheckedStream<R, SQLException> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.ResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return CheckedStream.<Holder<R>, SQLException> of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, query) : JdbcUtil.prepareQuery(conn, query)) //
                            .setFetchDirectionToForward()
                            .setFetchSize(pageSize)
                            .settParameters(it.value(), paramSetter)
                            .query(resultExtractor);

                    it.setValue(ret);

                    return ret;
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element(page) is loaded from database table by running sql {@code query}.
     *
     * @param <R>
     * @param conn
     * @param query this query must be ordered by at least one key/id and has the result size limitation: for example {@code LIMIT pageSize}, {@code ROWS FETCH NEXT pageSize ROWS ONLY}
     * @param pageSize
     * @param paramSetter the second parameter is the result set for previous page. it's {@code null} for first page.
     * @param resultExtractor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <R> CheckedStream<R, SQLException> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return CheckedStream.<Holder<R>, SQLException> of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, query) : JdbcUtil.prepareQuery(conn, query)) //
                            .setFetchDirectionToForward()
                            .setFetchSize(pageSize)
                            .settParameters(it.value(), paramSetter)
                            .query(resultExtractor);

                    it.setValue(ret);

                    return ret;
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    @SuppressWarnings("rawtypes")
    static boolean isNotEmptyResult(Object ret) {
        if (ret == null) {
            return false;
        }

        if (ret instanceof DataSet) {
            return N.notEmpty((DataSet) ret);
        } else if (ret instanceof Collection) {
            return N.notEmpty((Collection) ret);
        } else if (ret instanceof Map) {
            return N.notEmpty((Map) ret);
        } else if (ret instanceof Iterable) {
            return N.notEmpty((Iterable) ret);
        } else if (ret instanceof Iterator) {
            return N.notEmpty((Iterator) ret);
        }

        return true;
    }

    static <R> R checkNotResultSet(R result) {
        if (result instanceof ResultSet) {
            throw new UnsupportedOperationException("The result value of ResultExtractor/BiResultExtractor.apply can't be ResultSet");
        }

        return result;
    }

    static boolean checkDateType(final ResultSet rs) {
        try {
            return checkDateType(rs.getStatement());
        } catch (Exception e) {
            return true;
        }
    }

    static boolean checkDateType(final Statement stmt) {
        try {
            return Strings.containsIgnoreCase(JdbcUtil.getDBProductInfo(stmt.getConnection()).getProductName(), "Oracle");
        } catch (SQLException e) {
            return true;
        }
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

        if (N.isEmpty(outParams)) {
            return new OutParamResult(N.<OutParam> emptyList(), N.<Object, Object> emptyMap());
        }

        final Map<Object, Object> outParamValues = new LinkedHashMap<>(outParams.size());
        OutParameterGetter outParameterGetter = null;
        Object key = null;
        Object value = null;

        for (OutParam outParam : outParams) {
            outParameterGetter = sqlTypeGetterMap.getOrDefault(outParam.getSqlType(), objOutParameterGetter);

            if (outParam.getParameterIndex() > 0) {
                key = outParam.getParameterIndex();
                value = outParameterGetter.getOutParameter(stmt, outParam.getParameterIndex());
            } else {
                key = outParam.getParameterName();
                value = outParameterGetter.getOutParameter(stmt, outParam.getParameterName());
            }

            if (value instanceof ResultSet rs) {
                try {
                    value = JdbcUtil.extractData(rs);
                } finally {
                    JdbcUtil.closeQuietly(rs);
                }
            } else if (value instanceof Blob blob) {
                value = blob.getBytes(1, (int) blob.length());
            } else if (value instanceof Clob clob) {
                value = clob.getSubString(1, (int) clob.length());
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
     * @param ds
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
     * @param ds
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
     * @param ds
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
        if (e instanceof SQLException sqlException) {
            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return Strings.isNotEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        } else if (e instanceof UncheckedSQLException sqlException) {
            return isTableNotExistsException(sqlException.getCause());
        }

        return false;
    }

    static boolean isSqlLogAllowed = true;

    /**
     *
     */
    public static void turnOffSqlLogGlobally() {
        isSqlLogAllowed = false;
    }

    static boolean isSqlPerfLogAllowed = true;

    /**
     *
     */
    public static void turnOffSqlPerfLogGlobally() {
        isSqlPerfLogAllowed = false;
    }

    static boolean isDaoMethodPerfLogAllowed = true;

    /**
     *
     */
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
        if (!isSqlLogAllowed || !sqlLogger.isDebugEnabled()) {
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
    //        if (isSqlPerfLogAllowed && N.notEmpty(sql)) {
    //            stmtPoolForSql.put(stmt, Poolable.wrap(sql, 3000, 3000));
    //        }
    //
    //        return stmt;
    //    }

    /**
     *
     *
     * @return
     */
    public static Throwables.Function<Statement, String, SQLException> getSqlExtractor() {
        return _sqlExtractor;
    }

    /**
     *
     *
     * @param sqlExtractor
     */
    public static void setSqlExtractor(final Throwables.Function<Statement, String, SQLException> sqlExtractor) {
        _sqlExtractor = sqlExtractor;
    }

    private static TriConsumer<String, Long, Long> _sqlLogHandler = null; //NOSONAR

    /**
     *
     *
     * @return
     */
    public static TriConsumer<String, Long, Long> getSqlLogHandler() {
        return _sqlLogHandler;
    }

    /**
     *
     * @param sqlLogHandler 1st parameter is sql, 2nd parameter is start time of sql execution, 3rd parameter is end time of sql execution.
     */
    public static void setSqlLogHandler(final TriConsumer<String, Long, Long> sqlLogHandler) {
        _sqlLogHandler = sqlLogHandler;
    }

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

    static void handleSqlLog(final Statement stmt, final SqlLogConfig sqlLogConfig, final long startTime) throws SQLException {
        final long endTime = System.currentTimeMillis();
        final long elapsedTime = endTime - startTime;
        String sql = null;

        final Throwables.Function<Statement, String, SQLException> sqlExtractor = N.defaultIfNull(JdbcUtil._sqlExtractor, JdbcUtil.DEFAULT_SQL_EXTRACTOR);

        if (isSqlPerfLogAllowed && sqlLogger.isInfoEnabled() && elapsedTime >= sqlLogConfig.minExecutionTimeForSqlPerfLog) {
            sql = sqlExtractor.apply(stmt);

            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", sql));
            } else {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), ", ", sql.substring(0, sqlLogConfig.maxSqlLogLength)));
            }
        }

        final TriConsumer<String, Long, Long> sqlLogHandler = _sqlLogHandler;

        if (sqlLogHandler != null) {
            if (sql == null) {
                sql = sqlExtractor.apply(stmt);
            }

            sqlLogHandler.accept(sql, startTime, endTime);
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
            if (logger.isWarnEnabled() && isSpringTransactionalDisabled_TL.get() != b) { //NOSONAR
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

    static final com.landawn.abacus.util.function.Predicate<Object> defaultIdTester = JdbcUtil::isDefaultIdPropValue;

    /**
     * Checks if is default id prop value.
     *
     * @param value
     * @return true, if is default id prop value
     * @deprecated for internal only.
     */
    @Deprecated
    @Internal
    static boolean isDefaultIdPropValue(final Object value) {
        if ((value == null) || (value instanceof Number && (((Number) value).longValue() == 0))) {
            return true;
        } else if (value instanceof EntityId) {
            return N.allMatch(((EntityId) value).entrySet(), it -> JdbcUtil.isDefaultIdPropValue(it.getValue()));
        } else if (ClassUtil.isBeanClass(value.getClass())) {
            final Class<?> entityClass = value.getClass();
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(entityClass);

            if (N.isEmpty(idPropNameList)) {
                return true;
            } else {
                final BeanInfo idBeanInfo = ParserUtil.getBeanInfo(entityClass);
                return N.allMatch(idPropNameList, idName -> JdbcUtil.isDefaultIdPropValue(idBeanInfo.getPropValue(value, idName)));
            }
        }

        return false;
    }

    static <ID> boolean isAllNullIds(final List<ID> ids) {
        return isAllNullIds(ids, defaultIdTester);
    }

    static <ID> boolean isAllNullIds(final List<ID> ids, final Predicate<Object> isDefaultIdTester) {
        return N.notEmpty(ids) && ids.stream().allMatch(isDefaultIdTester);
    }

    /**
     *
     *
     * @param sqlAction
     */
    @Beta
    public static void run(final Throwables.Runnable<Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.run();
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     *
     *
     * @param <T>
     * @param t
     * @param sqlAction
     */
    @Beta
    public static <T> void run(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.accept(t);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     *
     *
     * @param <T>
     * @param <U>
     * @param t
     * @param u
     * @param sqlAction
     */
    @Beta
    public static <T, U> void run(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.accept(t, u);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     *
     *
     * @param <A>
     * @param <B>
     * @param <C>
     * @param a
     * @param b
     * @param c
     * @param sqlAction
     */
    @Beta
    public static <A, B, C> void run(final A a, final B b, final C c, final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            sqlAction.accept(a, b, c);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     *
     *
     * @param <R>
     * @param sqlAction
     * @return
     */
    @Beta
    public static <R> R call(final Callable<R> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            return sqlAction.call();
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     *
     *
     * @param <T>
     * @param <R>
     * @param t
     * @param sqlAction
     * @return
     */
    @Beta
    public static <T, R> R call(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            return sqlAction.apply(t);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     *
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
    public static <T, U, R> R call(final T t, final U u, final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction) {
        N.checkArgNotNull(sqlAction, "sqlAction");

        try {
            return sqlAction.apply(t, u);
        } catch (Exception e) {
            throw N.toRuntimeException(e);
        }
    }

    /**
     *
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

    static final RowMapper<Object> NO_GENERATED_KEY_EXTRACTOR = rs -> null;

    static final RowMapper<Object> SINGLE_GENERATED_KEY_EXTRACTOR = rs -> getColumnValue(rs, 1);

    @SuppressWarnings("deprecation")
    static final RowMapper<Object> MULTI_GENERATED_KEY_EXTRACTOR = rs -> {
        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

        if (columnLabels.size() == 1) {
            return getColumnValue(rs, 1);
        } else {
            final int columnCount = columnLabels.size();
            final Seid id = Seid.of(Strings.EMPTY_STRING);

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
            final Seid id = Seid.of(Strings.EMPTY_STRING);

            for (int i = 1; i <= columnCount; i++) {
                id.set(columnLabels.get(i - 1), getColumnValue(rs, i));
            }

            return id;
        }
    };

    private static final Map<Class<?>, Map<String, Optional<PropInfo>>> entityPropInfoQueueMap = new ConcurrentHashMap<>();

    static PropInfo getSubPropInfo(final Class<?> entityClass, String propName) {
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
        Map<String, Optional<PropInfo>> propInfoQueueMap = entityPropInfoQueueMap.get(entityClass);
        Optional<PropInfo> propInfoHolder = null;
        PropInfo propInfo = null;

        if (propInfoQueueMap == null) {
            propInfoQueueMap = new ObjectPool<>((entityInfo.propInfoList.size() + 1) * 2);
            entityPropInfoQueueMap.put(entityClass, propInfoQueueMap);
        } else {
            propInfoHolder = propInfoQueueMap.get(propName);
        }

        if (propInfoHolder == null) {
            final String[] strs = Splitter.with('.').splitToArray(propName);

            if (strs.length > 1) {
                Class<?> propClass = entityClass;
                BeanInfo propBeanInfo = null;

                for (int i = 0, len = strs.length; i < len; i++) {
                    propBeanInfo = ClassUtil.isBeanClass(propClass) ? ParserUtil.getBeanInfo(propClass) : null;
                    propInfo = propBeanInfo == null ? null : propBeanInfo.getPropInfo(strs[i]);

                    if (propInfo == null) {
                        if (i == 0) {
                            return null; // return directly because the first part is not valid property/field name of the target entity class.
                        }

                        break;
                    }

                    if (i == len - 1) {
                        propInfoHolder = Optional.of(propInfo);
                        break;
                    }

                    if (propInfo.type.isCollection()) {
                        propClass = propInfo.type.getElementType().clazz();
                    } else {
                        propClass = propInfo.clazz;
                    }
                }
            }

            propInfoQueueMap.put(propName, propInfoHolder == null ? Optional.empty() : propInfoHolder);
        } else if (propInfoHolder.isPresent()) {
            propInfo = propInfoHolder.get();
        }

        return propInfo;
    }

    static Object[] getParameterArray(final SP sp) {
        return N.isEmpty(sp.parameters) ? N.EMPTY_OBJECT_ARRAY : sp.parameters.toArray();
    }

    static <R> BiRowMapper<R> toBiRowMapper(final RowMapper<R> rowMapper) {
        return (rs, columnLabels) -> rowMapper.apply(rs);
    }

    @SuppressWarnings("rawtypes")
    private static final Map<Tuple2<Class<?>, Class<?>>, Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer> noIdGeneratorGetterSetter = Tuple
            .of(NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null, BiConsumers.doNothing());

    @SuppressWarnings({ "rawtypes", "deprecation", "null" })
    static <ID> Tuple3<BiRowMapper<ID>, com.landawn.abacus.util.function.Function<Object, ID>, com.landawn.abacus.util.function.BiConsumer<ID, Object>> getIdGeneratorGetterSetter(
            final Class<? extends Dao> daoInterface, final Class<?> entityClass, final NamingPolicy namingPolicy, final Class<?> idType) {
        if (entityClass == null || !ClassUtil.isBeanClass(entityClass)) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        final Tuple2<Class<?>, Class<?>> key = Tuple.of(entityClass, idType);

        Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>> map = idGeneratorGetterSetterPool
                .get(key);

        if (map == null) {
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(entityClass);
            final boolean isNoId = N.isEmpty(idPropNameList) || QueryUtil.isFakeId(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final BeanInfo entityInfo = isNoId ? null : ParserUtil.getBeanInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = isNoId ? false : idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);
            final BeanInfo idBeanInfo = idType != null && ClassUtil.isBeanClass(idType) ? ParserUtil.getBeanInfo(idType) : null;

            final com.landawn.abacus.util.function.Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? idPropInfo::getPropValue //
                            : (isEntityId ? entity -> {
                                final Seid ret = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (PropInfo propInfo : idPropInfoList) {
                                    ret.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            } : entity -> {
                                final Object ret = idBeanInfo.createBeanResult();

                                for (PropInfo propInfo : idPropInfoList) {
                                    ClassUtil.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) idBeanInfo.finishBeanResult(ret);
                            }));

            final com.landawn.abacus.util.function.BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (isEntityId ? (id, entity) -> {
                                if (id instanceof EntityId entityId) {
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
                                if (id != null && ClassUtil.isBeanClass(id.getClass())) {
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
                        .flatmapKey(e -> N.asList(e, e.toLowerCase(), e.toUpperCase()))
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
                                                        .filter(it -> idBeanInfo.getPropInfo(it) != null)
                                                        .map(it -> Tuple.of(it, idBeanInfo.getPropInfo(it)))
                                                        .toList();
                                                final Object id = idBeanInfo.createBeanResult();

                                                for (Tuple2<String, PropInfo> tp : tpList) {
                                                    tp._2.setPropValue(id, tp._2.dbType.get(rs, tp._1));
                                                }

                                                return idBeanInfo.finishBeanResult(id);
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

    /**
     *
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param idExtractor
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final RowMapper<? extends ID> idExtractor) {
        N.checkArgNotNull(daoInterface, "daoInterface");
        N.checkArgNotNull(idExtractor, "idExtractor");

        idExtractorPool.put(daoInterface, (rs, cls) -> idExtractor.apply(rs));
    }

    /**
     *
     *
     * @param <T>
     * @param <ID>
     * @param <SB>
     * @param <TD>
     * @param daoInterface
     * @param idExtractor
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final BiRowMapper<? extends ID> idExtractor) {
        N.checkArgNotNull(daoInterface, "daoInterface");
        N.checkArgNotNull(idExtractor, "idExtractor");

        idExtractorPool.put(daoInterface, idExtractor);
    }

    //    @SuppressWarnings("rawtypes")
    //    static Class<?> getTargetEntityClass(final Class<? extends Dao> daoInterface) {
    //        if (N.notEmpty(daoInterface.getGenericInterfaces()) && daoInterface.getGenericInterfaces()[0] instanceof ParameterizedType) {
    //            final ParameterizedType parameterizedType = (ParameterizedType) daoInterface.getGenericInterfaces()[0];
    //            java.lang.reflect.Type[] typeArguments = parameterizedType.getActualTypeArguments();
    //
    //            if (typeArguments.length >= 1 && typeArguments[0] instanceof Class) {
    //                if (!ClassUtil.isBeanClass((Class) typeArguments[0])) {
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
     * @param cache It's better to not share cache between Dao instances.
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
     * @param cache It's better to not share cache between Dao instances.
     * @param executor
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Cache<String, Object> cache, final Executor executor) {

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

        return DaoImpl.createDao(daoInterface, null, ds, sqlMapper, cache, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds) {
        return createDao(daoInterface, targetTableName, ds, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @param cache It's better to not share cache between Dao instances.
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Cache<String, Object> cache) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, cache, asyncExecutor.getExecutor());
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final Executor executor) {
        return createDao(daoInterface, targetTableName, ds, null, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @param executor
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Executor executor) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, null, executor);
    }

    /**
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @param cache It's better to not share cache between Dao instances.
     * @param executor
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final Cache<String, Object> cache, final Executor executor) {

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

        return DaoImpl.createDao(daoInterface, targetTableName, ds, sqlMapper, cache, executor);
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
    //                if (N.isEmpty(entityDaoPool)) {
    //                    dsEntityDaoPool.remove(ds);
    //                }
    //            }
    //        }
    //    }

    /**
     *
     *
     * @param ds
     * @param tableName
     * @return
     */
    public static String generateEntityClass(final DataSource ds, final String tableName) {
        return CodeGenerationUtil.generateEntityClass(ds, tableName);
    }

    /**
     *
     *
     * @param ds
     * @param tableName
     * @param config
     * @return
     */
    public static String generateEntityClass(final DataSource ds, final String tableName, final EntityCodeConfig config) {
        return CodeGenerationUtil.generateEntityClass(ds, tableName, config);
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateEntityClass(final Connection conn, final String tableName) {
        return CodeGenerationUtil.generateEntityClass(conn, tableName);
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @param config
     * @return
     */
    public static String generateEntityClass(final Connection conn, final String tableName, final EntityCodeConfig config) {
        return CodeGenerationUtil.generateEntityClass(conn, tableName, config);
    }

    /**
     *
     *
     * @param ds
     * @param entityName
     * @param query
     * @return
     */
    public static String generateEntityClass(final DataSource ds, final String entityName, String query) {
        return CodeGenerationUtil.generateEntityClass(ds, entityName, query);
    }

    /**
     *
     *
     * @param ds
     * @param entityName
     * @param query
     * @param config
     * @return
     */
    public static String generateEntityClass(final DataSource ds, final String entityName, String query, final EntityCodeConfig config) {
        return CodeGenerationUtil.generateEntityClass(ds, entityName, query, config);
    }

    /**
     *
     *
     * @param conn
     * @param entityName
     * @param query
     * @return
     */
    public static String generateEntityClass(final Connection conn, final String entityName, String query) {
        return CodeGenerationUtil.generateEntityClass(conn, entityName, query);
    }

    /**
     *
     *
     * @param conn
     * @param entityName
     * @param query
     * @param config
     * @return
     */
    public static String generateEntityClass(final Connection conn, final String entityName, String query, final EntityCodeConfig config) {
        return CodeGenerationUtil.generateEntityClass(conn, entityName, query, config);
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateSelectSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateSelectSql(conn, tableName);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateSelectSql(final Connection conn, final String tableName) {
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(columnLabelList, ", ", "select ", " from " + tableName);

        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateInsertSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateInsertSql(conn, tableName);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateInsertSql(final Connection conn, final String tableName) {
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(columnLabelList, ", ", "insert into " + tableName + "(",
                    ") values (" + Strings.repeat("?", columnLabelList.size(), ", ") + ")");
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateNamedInsertSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateNamedInsertSql(conn, tableName);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateNamedInsertSql(final Connection conn, final String tableName) {
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(columnLabelList, ", ", "insert into " + tableName + "(",
                    Stream.of(columnLabelList).map(it -> ":" + Strings.toCamelCase(it)).join(", ", ") values (", ")"));
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateUpdateSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateUpdateSql(conn, tableName);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateUpdateSql(final Connection conn, final String tableName) {
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return "update " + tableName + " set " + Stream.of(columnLabelList).map(it -> it + " = ?").join(", ");
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateNamedUpdateSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateNamedUpdateSql(conn, tableName);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateNamedUpdateSql(final Connection conn, final String tableName) {
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return "update " + tableName + " set " + Stream.of(columnLabelList).map(it -> it + " = :" + Strings.toCamelCase(it)).join(", ");
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
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
    //                if (N.isEmpty(entityDaoPool)) {
    //                    dsEntityDaoPool.remove(ds);
    //                }
    //            }
    //        }
    //    }

    /**
     *
     *
     * @param entity
     * @return
     */
    public static Collection<String> getInsertPropNames(final Object entity) {
        return getInsertPropNames(entity, null);
    }

    /**
     *
     *
     * @param entity
     * @param excludedPropNames
     * @return
     */
    public static Collection<String> getInsertPropNames(final Object entity, final Set<String> excludedPropNames) {
        return QueryUtil.getInsertPropNames(entity, excludedPropNames);
    }

    /**
     *
     *
     * @param entityClass
     * @return
     */
    public static Collection<String> getInsertPropNames(final Class<?> entityClass) {
        return getInsertPropNames(entityClass, null);
    }

    /**
     *
     *
     * @param entityClass
     * @param excludedPropNames
     * @return
     */
    public static Collection<String> getInsertPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.getInsertPropNames(entityClass, excludedPropNames);
    }

    /**
     *
     *
     * @param entityClass
     * @return
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass) {
        return getSelectPropNames(entityClass, null);
    }

    /**
     *
     *
     * @param entityClass
     * @param excludedPropNames
     * @return
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return getSelectPropNames(entityClass, false, excludedPropNames);
    }

    /**
     *
     *
     * @param entityClass
     * @param includeSubEntityProperties
     * @param excludedPropNames
     * @return
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final boolean includeSubEntityProperties,
            final Set<String> excludedPropNames) {
        return QueryUtil.getSelectPropNames(entityClass, includeSubEntityProperties, excludedPropNames);
    }

    /**
     *
     *
     * @param entityClass
     * @return
     */
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass) {
        return getUpdatePropNames(entityClass, null);
    }

    /**
     *
     *
     * @param entityClass
     * @param excludedPropNames
     * @return
     */
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.getUpdatePropNames(entityClass, excludedPropNames);
    }

    /**
     *
     *
     * @param blob
     * @return
     * @throws SQLException
     */
    public static String blob2String(final Blob blob) throws SQLException {
        return new String(blob.getBytes(1, (int) blob.length()), Charsets.UTF_8);
    }

    /**
     *
     *
     * @param blob
     * @param charset
     * @return
     * @throws SQLException
     */
    public static String blob2String(final Blob blob, final Charset charset) throws SQLException {
        return new String(blob.getBytes(1, (int) blob.length()), charset);
    }

    /**
     *
     *
     * @param blob
     * @param output
     * @return
     * @throws SQLException
     * @throws IOException
     */
    public static long writeBlobToFile(final Blob blob, final File output) throws SQLException, IOException {
        return IOUtil.write(blob.getBinaryStream(), output);
    }

    /**
     *
     *
     * @param value
     * @return
     */
    public static boolean isNullOrDefault(final Object value) {
        return (value == null) || N.equals(value, N.defaultValueOf(value.getClass()));
    }
}
