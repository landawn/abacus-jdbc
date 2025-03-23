/*
 * Copyright (c) 2015, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.function.Supplier;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiParametersSetter;
import com.landawn.abacus.jdbc.Jdbc.BiResultExtractor;
import com.landawn.abacus.jdbc.Jdbc.BiRowFilter;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.DaoCache;
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
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.ExceptionUtil;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.BiConsumers;
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
import com.landawn.abacus.util.stream.ObjIteratorEx;
import com.landawn.abacus.util.stream.Stream;

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
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Connection.html">Connection</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/Statement.html">Statement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/PreparedStatement.html">PreparedStatement</a>
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.sql/java/sql/ResultSet.html">ResultSet</a>
 */
@SuppressWarnings({ "java:S1192", "java:S6539", "resource" })
public final class JdbcUtil {

    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    static final Logger sqlLogger = LoggerFactory.getLogger("com.landawn.abacus.SQL");

    static final char CHAR_ZERO = 0;

    public static final int DEFAULT_BATCH_SIZE = 200;

    // static final int MAX_BATCH_SIZE = 1000;

    public static final int DEFAULT_FETCH_SIZE_FOR_BIG_RESULT = 1000;

    public static final int DEFAULT_FETCH_SIZE_FOR_STREAM = 100;

    public static final int DEFAULT_CACHE_CAPACITY = 1000;

    /**
     * Default cache evict delay in milliseconds
     */
    public static final int DEFAULT_CACHE_EVICT_DELAY = 3 * 1000;

    /**
     * Default cache live time in milliseconds.
     */
    public static final int DEFAULT_CACHE_LIVE_TIME = 30 * 60 * 1000;

    /**
     * Default cache idle time in milliseconds
     */
    public static final int DEFAULT_CACHE_MAX_IDLE_TIME = 3 * 60 * 1000;

    public static final Throwables.Function<Statement, String, SQLException> DEFAULT_SQL_EXTRACTOR = stmt -> {
        Statement stmtToUse = stmt;
        String clsName = stmtToUse.getClass().getName();

        if ((clsName.startsWith("com.zaxxer.hikari") || clsName.startsWith("com.mchange.v2.c3p0")) && stmt.isWrapperFor(Statement.class)) {
            stmtToUse = stmt.unwrap(Statement.class);
            clsName = stmtToUse.getClass().getName();
        }

        if (clsName.startsWith("oracle.jdbc") && (stmtToUse instanceof oracle.jdbc.internal.OraclePreparedStatement)) { //NOSONAR
            try {
                return ((oracle.jdbc.internal.OraclePreparedStatement) stmtToUse).getOriginalSql();
            } catch (final SQLException e) {
                // ignore.
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
     * Retrieves the database product information from the given DataSource.
     *
     * @param ds The DataSource from which to retrieve the database product information.
     * @return The database product information.
     * @throws UncheckedSQLException If a SQL exception occurs while retrieving the database product information.
     */
    public static DBProductInfo getDBProductInfo(final javax.sql.DataSource ds) throws UncheckedSQLException {
        Connection conn = null;

        try {
            conn = ds.getConnection();
            return getDBProductInfo(conn);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }
    }

    /**
     * Retrieves the database product information from the given connection.
     *
     * @param conn The connection to the database.
     * @return The database product information.
     * @throws UncheckedSQLException If a SQL exception occurs while retrieving the database product information.
     */
    public static DBProductInfo getDBProductInfo(final Connection conn) throws UncheckedSQLException {
        try {
            final DatabaseMetaData metaData = conn.getMetaData();

            final String dbProductName = metaData.getDatabaseProductName();
            final String dbProductVersion = metaData.getDatabaseProductVersion();
            final String upperCaseProductName = dbProductName.toUpperCase();

            DBVersion dbVersion = DBVersion.OTHERS;

            if (upperCaseProductName.contains("H2")) {
                dbVersion = DBVersion.H2;
            } else if (upperCaseProductName.contains("HSQL")) {
                dbVersion = DBVersion.HSQLDB;
            } else if (upperCaseProductName.contains("MYSQL")) {
                if (dbProductVersion.startsWith("5.5")) {
                    dbVersion = DBVersion.MYSQL_5_5;
                } else if (dbProductVersion.startsWith("5.6")) {
                    dbVersion = DBVersion.MYSQL_5_6;
                } else if (dbProductVersion.startsWith("5.7")) {
                    dbVersion = DBVersion.MYSQL_5_7;
                } else if (dbProductVersion.startsWith("5.8")) {
                    dbVersion = DBVersion.MYSQL_5_8;
                } else if (dbProductVersion.startsWith("5.9")) {
                    dbVersion = DBVersion.MYSQL_5_9;
                } else if (dbProductVersion.startsWith("6")) {
                    dbVersion = DBVersion.MYSQL_6;
                } else if (dbProductVersion.startsWith("7")) {
                    dbVersion = DBVersion.MYSQL_7;
                } else if (dbProductVersion.startsWith("8")) {
                    dbVersion = DBVersion.MYSQL_8;
                } else if (dbProductVersion.startsWith("9")) {
                    dbVersion = DBVersion.MYSQL_9;
                } else if (dbProductVersion.startsWith("10")) {
                    dbVersion = DBVersion.MYSQL_10;
                } else {
                    dbVersion = DBVersion.MYSQL_OTHERS;
                }
            } else if (upperCaseProductName.contains("POSTGRESQL")) {
                if (dbProductVersion.startsWith("9.2")) {
                    dbVersion = DBVersion.POSTGRESQL_9_2;
                } else if (dbProductVersion.startsWith("9.3")) {
                    dbVersion = DBVersion.POSTGRESQL_9_3;
                } else if (dbProductVersion.startsWith("9.4")) {
                    dbVersion = DBVersion.POSTGRESQL_9_4;
                } else if (dbProductVersion.startsWith("9.5")) {
                    dbVersion = DBVersion.POSTGRESQL_9_5;
                } else if (dbProductVersion.startsWith("10")) {
                    dbVersion = DBVersion.POSTGRESQL_10;
                } else if (dbProductVersion.startsWith("11")) {
                    dbVersion = DBVersion.POSTGRESQL_11;
                } else if (dbProductVersion.startsWith("12")) {
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

            return new DBProductInfo(dbProductName, dbProductName, dbVersion);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates a HikariCP DataSource with the specified database connection details.
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for the database connection.
     * @param password The password for the database connection.
     * @return A DataSource configured with the specified connection details.
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password) {
        try {
            final com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);

            return new com.zaxxer.hikari.HikariDataSource(config);
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a HikariCP DataSource with the specified database connection details.
     *
     * @param url
     * @param user
     * @param password
     * @param minIdle
     * @param maxPoolSize
     * @return
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password, final int minIdle,
            final int maxPoolSize) {
        try {
            final com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setMinimumIdle(minIdle);
            config.setMaximumPoolSize(maxPoolSize);

            return new com.zaxxer.hikari.HikariDataSource(config);
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a C3P0 DataSource with the specified database connection details.
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for the database connection.
     * @param password The password for the database connection.
     * @return A DataSource configured with the specified connection details.
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password) {
        try {
            final com.mchange.v2.c3p0.ComboPooledDataSource cpds = new com.mchange.v2.c3p0.ComboPooledDataSource();
            cpds.setJdbcUrl(url);
            cpds.setUser(user);
            cpds.setPassword(password);

            return cpds;
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a C3P0 DataSource with the specified database connection details.
     *
     * @param url
     * @param user
     * @param password
     * @param minPoolSize
     * @param maxPoolSize
     * @return
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password, final int minPoolSize,
            final int maxPoolSize) {
        try {
            final com.mchange.v2.c3p0.ComboPooledDataSource cpds = new com.mchange.v2.c3p0.ComboPooledDataSource();
            cpds.setJdbcUrl(url);
            cpds.setUser(user);
            cpds.setPassword(password);
            cpds.setMinPoolSize(minPoolSize);
            cpds.setMaxPoolSize(maxPoolSize);
            return cpds;
        } catch (final Exception e) {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a connection to the database using the specified URL, username, and password.
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for the database connection.
     * @param password The password for the database connection.
     * @return A Connection object that represents a connection to the database.
     * @throws UncheckedSQLException If a SQL exception occurs while creating the connection.
     */
    public static Connection createConnection(final String url, final String user, final String password) throws UncheckedSQLException {
        return createConnection(getDriverClassByUrl(url), url, user, password);
    }

    /**
     * Creates a connection to the database using the specified driver class, URL, username, and password.
     *
     * @param driverClass The fully qualified name of the JDBC driver class.
     * @param url The JDBC URL for the database connection.
     * @param user The username for the database connection.
     * @param password The password for the database connection.
     * @return A Connection object that represents a connection to the database.
     * @throws UncheckedSQLException If a SQL exception occurs while creating the connection.
     */
    public static Connection createConnection(final String driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        final Class<? extends Driver> cls = ClassUtil.forClass(driverClass);

        return createConnection(cls, url, user, password);
    }

    /**
     * Creates a connection to the database using the specified driver class, URL, username, and password.
     *
     * @param driverClass The fully qualified name of the JDBC driver class.
     * @param url The JDBC URL for the database connection.
     * @param user The username for the database connection.
     * @param password The password for the database connection.
     * @return A Connection object that represents a connection to the database.
     * @throws UncheckedSQLException If a SQL exception occurs while creating the connection.
     */
    public static Connection createConnection(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws UncheckedSQLException {
        try {
            DriverManager.registerDriver(N.newInstance(driverClass));

            return DriverManager.getConnection(url, user, password);
        } catch (final SQLException e) {
            throw new UncheckedSQLException("Failed to close create connection", e);
        }
    }

    /**
     * Gets the driver classs by url.
     *
     * @param url
     * @return
     */
    private static Class<? extends Driver> getDriverClassByUrl(final String url) {
        N.checkArgNotEmpty(url, cs.url);

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
        } catch (final Throwable e) {
            isInSpring = false;
        }
    }

    /**
     * Retrieves a connection from the specified DataSource.
     * If Spring transaction management is enabled and a transaction is active,
     * it will return the connection associated with the current transaction.
     * Otherwise, it will return a new connection from the DataSource.
     *
     * @param ds The DataSource from which to retrieve the connection.
     * @return A Connection object that represents a connection to the database.
     * @throws UncheckedSQLException If a SQL exception occurs while retrieving the connection.
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws UncheckedSQLException {
        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;

                try {
                    return ds.getConnection();
                } catch (final SQLException e1) {
                    throw new UncheckedSQLException(e1);
                }
            }
        } else {
            try {
                return ds.getConnection();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Releases the given connection back to the DataSource.
     * If Spring transaction management is enabled and a transaction is active,
     * it will release the connection associated with the current transaction.
     * Otherwise, it will close the connection directly.
     *
     * @param conn The Connection to be released.
     * @param ds The DataSource from which the connection was obtained.
     */
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (final NoClassDefFoundError e) {
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
            } catch (final SQLException e) {
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
        } catch (final SQLException e) {
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
            } catch (final SQLException e) {
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
            } catch (final SQLException e) {
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
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (final SQLException e) {
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
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (final SQLException e) {
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
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            try {
                if (stmt != null) {
                    stmt.close();
                }
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e); //NOSONAR
            } finally {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e); //NOSONAR
                }
            }
        }
    }

    /**
     * Unconditionally close an {@code ResultSet}.
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
        } catch (final SQLException e) {
            logger.error("Failed to get Statement or Connection by ResultSet", e);
        } finally {
            closeQuietly(rs, stmt, conn);
        }
    }

    /**
     * Unconditionally close an {@code Statement}.
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
     * Unconditionally close an {@code Connection}.
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
            } catch (final Exception e) {
                logger.error("Failed to close ResultSet", e);
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (final Exception e) {
                logger.error("Failed to close Statement", e);
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (final Exception e) {
                logger.error("Failed to close Connection", e);
            }
        }
    }

    /**
     * Skips the specified number of rows in the given ResultSet.
     *
     * @param rs The ResultSet to skip rows in.
     * @param n The number of rows to skip.
     * @return The number of rows actually skipped.
     * @throws SQLException If a SQL exception occurs while skipping rows.
     */
    public static int skip(final ResultSet rs, final int n) throws SQLException {
        return skip(rs, (long) n);
    }

    private static final Set<Class<?>> resultSetClassNotSupportAbsolute = ConcurrentHashMap.newKeySet();

    /**
     * Skips the specified number of rows in the given ResultSet.
     *
     * @param rs The ResultSet to skip rows in.
     * @param n The number of rows to skip.
     * @return The number of rows actually skipped.
     * @throws SQLException If a SQL exception occurs while skipping rows.
     * @see ResultSet#absolute(int)
     */
    public static int skip(final ResultSet rs, long n) throws SQLException {
        if (n <= 0) {
            return 0;
        } else if (n == 1) {
            return rs.next() ? 1 : 0;
        } else {
            final int currentRow = rs.getRow();

            if ((n > Integer.MAX_VALUE) || (n > Integer.MAX_VALUE - currentRow
                    || (resultSetClassNotSupportAbsolute.size() > 0 && resultSetClassNotSupportAbsolute.contains(rs.getClass())))) {
                while (n-- > 0L && rs.next()) {
                    // continue.
                }
            } else {
                try {
                    rs.absolute((int) n + currentRow);
                } catch (final SQLException e) {
                    while (n-- > 0L && rs.next()) {
                        // continue.
                    }

                    resultSetClassNotSupportAbsolute.add(rs.getClass());
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
    public static int getColumnCount(final ResultSet rs) throws SQLException {
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
    public static List<String> getColumnLabelList(final ResultSet rs) throws SQLException {
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
        } catch (final SQLException e) {
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
     * Retrieves the value of the specified column in the current row of the given ResultSet.
     *
     * @param rs The ResultSet from which to retrieve the column value.
     * @param columnIndex The index of the column to retrieve, starting from 1.
     * @return The value of the specified column in the current row of the ResultSet.
     * @throws SQLException If a SQL exception occurs while retrieving the column value.
     */
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws SQLException {
        return getColumnValue(rs, columnIndex, true);
    }

    /**
     * Retrieves the value of the specified column in the current row of the given ResultSet.
     * This method also checks the data type of the column value if specified.
     *
     * @param rs The ResultSet from which to retrieve the column value.
     * @param columnIndex The index of the column to retrieve, starting from 1.
     * @param checkDateType Whether to check the data type of the column value.
     * @return The value of the specified column in the current row of the ResultSet.
     * @throws SQLException If a SQL exception occurs while retrieving the column value.
     */
    static Object getColumnValue(final ResultSet rs, final int columnIndex, final boolean checkDateType) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object ret = rs.getObject(columnIndex);

        if (ret == null || ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
            return ret;
        }

        if (ret instanceof final Blob blob) {
            ret = blob.getBytes(1, (int) blob.length());
        } else if (ret instanceof final Clob clob) {
            ret = clob.getSubString(1, (int) clob.length());
        } else if (checkDateType) {
            ret = columnConverterByIndex.apply(rs, columnIndex, ret);
        }

        return ret;
    }

    /**
     * Retrieves the value of the specified column in the current row of the given ResultSet.
     *
     * @param rs The ResultSet from which to retrieve the column value.
     * @param columnLabel The label of the column to retrieve.
     * @return The value of the specified column in the current row of the ResultSet.
     * @throws SQLException If a SQL exception occurs while retrieving the column value.
     * @deprecated Please consider using {@link #getColumnValue(ResultSet, int)} instead.
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

        if (ret instanceof final Blob blob) {
            ret = blob.getBytes(1, (int) blob.length());
        } else if (ret instanceof final Clob clob) {
            ret = clob.getSubString(1, (int) clob.length());
        } else if (checkDateType) {
            ret = columnConverterByLabel.apply(rs, columnLabel, ret);
        }

        return ret;
    }

    /**
     * Retrieves all values of the specified column in the given ResultSet.
     *
     * @param <T> The type of the column values.
     * @param rs The ResultSet from which to retrieve the column values.
     * @param columnIndex The index of the column to retrieve, starting from 1.
     * @return A list of all values in the specified column.
     * @throws SQLException If a SQL exception occurs while retrieving the column values.
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

                        do {
                            result.add(rs.getTimestamp(columnIndex));
                        } while (rs.next());
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
     * Retrieves all values of the specified column in the given ResultSet.
     *
     * @param <T> The type of the column values.
     * @param rs The ResultSet from which to retrieve the column values.
     * @param columnLabel The label of the column to retrieve.
     * @return A list of all values in the specified column.
     * @throws SQLException If a SQL exception occurs while retrieving the column values.
     */
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final String columnLabel) throws SQLException {
        final int columnIndex = JdbcUtil.getColumnIndex(rs, columnLabel);

        if (columnIndex < 1) {
            throw new IllegalArgumentException("No column found by name: " + columnLabel + " in result set: " + JdbcUtil.getColumnLabelList(rs));
        }

        return getAllColumnValues(rs, columnIndex);
    }

    /**
     * Retrieves the value of the specified column in the current row of the given ResultSet.
     *
     * @param <T> The type of the column value.
     * @param rs The ResultSet from which to retrieve the column value.
     * @param columnIndex The index of the column to retrieve, starting from 1.
     * @param targetClass The class of the column value to retrieve.
     * @return The value of the specified column in the current row of the ResultSet.
     * @throws SQLException If a SQL exception occurs while retrieving the column value.
     */
    public static <T> T getColumnValue(final ResultSet rs, final int columnIndex, final Class<? extends T> targetClass) throws SQLException {
        return N.<T> typeOf(targetClass).get(rs, columnIndex);
    }

    /**
     * Retrieves the value of the specified column in the current row of the given ResultSet.
     *
     * @param <T> The type of the column value.
     * @param rs The ResultSet from which to retrieve the column value.
     * @param columnLabel The label of the column to retrieve.
     * @param targetClass The class of the column value to retrieve.
     * @return The value of the specified column in the current row of the ResultSet.
     * @throws SQLException If a SQL exception occurs while retrieving the column value.
     * @deprecated Please consider using {@link #getColumnValue(ResultSet, int, Class)} instead.
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
     * Retrieves a mapping of column names to field names for the specified entity class.
     *
     * @param entityClass The class of the entity for which to retrieve the column-to-field name mapping.
     * @return An immutable map where the keys are column names and the values are field names.
     */
    public static ImmutableMap<String, String> getColumn2FieldNameMap(final Class<?> entityClass) {
        return QueryUtil.getColumn2PropNameMap(entityClass);
    }

    /**
     * Checks if there is an active transaction for the given DataSource in current thread.
     *
     * @param ds The DataSource to check for an active transaction.
     * @return {@code true} if there is an active transaction, {@code false} otherwise.
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
            } catch (final NoClassDefFoundError e) {
                isInSpring = false;
            } finally {
                releaseConnection(conn, ds);
            }
        }

        return false;
    }

    /**
     * Begins a new transaction for the given DataSource.
     *
     * @param dataSource The DataSource for which to begin the transaction.
     * @return A SQLTransaction object representing the new transaction.
     * @throws UncheckedSQLException If a SQL exception occurs while beginning the transaction.
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource) throws UncheckedSQLException {
        return beginTransaction(dataSource, IsolationLevel.DEFAULT);
    }

    /**
     * Begins a new transaction for the given DataSource with the specified isolation level.
     *
     * @param dataSource The DataSource for which to begin the transaction.
     * @param isolationLevel The isolation level for the transaction.
     * @return A SQLTransaction object representing the new transaction.
     * @throws UncheckedSQLException If a SQL exception occurs while beginning the transaction.
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
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
     */
    public static SQLTransaction beginTransaction(final javax.sql.DataSource dataSource, final IsolationLevel isolationLevel, final boolean isForUpdateOnly)
            throws UncheckedSQLException {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(isolationLevel, cs.isolationLevel);

        SQLTransaction tran = SQLTransaction.getTransaction(dataSource, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try { //NOSONAR
                conn = getConnection(dataSource);
                tran = new SQLTransaction(dataSource, conn, isolationLevel, CreatedBy.JDBC_UTIL, true); //NOSONAR
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (final SQLException e) {
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
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

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
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource dataSource, final Throwables.Function<Connection, T, E> cmd)
            throws E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

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
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            cmd.run();
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     * Executes the given command within a transaction for the specified DataSource.
     * If the command completes successfully, the transaction is committed.
     * If an exception occurs, the transaction is rolled back.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to begin the transaction.
     * @param cmd The command to execute within the transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource dataSource, final Throwables.Consumer<Connection, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

        final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            cmd.accept(tran.connection());
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    /**
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Callable<T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

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
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <T> The type of the result returned by the command.
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @return The result of the command execution.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <T, E extends Throwable> T callNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Function<javax.sql.DataSource, T, E> cmd) throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

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
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

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
     * Executes the given command outside any started transaction for the specified DataSource.
     * If a transaction is already started in current thread, a new connection which is not used to started transaction will be used to execute the command.
     *
     * @param <E> The type of exception that the command may throw.
     * @param dataSource The DataSource for which to execute the command.
     * @param cmd The command to execute outside any started transaction.
     * @throws IllegalArgumentException If the dataSource or cmd is {@code null}.
     * @throws E If the command throws an exception.
     */
    @Beta
    public static <E extends Throwable> void runNotInStartedTransaction(final javax.sql.DataSource dataSource,
            final Throwables.Consumer<javax.sql.DataSource, E> cmd) throws IllegalArgumentException, E {
        N.checkArgNotNull(dataSource, cs.dataSource);
        N.checkArgNotNull(cmd, cs.cmd);

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
    static SQLOperation getSQLOperation(final String sql) {
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
            for (final SQLOperation so : SQLOperation.values()) {
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
     * Prepares a SQL query using the provided DataSource and SQL string.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

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
     * Prepares a SQL query using the provided DataSource and SQL string.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @param autoGeneratedKeys whether auto-generated keys should be returned
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

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
     * Prepares a SQL query using the provided DataSource, SQL string, and column indexes for auto-generated keys.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @param returnColumnIndexes the column indexes for which auto-generated keys should be returned
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

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
     * Prepares a SQL query using the provided DataSource, SQL string, and column names for auto-generated keys.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @param returnColumnNames the column names for which auto-generated keys should be returned
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

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
     * Prepares a SQL query using the provided DataSource, SQL string, and a custom statement creator.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @param stmtCreator a function to create a PreparedStatement
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

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
     * Prepares a SQL query using the provided Connection and SQL string.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new PreparedQuery(prepareStatement(conn, sql));
    }

    /**
     * Prepares a SQL query using the provided Connection and SQL string.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, autoGeneratedKeys);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @param autoGeneratedKeys whether auto-generated keys should be returned
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new PreparedQuery(prepareStatement(conn, sql, autoGeneratedKeys));
    }

    /**
     * Prepares a SQL query using the provided Connection, SQL string, and column indexes for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnIndexes);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @param returnColumnIndexes the column indexes for which auto-generated keys should be returned
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the Connection, SQL string, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnIndexes));
    }

    /**
     * Prepares a SQL query using the provided Connection, SQL string, and column names for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, returnColumnNames);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @param returnColumnNames the column names for which auto-generated keys should be returned
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the Connection, SQL string, or returnColumnNames is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

        return new PreparedQuery(prepareStatement(conn, sql, returnColumnNames));
    }

    /**
     * Prepares a SQL query using the provided Connection and SQL string.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @param stmtCreator a function to create a PreparedStatement
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws IllegalArgumentException if the Connection, SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return new PreparedQuery(prepareStatement(conn, sql, stmtCreator));
    }

    /**
     * Prepares a SQL query for a large result set using the provided DataSource and SQL string.
     * <p>
     * This method sets the fetch direction to {@code FetchDirection.FORWARD} and the fetch size to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    @Beta
    public static PreparedQuery prepareQueryForBigResult(final javax.sql.DataSource ds, final String sql) throws SQLException {
        return prepareQuery(ds, sql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a SQL query for a large result set using the provided Connection and SQL string.
     * <p>
     * This method sets the fetch direction to {@code FetchDirection.FORWARD} and the fetch size to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @return a PreparedQuery object representing the prepared SQL query
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    @Beta
    public static PreparedQuery prepareQueryForBigResult(final Connection conn, final String sql) throws SQLException {
        return prepareQuery(conn, sql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query using the provided DataSource and named SQL string.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

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
     * Prepares a named SQL query using the provided DataSource and named SQL string.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys whether auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

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
     * Prepares a named SQL query using the provided DataSource, named SQL string, and column indexes for auto-generated keys.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes the column indexes for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource, named SQL string, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

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
     * Prepares a named SQL query using the provided DataSource, named SQL string, and column names for auto-generated keys.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames the column names for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource, named SQL string, or returnColumnNames is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

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
     * Prepares a named SQL query using the provided DataSource, named SQL string, and a custom statement creator.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator a function to create a PreparedStatement
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource, named SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

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
     * Prepares a named SQL query using the provided Connection and named SQL string.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql), parsedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection, named SQL string, and a flag for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, autoGeneratedKeys);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys whether auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, autoGeneratedKeys), parsedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection, named SQL string, and column indexes for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, returnColumnIndexes);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes the column indexes for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection, named SQL string, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnIndexes), parsedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection, named SQL string, and column names for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, returnColumnNames);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames the column names for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection, named SQL string, or returnColumnNames is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, returnColumnNames), parsedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection and named SQL string.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, stmtCreator);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator a function to create a PreparedStatement
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection, named SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, stmtCreator), parsedSql);
    }

    /**
     * Prepares a named SQL query using the provided DataSource and ParsedSql object.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
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
     * Prepares a named SQL query using the provided DataSource, ParsedSql object, and a flag for auto-generated keys.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys whether auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
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
     * Prepares a named SQL query using the provided DataSource, ParsedSql object, and column indexes for auto-generated keys.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes the column indexes for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource, named SQL string, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);
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
     * Prepares a named SQL query using the provided DataSource, ParsedSql object, and column names for auto-generated keys.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames the column names for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource, named SQL string, or returnColumnNames is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);
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
     * Prepares a named SQL query using the provided DataSource, ParsedSql object, and a custom statement creator.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator a function to create a PreparedStatement
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the DataSource, named SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);
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
     * Prepares a named SQL query using the provided Connection and ParsedSql object.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql), namedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection, ParsedSql object, and a flag for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, autoGeneratedKeys);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param autoGeneratedKeys whether auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection or named SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, autoGeneratedKeys), namedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection, ParsedSql object, and column indexes for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, returnColumnIndexes);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnIndexes the column indexes for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection, named SQL string, or returnColumnIndexes is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final int[] returnColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnIndexes, cs.returnColumnIndexes);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnIndexes), namedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection, ParsedSql object, and column names for auto-generated keys.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, returnColumnNames);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param returnColumnNames the column names for which auto-generated keys should be returned
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection, named SQL string, or returnColumnNames is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final String[] returnColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(returnColumnNames, cs.returnColumnNames);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, returnColumnNames), namedSql);
    }

    /**
     * Prepares a named SQL query using the provided Connection and ParsedSql object.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareNamedQuery(dataSource.getConnection(), namedSql, stmtCreator);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @param stmtCreator a function to create a PreparedStatement
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws IllegalArgumentException if the Connection, named SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, stmtCreator), namedSql);
    }

    /**
     * Prepares a named SQL query for a big result set using the provided DataSource and named SQL string.
     * <p>
     * This method configures the fetch direction to {@code FetchDirection.FORWARD} and sets the fetch size to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final javax.sql.DataSource ds, final String namedSql) throws SQLException {
        return prepareNamedQuery(ds, namedSql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query for a big result set using the provided DataSource and ParsedSql object.
     * <p>
     * This method configures the fetch direction to {@code FetchDirection.FORWARD} and sets the fetch size to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param namedSql the named SQL string to prepare, for example {@code SELECT first_name, last_name FROM account where id = :id}
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final javax.sql.DataSource ds, final ParsedSql namedSql) throws SQLException {
        return prepareNamedQuery(ds, namedSql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query for a big result set using the provided Connection and named SQL string.
     * <p>
     * This method configures the fetch direction to {@code FetchDirection.FORWARD} and sets the fetch size to {@code DEFAULT_FETCH_SIZE_FOR_BIG_RESULT=1000}.
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param namedSql the named SQL string to prepare
     * @return a NamedQuery object representing the prepared named SQL query
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    @Beta
    public static NamedQuery prepareNamedQueryForBigResult(final Connection conn, final String namedSql) throws SQLException {
        return prepareNamedQuery(conn, namedSql).configStmt(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a callable SQL query using the provided DataSource and SQL Stored Procedure.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @return a CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

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
     * Prepares a callable SQL query using the provided DataSource and SQL Stored Procedure.
     * <p>
     * If this method is called where a transaction is started by {@code JdbcUtil.beginTransaction}
     * or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the Transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to prepare
     * @return a CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

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
     * Prepares a callable SQL query using the provided Connection and SQL Stored Procedure.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @return a CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new CallableQuery(prepareCallable(conn, sql));
    }

    /**
     * Prepares a callable SQL query using the provided Connection and SQL Stored Procedure.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * Never write below code because it will definitely cause {@code Connection} leak:
     * <pre>
     * <code>
     * JdbcUtil.prepareCallableQuery(dataSource.getConnection(), sql, stmtCreator);
     * </code>
     * </pre>
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to prepare
     * @param stmtCreator a function to create a CallableStatement
     * @return a CallableQuery object representing the prepared callable SQL query
     * @throws IllegalArgumentException if the Connection, SQL string, or stmtCreator is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while preparing the query
     */
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

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
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

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
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

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
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        for (final Object parameters : parametersList) {
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
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        for (final Object parameters : parametersList) {
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
        N.checkArgNotEmpty(namedSql, cs.namedSql);

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
     * Executes a SQL query using the provided DataSource and SQL string with optional parameters.
     * <p>
     * If a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the query
     * @param sql the SQL string to execute
     * @param parameters optional parameters for the SQL query
     * @return a DataSet object containing the result of the query
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the query
     */
    @SafeVarargs
    public static DataSet executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

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
     * Executes a SQL query using the provided Connection and SQL string with optional parameters.
     * <p>
     * This method does not close the provided Connection after the query is executed.
     * </p>
     *
     * @param conn the Connection to use for the query
     * @param sql the SQL string to execute
     * @param parameters optional parameters for the SQL query
     * @return a DataSet object containing the result of the query
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the query
     */
    @SafeVarargs
    public static DataSet executeQuery(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

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
    //            rs = executeQuery(stmt);
    //
    //            return extractData(rs);
    //        } finally {
    //            closeQuietly(rs);
    //        }
    //    }

    /**
     * Executes a SQL update using the provided DataSource and SQL string with optional parameters.
     * <p>
     * If a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the update
     * @param sql the SQL string to execute
     * @param parameters optional parameters for the SQL update
     * @return the number of rows affected by the update
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the update
     */
    @SafeVarargs
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

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
     * Executes a SQL update using the provided Connection and SQL string with optional parameters.
     * <p>
     * This method does not close the provided Connection after the update is executed.
     * </p>
     *
     * @param conn the Connection to use for the update
     * @param sql the SQL string to execute
     * @param parameters optional parameters for the SQL update
     * @return the number of rows affected by the update
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the update
     */
    @SafeVarargs
    public static int executeUpdate(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return executeUpdate(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    /**
     * Executes a batch SQL update using the provided DataSource, SQL string, and list of parameters.
     * <p>
     * If a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeBatchUpdate(ds, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a batch SQL update using the provided DataSource, SQL string, list of parameters, and batch size.
     * <p>
     * If a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @param batchSize the size of each batch
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

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
     * Executes a batch SQL update using the provided Connection, SQL string, and list of parameters.
     * <p>
     * This method does not close the provided Connection after the batch update is executed.
     * </p>
     *
     * @param conn the Connection to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeBatchUpdate(conn, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a batch SQL update using the provided Connection, SQL string, list of parameters, and batch size.
     * <p>
     * This method does not close the provided Connection after the batch update is executed.
     * </p>
     *
     * @param conn the Connection to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @param batchSize the size of each batch
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, cs.batchSize);

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

            for (final Object parameter : listOfParameters) {
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
     * Executes a large batch SQL update using the provided DataSource, SQL string, and list of parameters.
     * <p>
     * If a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeLargeBatchUpdate(ds, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a large batch SQL update using the provided DataSource, SQL string, list of parameters, and batch size.
     * <p>
     * If a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @param batchSize the size of each batch
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

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
     * Executes a large batch SQL update using the provided Connection, SQL string, and list of parameters.
     * <p>
     * This method does not close the provided Connection after the batch update is executed.
     * </p>
     *
     * @param conn the Connection to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeLargeBatchUpdate(conn, sql, listOfParameters, DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a large batch SQL update using the provided Connection, SQL string, list of parameters, and batch size.
     * <p>
     * This method does not close the provided Connection after the batch update is executed.
     * </p>
     *
     * @param conn the Connection to use for the batch update
     * @param sql the SQL string to execute
     * @param listOfParameters a list of parameter sets for the batch update
     * @param batchSize the size of each batch
     * @return the number of rows affected by the batch update
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the batch update
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(sql);
        N.checkArgPositive(batchSize, cs.batchSize);

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

            for (final Object parameter : listOfParameters) {
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
     * Executes a SQL statement using the provided DataSource, SQL string, and optional parameters.
     * <p>
     * If a transaction is started by {@code JdbcUtil.beginTransaction} or in {@code Spring} with the same {@code DataSource} in the same thread,
     * the {@code Connection} started the transaction will be used here.
     * Otherwise, a {@code Connection} directly from the specified {@code DataSource} (Connection pool) will be borrowed and used.
     * </p>
     *
     * @param ds the DataSource to use for the SQL execution
     * @param sql the SQL string to execute
     * @param parameters optional parameters for the SQL statement
     * @return {@code true} if the first result is a ResultSet object; {@code false} if it is an update count or there are no results
     * @throws IllegalArgumentException if the DataSource or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the statement
     */
    @SafeVarargs
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.dataSource);
        N.checkArgNotEmpty(sql, cs.sql);

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
     * Executes a SQL statement using the provided Connection, SQL string, and optional parameters.
     * <p>
     * This method does not close the provided Connection after the statement is executed.
     * </p>
     *
     * @param conn the Connection to use for the SQL execution
     * @param sql the SQL string to execute
     * @param parameters optional parameters for the SQL statement
     * @return {@code true} if the first result is a ResultSet object; {@code false} if it is an update count or there are no results
     * @throws IllegalArgumentException if the Connection or SQL string is {@code null} or empty
     * @throws SQLException if a SQL exception occurs while executing the statement
     */
    @SafeVarargs
    public static boolean execute(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        PreparedStatement stmt = null;

        try {
            stmt = prepareStmt(conn, sql, parameters);

            return JdbcUtil.execute(stmt);
        } finally {
            closeQuietly(stmt);
        }
    }

    static ResultSet executeQuery(final PreparedStatement stmt) throws SQLException {
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

    static int executeUpdate(final PreparedStatement stmt) throws SQLException {
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

    static long executeLargeUpdate(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeUpdate();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeUpdate", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeUpdate();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeUpdate", e);
                }
            }
        }
    }

    static int[] executeBatch(final Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeBatch();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeBatch", e);
                }
            }
        }
    }

    static long[] executeLargeBatch(final Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = minExecutionTimeForSqlPerfLog_TL.get();

        if (isToHandleSqlLog(sqlLogConfig)) {
            final long startTime = System.currentTimeMillis();

            try {
                return stmt.executeLargeBatch();
            } finally {
                handleSqlLog(stmt, sqlLogConfig, startTime);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        } else {
            try {
                return stmt.executeLargeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.error("Failed to clear batch parameters after executeLargeBatch", e);
                }
            }
        }
    }

    static boolean execute(final PreparedStatement stmt) throws SQLException {
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
            } catch (final SQLException e) {
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
                @SuppressWarnings("UnnecessaryLocalVariable")
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
            } else if (parameters[0] instanceof final Collection<?> c && (((List<?>) parameters[0]).size() >= parsedSql.getParameterCount())) {
                return c.toArray(new Object[0]);
            }
        }

        return parameters;
    }

    static boolean isEntityOrMapParameter(final ParsedSql parsedSql, final Object... parameters) {
        if (N.isEmpty(parsedSql.getNamedParameters()) || N.isEmpty(parameters) || (parameters.length != 1) || (parameters[0] == null)) {
            return false;
        }

        final Class<?> cls = parameters[0].getClass();

        return ClassUtil.isBeanClass(cls) || ClassUtil.isRecordClass(cls) || Map.class.isAssignableFrom(cls) || EntityId.class.isAssignableFrom(cls);
    }

    static final RowFilter INTERNAL_DUMMY_ROW_FILTER = RowFilter.ALWAYS_TRUE;

    static final RowExtractor INTERNAL_DUMMY_ROW_EXTRACTOR = (rs, outputRow) -> {
        throw new UnsupportedOperationException("DO NOT CALL ME.");
    };

    /**
     * Extracts data from the provided ResultSet and returns it as a DataSet.
     *
     * @param rs the ResultSet to extract data from
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs) throws SQLException {
        return extractData(rs, false);
    }

    /**
     * Extracts data from the provided ResultSet starting from the specified offset and up to the specified count.
     *
     * @param rs the ResultSet to extract data from
     * @param offset the starting position in the ResultSet
     * @param count the number of rows to extract
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count) throws SQLException {
        return extractData(rs, offset, count, false);
    }

    /**
     * Extracts data from the provided ResultSet using the specified RowFilter.
     *
     * @param rs the ResultSet to extract data from
     * @param filter the RowFilter to apply while extracting data
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final RowFilter filter) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, false);
    }

    /**
     * Extracts data from the provided ResultSet using the specified RowExtractor.
     *
     * @param rs the ResultSet to extract data from
     * @param rowExtractor the RowExtractor to apply while extracting data
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final RowExtractor rowExtractor) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, false);
    }

    /**
     * Extracts data from the provided ResultSet using the specified RowFilter and RowExtractor.
     *
     * @param rs the ResultSet to extract data from
     * @param filter the RowFilter to apply while extracting data
     * @param rowExtractor the RowExtractor to apply while extracting data
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final RowFilter filter, final RowExtractor rowExtractor) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, filter, rowExtractor, false);
    }

    /**
     * Extracts data from the provided ResultSet and returns it as a DataSet.
     * <p>
     * This method allows specifying whether to close the ResultSet after extraction.
     * </p>
     *
     * @param rs the ResultSet to extract data from
     * @param closeResultSet whether to close the ResultSet after extraction
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final boolean closeResultSet) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet starting from the specified offset and up to the specified count.
     * <p>
     * This method allows specifying whether to close the ResultSet after extraction.
     * </p>
     *
     * @param rs the ResultSet to extract data from
     * @param offset the starting position in the ResultSet
     * @param count the number of rows to extract
     * @param closeResultSet whether to close the ResultSet after extraction
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet starting from the specified offset and up to the specified count.
     * <p>
     * This method allows specifying a RowFilter to apply while extracting data and whether to close the ResultSet after extraction.
     * </p>
     *
     * @param rs the ResultSet to extract data from
     * @param offset the starting position in the ResultSet
     * @param count the number of rows to extract
     * @param filter the RowFilter to apply while extracting data
     * @param closeResultSet whether to close the ResultSet after extraction
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count, final RowFilter filter, final boolean closeResultSet)
            throws SQLException {
        return extractData(rs, offset, count, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet starting from the specified offset and up to the specified count.
     * <p>
     * This method allows specifying a RowExtractor to apply while extracting data and whether to close the ResultSet after extraction.
     * </p>
     *
     * @param rs the ResultSet to extract data from
     * @param offset the starting position in the ResultSet
     * @param count the number of rows to extract
     * @param rowExtractor the RowExtractor to apply while extracting data
     * @param closeResultSet whether to close the ResultSet after extraction
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count, final RowExtractor rowExtractor, final boolean closeResultSet)
            throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet starting from the specified offset and up to the specified count.
     * <p>
     * This method allows specifying a RowFilter and a RowExtractor to apply while extracting data and whether to close the ResultSet after extraction.
     * </p>
     *
     * @param rs the ResultSet to extract data from
     * @param offset the starting position in the ResultSet
     * @param count the number of rows to extract
     * @param filter the RowFilter to apply while extracting data
     * @param rowExtractor the RowExtractor to apply while extracting data
     * @param closeResultSet whether to close the ResultSet after extraction
     * @return a DataSet containing the extracted data
     * @throws SQLException if a SQL exception occurs while extracting data
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static DataSet extractData(final ResultSet rs, final int offset, final int count, final RowFilter filter, final RowExtractor rowExtractor,
            final boolean closeResultSet) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.ResultSet);
        N.checkArgNotNegative(offset, cs.offset);
        N.checkArgNotNegative(count, cs.count);
        N.checkArgNotNull(filter, cs.filter);
        N.checkArgNotNull(rowExtractor, cs.rowExtractor);
        final boolean checkDateType = checkDateType(rs);

        try {
            return JdbcUtil.extractResultSetToDataSet(rs, offset, count, filter, rowExtractor, checkDateType);
        } finally {
            if (closeResultSet) {
                closeQuietly(rs);
            }
        }
    }

    static DataSet extractResultSetToDataSet(final ResultSet rs, final int offset, int count, final RowFilter filter, final RowExtractor rowExtractor,
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

    static <R> R extractAndCloseResultSet(final ResultSet rs, final ResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs));
        } finally {
            closeQuietly(rs);
        }
    }

    static <R> R extractAndCloseResultSet(final ResultSet rs, final BiResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs, getColumnLabelList(rs)));
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     * Creates a stream from the provided ResultSet.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param resultSet the ResultSet to create a stream from
     * @return a Stream of Object arrays containing the data from the ResultSet
     * @throws IllegalArgumentException if the provided ResultSet is null
     */
    public static Stream<Object[]> stream(final ResultSet resultSet) {
        return stream(resultSet, Object[].class);
    }

    /**
     * Creates a stream from the provided ResultSet.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param targetClass the class of the result type
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final Class<? extends T> targetClass) throws IllegalArgumentException {
        N.checkArgNotNull(targetClass, cs.targetClass);
        N.checkArgNotNull(resultSet, cs.resultSet);

        return stream(resultSet, BiRowMapper.to(targetClass));
    }

    /**
     * Creates a stream from the provided ResultSet using the specified RowMapper.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet, rowMapper).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowMapper the RowMapper to apply while extracting data
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final RowMapper<? extends T> rowMapper) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowMapper, null));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final RowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new ObjIteratorEx<>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (!hasNext) {
                    try {
                        hasNext = resultSet.next();
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                try {
                    return rowMapper.apply(resultSet);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }

            @Override
            public void advance(final long n) throws IllegalArgumentException {
                N.checkArgNotNegative(n, cs.n);

                final long m = hasNext ? n - 1 : n;

                try {
                    JdbcUtil.skip(resultSet, m);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                hasNext = false;
            }

            @Override
            public long count() {
                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                try {
                    while (resultSet.next()) {
                        cnt++;
                    }
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                return cnt;
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified RowFilter and RowMapper.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet, rowFilter, rowMapper).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowFilter the RowFilter to apply while filtering rows
     * @param rowMapper the RowMapper to apply while extracting data
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper)
            throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowFilter, rowMapper, null));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper, final Runnable onClose) {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return new ObjIteratorEx<>() {
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (!hasNext) {
                    try {
                        while (resultSet.next()) {
                            if (rowFilter.test(resultSet)) {
                                hasNext = true;
                                break;
                            }
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                try {
                    return rowMapper.apply(resultSet);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified BiRowMapper.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet, rowMapper).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowMapper the BiRowMapper to apply while extracting data
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowMapper, null));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final BiRowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new ObjIteratorEx<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (!hasNext) {
                    try {
                        hasNext = resultSet.next();
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                if (columnLabels == null) {
                    try {
                        columnLabels = getColumnLabelList(resultSet);
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                try {
                    return rowMapper.apply(resultSet, columnLabels);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }

            @Override
            public void advance(final long n) {
                N.checkArgNotNegative(n, cs.n);

                final long m = hasNext ? n - 1 : n;

                try {
                    JdbcUtil.skip(resultSet, m);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                hasNext = false;
            }

            @Override
            public long count() {
                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                try {
                    while (resultSet.next()) {
                        cnt++;
                    }
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                return cnt;
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (onClose != null) {
                    onClose.run();
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified BiRowFilter and BiRowMapper.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet, rowFilter, rowMapper).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param rowFilter the BiRowFilter to apply while filtering rows
     * @param rowMapper the BiRowMapper to apply while extracting data
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(resultSet, rowFilter, rowMapper));
    }

    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper) {
        return new ObjIteratorEx<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;

            @Override
            public boolean hasNext() {
                if (columnLabels == null) {
                    try {
                        columnLabels = JdbcUtil.getColumnLabelList(resultSet);
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                if (!hasNext) {
                    try {
                        while (resultSet.next()) {
                            if (rowFilter.test(resultSet, columnLabels)) {
                                hasNext = true;
                                break;
                            }
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return hasNext;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                hasNext = false;

                try {
                    return rowMapper.apply(resultSet, columnLabels);
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        };
    }

    /**
     * Creates a stream from the provided ResultSet using the specified column index.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet, columnIndex).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param columnIndex the index of the column to extract data from, starting from 1
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final int columnIndex) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgPositive(columnIndex, cs.columnIndex);

        final boolean checkDateType = JdbcUtil.checkDateType(resultSet);
        final RowMapper<? extends T> rowMapper = rs -> (T) getColumnValue(resultSet, columnIndex, checkDateType);

        return stream(resultSet, rowMapper);
    }

    /**
     * Creates a stream from the provided ResultSet using the specified column name.
     * <p>
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.stream(resultSet, columnName).onClose(Fn.closeQuietly(resultSet))...}
     * </p>
     *
     * @param <T> the type of the result extracted from the ResultSet
     * @param resultSet the ResultSet to create a stream from
     * @param columnName the name of the column to extract data from
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    public static <T> Stream<T> stream(final ResultSet resultSet, final String columnName) throws IllegalArgumentException {
        N.checkArgNotNull(resultSet, cs.resultSet);
        N.checkArgNotEmpty(columnName, cs.columnName);

        final RowMapper<? extends T> rowMapper = new RowMapper<>() {
            private int columnIndex = -1;
            private boolean checkDateType = true;

            @Override
            public T apply(final ResultSet rs) throws SQLException {
                if (columnIndex == -1) {
                    columnIndex = getColumnIndex(resultSet, columnName);
                    checkDateType = JdbcUtil.checkDateType(resultSet);
                }

                return (T) getColumnValue(resultSet, columnIndex, checkDateType);
            }
        };

        return stream(resultSet, rowMapper);
    }

    //    /**
    //     * Creates a stream of all result sets from the provided Statement.
    //     * <p>
    //     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
    //     * <br />
    //     * {@code JdbcUtil.streamAllResultSets(stmt, targetClass).onClose(Fn.closeQuietly(stmt))...}
    //     * </p>
    //     *
    //     * @param <T> the type of the result extracted from each ResultSet
    //     * @param stmt the Statement to create streams from
    //     * @param targetClass the class of the result type
    //     * @return a Stream of Streams of the extracted results
    //     * @throws IllegalArgumentException if the provided arguments are invalid
    //     */
    //    @SuppressWarnings("resource")
    //    public static <T> Stream<Stream<T>> streamAllResultSets(final Statement stmt, final Class<? extends T> targetClass) throws IllegalArgumentException {
    //        N.checkArgNotNull(stmt, s.stmt);
    //        N.checkArgNotNull(targetClass, s.targetClass);
    //
    //        JdbcUtil.checkDateType(stmt);
    //
    //        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));
    //
    //        return Stream.just(supplier) //
    //                .onClose(() -> supplier.get().close())
    //                .flatMap(it -> Stream.of(it.get()))
    //                .map(rs -> {
    //                    final BiRowMapper<T> rowMapper = BiRowMapper.to(targetClass);
    //
    //                    return JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs));
    //                });
    //    }
    //
    //    /**
    //     * Creates a stream of all result sets from the provided Statement.
    //     * <p>
    //     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
    //     * <br />
    //     * {@code JdbcUtil.streamAllResultSets(stmt, rowMapper).onClose(Fn.closeQuietly(stmt))...}
    //     * </p>
    //     *
    //     * @param <T> the type of the result extracted from each ResultSet
    //     * @param stmt the Statement to create streams from
    //     * @param rowMapper the RowMapper to map each row of the ResultSet to the desired type
    //     * @return a Stream of Streams of the extracted results
    //     * @throws IllegalArgumentException if the provided arguments are invalid
    //     */
    //    @SuppressWarnings("resource")
    //    public static <T> Stream<Stream<T>> streamAllResultSets(final Statement stmt, final RowMapper<? extends T> rowMapper) throws IllegalArgumentException {
    //        N.checkArgNotNull(stmt, s.stmt);
    //        N.checkArgNotNull(rowMapper, s.rowMapper);
    //
    //        JdbcUtil.checkDateType(stmt);
    //
    //        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));
    //
    //        return Stream.just(supplier)
    //                .onClose(() -> supplier.get().close())
    //                .flatMap(it -> Stream.of(it.get()))
    //                .map(rs -> JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    //    }
    //
    //    /**
    //     * Creates a stream of all result sets from the provided Statement.
    //     * <p>
    //     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
    //     * <br />
    //     * {@code JdbcUtil.streamAllResultSets(stmt, rowFilter, rowMapper).onClose(Fn.closeQuietly(stmt))...}
    //     * </p>
    //     *
    //     * @param <T> the type of the result extracted from each ResultSet
    //     * @param stmt the Statement to create streams from
    //     * @param rowFilter the RowFilter to filter rows of the ResultSet
    //     * @param rowMapper the RowMapper to map each row of the ResultSet to the desired type
    //     * @return a Stream of Streams of the extracted results
    //     * @throws IllegalArgumentException if the provided arguments are invalid
    //     */
    //    @SuppressWarnings("resource")
    //    public static <T> Stream<Stream<T>> streamAllResultSets(final Statement stmt, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(stmt, s.stmt);
    //        N.checkArgNotNull(rowFilter, s.rowFilter);
    //        N.checkArgNotNull(rowMapper, s.rowMapper);
    //
    //        JdbcUtil.checkDateType(stmt);
    //
    //        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));
    //
    //        return Stream.just(supplier)
    //                .onClose(() -> supplier.get().close())
    //                .flatMap(it -> Stream.of(it.get()))
    //                .map(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    //    }
    //
    //    /**
    //     * Creates a stream of all result sets from the provided Statement.
    //     * <p>
    //     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
    //     * <br />
    //     * {@code JdbcUtil.streamAllResultSets(stmt, rowMapper).onClose(Fn.closeQuietly(stmt))...}
    //     * </p>
    //     *
    //     * @param <T> the type of the result extracted from each ResultSet
    //     * @param stmt the Statement to create streams from
    //     * @return a Stream of Streams of the extracted results
    //     * @throws IllegalArgumentException if the provided arguments are invalid
    //     */
    //    @SuppressWarnings("resource")
    //    public static <T> Stream<Stream<T>> streamAllResultSets(final Statement stmt, final BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException {
    //        N.checkArgNotNull(stmt, s.stmt);
    //        N.checkArgNotNull(rowMapper, s.rowMapper);
    //
    //        JdbcUtil.checkDateType(stmt);
    //
    //        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));
    //
    //        return Stream.just(supplier)
    //                .onClose(() -> supplier.get().close())
    //                .flatMap(it -> Stream.of(it.get()))
    //                .map(rs -> JdbcUtil.<T> stream(rs, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    //    }
    //
    //    /**
    //     * Creates a stream of all result sets from the provided Statement.
    //     * <p>
    //     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
    //     * <br />
    //     * {@code JdbcUtil.streamAllResultSets(stmt, rowFilter, rowMapper).onClose(Fn.closeQuietly(stmt))...}
    //     * </p>
    //     *
    //     * @param <T> the type of the result extracted from each ResultSet
    //     * @param stmt the Statement to create streams from
    //     * @param rowFilter the BiRowFilter to filter rows of the ResultSet
    //     * @param rowMapper the BiRowMapper to map each row of the ResultSet to the desired type
    //     * @return a Stream of Streams of the extracted results
    //     * @throws IllegalArgumentException if the provided arguments are invalid
    //     */
    //    @SuppressWarnings("resource")
    //    public static <T> Stream<Stream<T>> streamAllResultSets(final Statement stmt, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(stmt, s.stmt);
    //        N.checkArgNotNull(rowFilter, s.rowFilter);
    //        N.checkArgNotNull(rowMapper, s.rowMapper);
    //
    //        JdbcUtil.checkDateType(stmt);
    //
    //        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));
    //
    //        return Stream.just(supplier)
    //                .onClose(() -> supplier.get().close())
    //                .flatMap(it -> Stream.of(it.get()))
    //                .map(rs -> JdbcUtil.<T> stream(rs, rowFilter, rowMapper).onClose(() -> JdbcUtil.closeQuietly(rs)));
    //    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream of DataSet.
     * <p>
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.extractAllResultSets(stmt).onClose(Fn.closeQuietly(stmt))...}
     * </p>
     *
     * @param stmt the Statement to extract ResultSets from
     * @return a Stream of DataSet containing the extracted ResultSets
     */
    public static Stream<DataSet> streamAllResultSets(final Statement stmt) {
        return streamAllResultSets(stmt, ResultExtractor.TO_DATA_SET);
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream.
     * <p>
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.extractAllResultSets(stmt, resultExtractor).onClose(Fn.closeQuietly(stmt))...}
     * </p>
     *
     * @param <R> the type of the result extracted from the ResultSet
     * @param stmt the Statement to extract ResultSets from
     * @param resultExtractor the ResultExtractor to apply while extracting data
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    @SuppressWarnings("resource")
    public static <R> Stream<R> streamAllResultSets(final Statement stmt, final ResultExtractor<R> resultExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(stmt, cs.stmt);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));

        return Stream.just(supplier)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> Stream.of(it.get()))
                .map(Fn.ff(rs -> extractAndCloseResultSet(rs, resultExtractor)));
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream.
     * <p>
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished, or call:
     * <br />
     * {@code JdbcUtil.extractAllResultSets(stmt, resultExtractor).onClose(Fn.closeQuietly(stmt))...}
     * </p>
     *
     * @param <R> the type of the result extracted from the ResultSet
     * @param stmt the Statement to extract ResultSets from
     * @param resultExtractor the BiResultExtractor to apply while extracting data
     * @return a Stream of the extracted results
     * @throws IllegalArgumentException if the provided arguments are invalid
     */
    @SuppressWarnings("resource")
    public static <R> Stream<R> streamAllResultSets(final Statement stmt, final BiResultExtractor<R> resultExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(stmt, cs.stmt);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));

        return Stream.just(supplier)
                .onClose(() -> supplier.get().close())
                .flatMap(it -> Stream.of(it.get()))
                .map(Fn.ff(rs -> extractAndCloseResultSet(rs, resultExtractor)));
    }

    static ObjIteratorEx<ResultSet> iterateAllResultSets(final Statement stmt, final boolean isFirstResultSet) { //NOSONAR
        return new ObjIteratorEx<>() {
            private final Holder<ResultSet> resultSetHolder = new Holder<>();
            private boolean isNextResultSet = isFirstResultSet;
            private boolean noMoreResult = false;

            @Override
            public boolean hasNext() {
                if (resultSetHolder.isNull() && !noMoreResult) {
                    try {
                        while (true) {
                            if (isNextResultSet) {
                                resultSetHolder.setValue(stmt.getResultSet());
                                isNextResultSet = false;
                                break;
                            } else if (stmt.getUpdateCount() != -1) {
                                isNextResultSet = stmt.getMoreResults();
                            } else {
                                noMoreResult = true;

                                break;
                            }
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                return resultSetHolder.isNotNull();
            }

            @Override
            public ResultSet next() {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                return resultSetHolder.getAndSet(null);
            }

            private boolean isClosed = false;

            @Override
            public void close() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (resultSetHolder.isNotNull()) {
                    JdbcUtil.closeQuietly(resultSetHolder.getAndSet(null));
                }
            }
        };
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * <p>
     * The query must be ordered by at least one key/id and have a result size limitation, for example, {@code LIMIT pageSize} or {@code ROWS FETCH NEXT pageSize ROWS ONLY}.
     * </p>
     *
     * @param ds the DataSource to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query; the second parameter is the result set for the previous page, and it's {@code null} for the first page
     * @return a Stream of DataSet, each representing a page of results
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static Stream<DataSet> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, DataSet> paramSetter) {
        return queryByPage(ds, query, pageSize, paramSetter, Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * <p>
     * The query must be ordered by at least one key/id and have a result size limitation, for example, {@code LIMIT pageSize} or {@code ROWS FETCH NEXT pageSize ROWS ONLY}.
     * </p>
     *
     * @param <R> the type of the result extracted from each page
     * @param ds the DataSource to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query; the second parameter is the result set for the previous page, and it's {@code null} for the first page
     * @param resultExtractor the ResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.ResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, query) : JdbcUtil.prepareQuery(ds, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * <p>
     * The query must be ordered by at least one key/id and have a result size limitation, for example, {@code LIMIT pageSize} or {@code ROWS FETCH NEXT pageSize ROWS ONLY}.
     * </p>
     *
     * @param <R> the type of the result extracted from each page
     * @param ds the DataSource to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query; the second parameter is the result set for the previous page, and it's {@code null} for the first page
     * @param resultExtractor the ResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, query) : JdbcUtil.prepareQuery(ds, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * <p>
     * The query must be ordered by at least one key/id and have a result size limitation, for example, {@code LIMIT pageSize} or {@code ROWS FETCH NEXT pageSize ROWS ONLY}.
     * </p>
     *
     * @param conn the Connection to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query; the second parameter is the result set for the previous page, and it's {@code null} for the first page
     * @return a Stream of DataSet, each representing a page of results
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static Stream<DataSet> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, DataSet> paramSetter) {
        return queryByPage(conn, query, pageSize, paramSetter, Jdbc.ResultExtractor.TO_DATA_SET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * <p>
     * The query must be ordered by at least one key/id and have a result size limitation, for example, {@code LIMIT pageSize} or {@code ROWS FETCH NEXT pageSize ROWS ONLY}.
     * </p>
     *
     * @param <R> the type of the result extracted from each page
     * @param conn the Connection to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query; the second parameter is the result set for the previous page, and it's {@code null} for the first page
     * @param resultExtractor the ResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.ResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, query) : JdbcUtil.prepareQuery(conn, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * <p>
     * The query must be ordered by at least one key/id and have a result size limitation, for example, {@code LIMIT pageSize} or {@code ROWS FETCH NEXT pageSize ROWS ONLY}.
     * </p>
     *
     * @param <R> the type of the result extracted from each page
     * @param conn the Connection to get the connection from
     * @param query the SQL query to run for each page
     * @param pageSize the number of rows to fetch per page
     * @param paramSetter the BiParametersSetter to set parameters for the query; the second parameter is the result set for the previous page, and it's {@code null} for the first page
     * @param resultExtractor the ResultExtractor to extract results from the ResultSet
     * @return a Stream of the extracted results
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String query, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> paramSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {

        final boolean isNamedQuery = ParsedSql.parse(query).getNamedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, query) : JdbcUtil.prepareQuery(conn, query)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), paramSetter)
                                .query(resultExtractor);

                        it.setValue(ret);

                        return ret;
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .takeWhile(JdbcUtil::isNotEmptyResult);
    }

    @SuppressWarnings("rawtypes")
    static boolean isNotEmptyResult(final Object ret) {
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

    static <R> R checkNotResultSet(final R result) {
        if (result instanceof ResultSet) {
            throw new UnsupportedOperationException("The result value of ResultExtractor/BiResultExtractor.apply can't be ResultSet");
        }

        return result;
    }

    static boolean checkDateType(final ResultSet rs) {
        try {
            return checkDateType(rs.getStatement());
        } catch (final Exception e) {
            return true;
        }
    }

    static boolean checkDateType(final Statement stmt) {
        try {
            return Strings.containsIgnoreCase(JdbcUtil.getDBProductInfo(stmt.getConnection()).productName(), "Oracle");
        } catch (final SQLException e) {
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
     * Retrieves the output parameters from the given CallableStatement.
     *
     * @param stmt The CallableStatement from which to retrieve the output parameters.
     * @param outParams The list of OutParam objects representing the output parameters.
     * @return An OutParamResult containing the retrieved output parameters.
     * @throws IllegalArgumentException If the provided arguments are invalid.
     * @throws SQLException If a SQL exception occurs while retrieving the output parameters.
     */
    public static OutParamResult getOutParameters(final CallableStatement stmt, final List<OutParam> outParams) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(stmt, cs.stmt);

        if (N.isEmpty(outParams)) {
            return new OutParamResult(N.emptyList(), N.emptyMap());
        }

        final Map<Object, Object> outParamValues = new LinkedHashMap<>(outParams.size());
        OutParameterGetter outParameterGetter = null;
        Object key = null;
        Object value = null;

        for (final OutParam outParam : outParams) {
            outParameterGetter = sqlTypeGetterMap.getOrDefault(outParam.getSqlType(), objOutParameterGetter);

            if (outParam.getParameterIndex() > 0) {
                key = outParam.getParameterIndex();
                value = outParameterGetter.getOutParameter(stmt, outParam.getParameterIndex());
            } else {
                key = outParam.getParameterName();
                value = outParameterGetter.getOutParameter(stmt, outParam.getParameterName());
            }

            if (value instanceof final ResultSet rs) {
                try {
                    value = JdbcUtil.extractData(rs);
                } finally {
                    JdbcUtil.closeQuietly(rs);
                }
            } else if (value instanceof final Blob blob) {
                value = blob.getBytes(1, (int) blob.length());
            } else if (value instanceof final Clob clob) {
                value = clob.getSubString(1, (int) clob.length());
            }

            outParamValues.put(key, value);
        }

        return new OutParamResult(outParams, outParamValues);
    }

    /**
     * Checks if a table exists in the database.
     *
     * @param conn The database connection.
     * @param tableName The name of the table to check.
     * @return {@code true} if the table exists, {@code false} otherwise.
     */
    public static boolean doesTableExist(final Connection conn, final String tableName) {
        try {
            executeQuery(conn, "SELECT 1 FROM " + tableName + " WHERE 1 > 2");

            return true;
        } catch (final SQLException e) {
            if (isTableNotExistsException(e)) {
                return false;
            }

            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates a table if it does not already exist in the database.
     *
     * @param conn The database connection.
     * @param tableName The name of the table to create.
     * @param schema The schema definition of the table.
     * @return {@code true} if the table was created, {@code false} if the table already exists.
     * @throws UncheckedSQLException If a database access error occurs.
     */
    public static boolean createTableIfNotExists(final Connection conn, final String tableName, final String schema) {
        if (doesTableExist(conn, tableName)) {
            return false;
        }

        try {
            execute(conn, schema);

            return true;
        } catch (final SQLException e) {
            return false;
        }
    }

    /**
     * Drops the specified table if it exists in the database.
     *
     * @param conn The database connection.
     * @param tableName The name of the table to drop.
     * @return {@code true} if the table was dropped, {@code false} otherwise.
     */
    public static boolean dropTableIfExists(final Connection conn, final String tableName) {
        try {
            if (doesTableExist(conn, tableName)) {
                execute(conn, "DROP TABLE " + tableName);

                return true;
            }
        } catch (final SQLException e) {
            // ignore.
        }

        return false;
    }

    /**
     * Returns a new instance of {@code DBSequence} for the specified table and sequence.
     *
     * @param ds The data source.
     * @param tableName The name of the table.
     * @param seqName The name of the sequence.
     * @return The DBSequence object.
     */
    public static DBSequence getDBSequence(final javax.sql.DataSource ds, final String tableName, final String seqName) {
        return new DBSequence(ds, tableName, seqName, 0, 1000);
    }

    /**
     * Returns a new instance of {@code DBSequence} for the specified table, sequence, start value and buffer size.
     *
     * @param ds The data source.
     * @param tableName The name of the table.
     * @param seqName The name of the sequence.
     * @param startVal The starting value of the sequence.
     * @param seqBufferSize The numbers to allocate/reserve from the database table when cached numbers are used up.
     * @return A new instance of {@code DBSequence} for the specified table and sequence.
     */
    public static DBSequence getDBSequence(final javax.sql.DataSource ds, final String tableName, final String seqName, final long startVal,
            final int seqBufferSize) {
        return new DBSequence(ds, tableName, seqName, startVal, seqBufferSize);
    }

    /**
     * Supports global lock by db table.
     *
     * @param ds The data source.
     * @param tableName The name of the table.
     * @return A new instance of {@code DBLock} for the specified table.
     */
    public static DBLock getDBLock(final javax.sql.DataSource ds, final String tableName) {
        return new DBLock(ds, tableName);
    }

    /**
     * Checks if is table not exists exception.
     *
     * @param e
     * @return {@code true}, if is table not exists exception
     */
    static boolean isTableNotExistsException(final Throwable e) {
        if (e instanceof final SQLException sqlException) {
            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase();
            return Strings.isNotEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        }

        return false;
    }

    static boolean isSqlLogAllowed = true;

    /**
     * Turns off SQL logging globally.
     * This method sets the flag to disable SQL logging across the entire application.
     */
    public static void turnOffSqlLogGlobally() {
        isSqlLogAllowed = false;
    }

    static boolean isSqlPerfLogAllowed = true;

    /**
     * Turns off SQL performance logging globally.
     * This method sets the flag to disable SQL performance logging across the entire application.
     */
    public static void turnOffSqlPerfLogGlobally() {
        isSqlPerfLogAllowed = false;
    }

    static boolean isDaoMethodPerfLogAllowed = true;

    /**
     * Turns off DAO method performance logging globally.
     * This method sets the flag to disable DAO method performance logging across the entire application.
     */
    public static void turnOffDaoMethodPerfLogGlobally() {
        isDaoMethodPerfLogAllowed = false;
    }

    public static final int DEFAULT_MAX_SQL_LOG_LENGTH = 1024;

    static final ThreadLocal<SqlLogConfig> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> new SqlLogConfig(false, DEFAULT_MAX_SQL_LOG_LENGTH));

    /**
     * Enables/Disables SQL logging in the current thread.
     *
     * @param b {@code true} to enable SQL logging, {@code false} to disable it.
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    public static void enableSqlLog(final boolean b) {
        enableSqlLog(b, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enables/Disables SQL logging in the current thread.
     *
     * @param b {@code true} to enable SQL logging, {@code false} to disable it.
     * @param maxSqlLogLength The maximum length of the SQL log. Default value is 1024.
     * @deprecated replaced by {@code enableSqlLog/disableSqlLog}.
     */
    @Deprecated
    public static void enableSqlLog(final boolean b, final int maxSqlLogLength) {
        final SqlLogConfig config = isSQLLogEnabled_TL.get();
        // synchronized (isSQLLogEnabled_TL) {
        if (logger.isDebugEnabled() && config.isEnabled != b) {
            if (b) {
                logger.debug("Turn on [SQL] log");
            } else {
                logger.debug("Turn off [SQL] log");
            }
        }

        config.set(b, maxSqlLogLength);
        // }
    }

    /**
     * Enables SQL logging in the current thread.
     * This method enables SQL logging with the default maximum SQL log length.
     */
    public static void enableSqlLog() {
        enableSqlLog(DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enables SQL logging in the current thread with a specified maximum SQL log length.
     *
     * @param maxSqlLogLength The maximum length of the SQL log. Default value is 1024.
     */
    public static void enableSqlLog(final int maxSqlLogLength) {
        enableSqlLog(true, maxSqlLogLength);
    }

    /**
     * Disables SQL logging in the current thread.
     * This method disables SQL logging while retaining the current maximum SQL log length.
     */
    public static void disableSqlLog() {
        enableSqlLog(false, isSQLLogEnabled_TL.get().maxSqlLogLength);
    }

    /**
     * Checks if SQL logging is enabled in the current thread.
     *
     * @return {@code true} if SQL logging is enabled, otherwise {@code false}.
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
     * Retrieves the current SQL extractor function.
     * This function is used to extract SQL statements from a given Statement object.
     *
     * @return The current SQL extractor function.
     */
    public static Throwables.Function<Statement, String, SQLException> getSqlExtractor() {
        return _sqlExtractor;
    }

    /**
     * Sets the SQL extractor function.
     * This function is used to extract SQL statements from a given Statement object.
     *
     * @param sqlExtractor The SQL extractor function to set.
     */
    public static void setSqlExtractor(final Throwables.Function<Statement, String, SQLException> sqlExtractor) {
        _sqlExtractor = sqlExtractor;
    }

    private static TriConsumer<String, Long, Long> _sqlLogHandler = null; //NOSONAR

    /**
     * Retrieves the SQL log handler.
     * The SQL log handler is a TriConsumer that handles SQL log messages along with their execution times.
     *
     * @return The current SQL log handler.
     */
    public static TriConsumer<String, Long, Long> getSqlLogHandler() {
        return _sqlLogHandler;
    }

    /**
     * Sets the SQL log handler.
     * The SQL log handler is a TriConsumer that handles SQL log messages along with their execution times.
     *
     * @param sqlLogHandler 1st parameter is the SQL statement,
     *                      2nd parameter is the start time of SQL execution,
     *                      3rd parameter is the end time of SQL execution.
     */
    public static void setSqlLogHandler(final TriConsumer<String, Long, Long> sqlLogHandler) {
        _sqlLogHandler = sqlLogHandler;
    }

    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG = 3000L;

    public static final long DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG = 1000L;

    static final ThreadLocal<SqlLogConfig> minExecutionTimeForSqlPerfLog_TL = ThreadLocal
            .withInitial(() -> new SqlLogConfig(DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG, DEFAULT_MAX_SQL_LOG_LENGTH));

    /**
     * Sets the minimum execution time to log SQL performance in the current thread.
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds
     *                                      for logging SQL performance.
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog) {
        setMinExecutionTimeForSqlPerfLog(minExecutionTimeForSqlPerfLog, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Sets the minimum execution time to log SQL performance in the current thread.
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds
     *                                      for logging SQL performance. Default value is 1000 (milliseconds).
     * @param maxSqlLogLength the maximum length of the SQL log. Default value is 1024.
     */
    public static void setMinExecutionTimeForSqlPerfLog(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        final SqlLogConfig config = minExecutionTimeForSqlPerfLog_TL.get();
        // synchronized (minExecutionTimeForSqlPerfLog_TL) {
        if (logger.isDebugEnabled() && config.minExecutionTimeForSqlPerfLog != minExecutionTimeForSqlPerfLog) {
            if (minExecutionTimeForSqlPerfLog >= 0) {
                logger.debug("set 'minExecutionTimeForSqlPerfLog' to: " + minExecutionTimeForSqlPerfLog);
            } else {
                logger.debug("Turn off SQL performance log");
            }
        }

        config.set(minExecutionTimeForSqlPerfLog, maxSqlLogLength);
        // }
    }

    /**
     * Returns the minimum execution time in milliseconds to log SQL performance in the current thread.
     * The default value is 1000 milliseconds.
     *
     * @return the minimum execution time for logging SQL performance in milliseconds.
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
     * Don't share {@code Spring Transactional} in current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to not share, {@code false} to share it again.
     * @deprecated replaced by {@link #doNotUseSpringTransactional(boolean)}
     */
    @Deprecated
    public static void disableSpringTransactional(final boolean b) {
        doNotUseSpringTransactional(b);
    }

    /**
     * Don't share {@code Spring Transactional} in the current thread.
     *
     * {@code Spring Transactional} won't be used in fetching Connection if it's disabled.
     *
     * @param b {@code true} to not share, {@code false} to share it again.
     */
    public static void doNotUseSpringTransactional(final boolean b) {
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
     * Check if {@code Spring Transactional} is shared or not in the current thread.
     *
     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
     * @deprecated replaced by {@link #isSpringTransactionalNotUsed()}
     */
    @Deprecated
    public static boolean isSpringTransactionalDisabled() {
        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    }

    /**
     * Check if {@code Spring Transactional} is shared or not in the current thread.
     *
     * @return {@code true} if it's not shared, otherwise {@code false} is returned.
     */
    public static boolean isSpringTransactionalNotUsed() {
        return !isInSpring || isSpringTransactionalDisabled_TL.get();
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
     * @return {@code true}, if is default id prop value
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

    //    /**
    //     * Executes the specified SQL action.
    //     *
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static void run(final Throwables.Runnable<Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.run();
    //        } catch (final Exception e) {
    //           throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameter.
    //     *
    //     * @param <T> The type of the input parameter.
    //     * @param t The input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T> void run(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.accept(t);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameters.
    //     *
    //     * @param <T> The type of the first input parameter.
    //     * @param <U> The type of the second input parameter.
    //     * @param t The first input parameter.
    //     * @param u The second input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T, U> void run(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.accept(t, u);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameters.
    //     *
    //     * @param <A> The type of the first input parameter.
    //     * @param <B> The type of the second input parameter.
    //     * @param <C> The type of the third input parameter.
    //     * @param a The first input parameter.
    //     * @param b The second input parameter.
    //     * @param c The third input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <A, B, C> void run(final A a, final B b, final C c, final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            sqlAction.accept(a, b, c);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action and returns the result.
    //     *
    //     * @param <R> The type of the result.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is {@code null}.
    //     */
    //    @Beta
    //    public static <R> R call(final Callable<R> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.call();
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Executes the specified SQL action with the given input parameter and returns the result.
    //     *
    //     * @param <T> The type of the input parameter.
    //     * @param <R> The type of the result.
    //     * @param t The input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T, R> R call(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.apply(t);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Calls the specified SQL action with two input parameters and returns the result.
    //     *
    //     * @param <T> The type of the first input parameter.
    //     * @param <U> The type of the second input parameter.
    //     * @param <R> The type of the result.
    //     * @param t The first input parameter.
    //     * @param u The second input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <T, U, R> R call(final T t, final U u, final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction)
    //            throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.apply(t, u);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }
    //
    //    /**
    //     * Calls the specified SQL action with three input parameters and returns the result.
    //     *
    //     * @param <A> The type of the first input parameter.
    //     * @param <B> The type of the second input parameter.
    //     * @param <C> The type of the third input parameter.
    //     * @param <R> The type of the result.
    //     * @param a The first input parameter.
    //     * @param b The second input parameter.
    //     * @param c The third input parameter.
    //     * @param sqlAction The SQL action to be executed.
    //     * @return The result of the SQL action.
    //     * @throws IllegalArgumentException If the SQL action is invalid.
    //     */
    //    @Beta
    //    public static <A, B, C, R> R call(final A a, final B b, final C c,
    //            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
    //        N.checkArgNotNull(sqlAction, s.sqlAction);
    //
    //        try {
    //            return sqlAction.apply(a, b, c);
    //        } catch (final Exception e) {
    //            throw ExceptionUtil.toRuntimeException(e, true);
    //        }
    //    }

    /**
     * Asynchronously runs the specified SQL action in a separate thread.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the specified SQL action is {@code null}.
     */
    @Beta
    public static ContinuableFuture<Void> asyncRun(final Throwables.Runnable<Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Asynchronously runs the specified SQL actions in separate threads.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param sqlAction1 The first SQL action to be executed.
     * @param sqlAction2 The second SQL action to be executed.
     * @return A Tuple2 containing two ContinuableFuture objects representing the results of the asynchronous computations.
     * @throws IllegalArgumentException If any of the SQL actions are invalid.
     */
    @Beta
    public static Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> asyncRun(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Asynchronously runs the specified SQL actions in separate threads.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param sqlAction1 The first SQL action to be executed.
     * @param sqlAction2 The second SQL action to be executed.
     * @param sqlAction3 The third SQL action to be executed.
     * @return A Tuple3 containing three ContinuableFuture objects representing the results of the asynchronous computations.
     * @throws IllegalArgumentException If any of the SQL actions are invalid.
     */
    @Beta
    public static Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> asyncRun(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2, final Throwables.Runnable<Exception> sqlAction3) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);
        N.checkArgNotNull(sqlAction3, cs.sqlAction3);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
    }

    /**
     * Asynchronously runs the specified SQL action with the given parameter.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <T> The type of the parameter.
     * @param t The parameter to be passed to the SQL action.
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the SQL action is invalid.
     */
    @Beta
    public static <T> ContinuableFuture<Void> asyncRun(final T t, final Throwables.Consumer<? super T, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(t));
    }

    /**
     * Asynchronously runs the specified SQL action with two parameters.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <T> The type of the first parameter.
     * @param <U> The type of the second parameter.
     * @param t The first parameter to be passed to the SQL action.
     * @param u The second parameter to be passed to the SQL action.
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the SQL action is invalid.
     */
    @Beta
    public static <T, U> ContinuableFuture<Void> asyncRun(final T t, final U u, final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(t, u));
    }

    /**
     * Asynchronously runs the specified SQL action with three parameters.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <A> The type of the first parameter.
     * @param <B> The type of the second parameter.
     * @param <C> The type of the third parameter.
     * @param a The first parameter to be passed to the SQL action.
     * @param b The second parameter to be passed to the SQL action.
     * @param c The third parameter to be passed to the SQL action.
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the SQL action is invalid.
     */
    @Beta
    public static <A, B, C> ContinuableFuture<Void> asyncRun(final A a, final B b, final C c,
            final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(a, b, c));
    }

    /**
     * Asynchronously calls the specified SQL action.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R> The type of the result of the SQL action.
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the SQL action is invalid.
     */
    @Beta
    public static <R> ContinuableFuture<R> asyncCall(final Callable<R> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Asynchronously calls the specified SQL actions with two parameters.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R1> The type of the result of the first SQL action.
     * @param <R2> The type of the result of the second SQL action.
     * @param sqlAction1 The first SQL action to be executed.
     * @param sqlAction2 The second SQL action to be executed.
     * @return A Tuple2 containing two ContinuableFutures representing the results of the asynchronous computations.
     * @throws IllegalArgumentException If any of the SQL actions are invalid.
     */
    @Beta
    public static <R1, R2> Tuple2<ContinuableFuture<R1>, ContinuableFuture<R2>> asyncCall(final Callable<R1> sqlAction1, final Callable<R2> sqlAction2)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Asynchronously calls the specified SQL actions with three parameters.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R1> The type of the result of the first SQL action.
     * @param <R2> The type of the result of the second SQL action.
     * @param <R3> The type of the result of the third SQL action.
     * @param sqlAction1 The first SQL action to be executed.
     * @param sqlAction2 The second SQL action to be executed.
     * @param sqlAction3 The third SQL action to be executed.
     * @return A Tuple3 containing three ContinuableFutures representing the results of the asynchronous computations.
     * @throws IllegalArgumentException If any of the SQL actions are invalid.
     */
    @Beta
    public static <R1, R2, R3> Tuple3<ContinuableFuture<R1>, ContinuableFuture<R2>, ContinuableFuture<R3>> asyncCall(final Callable<R1> sqlAction1,
            final Callable<R2> sqlAction2, final Callable<R3> sqlAction3) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);
        N.checkArgNotNull(sqlAction3, cs.sqlAction3);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
    }

    /**
     * Asynchronously calls the specified SQL action with one parameter.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <T> The type of the parameter.
     * @param <R> The type of the result.
     * @param t The parameter.
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the sqlAction is invalid.
     */
    @Beta
    public static <T, R> ContinuableFuture<R> asyncCall(final T t, final Throwables.Function<? super T, ? extends R, Exception> sqlAction)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(t));
    }

    /**
     * Asynchronously calls the specified SQL action with two parameters.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <T> The type of the first parameter.
     * @param <U> The type of the second parameter.
     * @param <R> The type of the result.
     * @param t The first parameter.
     * @param u The second parameter.
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the sqlAction is invalid.
     */
    @Beta
    public static <T, U, R> ContinuableFuture<R> asyncCall(final T t, final U u,
            final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(t, u));
    }

    /**
     * Asynchronously calls the specified SQL action with three parameters.
     * <br />
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <A> The type of the first parameter.
     * @param <B> The type of the second parameter.
     * @param <C> The type of the third parameter.
     * @param <R> The type of the result.
     * @param a The first parameter.
     * @param b The second parameter.
     * @param c The third parameter.
     * @param sqlAction The SQL action to be executed.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException If the sqlAction is invalid.
     */
    @Beta
    public static <A, B, C, R> ContinuableFuture<R> asyncCall(final A a, final B b, final C c,
            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

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

    static PropInfo getSubPropInfo(final Class<?> entityClass, final String propName) {
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
        if (!ClassUtil.isBeanClass(entityClass)) {
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
            final boolean isOneId = !isNoId && idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);
            final BeanInfo idBeanInfo = ClassUtil.isBeanClass(idType) ? ParserUtil.getBeanInfo(idType) : null;

            final com.landawn.abacus.util.function.Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? idPropInfo::getPropValue //
                            : (isEntityId ? entity -> {
                                final Seid ret = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (final PropInfo propInfo : idPropInfoList) {
                                    ret.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            } : entity -> {
                                final Object ret = idBeanInfo.createBeanResult();

                                for (final PropInfo propInfo : idPropInfoList) {
                                    ClassUtil.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) idBeanInfo.finishBeanResult(ret);
                            }));

            final com.landawn.abacus.util.function.BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (isEntityId ? (id, entity) -> {
                                if (id instanceof final EntityId entityId) {
                                    PropInfo propInfo = null;

                                    for (final String propName : entityId.keySet()) {
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
                                    @SuppressWarnings("UnnecessaryLocalVariable")
                                    final Object entityId = id;

                                    for (final PropInfo propInfo : idPropInfoList) {
                                        propInfo.setPropValue(entity, ClassUtil.getPropValue(entityId, propInfo.name));
                                    }
                                } else {
                                    logger.warn("Can't set generated keys by id type: " + ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            }));

            map = new EnumMap<>(NamingPolicy.class);

            for (final NamingPolicy np : NamingPolicy.values()) {
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
                                                final List<Tuple2<String, PropInfo>> tpList = Stream.of(columnLabels)
                                                        .filter(it -> idBeanInfo.getPropInfo(it) != null)
                                                        .map(it -> Tuple.of(it, idBeanInfo.getPropInfo(it)))
                                                        .toList();
                                                final Object id = idBeanInfo.createBeanResult();

                                                for (final Tuple2<String, PropInfo> tp : tpList) {
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
     * Sets the ID extractor for the specified DAO interface.
     *
     * @param <T> The type of the entity.
     * @param <ID> The type of the ID.
     * @param <SB> The type of the SQLBuilder.
     * @param <TD> The type of the CrudDao.
     * @param daoInterface The DAO interface class.
     * @param idExtractor The RowMapper used to extract the ID.
     * @throws IllegalArgumentException If the daoInterface or idExtractor is invalid.
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final RowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

        idExtractorPool.put(daoInterface, (rs, cls) -> idExtractor.apply(rs));
    }

    /**
     * Sets the ID extractor for the specified DAO interface.
     *
     * @param <T> The type of the entity.
     * @param <ID> The type of the ID.
     * @param <SB> The type of the SQLBuilder.
     * @param <TD> The type of the CrudDao.
     * @param daoInterface The DAO interface class.
     * @param idExtractor The RowMapper used to extract the ID.
     * @throws IllegalArgumentException If the daoInterface or idExtractor is invalid.
     */
    public static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> void setIdExtractorForDao(
            final Class<? extends CrudDao<T, ID, SB, TD>> daoInterface, final BiRowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

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
     * @param daoCache It's better to not share cache between Dao instances.
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final DaoCache daoCache) {
        return createDao(daoInterface, ds, sqlMapper, daoCache, asyncExecutor.getExecutor());
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
     * @param daoCache It's better to not share cache between Dao instances.
     * @param executor
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SQLMapper sqlMapper, final DaoCache daoCache,
            final Executor executor) {

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

        return DaoImpl.createDao(daoInterface, null, ds, sqlMapper, daoCache, executor);
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
     * @param daoCache It's better to not share cache between Dao instances.
     * @return
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final DaoCache daoCache) {
        return createDao(daoInterface, targetTableName, ds, sqlMapper, daoCache, asyncExecutor.getExecutor());
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
     *
     * @param <TD>
     * @param daoInterface
     * @param targetTableName
     * @param ds
     * @param sqlMapper
     * @param cache It's better to not share cache between Dao instances.
     * @param executor
     * @return
     * @throws IllegalArgumentException
     * @deprecated
     */
    @Deprecated
    @SuppressWarnings("rawtypes")
    public static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds,
            final SQLMapper sqlMapper, final DaoCache cache, final Executor executor) throws IllegalArgumentException {

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
     * Extracts the named parameters from the given SQL string.
     *
     * @param sql the SQL string containing named parameters.
     * @return a list of named parameters found in the SQL string.
     */
    public static List<String> getNamedParameters(final String sql) {
        return ParsedSql.parse(sql).getNamedParameters();
    }

    /**
     * Parses the given SQL string and returns a ParsedSql object.
     *
     * @param sql the SQL string to be parsed.
     * @return a ParsedSql object representing the parsed SQL string.
     * @see ParsedSql#parse(String)
     */
    public static ParsedSql parseSql(final String sql) {
        return ParsedSql.parse(sql);
    }

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
     * Converts the given Blob to a String and close the Blob in finally block.
     *
     * @param blob The Blob object to be converted to a String.
     * @return The String representation of the Blob.
     * @throws SQLException If a SQL exception occurs while accessing the Blob.
     */
    public static String blob2String(final Blob blob) throws SQLException {
        try {
            return new String(blob.getBytes(1, (int) blob.length()), Charsets.UTF_8);
        } finally {
            if (blob != null) {
                blob.free();
            }
        }
    }

    /**
     * Converts the given Blob to a String using the specified Charset and closes the Blob in the finally block.
     *
     * @param blob The Blob object to be converted to a String.
     * @param charset The Charset to be used for decoding the Blob data.
     * @return The String representation of the Blob.
     * @throws SQLException If a SQL exception occurs while accessing the Blob.
     */
    public static String blob2String(final Blob blob, final Charset charset) throws SQLException {
        try {
            return new String(blob.getBytes(1, (int) blob.length()), charset);
        } finally {
            if (blob != null) {
                blob.free();
            }
        }
    }

    /**
     * Writes the content of the given Blob to the specified file and closes the Blob in the finally block.
     *
     * @param blob The Blob object containing the data to be written to the file.
     * @param output The File object representing the file to which the Blob data will be written.
     * @return The number of bytes written to the file.
     * @throws SQLException If a SQL exception occurs while accessing the Blob.
     * @throws IOException If an I/O error occurs while writing to the file.
     */
    public static long writeBlobToFile(final Blob blob, final File output) throws SQLException, IOException {
        try {
            return IOUtil.write(blob.getBinaryStream(), output);
        } finally {
            if (blob != null) {
                blob.free();
            }
        }
    }

    /**
     * Converts the given Clob to a String and closes the Clob in the finally block.
     *
     * @param clob The Clob object to be converted to a String.
     * @return The String representation of the Clob.
     * @throws SQLException If a SQL exception occurs while accessing the Clob.
     */
    public static String clob2String(final Clob clob) throws SQLException {
        try {
            return clob.getSubString(1, (int) clob.length());
        } finally {
            if (clob != null) {
                clob.free();
            }
        }
    }

    /**
     * Writes the content of the given Clob to the specified file and closes the Clob in the finally block.
     *
     * @param clob The Clob object containing the data to be written to the file.
     * @param output The File object representing the file to which the Clob data will be written.
     * @return The number of characters written to the file.
     * @throws SQLException If a SQL exception occurs while accessing the Clob.
     * @throws IOException If an I/O exception occurs while writing to the file.
     */
    public static long writeClobToFile(final Clob clob, final File output) throws SQLException, IOException {
        try {
            return IOUtil.write(clob.getCharacterStream(), output);
        } finally {
            if (clob != null) {
                clob.free();
            }
        }
    }

    /**
     * Checks if the given value is {@code null} or the default value for its type.
     *
     * @param value The value to be checked.
     * @return {@code true} if the value is {@code null} or the default value for its type, {@code false} otherwise.
     */
    public static boolean isNullOrDefault(final Object value) {
        return (value == null) || N.equals(value, N.defaultValueOf(value.getClass()));
    }

    static final ThreadLocal<Jdbc.DaoCache> localThreadCache_TL = new ThreadLocal<>();

    /**
     * Enables the cache for Dao queries in the current thread.
     *
     * <pre>
     * <code>
     * Jdbc.startThreadCacheForDao();
     * try {
     *    // your code here
     * } finally {
     *   Jdbc.closeThreadCacheForDao();
     * }
     *
     * </code>
     * </pre>
     *
     */
    public static void startThreadCacheForDao() {
        localThreadCache_TL.set(DaoCache.createByMap());
    }

    /**
     * Enables the cache for Dao queries in the current thread.
     *
     * <pre>
     * <code>
     * Jdbc.startThreadCacheForDao(localThreadCache);
     * try {
     *    // your code here
     * } finally {
     *   Jdbc.closeThreadCacheForDao();
     * }
     *
     * </code>
     * </pre>
     * @param localThreadCache
     */
    public static void startThreadCacheForDao(final Jdbc.DaoCache localThreadCache) {
        localThreadCache_TL.set(localThreadCache);
    }

    /**
     * Closes the cache for Dao queries in the current thread.
     */
    public static void closeThreadCacheForDao() {
        localThreadCache_TL.remove();
    }
}
