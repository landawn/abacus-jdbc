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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.JoinedBy;
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
import com.landawn.abacus.jdbc.SqlTransaction.CreatedBy;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.jdbc.dao.DaoBase;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.JsonParser;
import com.landawn.abacus.parser.KryoParser;
import com.landawn.abacus.parser.ParserFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.AbstractQueryBuilder.SP;
import com.landawn.abacus.query.Dsl;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SqlDialect;
import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.query.SqlMapper;
import com.landawn.abacus.query.SqlOperation;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.Charsets;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ConcurrentCacheMap;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.ExceptionUtil;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.BiConsumers;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.InternalUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.RowDataset;
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
import com.landawn.abacus.util.stream.Stream.StreamEx;

import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Utility class providing high-level JDBC operations with automatic resource management,
 * transaction support, and connection pooling integration.
 *
 * <p>This class simplifies common JDBC tasks by abstracting boilerplate code for connections,
 * statements, and result sets. It supports both traditional JDBC patterns and functional
 * programming approaches via {@link PreparedQuery} and Abacus {@link Stream} instances.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>Connection management with Spring transaction awareness</li>
 *   <li>Connection pool creation (HikariCP, C3P0)</li>
 *   <li>Transaction management via {@link SqlTransaction}</li>
 *   <li>Batch processing with configurable batch sizes</li>
 *   <li>Stream-based {@link ResultSet} processing for large result sets</li>
 *   <li>Dynamic DAO proxy creation via {@link #createDao}</li>
 *   <li>SQL execution logging and performance monitoring</li>
 *   <li>Asynchronous operations with {@link ContinuableFuture}</li>
 * </ul>
 *
 * <p><b>Core API Categories:</b></p>
 * <ul>
 *   <li><b>Connection Management:</b> {@code getConnection()}, {@code releaseConnection()}, {@code createHikariDataSource()}</li>
 *   <li><b>Query Execution:</b> {@code executeQuery()}, {@code prepareQuery()}</li>
 *   <li><b>Data Modification:</b> {@code executeUpdate()}, {@code executeBatchUpdate()}</li>
 *   <li><b>Transactions:</b> {@code beginTransaction()}, {@code runInTransaction()}, {@code callInTransaction()}</li>
 *   <li><b>DAO Creation:</b> {@code createDao()}</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a connection pool
 * DataSource dataSource = JdbcUtil.createHikariDataSource(url, user, password);
 *
 * // Query with automatic resource management
 * List<User> users = JdbcUtil.prepareQuery(dataSource,
 *     "SELECT * FROM users WHERE department = ?")
 *     .setString(1, "Engineering")
 *     .list(User.class);
 *
 * // Transaction management
 * SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     JdbcUtil.executeUpdate(transaction.connection(),
 *         "UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, fromId);
 *     JdbcUtil.executeUpdate(transaction.connection(),
 *         "UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, toId);
 *     transaction.commit();
 * } finally {
 *     transaction.rollbackIfNotCommitted();
 * }
 *
 * // Stream processing for large result sets
 * try (Stream<Product> stream = JdbcUtil.prepareQuery(dataSource,
 *         "SELECT * FROM products WHERE category = ?")
 *         .setString(1, category)
 *         .stream(Product.class)) {
 *     Map<String, List<Product>> byBrand = stream
 *         .collect(Collectors.groupingBy(Product::getBrand));
 * }
 * }</pre>
 *
 * @see PreparedQuery
 * @see SqlTransaction
 * @see javax.sql.DataSource
 * @see com.landawn.abacus.jdbc.dao.Dao
 * @see com.landawn.abacus.jdbc.dao.CrudDao
 */
@SuppressWarnings({ "java:S1192", "java:S6539", "resource" })
public final class JdbcUtil {

    /**
     * Internal logger for this class's diagnostic messages (e.g., failures in cleanup or observability paths).
     */
    static final Logger logger = LoggerFactory.getLogger(JdbcUtil.class);

    /**
     * Dedicated logger (name {@code "com.landawn.abacus.SQL"}) for SQL execution and performance log entries,
     * allowing SQL logging to be configured independently of the module's diagnostic logging.
     */
    static final Logger sqlLogger = LoggerFactory.getLogger("com.landawn.abacus.SQL");

    /**
     * Default batch size for batch operations (insert/update/delete). Value: {@code 200}.
     */
    public static final int DEFAULT_BATCH_SIZE = 200;

    /**
     * Default fetch size applied when preparing queries that are expected to return a large result set.
     * Value: {@code 1000}.
     */
    public static final int DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET = 1000;

    /**
     * Default fetch size for stream-based result processing. Value: {@code 100}.
     */
    public static final int DEFAULT_FETCH_SIZE_FOR_STREAM = 100;

    /**
     * Default capacity for internal caches. Value: {@code 1000}.
     */
    public static final int DEFAULT_CACHE_CAPACITY = 1000;

    /**
     * Default cache evict delay in milliseconds. Value: {@code 3000} (3 seconds).
     */
    public static final int DEFAULT_CACHE_EVICT_DELAY = 3 * 1000;

    /**
     * Default cache live time in milliseconds. Value: {@code 1_800_000} (30 minutes).
     */
    public static final int DEFAULT_CACHE_LIVE_TIME = 30 * 60 * 1000;

    /**
     * Default maximum length (in characters) for SQL statements written to the SQL log.
     * Longer SQL is abbreviated before being logged. Value: {@code 1024}.
     */
    public static final int DEFAULT_MAX_SQL_LOG_LENGTH = 1024;

    /**
     * Default minimum execution time (in milliseconds) for SQL performance logging. SQL statements
     * that complete in less than this threshold are not logged at the {@code SQL-PERF} level.
     * Value: {@code 1000} (1 second).
     */
    public static final long DEFAULT_SQL_PERF_LOG_THRESHOLD_MILLIS = 1000L;

    /**
     * Default minimum execution time (in milliseconds) for DAO method performance logging.
     * Value: {@code 3000} (3 seconds).
     */
    public static final long DEFAULT_DAO_METHOD_PERF_LOG_THRESHOLD_MILLIS = 3000L;

    /**
     * Default maximum idle time for cache entries in milliseconds. Value: {@code 180_000} (3 minutes).
     */
    public static final int DEFAULT_CACHE_MAX_IDLE_TIME = 3 * 60 * 1000;

    /**
     * Default SQL extractor function used to extract SQL statements from {@link Statement} objects for
     * logging and performance monitoring.
     *
     * <p>This function unwraps statements produced by common connection pools (HikariCP and C3P0) and,
     * for Oracle prepared statements, returns the original SQL via
     * {@code oracle.jdbc.internal.OraclePreparedStatement#getOriginalSql()}. For all other statements it
     * falls back to {@link Object#toString() Statement.toString()}, which most JDBC drivers implement to return the SQL.</p>
     *
     * <p>If a {@link SQLException} is thrown while calling {@code getOriginalSql()} on an Oracle statement,
     * the function silently falls back to {@code toString()}. A {@link SQLException} thrown while unwrapping a
     * pooled statement is propagated to the caller.</p>
     */
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

    /**
     * Shared {@link JsonParser} instance available for JSON (de)serialization within the JDBC module.
     */
    static final JsonParser jsonParser = ParserFactory.createJsonParser();

    /**
     * Shared {@link KryoParser} used, among other things, to serialize DAO cache-key arguments;
     * {@code null} when Kryo is not available on the classpath.
     */
    static final KryoParser kryoParser = ParserFactory.isKryoParserAvailable() ? ParserFactory.createKryoParser() : null;

    /**
     * Reserved named parameter ({@code "now"}) that is automatically bound to the current timestamp at execution time.
     */
    static final String PN_NOW = "now";

    /**
     * Reserved named parameter ({@code "sysTime"}) that is automatically bound to the current timestamp at execution time.
     */
    static final String PN_SYS_TIME = "sysTime";

    /**
     * Reserved named parameter ({@code "sysDate"}) that is automatically bound to the current date at execution time.
     */
    static final String PN_SYS_DATE = "sysDate";

    /**
     * The reserved named parameters ({@code now}, {@code sysTime}, {@code sysDate}) that are bound automatically,
     * and therefore are not required to be supplied by the caller.
     */
    static final Set<String> SYS_DATE_TIME_NAME_SET = Set.of(PN_NOW, PN_SYS_TIME, PN_SYS_DATE);

    /**
     * The zero character, used as a "no quotation" marker in serialization configurations
     * (a character that never occurs in the serialized text).
     */
    static final char CHAR_ZERO = 0;

    /**
     * Shared executor backing the asynchronous query and DAO operations (e.g., {@code callAsync} and DAO async methods).
     */
    static final AsyncExecutor asyncExecutor = new AsyncExecutor(//
            N.max(64, IOUtil.CPU_CORES * 8), // coreThreadPoolSize
            N.max(128, IOUtil.CPU_CORES * 16), // maxThreadPoolSize
            180L, TimeUnit.SECONDS);

    /**
     * Default {@link BiParametersSetter} that binds an {@code Object[]} of parameters positionally
     * via {@link PreparedStatement#setObject(int, Object)} (1-based parameter indexes).
     */
    static final BiParametersSetter<? super PreparedQuery, ? super Object[]> DEFAULT_STMT_SETTER = (stmt, parameters) -> {
        for (int i = 0, len = parameters.length; i < len; i++) {
            stmt.setObject(i + 1, parameters[i]);
        }
    };

    /**
     * SQLState codes that indicate a "table does not exist" error across supported databases;
     * consulted by {@link #isTableNotExistsException(Throwable)}.
     */
    private static final Set<String> sqlStateForTableNotExists = N.newHashSet();

    static {
        sqlStateForTableNotExists.add("42S02"); // MySQL, SQL Server
        sqlStateForTableNotExists.add("42P01"); // PostgreSQL
        sqlStateForTableNotExists.add("42501"); // HSQLDB
        sqlStateForTableNotExists.add("42704"); // DB2
    }

    /**
     * Method-name prefixes identifying DAO methods as (read-only) query operations.
     */
    static final Set<String> QUERY_METHOD_NAME_SET = N.toSet("query", "queryFor", "list", "get", "batchGet", "find", "findFirst", "findOnlyOne", "load",
            "exist", "notExist", "count");

    /**
     * Method-name prefixes identifying DAO methods as update (write) operations.
     */
    static final Set<String> UPDATE_METHOD_NAME_SET = N.toSet("update", "delete", "deleteById", "insert", "save", "batchUpdate", "batchDelete",
            "batchDeleteByIds", "batchInsert", "batchSave", "batchUpsert", "upsert", "execute");

    /**
     * The public, non-static update methods declared by the built-in DAO interfaces in the DAO package,
     * excluding methods annotated with {@link NonDBOperation}; used to recognize update operations on DAO proxies.
     */
    static final Set<Method> BUILT_IN_DAO_UPDATE_METHODS = StreamEx.of(ClassUtil.findClassesInPackage(Dao.class.getPackageName(), false, true)) //
            .filter(DaoBase.class::isAssignableFrom)
            .flatMapArray(Class::getDeclaredMethods)
            .filter(it -> Modifier.isPublic(it.getModifiers()) && !Modifier.isStatic(it.getModifiers()))
            .filter(it -> it.getAnnotation(NonDBOperation.class) == null)
            .filter(it -> N.anyMatch(UPDATE_METHOD_NAME_SET, e -> Strings.containsIgnoreCase(it.getName(), e)))
            .toImmutableSet();

    /**
     * Returns {@code true} if the given method's name starts with one of the {@link #QUERY_METHOD_NAME_SET} prefixes.
     */
    static final Predicate<Method> IS_QUERY_METHOD = method -> N.anyMatch(QUERY_METHOD_NAME_SET,
            it -> Strings.isNotEmpty(it) && Strings.startsWith(method.getName(), it));

    /**
     * Returns {@code true} if the given method's name starts with one of the {@link #UPDATE_METHOD_NAME_SET} prefixes.
     */
    static final Predicate<Method> IS_UPDATE_METHOD = method -> N.anyMatch(UPDATE_METHOD_NAME_SET,
            it -> Strings.isNotEmpty(it) && Strings.startsWith(method.getName(), it));

    /**
     * The currently active SQL extractor used for SQL logging; initially {@link #DEFAULT_SQL_EXTRACTOR}
     * and replaceable via {@link #setSqlExtractor(Throwables.Function)}.
     */
    static volatile Throwables.Function<Statement, String, SQLException> _sqlExtractor = DEFAULT_SQL_EXTRACTOR; //NOSONAR

    /**
     * Per-thread configuration holding whether general SQL logging (logging every executed statement) is enabled
     * and the maximum length of logged SQL.
     */
    static final ThreadLocal<SqlLogConfig> isSQLLogEnabled_TL = ThreadLocal.withInitial(() -> new SqlLogConfig(false, DEFAULT_MAX_SQL_LOG_LENGTH));

    /**
     * Per-thread configuration holding the SQL performance-log threshold in milliseconds
     * and the maximum length of logged SQL.
     */
    static final ThreadLocal<SqlLogConfig> sqlPerfLogThresholdMillis_TL = ThreadLocal
            .withInitial(() -> new SqlLogConfig(DEFAULT_SQL_PERF_LOG_THRESHOLD_MILLIS, DEFAULT_MAX_SQL_LOG_LENGTH));

    /**
     * Per-thread flag that, when {@code true}, disables participation in Spring-managed transactions
     * for JDBC operations on the current thread.
     */
    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * Global master switch for SQL logging; when {@code false}, SQL logging is disabled regardless of per-thread settings.
     */
    static volatile boolean isSqlLogAllowed = true;

    /**
     * Global master switch for SQL performance logging; when {@code false}, no SQL-PERF entries are written
     * regardless of per-thread settings.
     */
    static volatile boolean isSqlPerfLogAllowed = true;

    /**
     * Global master switch for DAO method performance logging.
     */
    static volatile boolean isDaoMethodPerfLogAllowed = true;

    /**
     * Whether Spring JDBC ({@code org.springframework.jdbc}) is present on the classpath; detected once during class initialization.
     */
    static volatile boolean isInSpring = true;

    /**
     * Optional global callback invoked after each statement execution with the SQL text, the start time in
     * milliseconds, and the end time in milliseconds; {@code null} when no handler is registered.
     */
    static volatile TriConsumer<String, Long, Long> _sqlLogHandler = null; //NOSONAR

    @SuppressWarnings("rawtypes")
    // Keyed by (daoInterface, entityClass, idType): the cached key extractor depends on the DAO interface's
    // registration in idExtractorPool, so DAOs sharing the same entity/id types must not share entries.
    /**
     * Cache of per-DAO (generated-key extractor, id getter, id setter) triples, keyed by
     * (DAO interface, entity class, id type) and then by {@link NamingPolicy}.
     */
    private static final Map<Tuple3<Class<?>, Class<?>, Class<?>>, Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>>> idGeneratorGetterSetterPool = new ConcurrentHashMap<>();

    /**
     * Id extractors registered per DAO interface; an extractor reads the entity id from a generated-keys {@link ResultSet}.
     */
    @SuppressWarnings("rawtypes")
    private static final Map<Class<? extends Dao>, BiRowMapper<?>> idExtractorPool = new ConcurrentHashMap<>();

    // Memoizes one Dsl per SqlDialect value so createDao(Class, DataSource, SqlDialect) hands DaoImpl a stable
    // Dsl identity: Dsl.forDialect only returns a shared instance for the ~15 canonical dialects, so without this
    // a non-canonical dialect would yield a fresh Dsl on every call and defeat DaoImpl's identity-keyed proxy cache.
    /**
     * Cached {@link Dsl} instances keyed by {@link SqlDialect}, shared by the DAOs created for each dialect.
     */
    private static final Map<SqlDialect, Dsl> dslPool = new ConcurrentHashMap<>();

    /**
     * Separator ({@code "#"}) used to join the segments (full method name, table name, parameter key) of generated DAO cache keys.
     */
    static final String CACHE_KEY_SEPARATOR = "#";

    /**
     * The DAO cache bound to the current thread, or {@code null} when DAO result caching is not enabled on this thread.
     */
    static final ThreadLocal<Jdbc.DaoCache> localThreadCache_TL = new ThreadLocal<>();

    /**
     * The innermost {@link DaoCacheScope} currently open on the current thread, used to enforce last-opened, first-closed nesting.
     */
    private static final ThreadLocal<DaoCacheScope> daoCacheScope_TL = new ThreadLocal<>();

    static {
        try {
            isInSpring = ClassUtil.forName("org.springframework.jdbc.datasource.DataSourceUtils") != null;
        } catch (final Exception | LinkageError e) {
            isInSpring = false;
        }
    }

    /**
     * Prevents instantiation; this is a utility class with only static members.
     */
    private JdbcUtil() {
        // utility class - prevent instantiation.
    }

    /**
     * Retrieves the database product information from the given DataSource.
     * This method establishes a temporary connection to extract metadata about the database,
     * including its product name and version.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...;  // Obtain a DataSource instance
     * SqlDialect.ProductInfo dbInfo = JdbcUtil.getDBProductInfo(dataSource);
     *
     * System.out.println("Database Product Name: " + dbInfo.name());
     * System.out.println("Database Product Version: " + dbInfo.version());
     * }</pre>
     *
     * @param ds The DataSource from which to obtain a database connection.
     * @return A {@link SqlDialect.ProductInfo} object containing the database product name and version.
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or if the database metadata reports a
     *         null, empty, or blank product name.
     * @throws UncheckedSQLException if acquiring a connection or reading its database metadata fails;
     *         connection acquisition follows {@link #getConnection(javax.sql.DataSource)}.
     * @see #getDBProductInfo(Connection)
     * @see SqlDialect.ProductInfo
     */
    public static ProductInfo getDBProductInfo(final javax.sql.DataSource ds) throws IllegalArgumentException, UncheckedSQLException {
        Connection conn = null;

        try {
            conn = getConnection(ds);
            return getDBProductInfo(conn);
        } finally {
            releaseConnection(conn, ds);
        }
    }

    /**
     * Retrieves the database product information from the given {@link Connection}.
     * This method extracts metadata to determine the database product name and version without closing the connection.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection connection = dataSource.getConnection()) {
     *     SqlDialect.ProductInfo dbInfo = JdbcUtil.getDBProductInfo(connection);
     *     System.out.println("Database Name: " + dbInfo.name());
     *     System.out.println("Database Version: " + dbInfo.version());
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for retrieving metadata. It must be an active connection.
     * @return A {@link SqlDialect.ProductInfo} object containing the database product name and version.
     * @throws IllegalArgumentException if {@code conn} is {@code null}, or if the database metadata reports a
     *         null, empty, or blank product name.
     * @throws UncheckedSQLException if a database access error occurs while retrieving metadata.
     * @see #getDBProductInfo(javax.sql.DataSource)
     * @see DatabaseMetaData
     */
    public static ProductInfo getDBProductInfo(final Connection conn) throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotNull(conn, cs.conn);

        try {
            final DatabaseMetaData metaData = conn.getMetaData();

            return ProductInfo.of(metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
        } catch (final SQLException e) {
            logger.warn(e, "Failed to read database product metadata");
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Creates a {@code HikariDataSource} with the specified database connection details.
     * HikariCP is a high-performance JDBC connection pool known for its speed and reliability.
     * This method provides a convenient way to configure a connection pool with basic settings.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a DataSource for a MySQL database
     * DataSource dataSource = JdbcUtil.createHikariDataSource(
     *     "jdbc:mysql://localhost:3306/mydatabase",
     *     "db_user",
     *     "db_password"
     * );
     *
     * // The returned DataSource can be used with other methods in JdbcUtil
     * try (Connection connection = dataSource.getConnection()) {
     *     // Use the connection...
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param url The JDBC URL for the database connection (e.g., "jdbc:mysql://localhost:3306/mydb").
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A {@code javax.sql.DataSource} instance configured with HikariCP.
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty.
     * @throws RuntimeException if configuration or pool initialization fails.
     * @throws LinkageError if HikariCP or one of its required runtime classes is unavailable or incompatible.
     * @see #createHikariDataSource(String, String, String, int, int)
     * @see com.zaxxer.hikari.HikariDataSource
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password) {
        N.checkArgNotEmpty(url, cs.url);

        try {
            final com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);

            final com.zaxxer.hikari.HikariDataSource dataSource = new com.zaxxer.hikari.HikariDataSource(config);
            logger.info("Created HikariDataSource(urlConfigured={}, userConfigured={})", Strings.isNotEmpty(url), Strings.isNotEmpty(user));

            return dataSource;
        } catch (final Exception e) {
            logger.warn(e, "Failed to create HikariDataSource(urlConfigured={}, userConfigured={})", Strings.isNotEmpty(url), Strings.isNotEmpty(user));
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a {@code HikariDataSource} with specified connection details and pool size configuration.
     * This method allows for more advanced tuning of the connection pool's performance characteristics.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a DataSource with custom pool size for a PostgreSQL database
     * DataSource dataSource = JdbcUtil.createHikariDataSource(
     *     "jdbc:postgresql://localhost:5432/mydatabase",
     *     "db_user",
     *     "db_password",
     *     5,  // Minimum number of idle connections
     *     20  // Maximum number of connections in the pool
     * );
     *
     * // The DataSource is ready to be used for acquiring connections
     * try (Connection connection = dataSource.getConnection()) {
     *     // Perform database operations...
     * } catch (SQLException e) {
     *     // Handle potential exceptions
     * }
     * }</pre>
     *
     * @param url the JDBC URL for the database connection.
     * @param user the username for database authentication.
     * @param password the password for database authentication.
     * @param minIdle the minimum number of idle connections that HikariCP tries to maintain in the pool.
     * @param maxPoolSize the maximum number of connections that can be in the pool, including both idle and in-use connections.
     * @return a {@code javax.sql.DataSource} instance configured with HikariCP and custom pool settings.
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty.
     * @throws RuntimeException if configuration or pool initialization fails.
     * @throws LinkageError if HikariCP or one of its required runtime classes is unavailable or incompatible.
     * @see #createHikariDataSource(String, String, String)
     * @see com.zaxxer.hikari.HikariConfig
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password, final int minIdle,
            final int maxPoolSize) {
        N.checkArgNotEmpty(url, cs.url);

        try {
            final com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setMinimumIdle(minIdle);
            config.setMaximumPoolSize(maxPoolSize);

            final com.zaxxer.hikari.HikariDataSource dataSource = new com.zaxxer.hikari.HikariDataSource(config);
            logger.info("Created HikariDataSource(urlConfigured={}, userConfigured={}, minIdle={}, maxPoolSize={})", Strings.isNotEmpty(url),
                    Strings.isNotEmpty(user), minIdle, maxPoolSize);

            return dataSource;
        } catch (final Exception e) {
            logger.warn(e, "Failed to create HikariDataSource(urlConfigured={}, userConfigured={}, poolSize={})", Strings.isNotEmpty(url),
                    Strings.isNotEmpty(user), minIdle + ".." + maxPoolSize);
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a C3P0 {@code ComboPooledDataSource} with the specified database URL and credentials.
     * This overload applies only basic C3P0 configuration (URL, user, password) and leaves
     * all other pooling settings at C3P0 defaults.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // PostgreSQL with default pooling parameters
     * DataSource dataSource = JdbcUtil.createC3p0DataSource(
     *     "jdbc:postgresql://localhost:5432/mydatabase",
     *     "db_user",
     *     "db_password"
     * );
     * }</pre>
     *
     * <p>Any exception during creation/configuration is converted and rethrown as a
     * {@link RuntimeException} by {@code ExceptionUtil.toRuntimeException(Throwable, boolean)}.</p>
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A {@code javax.sql.DataSource} instance configured with C3P0 defaults.
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty.
     * @throws RuntimeException if pool construction or configuration fails.
     * @throws LinkageError if C3P0 or one of its required runtime classes is unavailable or incompatible.
     * @see #createC3p0DataSource(String, String, String, int, int)
     * @see com.mchange.v2.c3p0.ComboPooledDataSource
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password) {
        N.checkArgNotEmpty(url, cs.url);

        try {
            final com.mchange.v2.c3p0.ComboPooledDataSource cpds = new com.mchange.v2.c3p0.ComboPooledDataSource();
            cpds.setJdbcUrl(url);
            cpds.setUser(user);
            cpds.setPassword(password);

            logger.info("Created C3P0 DataSource(urlConfigured={}, userConfigured={})", Strings.isNotEmpty(url), Strings.isNotEmpty(user));

            return cpds;
        } catch (final Exception e) {
            logger.warn(e, "Failed to create C3P0 DataSource(urlConfigured={}, userConfigured={})", Strings.isNotEmpty(url), Strings.isNotEmpty(user));
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a C3P0 {@code ComboPooledDataSource} with explicit minimum and maximum pool size.
     *
     * <p>This overload sets URL, user, password, minimum pool size, and maximum pool size before
     * returning the configured {@link com.mchange.v2.c3p0.ComboPooledDataSource}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Oracle with explicit pool sizing
     * DataSource dataSource = JdbcUtil.createC3p0DataSource(
     *     "jdbc:oracle:thin:@localhost:1521:xe",
     *     "db_user",
     *     "db_password",
     *     3,  // minimum pool size
     *     15  // maximum pool size
     * );
     * }</pre>
     *
     * <p>Any exception during creation/configuration is converted and rethrown as a
     * {@link RuntimeException} by {@code ExceptionUtil.toRuntimeException(Throwable, boolean)}.</p>
     *
     * @param url The JDBC URL for the database connection.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @param minPoolSize The minimum number of connections the pool will maintain.
     * @param maxPoolSize The maximum number of connections the pool will allow.
     * @return A {@code javax.sql.DataSource} instance configured with C3P0 and custom pool settings.
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty.
     * @throws RuntimeException if pool construction or configuration fails.
     * @throws LinkageError if C3P0 or one of its required runtime classes is unavailable or incompatible.
     * @see #createC3p0DataSource(String, String, String)
     * @see com.mchange.v2.c3p0.ComboPooledDataSource
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password, final int minPoolSize,
            final int maxPoolSize) {
        N.checkArgNotEmpty(url, cs.url);

        try {
            final com.mchange.v2.c3p0.ComboPooledDataSource cpds = new com.mchange.v2.c3p0.ComboPooledDataSource();
            cpds.setJdbcUrl(url);
            cpds.setUser(user);
            cpds.setPassword(password);
            cpds.setMinPoolSize(minPoolSize);
            cpds.setMaxPoolSize(maxPoolSize);
            logger.info("Created C3P0 DataSource(urlConfigured={}, userConfigured={}, minPoolSize={}, maxPoolSize={})", Strings.isNotEmpty(url),
                    Strings.isNotEmpty(user), minPoolSize, maxPoolSize);

            return cpds;
        } catch (final Exception e) {
            logger.warn(e, "Failed to create C3P0 DataSource(urlConfigured={}, userConfigured={}, poolSize={})", Strings.isNotEmpty(url),
                    Strings.isNotEmpty(user), minPoolSize + ".." + maxPoolSize);
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    }

    /**
     * Creates a new database {@link Connection} using the specified URL, username, and password.
     * This method automatically determines the appropriate JDBC driver based on the provided URL.
     * Note: This method creates a new, non-pooled connection. For applications requiring connection pooling,
     * it is recommended to use a {@code DataSource} (e.g., created by {@link #createHikariDataSource}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String jdbcUrl = "jdbc:mysql://localhost:3306/mydatabase";
     * String username = "root";
     * String password = "your_password";
     *
     * try (Connection connection = JdbcUtil.createConnection(jdbcUrl, username, password);
     *         Statement statement = connection.createStatement()) {
     *     // Perform database operations with the connection
     *     // ...
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle exceptions
     * }
     * }</pre>
     *
     * @param url The JDBC URL for the database connection (e.g., "jdbc:mysql://localhost:3306/mydb").
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A new {@link Connection} object.
     * @throws IllegalArgumentException if {@code url} is empty or the driver class cannot be determined from the URL.
     * @throws RuntimeException if the JDBC driver class identified from the URL cannot be loaded (e.g. it is not on the classpath).
     * @throws UncheckedSQLException if a database access error occurs while creating the connection.
     * @see #createConnection(String, String, String, String)
     * @see DriverManager#getConnection(String, String, String)
     */
    public static Connection createConnection(final String url, final String user, final String password)
            throws IllegalArgumentException, UncheckedSQLException {
        return createConnection(getDriverClassByUrl(url), url, user, password);
    }

    /**
     * Creates a new database {@link Connection} using an explicitly specified driver class.
     * This method is useful when the JDBC driver cannot be automatically determined from the URL
     * or when a specific driver version needs to be enforced.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String driver = "com.mysql.cj.jdbc.Driver";
     * String url = "jdbc:mysql://localhost:3306/mydatabase";
     * String user = "root";
     * String password = "your_password";
     *
     * try (Connection connection = JdbcUtil.createConnection(driver, url, user, password)) {
     *     // Use the connection for database operations
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle connection errors
     * }
     * }</pre>
     *
     * @param driverClass The fully qualified name of the JDBC driver class (e.g., "com.mysql.cj.jdbc.Driver"). Must not be {@code null} or empty.
     * @param url The JDBC URL for the database connection. Must not be {@code null} or empty.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A new {@link Connection} object.
     * @throws IllegalArgumentException if {@code driverClass} or {@code url} is {@code null} or empty.
     * @throws RuntimeException if the specified driver class cannot be loaded (e.g., it is not on the classpath).
     * @throws UncheckedSQLException if a database access error occurs while creating the connection.
     * @see #createConnection(Class, String, String, String)
     */
    public static Connection createConnection(final String driverClass, final String url, final String user, final String password)
            throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotEmpty(driverClass, cs.driverClass);
        N.checkArgNotEmpty(url, cs.url);

        final Class<? extends Driver> cls = ClassUtil.forName(driverClass);

        return createConnection(cls, url, user, password);
    }

    /**
     * Driver classes already registered with {@link DriverManager}; used so each driver class is registered only once.
     */
    private static final Map<Class<? extends Driver>, Boolean> registeredDriverClasses = new ConcurrentHashMap<>();

    /**
     * Creates a new database {@link Connection} using a type-safe {@link Driver} class.
     * This method provides compile-time safety for specifying the JDBC driver and avoids potential
     * runtime errors from misspelled driver class names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example with the MySQL driver class
     * Class<? extends java.sql.Driver> driverClass = com.mysql.cj.jdbc.Driver.class;
     * String url = "jdbc:mysql://localhost:3306/mydatabase";
     * String user = "root";
     * String password = "your_password";
     *
     * try (Connection connection = JdbcUtil.createConnection(driverClass, url, user, password)) {
     *     // The connection is ready for use
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle exceptions
     * }
     * }</pre>
     *
     * @param driverClass The JDBC {@link Driver} class (e.g., {@code com.mysql.cj.jdbc.Driver.class}). Must not be {@code null}.
     * @param url The JDBC URL for the database connection. Must not be {@code null} or empty.
     * @param user The username for database authentication.
     * @param password The password for database authentication.
     * @return A new {@link Connection} object.
     * @throws IllegalArgumentException if {@code driverClass} is {@code null} or {@code url} is {@code null} or empty.
     * @throws RuntimeException if the specified {@code driverClass} cannot be instantiated (e.g., it has no accessible no-arg constructor).
     * @throws UncheckedSQLException if a database access error occurs during connection creation.
     * @see #createConnection(String, String, String, String)
     * @see DriverManager#registerDriver(Driver)
     */
    public static Connection createConnection(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotNull(driverClass, cs.driverClass);
        N.checkArgNotEmpty(url, cs.url);

        try {
            // computeIfAbsent gives per-class locking so a concurrent caller blocks until registration
            // completes rather than racing past a half-registered driver; a failed mapping function does
            // not leave an entry behind, so a later call can retry (no cache poisoning).
            registeredDriverClasses.computeIfAbsent(driverClass, cls -> {
                try {
                    DriverManager.registerDriver(N.newInstance(cls));
                    logger.debug("Registered JDBC driver(class={})", ClassUtil.getCanonicalClassName(cls));
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                return Boolean.TRUE;
            });

            return DriverManager.getConnection(url, user, password);
        } catch (final SQLException e) {
            logger.warn(e, "Failed to create JDBC connection(urlConfigured={}, userConfigured={}, driver={})", Strings.isNotEmpty(url),
                    Strings.isNotEmpty(user), ClassUtil.getCanonicalClassName(driverClass));
            throw new UncheckedSQLException("Failed to create connection", e);
        }
    }

    /**
     * Returns the JDBC driver class corresponding to the provided database URL.
     * This method analyzes the URL pattern to determine which JDBC driver should be used.
     *
     * @param url the JDBC URL to analyze
     * @return the driver class corresponding to the URL
     * @throws IllegalArgumentException if {@code url} is empty or does not match any supported driver
     */
    private static Class<? extends Driver> getDriverClassByUrl(final String url) {
        N.checkArgNotEmpty(url, cs.url);

        // Only the leading JDBC subprotocol identifies the driver. Connection properties can
        // contain arbitrary text, including another vendor's complete `:<vendor>:` token.
        Class<? extends Driver> driverClass = null;
        // jdbc:mysql://localhost:3306/abacustest
        if (Strings.startsWithIgnoreCase(url, "jdbc:mysql:")) {
            driverClass = ClassUtil.forName("com.mysql.cj.jdbc.Driver");
            // jdbc:mariadb://localhost:3306/abacustest
        } else if (Strings.startsWithIgnoreCase(url, "jdbc:mariadb:")) {
            driverClass = ClassUtil.forName("org.mariadb.jdbc.Driver");
            // jdbc:postgresql://localhost:5432/abacustest
        } else if (Strings.startsWithIgnoreCase(url, "jdbc:postgresql:")) {
            driverClass = ClassUtil.forName("org.postgresql.Driver");
            // jdbc:hsqldb:hsql://localhost/abacustest
        } else if (Strings.startsWithIgnoreCase(url, "jdbc:hsqldb:")) {
            driverClass = ClassUtil.forName("org.hsqldb.jdbc.JDBCDriver");
            // jdbc:h2:tcp://<host>:<port>/<database>
        } else if (Strings.startsWithIgnoreCase(url, "jdbc:h2:")) {
            driverClass = ClassUtil.forName("org.h2.Driver");
            // url=jdbc:oracle:thin:@localhost:1521:abacustest
        } else if (Strings.startsWithIgnoreCase(url, "jdbc:oracle:")) {
            driverClass = ClassUtil.forName("oracle.jdbc.driver.OracleDriver");
            // url=jdbc:sqlserver://localhost:1433;Database=abacustest
        } else if (Strings.startsWithIgnoreCase(url, "jdbc:sqlserver:")) {
            driverClass = ClassUtil.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            // jdbc:db2://localhost:50000/abacustest
        } else if (Strings.startsWithIgnoreCase(url, "jdbc:db2:")) {
            driverClass = ClassUtil.forName("com.ibm.db2.jcc.DB2Driver");
        } else {
            throw new IllegalArgumentException(
                    "Unsupported JDBC URL subprotocol. Supported vendors: MySQL, MariaDB, PostgreSQL, H2, HSQLDB, Oracle, SQL Server, and DB2");
        }
        return driverClass;
    }

    /**
     * Retrieves a {@link Connection} from the specified {@link javax.sql.DataSource}.
     * This method is aware of Spring-managed transactions. If a transaction is active,
     * it returns the connection associated with the current transaction. Otherwise, it
     * retrieves a new connection from the DataSource.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...;  // Your configured DataSource
     * Connection connection = null;
     * try {
     *     connection = JdbcUtil.getConnection(dataSource);
     *     // Perform database operations
     *     try (Statement stmt = connection.createStatement()) {
     *         // ...
     *     }
     * } catch (UncheckedSQLException | SQLException e) {
     *     // Handle exceptions
     * } finally {
     *     // Always release the connection in a finally block
     *     JdbcUtil.releaseConnection(connection, dataSource);
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} from which to obtain a connection. Must not be {@code null}.
     * @return A {@link Connection} object.
     * @throws IllegalArgumentException if {@code ds} is {@code null}.
     * @throws UncheckedSQLException if a database access error occurs. When Spring integration is enabled, the connection is obtained through
     *         {@link org.springframework.jdbc.datasource.DataSourceUtils#getConnection(javax.sql.DataSource)}
     *         and Spring's {@code CannotGetJdbcConnectionException} may propagate unwrapped instead.
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     * @see org.springframework.jdbc.datasource.DataSourceUtils#getConnection(javax.sql.DataSource)
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotNull(ds, cs.ds);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                return org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            } catch (final LinkageError e) {
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
     * Releases the given {@link Connection} back to the {@link javax.sql.DataSource}.
     * This method correctly handles connections in Spring-managed transactions, ensuring that
     * connections are not prematurely closed. If no transaction is active, it closes the connection.
     * It is crucial to call this method in a {@code finally} block to prevent connection leaks.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...;
     * Connection connection = null;
     * try {
     *     connection = JdbcUtil.getConnection(dataSource);
     *     // ... perform database work
     * } finally {
     *     // This ensures the connection is always released, even if an error occurs.
     *     JdbcUtil.releaseConnection(connection, dataSource);
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to be released. Can be {@code null}, in which case the method does nothing.
     * @param ds The {@link javax.sql.DataSource} from which the connection was obtained.
     * @see #getConnection(javax.sql.DataSource)
     * @see org.springframework.jdbc.datasource.DataSourceUtils#releaseConnection(Connection, javax.sql.DataSource)
     */
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (isInSpring && ds != null && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (final LinkageError e) {
                isInSpring = false;
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Creates a Runnable that releases the given connection back to the DataSource when invoked.
     *
     * @param conn The database Connection to release.
     * @param ds The DataSource from which the connection was obtained.
     * @return A Runnable that releases the connection back to the DataSource.
     */
    static Runnable createCloseHandler(final Connection conn, final javax.sql.DataSource ds) {
        return () -> JdbcUtil.releaseConnection(conn, ds);
    }

    /**
     * Closes the specified {@link ResultSet}.
     * It is recommended to use a try-with-resources statement to manage {@code ResultSet} resources,
     * which automatically handles closing. However, if manual closing is necessary, this method
     * can be used in a {@code finally} block.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Recommended: try-with-resources
     * try (ResultSet rs = statement.executeQuery("SELECT * FROM users")) {
     *     while (rs.next()) {
     *         // ... process results
     *     }
     * } // rs is automatically closed here
     *
     * // Manual closing in a finally block
     * ResultSet rs = null;
     * try {
     *     rs = statement.executeQuery("SELECT * FROM users");
     *     // ...
     * } finally {
     *     JdbcUtil.close(rs);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. If {@code null}, the method does nothing.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #closeQuietly(ResultSet)
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
     * Closes the specified {@link ResultSet} and, optionally, its associated {@link Statement}.
     * This is useful when the {@code Statement} is created and used only for a single {@code ResultSet}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = connection.createStatement();
     * ResultSet rs = null;
     * try {
     *     rs = stmt.executeQuery("SELECT * FROM orders");
     *     // ... process results
     * } finally {
     *     // Closes both the ResultSet and the Statement
     *     JdbcUtil.close(rs, stmt);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. If {@code null}, no action is taken.
     * @param closeStatement If {@code true}, the {@link Statement} that created the {@code ResultSet} will also be closed.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #close(ResultSet, boolean, boolean)
     * @see #closeQuietly(ResultSet, boolean)
     */
    public static void close(final ResultSet rs, final boolean closeStatement) throws UncheckedSQLException {
        close(rs, closeStatement, false);
    }

    /**
     * Closes a {@link ResultSet}, and optionally the associated {@link Statement} and {@link Connection}.
     * This method is intended for use in scenarios where all three resources are managed together
     * and need to be closed simultaneously. Using a {@code DataSource} and {@link #releaseConnection}
     * is generally preferred over manual connection management.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // This is a low-level operation; using a DataSource is typically better.
     * Connection conn = JdbcUtil.createConnection(url, user, pass);
     * Statement stmt = conn.createStatement();
     * ResultSet rs = null;
     * try {
     *     rs = stmt.executeQuery("SELECT * FROM products");
     *     // ... process results
     * } finally {
     *     // Closes rs, stmt, and conn
     *     JdbcUtil.close(rs, stmt, conn);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the {@link Statement} from the {@code ResultSet} is also closed.
     * @param closeConnection If {@code true}, the {@link Connection} from the {@code Statement} is also closed.
     *        This requires {@code closeStatement} to be {@code true}.
     * @throws IllegalArgumentException if {@code closeConnection} is {@code true} but {@code closeStatement} is {@code false}.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #closeQuietly(ResultSet, boolean, boolean)
     */
    public static void close(final ResultSet rs, final boolean closeStatement, final boolean closeConnection)
            throws IllegalArgumentException, UncheckedSQLException {
        if (closeConnection && !closeStatement) {
            throw new IllegalArgumentException("'closeStatement' cannot be false while 'closeConnection' is true");
        }

        if (rs == null) {
            return;
        }

        Connection conn = null;
        Statement stmt = null;
        Throwable lookupFailure = null;

        try {
            if (closeStatement) {
                stmt = rs.getStatement();
            }

            if (closeConnection && stmt != null) {
                conn = stmt.getConnection();
            }
        } catch (final SQLException | RuntimeException | Error e) {
            lookupFailure = e;
        }

        try {
            close(rs, stmt, conn);
        } catch (final RuntimeException | Error closeFailure) {
            if (lookupFailure == null) {
                throw closeFailure;
            }

            // close(...) wraps a JDBC close failure. Suppress its SQLException directly so callers see
            // the same failure ordering as the explicit multi-resource close overloads.
            final Throwable secondaryFailure = closeFailure instanceof UncheckedSQLException && closeFailure.getCause() != null ? closeFailure.getCause()
                    : closeFailure;
            addSuppressedIfDistinct(lookupFailure, secondaryFailure);
        }

        if (lookupFailure instanceof final SQLException sqlException) {
            throw new UncheckedSQLException(sqlException);
        } else if (lookupFailure instanceof final RuntimeException runtimeException) {
            throw runtimeException;
        } else if (lookupFailure instanceof final Error error) {
            throw error;
        }
    }

    /**
     * Closes the specified {@link Statement}.
     * It is recommended to use a try-with-resources statement to manage {@code Statement} resources.
     * This method can be used in a {@code finally} block for manual resource management.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Recommended: try-with-resources
     * try (Statement stmt = connection.createStatement()) {
     *     stmt.executeUpdate("DELETE FROM users WHERE status = 'inactive'");
     * } // stmt is automatically closed here
     *
     * // Manual closing
     * Statement stmt = null;
     * try {
     *     stmt = connection.createStatement();
     *     // ...
     * } finally {
     *     JdbcUtil.close(stmt);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. If {@code null}, the method does nothing.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #closeQuietly(Statement)
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
     * Closes the specified {@link Connection}.
     *
     * <p><b>Recommended Alternative:</b></p>
     * <pre>{@code
     * DataSource dataSource = ...;
     * Connection conn = null;
     * try {
     *     conn = JdbcUtil.getConnection(dataSource);
     *     // ... perform database operations
     * } finally {
     *     // Correctly releases the connection back to the pool or closes it.
     *     JdbcUtil.releaseConnection(conn, dataSource);
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to close. If {@code null}, the method does nothing.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     * @see #closeQuietly(Connection)
     * @deprecated This method is deprecated because it directly closes the connection, which is often not the desired
     * behavior when using a {@link javax.sql.DataSource} that provides pooled connections.
     * Use {@link #releaseConnection(Connection, javax.sql.DataSource)} instead to correctly handle
     * pooled connections and integration with transaction managers.
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
     * Attaches a secondary failure without allowing {@link Throwable#addSuppressed(Throwable)} to
     * replace the primary failure with an {@link IllegalArgumentException} when a driver or test
     * double throws the same {@code Throwable} instance from more than one cleanup operation.
     */
    private static void addSuppressedIfDistinct(final Throwable primaryFailure, final Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    /**
     * Merges a resource-close failure into the primary failure: returns {@code closeFailure} when there is no
     * primary failure, otherwise attaches {@code closeFailure} to {@code primaryFailure} as a suppressed exception
     * and returns the primary failure.
     *
     * @param primaryFailure The failure from the main operation, or {@code null} if none occurred.
     * @param closeFailure The failure thrown while closing a resource.
     * @return The failure to propagate.
     */
    private static Throwable collectCloseFailure(final Throwable primaryFailure, final Throwable closeFailure) {
        if (primaryFailure == null) {
            return closeFailure;
        }

        addSuppressedIfDistinct(primaryFailure, closeFailure);
        return primaryFailure;
    }

    /**
     * Rethrows a close failure, preserving its kind: a {@link SQLException} is wrapped in {@link UncheckedSQLException},
     * a {@link RuntimeException} or {@link Error} is rethrown as-is, and anything else is wrapped in {@link IllegalStateException}.
     * This method never returns normally.
     *
     * @param failure The failure to throw; must not be {@code null}.
     * @throws UncheckedSQLException if {@code failure} is a {@link SQLException}.
     */
    private static void throwCloseFailure(final Throwable failure) throws UncheckedSQLException {
        if (failure instanceof final SQLException sqlException) {
            throw new UncheckedSQLException(sqlException);
        } else if (failure instanceof final RuntimeException runtimeException) {
            throw runtimeException;
        } else if (failure instanceof final Error error) {
            throw error;
        }

        throw new IllegalStateException("Unexpected JDBC resource close failure", failure);
    }

    /**
     * Closes the specified {@link ResultSet} and {@link Statement}.
     * Resources are closed in the correct order: {@code ResultSet} first, then {@code Statement}.
     * Both close operations are attempted even if the first one fails. The first failure is thrown,
     * with any later failure attached to it as a suppressed exception.
     * Using try-with-resources is generally preferred for managing these resources.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Recommended: separate try-with-resources
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
     *     // ... process results
     * } // Both rs and stmt are automatically closed.
     *
     * // Manual closing
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     stmt = connection.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM users");
     *     // ...
     * } finally {
     *     JdbcUtil.close(rs, stmt);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #closeQuietly(ResultSet, Statement)
     */
    public static void close(final ResultSet rs, final Statement stmt) throws UncheckedSQLException {
        Throwable failure = null;

        if (rs != null) {
            try {
                rs.close();
            } catch (final SQLException | RuntimeException | Error e) {
                failure = e;
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (final SQLException | RuntimeException | Error e) {
                failure = collectCloseFailure(failure, e);
            }
        }

        if (failure != null) {
            throwCloseFailure(failure);
        }
    }

    /**
     * Closes the specified {@link Statement} and {@link Connection}.
     * Resources are closed in order: {@code Statement} first, then {@code Connection}.
     * Both close operations are attempted even if the first one fails. The first failure is thrown,
     * with any later failure attached to it as a suppressed exception.
     * It is generally better to manage connections at a higher level, for example, by using
     * a {@code DataSource} and {@link #releaseConnection}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Manual resource management (less common)
     * Connection conn = null;
     * Statement stmt = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     *     stmt = conn.createStatement();
     *     // ...
     * } finally {
     *     JdbcUtil.close(stmt, conn);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #closeQuietly(Statement, Connection)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static void close(final Statement stmt, final Connection conn) throws UncheckedSQLException {
        Throwable failure = null;

        if (stmt != null) {
            try {
                stmt.close();
            } catch (final SQLException | RuntimeException | Error e) {
                failure = e;
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (final SQLException | RuntimeException | Error e) {
                failure = collectCloseFailure(failure, e);
            }
        }

        if (failure != null) {
            throwCloseFailure(failure);
        }
    }

    /**
     * Closes the specified {@link ResultSet}, {@link Statement}, and {@link Connection}.
     * Resources are closed in the correct order: {@code ResultSet}, then {@code Statement}, then {@code Connection}.
     * Every close operation is attempted even if an earlier one fails. The first failure is thrown,
     * with any later failures attached to it as suppressed exceptions.
     * This is a low-level utility, and modern JDBC programming often relies on try-with-resources
     * or connection pooling which automates this cleanup.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     *     stmt = conn.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM employees");
     *     // ... process results
     * } finally {
     *     // Closes all three resources in the correct order.
     *     JdbcUtil.close(rs, stmt, conn);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @throws UncheckedSQLException if a database access error occurs during closing.
     * @see #closeQuietly(ResultSet, Statement, Connection)
     */
    public static void close(final ResultSet rs, final Statement stmt, final Connection conn) throws UncheckedSQLException {
        Throwable failure = null;

        if (rs != null) {
            try {
                rs.close();
            } catch (final SQLException | RuntimeException | Error e) {
                failure = e;
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (final SQLException | RuntimeException | Error e) {
                failure = collectCloseFailure(failure, e);
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (final SQLException | RuntimeException | Error e) {
                failure = collectCloseFailure(failure, e);
            }
        }

        if (failure != null) {
            throwCloseFailure(failure);
        }
    }

    /**
     * Unconditionally closes a {@link ResultSet}, ignoring any {@code SQLException} that occurs.
     * This method is useful in {@code finally} blocks where you need to ensure a resource is
     * closed without handling potential exceptions from the close operation itself.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = null;
     * try {
     *     rs = statement.executeQuery("SELECT * FROM users");
     *     // ... process data
     * } catch (SQLException e) {
     *     // handle query exception
     * } finally {
     *     // Ensures the ResultSet is closed, suppressing any close exceptions.
     *     JdbcUtil.closeQuietly(rs);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @see #close(ResultSet)
     */
    public static void closeQuietly(final ResultSet rs) {
        closeQuietly(rs, null, null);
    }

    /**
     * Unconditionally closes a {@link ResultSet} and, optionally, its associated {@link Statement}.
     * Any {@code SQLException} thrown during the closing of either resource is caught and ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     stmt = connection.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM products");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     // Quietly closes both the ResultSet and the Statement.
     *     JdbcUtil.closeQuietly(rs, stmt);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the {@link Statement} associated with the {@code ResultSet} will also be closed quietly.
     * @see #close(ResultSet, boolean)
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement) {
        closeQuietly(rs, closeStatement, false);
    }

    /**
     * Unconditionally closes a {@link ResultSet} and, optionally, its associated {@link Statement} and {@link Connection}.
     * Any exceptions thrown during closing are caught and ignored. This is a low-level utility for ensuring
     * all resources are closed without needing nested {@code finally} blocks or complex error handling.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     conn = ...;
     *     stmt = conn.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM logs");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     // Quietly closes all three resources.
     *     JdbcUtil.closeQuietly(rs, stmt, conn);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the associated {@link Statement} is also closed quietly.
     * @param closeConnection If {@code true}, the associated {@link Connection} is also closed quietly.
     *        Requires {@code closeStatement} to be {@code true}.
     * @throws IllegalArgumentException if {@code closeConnection} is {@code true} but {@code closeStatement} is {@code false}.
     * @see #close(ResultSet, boolean, boolean)
     */
    public static void closeQuietly(final ResultSet rs, final boolean closeStatement, final boolean closeConnection) throws IllegalArgumentException {
        if (closeConnection && !closeStatement) {
            throw new IllegalArgumentException("'closeStatement' cannot be false while 'closeConnection' is true");
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
            logger.warn(e, "Failed to get Statement or Connection from ResultSet(closeStatement={}, closeConnection={})", closeStatement, closeConnection);
        } finally {
            closeQuietly(rs, stmt, conn);
        }
    }

    /**
     * Unconditionally closes a {@link Statement}, ignoring any {@code SQLException} that occurs.
     * This is useful for ensuring a statement is closed in a {@code finally} block without
     * needing to handle potential closing exceptions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = null;
     * try {
     *     stmt = connection.createStatement();
     *     // ... execute statement
     * } catch (SQLException e) {
     *     // ... handle execution exception
     * } finally {
     *     JdbcUtil.closeQuietly(stmt);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @see #close(Statement)
     */
    public static void closeQuietly(final Statement stmt) {
        closeQuietly(null, stmt, null);
    }

    /**
     * Unconditionally closes a {@link Connection}, suppressing any close failures.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Deprecated fallback when you cannot rely on a DataSource.
     * Connection conn = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     * } catch (UncheckedSQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(conn);
     * }
     * }</pre>
     *
     * <p>This method is null-safe and always returns immediately when {@code conn} is {@code null}.
     * All closing exceptions are ignored; it delegates to {@link #closeQuietly(ResultSet, Statement, Connection)} with no ResultSet or Statement.</p>
     *
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     * @see #close(Connection)
     * @deprecated This method is deprecated as it encourages manual connection management.
     *             Prefer {@link #releaseConnection(Connection, javax.sql.DataSource)} and a pooled DataSource.
     */
    @Deprecated
    public static void closeQuietly(final Connection conn) {
        closeQuietly(null, null, conn);
    }

    /**
     * Unconditionally closes a {@link ResultSet} and a {@link Statement}.
     * Any {@code SQLException} thrown during closing is ignored. This method is useful for
     * cleaning up resources in a {@code finally} block without complex error handling.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     stmt = connection.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM users");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(rs, stmt);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @see #close(ResultSet, Statement)
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt) {
        closeQuietly(rs, stmt, null);
    }

    /**
     * Unconditionally closes a {@link Statement} and a {@link Connection}.
     * Any {@code SQLException} thrown during closing is ignored. This is primarily for cleanup
     * in {@code finally} blocks when not using a {@code DataSource}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * try {
     *     conn = JdbcUtil.createConnection(url, user, pass);
     *     stmt = conn.createStatement();
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(stmt, conn);
     * }
     * }</pre>
     *
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @see #close(Statement, Connection)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static void closeQuietly(final Statement stmt, final Connection conn) {
        closeQuietly(null, stmt, conn);
    }

    /**
     * Unconditionally closes a {@link ResultSet}, a {@link Statement}, and a {@link Connection}.
     * Any {@code SQLException} thrown during the closing of any of these resources is ignored.
     * This is a convenience method for resource cleanup in a {@code finally} block.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = null;
     * Statement stmt = null;
     * ResultSet rs = null;
     * try {
     *     conn = ...;
     *     stmt = conn.createStatement();
     *     rs = stmt.executeQuery("SELECT * FROM departments");
     *     // ...
     * } catch (SQLException e) {
     *     // ...
     * } finally {
     *     JdbcUtil.closeQuietly(rs, stmt, conn);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param stmt The {@link Statement} to close. Can be {@code null}.
     * @param conn The {@link Connection} to close. Can be {@code null}.
     * @see #close(ResultSet, Statement, Connection)
     */
    public static void closeQuietly(final ResultSet rs, final Statement stmt, final Connection conn) {
        if (rs != null) {
            try {
                rs.close();
            } catch (final Exception e) {
                logger.warn(e, "Failed to close ResultSet");
            }
        }

        if (stmt != null) {
            try {
                stmt.close();
            } catch (final Exception e) {
                logger.warn(e, "Failed to close Statement");
            }
        }

        if (conn != null) {
            try {
                conn.close();
            } catch (final Exception e) {
                logger.warn(e, "Failed to close Connection");
            }
        }
    }

    /**
     * Skips up to {@code rowsToSkip} rows in the given {@link ResultSet} by advancing the cursor.
     *
     * <p>This overload simply delegates to {@link #skip(ResultSet, long)}. Values
     * {@code rowsToSkip <= 0} are no-ops and return {@code 0}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = statement.executeQuery("SELECT * FROM users")) {
     *     // Skip the first 10 users
     *     int skippedRows = JdbcUtil.skip(rs, 10);
     *
     *     if (skippedRows == 10) {
     *         // Now processing from the 11th user
     *         if (rs.next()) {
     *             // ...
     *         }
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to advance; must not be {@code null}.
     * @param rowsToSkip The number of rows to skip; values {@code <= 0} are no-ops.
     * @return The number of rows actually skipped (may be less than {@code rowsToSkip} if the end of
     *         the {@code ResultSet} is reached).
     * @throws NullPointerException if {@code rs} is {@code null} and {@code rowsToSkip} is positive.
     * @throws SQLException if a database access error occurs.
     * @see #skip(ResultSet, long)
     */
    public static int skip(final ResultSet rs, final int rowsToSkip) throws SQLException {
        return (int) skip(rs, (long) rowsToSkip);
    }

    /**
     * {@link ResultSet} implementation classes known to reject {@link ResultSet#absolute(int)}; once a driver has
     * failed an {@code absolute()} call, later skip operations on that class iterate with {@link ResultSet#next()} directly.
     */
    private static final Set<Class<?>> resultSetClassNotSupportAbsolute = ConcurrentHashMap.newKeySet();

    /**
     * Skips a specified number of rows in a {@link ResultSet}, supporting a {@code long} count.
     * This method efficiently moves the cursor forward. It attempts to use {@link ResultSet#absolute(int)}
     * for scrollable result sets and falls back to manual {@link ResultSet#next()} iteration when the
     * skip count exceeds {@link Integer#MAX_VALUE}, when adding it to the current row would overflow,
     * or when the driver does not support {@code absolute()}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = statement.executeQuery("SELECT * FROM event_log")) {
     *     long largeNumberOfRowsToSkip = 100000L;
     *     long skipped = JdbcUtil.skip(rs, largeNumberOfRowsToSkip);
     *
     *     System.out.println("Skipped " + skipped + " rows.");
     *
     *     // Start processing from the next row
     *     while (rs.next()) {
     *         // ...
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to skip rows in.
     * @param rowsToSkip The number of rows to skip. Values {@code <= 0} are no-ops and return {@code 0}.
     * @return The number of rows actually skipped, which may be less than {@code rowsToSkip} if the end of the
     *         {@code ResultSet} is reached.
     * @throws NullPointerException if {@code rs} is {@code null} and {@code rowsToSkip} is positive.
     * @throws SQLException if a database access error occurs.
     * @see ResultSet#absolute(int)
     */
    public static long skip(final ResultSet rs, long rowsToSkip) throws SQLException {
        if (rowsToSkip <= 0) {
            return 0;
        } else if (rowsToSkip == 1) {
            return rs.next() ? 1 : 0;
        } else {
            final int currentRow;

            try {
                currentRow = rs.getRow();

                if (currentRow == 0 && rs.isAfterLast()) {
                    return 0;
                }
            } catch (final SQLException e) {
                logger.warn(e, "Failed to call ResultSet.getRow()/isAfterLast(); falling back to manual iteration");

                long skipped = 0;

                while (rowsToSkip-- > 0L && rs.next()) {
                    skipped++;
                }

                return skipped;
            }

            long skipped = 0;

            if ((rowsToSkip > Integer.MAX_VALUE) || (rowsToSkip > Integer.MAX_VALUE - currentRow)
                    || (resultSetClassNotSupportAbsolute.size() > 0 && resultSetClassNotSupportAbsolute.contains(rs.getClass()))) {
                while (rowsToSkip-- > 0L && rs.next()) {
                    skipped++;
                }
            } else {
                try {
                    rs.absolute((int) rowsToSkip + currentRow);

                    final int newRow = rs.getRow();

                    if (newRow > 0) {
                        skipped = newRow - currentRow;
                    } else {
                        // Cursor is past the last row; determine actual rows skipped
                        // by moving to the last row to get its position
                        if (rs.last()) {
                            final int lastRow = rs.getRow();
                            skipped = Math.max(lastRow - currentRow, 0);
                            rs.afterLast();
                        } else {
                            // Empty result set
                            skipped = 0;
                        }
                    }
                } catch (final SQLException e) {
                    logger.warn(e, "Failed to call ResultSet.absolute(rowsToSkip={}); falling back to manual iteration", rowsToSkip);

                    // After a failed absolute(), cursor state may be undefined.
                    // Attempt to determine how many rows were skipped, but fall back to
                    // the full count if getRow() also fails or returns an unreliable value.
                    long remaining = rowsToSkip;

                    try {
                        final int newCurrentRow = rs.getRow();

                        if (newCurrentRow >= currentRow) {
                            remaining = rowsToSkip - (newCurrentRow - currentRow);
                            skipped = newCurrentRow - currentRow;
                        }
                    } catch (final SQLException ignored) {
                        // getRow() also failed; use full rowsToSkip as remaining
                    }

                    if (remaining > 0) {
                        while (remaining-- > 0L && rs.next()) {
                            skipped++;
                        }
                    }

                    resultSetClassNotSupportAbsolute.add(rs.getClass());
                }
            }

            return skipped;
        }
    }

    /**
     * Returns the number of columns in the given {@link ResultSet}.
     *
     * <p>Equivalent to {@code rs.getMetaData().getColumnCount()}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = statement.executeQuery("SELECT id, name, email FROM users")) {
     *     int count = JdbcUtil.getColumnCount(rs);
     *     System.out.println("The ResultSet has " + count + " columns.");   // Prints 3
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to query; must not be {@code null}.
     * @return The number of columns in the result set.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @see ResultSet#getMetaData()
     * @see ResultSetMetaData#getColumnCount()
     */
    public static int getColumnCount(final ResultSet rs) throws SQLException {
        return rs.getMetaData().getColumnCount();
    }

    /**
     * Returns an ordered list of column names for a specified table.
     *
     * <p>This method first consults {@link DatabaseMetaData#getColumns} (trying the connection's
     * current schema — and, for an unqualified name, all schemas — with the table name as supplied,
     * and upper- and lower-case variants only when the driver reports no canonical identifier case). Undelimited parts are
     * folded to the storage case reported by the driver; delimited and case-folded parts are
     * matched case-sensitively. If the metadata lookup yields no match and all name parts
     * are simple identifiers (letters, digits, {@code _} and {@code $}), it falls back to running an
     * empty-result query of the form {@code SELECT * FROM <table> WHERE 1 > 2} to extract column
     * names from the result set metadata; quoted or otherwise exotic names have no such fallback.</p>
     *
     * <p>The {@code tableName} may be a simple identifier or a qualified name like
     * {@code schema.table} or {@code catalog.schema.table}. Schema and table parts are literal
     * identifiers: metadata wildcard characters and the driver's pattern escape sequence are escaped.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection connection = dataSource.getConnection()) {
     *     List<String> userColumns = JdbcUtil.getColumnNames(connection, "users");
     *     System.out.println("Columns in 'users' table: " + userColumns);
     *     // Example Output: [id, first_name, last_name, email, registration_date]
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use.
     * @param tableName The name of the table for which to retrieve column names.
     * @return A {@link List} of column names in the order they are defined in the table.
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code tableName} is blank or otherwise invalid.
     * @throws SQLException if a database access error occurs or the table does not exist.
     * @see #getColumnLabels(ResultSet)
     */
    public static List<String> getColumnNames(final Connection conn, final String tableName) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(tableName, cs.tableName);

        final String[] nameParts = splitQualifiedSqlIdentifier(tableName, cs.tableName);
        final boolean[] delimitedNameParts = SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(tableName, nameParts.length);
        final DatabaseMetaData metadata = conn.getMetaData();
        normalizeMetadataIdentifierParts(metadata, nameParts, delimitedNameParts);
        final String catalog;
        final String schema;
        final String table;
        final boolean catalogDelimited;
        final boolean schemaDelimited;
        final boolean tableDelimited;
        final String fallbackQualifiedTableName;

        if (nameParts.length == 1) {
            catalog = conn.getCatalog();
            schema = null; // unqualified: try the connection's current schema first, then all schemas.
            table = nameParts[0];
            catalogDelimited = false;
            schemaDelimited = false;
            tableDelimited = delimitedNameParts[0];
            fallbackQualifiedTableName = buildSimpleQualifiedTableName(null, null, table);
        } else if (nameParts.length == 2) {
            catalog = conn.getCatalog();
            schema = nameParts[0];
            table = nameParts[1];
            catalogDelimited = false;
            schemaDelimited = delimitedNameParts[0];
            tableDelimited = delimitedNameParts[1];
            fallbackQualifiedTableName = buildSimpleQualifiedTableName(null, schema, table);
        } else if (nameParts.length == 3) {
            catalog = nameParts[0];
            schema = nameParts[1];
            table = nameParts[2];
            catalogDelimited = delimitedNameParts[0];
            schemaDelimited = delimitedNameParts[1];
            tableDelimited = delimitedNameParts[2];
            fallbackQualifiedTableName = buildSimpleQualifiedTableName(catalog, schema, table);
        } else {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }

        if (Strings.isEmpty(table)) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }

        String schemaToUse = schema;

        if (schemaToUse == null) {
            try {
                schemaToUse = conn.getSchema();
            } catch (final SQLException e) {
                // Connection.getSchema() is JDBC 4.1 and optional; fall back to a schema-less lookup.
            }
        }

        // The null-schema retries broaden an unqualified lookup to all schemas; they only add anything
        // when a current schema was actually used above.
        final boolean retryWithAllSchemas = schema == null && schemaToUse != null;
        final String upperCaseTable = table.toUpperCase(Locale.ROOT);
        final String lowerCaseTable = table.toLowerCase(Locale.ROOT);

        List<String> columnNameList = getColumnNamesFromMetadata(metadata, catalog, schemaToUse, table, catalogDelimited, schemaDelimited, tableDelimited);

        if (N.isEmpty(columnNameList) && retryWithAllSchemas) {
            columnNameList = getColumnNamesFromMetadata(metadata, catalog, null, table, catalogDelimited, false, tableDelimited);
        }

        if (N.isEmpty(columnNameList) && !tableDelimited) {
            columnNameList = getColumnNamesFromMetadata(metadata, catalog, schemaToUse, upperCaseTable, catalogDelimited, schemaDelimited, false);
        }

        if (N.isEmpty(columnNameList) && !tableDelimited) {
            columnNameList = getColumnNamesFromMetadata(metadata, catalog, schemaToUse, lowerCaseTable, catalogDelimited, schemaDelimited, false);
        }

        if (N.isEmpty(columnNameList) && !tableDelimited && retryWithAllSchemas) {
            columnNameList = getColumnNamesFromMetadata(metadata, catalog, null, upperCaseTable, catalogDelimited, false, false);
        }

        if (N.isEmpty(columnNameList) && !tableDelimited && retryWithAllSchemas) {
            columnNameList = getColumnNamesFromMetadata(metadata, catalog, null, lowerCaseTable, catalogDelimited, false, false);
        }

        if (N.isEmpty(columnNameList) && !hasDelimitedIdentifierPart(tableName) && Strings.isNotEmpty(fallbackQualifiedTableName)) {
            columnNameList = getColumnNamesBySelect(conn, fallbackQualifiedTableName);
        }

        if (N.isEmpty(columnNameList)) {
            throw new SQLException("No columns found for table: " + tableName);
        }

        return columnNameList;
    }

    /**
     * Returns the column names of one table through {@link DatabaseMetaData#getColumns(String, String, String, String)}.
     * Because the metadata arguments are patterns, every returned row is checked against the requested
     * catalog, schema, and table before it is accepted. If a pattern matches more than one table, columns
     * from the first matching table (in metadata order) are returned.
     *
     * @param metadata The database metadata to query.
     * @param catalog The requested catalog, or {@code null} to accept any catalog.
     * @param schemaPattern The requested schema, or {@code null} to accept any schema.
     * @param tableNamePattern The requested table name.
     * @param catalogCaseSensitive Whether the catalog comparison is case-sensitive.
     * @param schemaCaseSensitive Whether the schema comparison is case-sensitive.
     * @param tableCaseSensitive Whether the table comparison is case-sensitive.
     * @return The column names in metadata order, or an empty list if no matching table is found.
     * @throws SQLException if a database access error occurs.
     */
    private static List<String> getColumnNamesFromMetadata(final DatabaseMetaData metadata, final String catalog, final String schemaPattern,
            final String tableNamePattern, final boolean catalogCaseSensitive, final boolean schemaCaseSensitive, final boolean tableCaseSensitive)
            throws SQLException {
        final ResultSet rs = metadata.getColumns(catalog, escapeMetadataPattern(metadata, schemaPattern), escapeMetadataPattern(metadata, tableNamePattern),
                null);

        if (rs == null) {
            return N.emptyList();
        }

        try (ResultSet rows = rs) {
            final List<String> columnNameList = new ArrayList<>();
            String tableNameInMetadata = null;
            String currentTableName = null;
            String currentSchema = null;
            String currentCatalog = null;

            while (rows.next()) {
                tableNameInMetadata = rows.getString("TABLE_NAME");
                final String schemaInMetadata = rows.getString("TABLE_SCHEM");
                final String catalogInMetadata = rows.getString("TABLE_CAT");

                // JDBC metadata arguments are patterns, so verify every requested identifier against
                // the concrete row before accepting columns returned through '_'/'%' expansion.
                if (!identifierMatches(tableNamePattern, tableNameInMetadata, tableCaseSensitive)
                        || !identifierMatches(schemaPattern, schemaInMetadata, schemaCaseSensitive)
                        || !identifierMatches(catalog, catalogInMetadata, catalogCaseSensitive)) {
                    continue;
                }

                if (currentTableName == null) {
                    currentTableName = tableNameInMetadata;
                    currentSchema = schemaInMetadata;
                    currentCatalog = catalogInMetadata;
                } else if (!N.equals(currentTableName, tableNameInMetadata) || !N.equals(currentSchema, schemaInMetadata)
                        || !N.equals(currentCatalog, catalogInMetadata)) {
                    continue;
                }

                columnNameList.add(rows.getString("COLUMN_NAME"));
            }

            return columnNameList;
        }
    }

    /** Returns whether an actual metadata identifier matches the requested identifier. */
    private static boolean identifierMatches(final String requested, final String actual, final boolean caseSensitive) {
        return requested == null || (caseSensitive ? requested.equals(actual) : requested.equalsIgnoreCase(actual));
    }

    /**
     * Converts a literal identifier to a JDBC metadata search pattern, escaping pattern wildcards
     * and the driver's own escape sequence. Catalog arguments are exact names and must not use this helper.
     *
     * @param metadata metadata supplying the search-pattern escape sequence
     * @param identifier a literal schema or table name, or {@code null} to match all names
     * @return the escaped pattern, preserving {@code null}
     * @throws SQLException if the driver's escape sequence cannot be obtained
     */
    private static String escapeMetadataPattern(final DatabaseMetaData metadata, final String identifier) throws SQLException {
        if (identifier == null) {
            return null;
        }

        final String escape = metadata.getSearchStringEscape();
        if (Strings.isEmpty(escape)) {
            return identifier;
        }

        final StringBuilder pattern = new StringBuilder(identifier.length());
        for (int i = 0; i < identifier.length(); i++) {
            if (identifier.startsWith(escape, i)) {
                pattern.append(escape).append(escape);
                i += escape.length() - 1;
            } else {
                final char ch = identifier.charAt(i);
                if (ch == '_' || ch == '%') {
                    pattern.append(escape);
                }
                pattern.append(ch);
            }
        }

        return pattern.toString();
    }

    /**
     * Fallback column lookup that executes {@code SELECT * FROM <table> WHERE 1 > 2} and reads the column names
     * from the result metadata; used when a {@link DatabaseMetaData} lookup did not resolve the table.
     *
     * @param conn The connection to query with.
     * @param qualifiedTableName The (possibly qualified) table name to select from.
     * @return The column names in ordinal order.
     * @throws SQLException if a database access error occurs.
     */
    private static List<String> getColumnNamesBySelect(final Connection conn, final String qualifiedTableName) throws SQLException {
        final String query = "SELECT * FROM " + qualifiedTableName + " WHERE 1 > 2";
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
     * Returns an ordered list of column labels from the given {@link ResultSet}.
     *
     * <p>Each label is obtained via {@link #getColumnLabel(ResultSetMetaData, int)}, which prefers
     * the column label (alias) and falls back to the column name when no label is set. The returned
     * list is in column-order (positions 1..n).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT user_id AS \"User ID\", user_name AS \"User Name\" FROM users")) {
     *
     *     List<String> labels = JdbcUtil.getColumnLabels(rs);
     *     System.out.println(labels);   // Output: [User ID, User Name]
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to read; must not be {@code null}.
     * @return A {@link List} of column labels (or column names when no label is set), in column-order.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @see #getColumnLabel(ResultSetMetaData, int)
     * @see ResultSetMetaData#getColumnLabel(int)
     */
    public static List<String> getColumnLabels(final ResultSet rs) throws SQLException {
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnCount = metaData.getColumnCount();
        final List<String> labelList = new ArrayList<>(columnCount);

        for (int i = 1, n = columnCount + 1; i < n; i++) {
            labelList.add(getColumnLabel(metaData, i));
        }

        return labelList;
    }

    /**
     * Returns the column label for a specified 1-based column index from {@link ResultSetMetaData},
     * falling back to the column name when the label is {@code null} or empty.
     *
     * <p>This method prioritizes the column label (i.e., any alias assigned in the SQL), so it produces
     * a meaningful, caller-controlled identifier when aliases are used and the underlying column name
     * when they are not.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT first_name AS name FROM users")) {
     *     ResultSetMetaData metaData = rs.getMetaData();
     *     String label = JdbcUtil.getColumnLabel(metaData, 1);   // Returns "name"
     * }
     *
     * try (ResultSet rs = stmt.executeQuery("SELECT first_name FROM users")) {
     *     ResultSetMetaData metaData = rs.getMetaData();
     *     String label = JdbcUtil.getColumnLabel(metaData, 1);   // Returns "first_name"
     * }
     * }</pre>
     *
     * @param rsmd The {@link ResultSetMetaData} to read from; must not be {@code null}.
     * @param columnIndex The 1-based index of the column.
     * @return The column label if non-empty, otherwise the column name.
     * @throws NullPointerException if {@code rsmd} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @see ResultSetMetaData#getColumnLabel(int)
     * @see ResultSetMetaData#getColumnName(int)
     */
    public static String getColumnLabel(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        final String result = rsmd.getColumnLabel(columnIndex);

        return Strings.isEmpty(result) ? rsmd.getColumnName(columnIndex) : result;
    }

    /**
     * Returns the 1-based index of the column with the given name (or label) in a {@link ResultSet}.
     *
     * <p>The search iterates columns in order, comparing {@code columnLabel} case-insensitively against
     * the column's {@link ResultSetMetaData#getColumnLabel(int) label} first and then its
     * {@link ResultSetMetaData#getColumnName(int) name}. The first match wins. Returns {@code -1} when
     * no column matches.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery(
     *         "SELECT user_id, user_name AS name FROM users")) {
     *     int indexByName = JdbcUtil.getColumnIndex(rs, "user_id");   // Returns 1
     *     int indexByLabel = JdbcUtil.getColumnIndex(rs, "name");     // Returns 2
     *     int notFound = JdbcUtil.getColumnIndex(rs, "email");        // Returns -1
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to search within; must not be {@code null}.
     * @param columnLabel The column label (or name) to look up; case-insensitive.
     * @return The 1-based index of the matching column, or {@code -1} if none matches.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @see #getColumnIndex(ResultSetMetaData, String)
     */
    public static int getColumnIndex(final ResultSet rs, final String columnLabel) throws SQLException {
        return getColumnIndex(rs.getMetaData(), columnLabel);
    }

    /**
     * Returns the 1-based index of a column from {@link ResultSetMetaData} given its name.
     * This method visits columns in order and compares each column's label, then its name,
     * case-insensitively. The first matching column wins, even when its name matches before a later
     * column's label. Returns {@code -1} if there is no match.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSetMetaData metaData = rs.getMetaData();
     * // For a query: "SELECT user_id, user_name AS name FROM users"
     *
     * int indexByLabel = JdbcUtil.getColumnIndex(metaData, "name");       // Returns 2
     * int indexByName = JdbcUtil.getColumnIndex(metaData, "user_id");     // Returns 1
     * int notFoundIndex = JdbcUtil.getColumnIndex(metaData, "address");   // Returns -1
     * }</pre>
     *
     * @param rsmd The {@link ResultSetMetaData} to search within.
     * @param columnLabel The label (or name) of the column to find.
     * @return The 1-based index of the column, or -1 if not found.
     * @throws NullPointerException if {@code rsmd} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @see #getColumnIndex(ResultSet, String)
     */
    public static int getColumnIndex(final ResultSetMetaData rsmd, final String columnLabel) throws SQLException {
        final int columnCount = rsmd.getColumnCount();

        String colName = null;

        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
            colName = rsmd.getColumnLabel(columnIndex);

            if (colName != null && colName.equalsIgnoreCase(columnLabel)) {
                return columnIndex;
            }

            colName = rsmd.getColumnName(columnIndex);

            if (colName != null && colName.equalsIgnoreCase(columnLabel)) {
                return columnIndex;
            }
        }

        return -1;
    }

    /**
     * Converts a column value read by 1-based column index.
     */
    @FunctionalInterface
    interface ColumnConverterByIndex {
        /**
         * Converts the given column value, reading from the {@link ResultSet} by column index when needed.
         *
         * @param rs The {@link ResultSet} the value was read from.
         * @param columnIndex The 1-based index of the column.
         * @param columnValue The column value to convert.
         * @return The converted column value.
         * @throws SQLException if a database access error occurs.
         */
        Object apply(ResultSet rs, int columnIndex, Object columnValue) throws SQLException;
    }

    /**
     * Converts a column value read by column label.
     */
    @FunctionalInterface
    interface ColumnConverterByLabel {
        /**
         * Converts the given column value, reading from the {@link ResultSet} by column label when needed.
         *
         * @param rs The {@link ResultSet} the value was read from.
         * @param columnLabel The label of the column.
         * @param columnValue The column value to convert.
         * @return The converted column value.
         * @throws SQLException if a database access error occurs.
         */
        Object apply(ResultSet rs, String columnLabel, Object columnValue) throws SQLException;
    }

    /**
     * Cache of (by-index, by-label) column converter pairs, keyed by the runtime class of the read column value.
     */
    private static final ConcurrentHashMap<Class<?>, Tuple2<ColumnConverterByIndex, ColumnConverterByLabel>> columnConverterPool = new ConcurrentHashMap<>();

    /**
     * Returns the cached converter pair for the runtime class of the given column value, computing and caching
     * an Oracle-/date-type-aware conversion the first time a class is encountered.
     */
    private static final Function<Object, Tuple2<ColumnConverterByIndex, ColumnConverterByLabel>> columnConverterGetter = ret -> {
        return columnConverterPool.computeIfAbsent(ret.getClass(), cls -> {
            final String className = cls.getName();
            Tuple2<ColumnConverterByIndex, ColumnConverterByLabel> converterTP;

            if ("oracle.sql.TIMESTAMP".equals(className)) {
                converterTP = Tuple.of((rs, columnIndex, val) -> ((oracle.sql.Datum) val).timestampValue(),
                        (rs, columnLabel, val) -> ((oracle.sql.Datum) val).timestampValue());
            } else if ("oracle.sql.TIMESTAMPTZ".equals(className) || "oracle.sql.TIMESTAMPLTZ".equals(className)) {
                // Datum.timestampValue() (no-arg) throws SQLException on TIMESTAMPTZ/TIMESTAMPLTZ because
                // they require the originating Connection's session timezone to materialize.
                // rs.getTimestamp lets the driver perform the timezone-aware conversion correctly for both.
                converterTP = Tuple.of((rs, columnIndex, val) -> rs.getTimestamp(columnIndex), (rs, columnLabel, val) -> rs.getTimestamp(columnLabel));
            } else if (className.startsWith("oracle.sql.DATE")) {
                converterTP = Tuple.of((rs, columnIndex, val) -> {
                    final String metaDataClassName = rs.getMetaData().getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    } else {
                        return rs.getDate(columnIndex);
                    }
                }, (rs, columnLabel, val) -> {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final int columnIndex = getColumnIndex(metaData, columnLabel);

                    if (columnIndex < 1) {
                        return val;
                    }

                    final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    } else {
                        return rs.getDate(columnIndex);
                    }
                });
            } else if (ret instanceof java.sql.Date) {
                // Also treat a declared metadata class of oracle.sql.TIMESTAMP as timestamp-bearing,
                // matching ResultSetProxy: otherwise time-of-day is silently truncated on such columns.
                converterTP = Tuple.of((rs, columnIndex, val) -> {
                    final String metaDataClassName = rs.getMetaData().getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    }

                    return val;
                }, (rs, columnLabel, val) -> {
                    final ResultSetMetaData metaData = rs.getMetaData();
                    final int columnIndex = getColumnIndex(metaData, columnLabel);

                    if (columnIndex < 1) {
                        return val;
                    }

                    final String metaDataClassName = metaData.getColumnClassName(columnIndex);

                    if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
                        return rs.getTimestamp(columnIndex);
                    }

                    return val;
                });
            } else {
                converterTP = Tuple.of((rs, columnIndex, val) -> val, (rs, columnLabel, val) -> val);
            }

            return converterTP;
        });
    };

    /**
     * Converts a column value read by index, dispatching to the converter pair registered for the value's runtime class.
     */
    private static final ColumnConverterByIndex columnConverterByIndex = (rs, columnIndex, val) -> columnConverterGetter.apply(val)._1.apply(rs, columnIndex,
            val);

    /**
     * Converts a column value read by label, dispatching to the converter pair registered for the value's runtime class.
     */
    private static final ColumnConverterByLabel columnConverterByLabel = (rs, columnLabel, val) -> columnConverterGetter.apply(val)._2.apply(rs, columnLabel,
            val);

    /**
     * Retrieves the value of a specified column in the current row of a {@link ResultSet}.
     * This method provides special handling for data types like {@link Blob}, {@link Clob},
     * and database-specific date/time types (e.g., Oracle's {@code TIMESTAMP}) to ensure
     * they are converted to standard Java types.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, profile_picture_blob FROM users")) {
     *     while (rs.next()) {
     *         long id = JdbcUtil.getColumnValue(rs, 1, long.class);
     *         String name = (String) JdbcUtil.getColumnValue(rs, 2);
     *         byte[] profilePic = (byte[]) JdbcUtil.getColumnValue(rs, 3);   // Blob is converted to byte[]
     *         // ...
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnIndex The 1-based index of the column.
     * @return The column value as a standard Java object. {@link Blob} is returned as {@code byte[]},
     *         {@link Clob} as {@code String}; converted LOBs are freed before this method returns.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @see #getColumnValue(ResultSet, String)
     */
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws SQLException {
        return getColumnValue(rs, columnIndex, true);
    }

    /**
     * Retrieves the value of the specified column in the current row of the given {@link ResultSet}.
     * When {@code checkDateType} is {@code true}, database-specific date/time types are normalized to
     * standard Java types.
     *
     * @param rs The {@link ResultSet} from which to retrieve the column value.
     * @param columnIndex The 1-based index of the column to retrieve.
     * @param checkDateType Whether to normalize database-specific date/time types to standard Java types.
     * @return The value of the specified column in the current row of the {@code ResultSet}.
     * @throws SQLException if a database access error occurs while retrieving the column value.
     */
    static Object getColumnValue(final ResultSet rs, final int columnIndex, final boolean checkDateType) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object ret = rs.getObject(columnIndex);

        if (ret == null || ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
            return ret;
        }

        if (ret instanceof final Blob blob) {
            ret = materializeBlob(blob);
        } else if (ret instanceof final Clob clob) {
            ret = materializeClob(clob);
        } else if (checkDateType && !(rs instanceof ResultSetProxy)) {
            ret = columnConverterByIndex.apply(rs, columnIndex, ret);
        }

        return ret;
    }

    /**
     * Retrieves the value of a specified column in the current row of a {@link ResultSet} by its label.
     * This method is deprecated because looking up a column by its label/name on every row can be inefficient.
     * It is recommended to look up column indices once and then use {@link #getColumnValue(ResultSet, int)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Using the deprecated method (less efficient)
     * try (ResultSet rs = stmt.executeQuery("SELECT id, user_name AS name FROM users")) {
     *     while (rs.next()) {
     *         long id = ((Number) JdbcUtil.getColumnValue(rs, "id")).longValue();
     *         String name = (String) JdbcUtil.getColumnValue(rs, "name");
     *     }
     * }
     *
     * // Recommended approach
     * try (ResultSet rs = stmt.executeQuery("SELECT id, user_name AS name FROM users")) {
     *     int idIndex = JdbcUtil.getColumnIndex(rs, "id");
     *     int nameIndex = JdbcUtil.getColumnIndex(rs, "name");
     *     while (rs.next()) {
     *         long id = JdbcUtil.getColumnValue(rs, idIndex, long.class);
     *         String name = (String) JdbcUtil.getColumnValue(rs, nameIndex);
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnLabel The label of the column to retrieve.
     * @return The value of the specified column.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @deprecated Use {@link #getColumnValue(ResultSet, int)} with a cached column index for better performance.
     * @see #getColumnValue(ResultSet, int)
     * @see #getColumnIndex(ResultSet, String)
     */
    @Deprecated
    public static Object getColumnValue(final ResultSet rs, final String columnLabel) throws SQLException {
        return getColumnValue(rs, columnLabel, true);
    }

    /**
     * Retrieves the value of the specified column in the current row of the given {@link ResultSet} by its label.
     * When {@code checkDateType} is {@code true}, database-specific date/time types are normalized to
     * standard Java types.
     *
     * @param rs The {@link ResultSet} from which to retrieve the column value.
     * @param columnLabel The label of the column to retrieve.
     * @param checkDateType Whether to normalize database-specific date/time types to standard Java types.
     * @return The value of the specified column in the current row of the {@code ResultSet}.
     * @throws SQLException if a database access error occurs while retrieving the column value.
     * @deprecated Use {@link #getColumnValue(ResultSet, int, boolean)} with a cached column index for better performance.
     */
    @Deprecated
    static Object getColumnValue(final ResultSet rs, final String columnLabel, final boolean checkDateType) throws SQLException {
        // Copied from JdbcUtils#getResultSetValue(ResultSet, int) in SpringJdbc under Apache License, Version 2.0.

        Object ret = rs.getObject(columnLabel);

        if (ret == null || ret instanceof String || ret instanceof Number || ret instanceof java.sql.Timestamp || ret instanceof Boolean) {
            return ret;
        }

        if (ret instanceof final Blob blob) {
            ret = materializeBlob(blob);
        } else if (ret instanceof final Clob clob) {
            ret = materializeClob(clob);
        } else if (checkDateType && !(rs instanceof ResultSetProxy)) {
            ret = columnConverterByLabel.apply(rs, columnLabel, ret);
        }

        return ret;
    }

    /**
     * Reads the whole {@link Blob} into a byte array and always frees it, attaching any {@code free()}
     * failure as a suppressed exception so it can never mask the read failure.
     * @throws SQLException if reading or freeing the value fails, or its length exceeds {@link Integer#MAX_VALUE}.
     */
    static byte[] materializeBlob(final Blob blob) throws SQLException {
        Throwable primaryFailure = null;

        try {
            final long len = blob.length();
            if (len > Integer.MAX_VALUE) {
                throw new SQLException("Blob size " + len + " exceeds maximum supported size of " + Integer.MAX_VALUE);
            }

            return blob.getBytes(1, (int) len);
        } catch (final SQLException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            freeBlob(blob, primaryFailure);
        }
    }

    /**
     * Reads the whole {@link Clob} into a string and always frees it, attaching any {@code free()}
     * failure as a suppressed exception so it can never mask the read failure.
     * @throws SQLException if reading or freeing the value fails, or its length exceeds {@link Integer#MAX_VALUE}.
     */
    static String materializeClob(final Clob clob) throws SQLException {
        Throwable primaryFailure = null;

        try {
            final long len = clob.length();
            if (len > Integer.MAX_VALUE) {
                throw new SQLException("Clob size " + len + " exceeds maximum supported size of " + Integer.MAX_VALUE);
            }

            return clob.getSubString(1, (int) len);
        } catch (final SQLException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            freeClob(clob, primaryFailure);
        }
    }

    /**
     * Frees the given {@link Blob}. If {@code primaryFailure} is {@code null}, a failure of {@code free()} is
     * rethrown; otherwise it is attached to {@code primaryFailure} as a suppressed exception.
     *
     * @param blob The {@link Blob} to free.
     * @param primaryFailure The failure from the preceding read operation, or {@code null} if none occurred.
     * @throws SQLException if freeing fails and there is no primary failure.
     */
    private static void freeBlob(final Blob blob, final Throwable primaryFailure) throws SQLException {
        try {
            blob.free();
        } catch (final SQLException | RuntimeException | Error e) {
            if (primaryFailure == null) {
                throw e;
            }

            addSuppressedIfDistinct(primaryFailure, e);
        }
    }

    /**
     * Frees the given {@link Clob}. If {@code primaryFailure} is {@code null}, a failure of {@code free()} is
     * rethrown; otherwise it is attached to {@code primaryFailure} as a suppressed exception.
     *
     * @param clob The {@link Clob} to free.
     * @param primaryFailure The failure from the preceding read operation, or {@code null} if none occurred.
     * @throws SQLException if freeing fails and there is no primary failure.
     */
    private static void freeClob(final Clob clob, final Throwable primaryFailure) throws SQLException {
        try {
            clob.free();
        } catch (final SQLException | RuntimeException | Error e) {
            if (primaryFailure == null) {
                throw e;
            }

            addSuppressedIfDistinct(primaryFailure, e);
        }
    }

    /**
     * Retrieves all values from a single column of a {@link ResultSet} and returns them as a list.
     * This method iterates through the entire {@code ResultSet}, extracts the value from the specified
     * column for each row, and collects them into a {@link List}. The {@code ResultSet} will be
     * fully consumed after this method is called.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get a list of all user emails from the 'users' table
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT email FROM users")) {
     *
     *     List<String> emails = JdbcUtil.getAllColumnValues(rs, 1);
     *     System.out.println("All user emails: " + emails);
     * }
     * }</pre>
     *
     * @param <T> The expected type of the elements in the returned list.
     * @param rs The {@link ResultSet} to retrieve values from. It will be iterated to the end.
     * @param columnIndex The 1-based index of the column to retrieve.
     * @return A {@link List} containing all values from the specified column.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final int columnIndex) throws SQLException {
        final List<T> result = new ArrayList<>();

        while (rs.next()) {
            // Convert each row independently. JDBC drivers can return different runtime representations
            // across rows (notably a scalar followed by a LOB), so the first non-null value cannot select a
            // safe fast path for the rest of the result set.
            result.add((T) getColumnValue(rs, columnIndex));
        }

        return result;
    }

    /**
     * Retrieves all values from a single column of a {@link ResultSet} by its label and returns them as a list.
     * This method iterates through the entire {@code ResultSet}, finds the column index by its label,
     * extracts the value for each row, and collects them into a {@link List}. The {@code ResultSet}
     * will be fully consumed after this method is called.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get a list of all user names from the 'users' table using a column label
     * try (Statement stmt = connection.createStatement();
     *      ResultSet rs = stmt.executeQuery("SELECT user_name AS name FROM users")) {
     *
     *     List<String> names = JdbcUtil.getAllColumnValues(rs, "name");
     *     System.out.println("All user names: " + names);
     * }
     * }</pre>
     *
     * @param <T> The expected type of the elements in the returned list.
     * @param rs The {@link ResultSet} to retrieve values from. It will be iterated to the end.
     * @param columnLabel The label of the column to retrieve.
     * @return A {@link List} containing all values from the specified column.
     * @throws IllegalArgumentException if the column label does not exist in the result set.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     */
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final String columnLabel) throws IllegalArgumentException, SQLException {
        final int columnIndex = JdbcUtil.getColumnIndex(rs, columnLabel);

        if (columnIndex < 1) {
            throw new IllegalArgumentException("No column found with name: " + columnLabel + " in result set: " + JdbcUtil.getColumnLabels(rs));
        }

        return getAllColumnValues(rs, columnIndex);
    }

    /**
     * Retrieves the value of a specified column in the current row of a {@link ResultSet} and converts it to the given target type.
     * This method simplifies type casting and conversion from JDBC types to Java types.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT id, balance, is_active FROM accounts")) {
     *     while (rs.next()) {
     *         // Retrieve values with explicit type conversion
     *         Long accountId = JdbcUtil.getColumnValue(rs, 1, Long.class);
     *         BigDecimal balance = JdbcUtil.getColumnValue(rs, 2, BigDecimal.class);
     *         boolean isActive = JdbcUtil.getColumnValue(rs, 3, boolean.class);
     *
     *         System.out.println("Account: " + accountId + ", Balance: " + balance + ", Active: " + isActive);
     *     }
     * }
     * }</pre>
     *
     * @param <T> The generic type of the desired value.
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnIndex The 1-based index of the column.
     * @param targetClass The {@link Class} of the desired type {@code T}.
     * @return The column value, converted to the specified {@code targetClass}.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @see #getColumnValue(ResultSet, String, Class)
     */
    public static <T> T getColumnValue(final ResultSet rs, final int columnIndex, final Class<? extends T> targetClass) throws SQLException {
        return Type.<T> of(targetClass).get(rs, columnIndex);
    }

    /**
     * Retrieves the value of a specified column by its label and converts it to the given target type.
     * This method is deprecated due to the performance overhead of looking up the column index for each row.
     * For better performance, cache the column index and use {@link #getColumnValue(ResultSet, int, Class)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Deprecated usage:
     * try (ResultSet rs = stmt.executeQuery("SELECT id, balance FROM accounts")) {
     *     while (rs.next()) {
     *         Long id = JdbcUtil.getColumnValue(rs, "id", Long.class);
     *         BigDecimal balance = JdbcUtil.getColumnValue(rs, "balance", BigDecimal.class);
     *     }
     * }
     *
     * // Recommended alternative:
     * try (ResultSet rs = stmt.executeQuery("SELECT id, balance FROM accounts")) {
     *     int idIndex = JdbcUtil.getColumnIndex(rs, "id");
     *     int balanceIndex = JdbcUtil.getColumnIndex(rs, "balance");
     *     while (rs.next()) {
     *         Long id = JdbcUtil.getColumnValue(rs, idIndex, Long.class);
     *         BigDecimal balance = JdbcUtil.getColumnValue(rs, balanceIndex, BigDecimal.class);
     *     }
     * }
     * }</pre>
     *
     * @param <T> The generic type of the desired value.
     * @param rs The {@link ResultSet} from which to retrieve the value.
     * @param columnLabel The label of the column to retrieve.
     * @param targetClass The {@link Class} of the desired type {@code T}.
     * @return The column value, converted to the specified {@code targetClass}.
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs.
     * @deprecated Use {@link #getColumnValue(ResultSet, int, Class)} with a cached column index for better performance.
     * @see #getColumnIndex(ResultSet, String)
     */
    @Deprecated
    public static <T> T getColumnValue(final ResultSet rs, final String columnLabel, final Class<? extends T> targetClass) throws SQLException {
        return Type.<T> of(targetClass).get(rs, columnLabel);
    }

    /**
     * Retrieves a mapping from database column names to entity field names for a given entity class.
     * This mapping is crucial for the automatic object-relational mapping (ORM) features,
     * allowing {@code JdbcUtil} to populate entity objects from a {@code ResultSet}.
     * The mapping is determined by analyzing the entity class's annotations (e.g., {@code @Column}) and naming conventions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming User class has fields like 'userId' and 'userName' mapped to columns 'user_id' and 'user_name'
     * ImmutableMap<String, String> columnToPropNameMap = JdbcUtil.getColumnToPropNameMap(User.class);
     *
     * System.out.println(columnToPropNameMap.get("user_id"));     // Output: userId
     * System.out.println(columnToPropNameMap.get("user_name"));   // Output: userName
     * }</pre>
     *
     * @param entityClass The entity class to analyze for column-to-field mappings; must not be {@code null}.
     * @return An {@link ImmutableMap} where keys are database column names and values are the corresponding entity field names.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     * @see com.landawn.abacus.annotation.Column
     * @see com.landawn.abacus.util.NamingPolicy
     */
    public static ImmutableMap<String, String> getColumnToPropNameMap(final Class<?> entityClass) {
        return QueryUtil.columnToPropNameMap(entityClass);
    }

    /**
     * Determines the {@link SqlOperation} type from a given SQL string by analyzing its leading keyword.
     * Leading parentheses and comments are ignored. For a common-table expression introduced by
     * {@code WITH} (optionally {@code RECURSIVE}), the operation following the CTE definitions is used;
     * this distinguishes, for example, a {@code WITH ... SELECT} from a {@code WITH ... UPDATE}.
     * Matching is case-insensitive and requires a token boundary.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlOperation op1 = JdbcUtil.getSqlOperation("SELECT * FROM users");
     * // op1 is SqlOperation.SELECT
     *
     * SqlOperation op2 = JdbcUtil.getSqlOperation("  insert into accounts (id, name) values (1, 'test')");
     * // op2 is SqlOperation.INSERT
     *
     * SqlOperation op3 = JdbcUtil.getSqlOperation("CREATE TABLE new_table (id BIGINT PRIMARY KEY)");
     * // op3 is SqlOperation.CREATE
     *
     * SqlOperation op4 = JdbcUtil.getSqlOperation("CREATEX foo");
     * // op4 is SqlOperation.UNKNOWN (no SqlOperation token matches the leading keyword)
     * }</pre>
     *
     * @param sql The SQL statement to analyze.
     * @return The identified {@link SqlOperation}, or {@link SqlOperation#UNKNOWN} if the operation cannot be determined.
     * @see SqlOperation
     */
    static SqlOperation getSqlOperation(final String sql) {
        String trimmedSql = sql.trim();

        // Skip leading parentheses and comments so queries like "(SELECT ...) UNION ALL (SELECT ...)",
        // "/* hint */ SELECT ..." or "-- note\nSELECT ..." are classified by their real leading keyword.
        while (!trimmedSql.isEmpty()) {
            if (trimmedSql.charAt(0) == '(') {
                trimmedSql = trimmedSql.substring(1).trim();
            } else if (trimmedSql.startsWith("/*")) {
                trimmedSql = trimmedSql.substring(skipBlockComment(trimmedSql, 2)).trim();
            } else if (trimmedSql.startsWith("--")) {
                trimmedSql = trimmedSql.substring(skipLineComment(trimmedSql, 2)).trim();
            } else {
                break;
            }
        }

        if (startsWithSqlToken(trimmedSql, "with")) {
            final int operationStart = findOperationAfterWith(trimmedSql);

            if (operationStart >= 0) {
                trimmedSql = trimmedSql.substring(operationStart);
            }
        }

        if (Strings.startsWithIgnoreCase(trimmedSql, "select ")) {
            return SqlOperation.SELECT;
        } else if (Strings.startsWithIgnoreCase(trimmedSql, "update ")) {
            return SqlOperation.UPDATE;
        } else if (Strings.startsWithIgnoreCase(trimmedSql, "insert ")) {
            return SqlOperation.INSERT;
        } else if (Strings.startsWithIgnoreCase(trimmedSql, "delete ")) {
            return SqlOperation.DELETE;
        } else if (Strings.startsWithIgnoreCase(trimmedSql, "merge ")) {
            return SqlOperation.MERGE;
        } else {
            // Use sqlToken() rather than name() so multi-word operations match SQL syntax
            // (e.g., BEGIN_TRANSACTION -> "BEGIN TRANSACTION"), and require a word boundary so
            // "CREATEX..." doesn't falsely match CREATE.
            for (final SqlOperation so : SqlOperation.values()) {
                if (so == SqlOperation.UNKNOWN) {
                    continue;
                }

                final String token = so.sqlToken();

                if (Strings.startsWithIgnoreCase(trimmedSql, token) //
                        && (trimmedSql.length() == token.length() || !isSqlIdentifierPart(trimmedSql.charAt(token.length())))) {
                    return so;
                }
            }
        }

        return SqlOperation.UNKNOWN;
    }

    /**
     * Returns {@code true} if {@code sql} starts with {@code token} (case-insensitive) followed by a word boundary,
     * so that, e.g., {@code "CREATED ..."} does not match the token {@code "CREATE"}.
     *
     * @param sql The SQL text to test.
     * @param token The leading token to look for.
     * @return {@code true} if {@code sql} begins with {@code token} as a whole word.
     */
    private static boolean startsWithSqlToken(final String sql, final String token) {
        return Strings.startsWithIgnoreCase(sql, token) //
                && (sql.length() == token.length() || !isSqlIdentifierPart(sql.charAt(token.length())));
    }

    /**
     * Finds the top-level operation following one or more CTE definitions. Tokens inside the CTE
     * query bodies, quoted text/identifiers, and comments are ignored. A completed top-level
     * parenthesized section is required before an operation is accepted, preventing a CTE whose
     * name happens to resemble an operation from being misclassified.
     */
    private static int findOperationAfterWith(final String sql) {
        int parenthesisDepth = 0;
        boolean completedParenthesizedSection = false;

        for (int i = 4, len = sql.length(); i < len;) {
            final char ch = sql.charAt(i);

            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i + 2);
            } else if (ch == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i + 2);
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                i = skipQuotedSqlText(sql, i, ch);
            } else if (ch == '[') {
                i = skipBracketQuotedSqlText(sql, i);
            } else if (ch == '$') {
                final int end = skipDollarQuotedSqlText(sql, i);
                i = end > i ? end : i + 1;
            } else if (ch == '(') {
                parenthesisDepth++;
                i++;
            } else if (ch == ')') {
                if (parenthesisDepth > 0 && --parenthesisDepth == 0) {
                    completedParenthesizedSection = true;
                }

                i++;
            } else if (parenthesisDepth == 0 && completedParenthesizedSection && Character.isLetter(ch)) {
                int end = i + 1;

                while (end < len && isSqlIdentifierPart(sql.charAt(end))) {
                    end++;
                }

                final String token = sql.substring(i, end);

                if (token.equalsIgnoreCase("select") || token.equalsIgnoreCase("update") || token.equalsIgnoreCase("insert") || token.equalsIgnoreCase("delete")
                        || token.equalsIgnoreCase("merge")) {
                    return i;
                }

                i = end;
            } else {
                i++;
            }
        }

        return -1;
    }

    /**
     * Returns {@code true} if the character can appear in a non-delimited SQL identifier:
     * a letter, a digit, {@code '_'}, or {@code '$'}.
     */
    private static boolean isSqlIdentifierPart(final char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    /**
     * Advances past the remainder of a line comment.
     *
     * @param sql The SQL text being scanned.
     * @param index The position just after the opening {@code --}.
     * @return The index just past the line-break character that ends the comment (for a CRLF terminator,
     *         the index of the {@code '\n'}), or the end of the string.
     */
    private static int skipLineComment(final String sql, int index) {
        final int len = sql.length();

        while (index < len) {
            final char ch = sql.charAt(index++);

            if (ch == '\n' || ch == '\r') {
                break;
            }
        }

        return index;
    }

    /**
     * Advances past a block comment, honoring nested block comments.
     *
     * @param sql The SQL text being scanned.
     * @param index The position just after the opening comment delimiter.
     * @return The index of the first character after the matching closing delimiter,
     *         or the end of the string if the comment is unterminated.
     */
    private static int skipBlockComment(final String sql, int index) {
        final int len = sql.length();
        int depth = 1;

        while (index < len && depth > 0) {
            if (index + 1 < len && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                depth++;
                index += 2;
            } else if (index + 1 < len && sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                depth--;
                index += 2;
            } else {
                index++;
            }
        }

        return index;
    }

    /**
     * Skips over a quoted string or delimited identifier. Like
     * {@code com.landawn.abacus.query.SqlParser}, this accepts the union of the dialect escaping
     * styles &mdash; doubled delimiters (SQL standard) and backslash escapes (MySQL/MariaDB) &mdash;
     * so SQL is tokenized the same way here as by the parser the rest of the framework uses. A
     * standard-SQL literal whose body ends with a backslash therefore reads as unterminated; the
     * scan then finds no leading operation keyword and the statement is classified
     * {@code UNKNOWN} rather than being misclassified.
     */
    private static int skipQuotedSqlText(final String sql, int index, final char quote) {
        final int len = sql.length();
        index++;

        while (index < len) {
            final char ch = sql.charAt(index++);

            if (ch == '\\' && index < len) {
                index++;
            } else if (ch == quote) {
                if (index < len && sql.charAt(index) == quote) {
                    index++;
                } else {
                    break;
                }
            }
        }

        return index;
    }

    /**
     * Advances past a bracket-delimited identifier ({@code [...]}), treating a doubled closing bracket as an escape.
     *
     * @param sql The SQL text being scanned.
     * @param index The position of the opening bracket.
     * @return The index of the first character after the closing bracket, or the end of the string if unterminated.
     */
    private static int skipBracketQuotedSqlText(final String sql, int index) {
        final int len = sql.length();
        index++;

        while (index < len) {
            if (sql.charAt(index++) == ']') {
                if (index < len && sql.charAt(index) == ']') {
                    index++;
                } else {
                    break;
                }
            }
        }

        return index;
    }

    /**
     * Advances past a PostgreSQL dollar-quoted string ({@code $tag$...$tag$}).
     *
     * @param sql The SQL text being scanned.
     * @param index The position of the opening {@code $}.
     * @return The index just past the closing delimiter; {@code index} unchanged if this is not a valid
     *         dollar-quote opener, or the end of the string if the closing delimiter is missing.
     */
    private static int skipDollarQuotedSqlText(final String sql, final int index) {
        final int len = sql.length();
        int tagEnd = index + 1;

        // A dollar-quote tag follows unquoted-identifier rules, so it cannot start with a digit;
        // $1, $2, ... are positional parameter references, not dollar-quote openers.
        if (tagEnd < len && Character.isDigit(sql.charAt(tagEnd))) {
            return index;
        }

        while (tagEnd < len && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_')) {
            tagEnd++;
        }

        if (tagEnd >= len || sql.charAt(tagEnd) != '$') {
            return index;
        }

        final String delimiter = sql.substring(index, tagEnd + 1);
        final int closingDelimiter = sql.indexOf(delimiter, tagEnd + 1);

        return closingDelimiter < 0 ? len : closingDelimiter + delimiter.length();
    }

    /**
     * Statement configurer for queries expected to return a large result set: sets forward-only fetch direction
     * and raises the fetch size to at least {@link #DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET}.
     */
    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);
        }
    };

    /**
     * Statement configurer for stream-based result processing: sets forward-only fetch direction
     * and raises the fetch size to at least {@link #DEFAULT_FETCH_SIZE_FOR_STREAM}.
     */
    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForStream = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_STREAM);
        }
    };

    /**
     * Prepares a SQL query for execution, returning a {@link PreparedQuery} object. By default the
     * returned {@code PreparedQuery} closes itself (and any connection it opened) after the first
     * execution. To reuse it multiple times with different parameters, call
     * {@link PreparedQuery#closeAfterExecution(boolean) closeAfterExecution(false)} and close it
     * explicitly when done. Reusing a single statement is more efficient than creating new prepared
     * statements for each execution, especially when the same query is executed repeatedly with
     * different parameters.
     *
     * <p>This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.</p>
     *
     * <p><b>Key Features:</b></p>
     * <ul>
     *   <li>Automatic resource management when used with try-with-resources</li>
     *   <li>Support for method chaining with fluent parameter setting API</li>
     *   <li>Integration with transaction context for transactional operations</li>
     *   <li>Type-safe result mapping to Java objects, Lists, Maps, and more</li>
     *   <li>Stream support for memory-efficient processing of large result sets</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Basic query execution with single result
     * // If closeAfterExecution(false) is not called,
     * // there is no need to place the query instance in a try-with-resources block to close it.
     * Optional<User> user = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users WHERE id = ?")
     *                                  .setLong(1, userId).findFirst(User.class);
     * if (user.isPresent()) {
     *     System.out.println("Found: " + user.get().getName());
     * }
     *
     * // Query with multiple parameters returning a list
     * List<Order> orders = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM orders WHERE customer_id = ? AND status = ? AND order_date > ?")
     *         .setLong(1, customerId)
     *         .setString(2, "PENDING")
     *         .setDate(3, Date.valueOf(lastWeek))
     *         .list(Order.class);
     *
     *  orders.forEach(order -> System.out.println("Order #" + order.getId()));
     *
     * // Reusing the same PreparedQuery with different parameters
     * try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource,
     *         "SELECT COUNT(*) FROM products WHERE category = ?").closeAfterExecution(false)) {
     *
     *     long electronicsCount = query.setString(1, "Electronics")
     *                                  .queryForSingleValue(Long.class)
     *                                  .orElse(0L);
     *
     *     long clothingCount = query.setString(1, "Clothing")
     *                               .queryForSingleValue(Long.class)
     *                               .orElse(0L);
     *
     *     System.out.println("Electronics: " + electronicsCount);
     *     System.out.println("Clothing: " + clothingCount);
     * }
     *
     * // Stream processing for large result sets
     * try (Stream<PaymentTransaction> stream = JdbcUtil.prepareQuery(dataSource,
     *         "SELECT * FROM transactions WHERE amount > ?")
     *     .setDouble(1, 1000.0)
     *     .stream(PaymentTransaction.class)) {
     *
     *     double totalAmount = stream
     *         .filter(t -> t.getStatus().equals("COMPLETED"))
     *         .mapToDouble(PaymentTransaction::getAmount)
     *         .sum();
     *
     *     System.out.println("Total: " + totalAmount);
     * }
     *
     * // Working with Dataset (column-oriented data structure)
     * Dataset dataset = JdbcUtil.prepareQuery(dataSource,
     *         "SELECT name, email, age FROM users WHERE department = ?")
     *     .setString(1, "Engineering")
     *     .query();
     * dataset.forEach(row -> {
     *     // Columns are positional, in SELECT order: name=0, email=1, age=2
     *     System.out.println(row.get(0) + " - " + row.get(1));
     * });
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from, must not be {@code null}.
     * @param sql The SQL query to prepare with optional {@code ?} parameter placeholders, must not be {@code null} or empty.
     * @return A new {@link PreparedQuery} instance ready for parameter setting and execution.
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs during preparation.
     * @see PreparedQuery
     * @see #prepareQuery(Connection, String)
     * @see #executeQuery(javax.sql.DataSource, String, Object...)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query with support for retrieving auto-generated keys.
     * This is typically used for {@code INSERT} statements when you need to get the ID of the newly created row.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Long> newUserId = JdbcUtil.prepareQuery(dataSource,
     *         "INSERT INTO users (first_name, last_name) VALUES (?, ?)", true).setString(1, "John")
     *                                     .setString(2, "Doe")
     *                                     .insert();
     *
     * if (newUserId.isPresent()) {
     *     System.out.println("New user created with ID: " + newUserId.get());
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare (usually an {@code INSERT} statement).
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link PreparedQuery} instance configured to handle auto-generated keys.
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs or the driver does not support auto-generated keys.
     * @see Statement#RETURN_GENERATED_KEYS
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, autoGeneratedKeys);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query to make auto-generated keys available from specified column indexes.
     * This is an advanced feature for drivers that can return values from multiple auto-generated columns.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table where a trigger generates a UUID and a timestamp on insert.
     * String insertSql = "INSERT INTO documents (content) VALUES (?)";
     *
     * // Use insert(RowMapper) to read the generated column(s)
     * Optional<String> generatedUuid = JdbcUtil.prepareQuery(dataSource, insertSql, new int[]{1, 4})
     *     .setString(1, "Some content...")
     *     .insert(rs -> rs.getString(1));
     *
     * generatedUuid.ifPresent(uuid ->
     *     System.out.println("New document created with UUID: " + uuid));
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare.
     * @param generatedKeyColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see Connection#prepareStatement(String, int[])
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, generatedKeyColumnIndexes);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, generatedKeyColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query to make auto-generated keys available from specified column names.
     * This is an advanced feature for drivers that can return values from multiple auto-generated columns by name.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table with an auto-incrementing 'id' and a 'created_at' column with a default value.
     * String query = "INSERT INTO logs (message) VALUES (?)";
     *
     * Optional<Long> newId = JdbcUtil.prepareQuery(dataSource, query, new String[]{"id", "created_at"})
     *     .setString(1, "User logged in")
     *     .insert();
     *
     * newId.ifPresent(id ->
     *     System.out.println("New log entry created with ID: " + id));
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare.
     * @param generatedKeyColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see Connection#prepareStatement(String, String[])
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, generatedKeyColumnNames);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, generatedKeyColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query using a custom {@link PreparedStatement} creator.
     * This method provides an extension point to customize the creation of the {@code PreparedStatement}.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Creating a scrollable and updatable ResultSet
     * List<User> users = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users",
     *      (conn, sql) -> conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
     *     .list(User.class);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL statement to prepare.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link PreparedQuery} instance wrapping the custom-created statement.
     * @throws IllegalArgumentException if {@code ds} or {@code stmtCreator} is {@code null}, or if {@code sql} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareQuery(tran.connection(), sql, stmtCreator);
        } else {
            PreparedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a SQL query using a provided {@link Connection}.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // If closeAfterExecution(false) is not called,
     *     // there is no need to place the query instance in a try-with-resources block to close it.
     *     User user = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE id = ?").setLong(1, userId).findOnlyOneOrNull(User.class);
     *     // ...
     * } catch (SQLException e) {
     *     // Handle exceptions
     * } // Connection is automatically closed here
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for the query. It will not be closed by this method.
     * @param sql The SQL query to prepare.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty.
     * @throws SQLException if a database access error occurs.
     * @see #prepareQuery(javax.sql.DataSource, String)
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new PreparedQuery(prepareStatement(conn, sql));
    }

    /**
     * Prepares a SQL query with auto-generated keys support using a provided {@link Connection}.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // If closeAfterExecution(false) is not called,
     *     // there is no need to place the query instance in a try-with-resources block to close it.
     *     Optional<Long> newId = JdbcUtil.prepareQuery(conn, "INSERT INTO users (name) VALUES (?)", true)
     *         .setString(1, "New User")
     *         .insert();
     *     System.out.println("New user ID: " + newId.orElse(null));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param autoGeneratedKeys If {@code true}, generated keys will be available for retrieval.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty.
     * @throws SQLException if a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new PreparedQuery(prepareStatement(conn, sql, autoGeneratedKeys));
    }

    /**
     * Prepares a SQL query to retrieve auto-generated keys from specified column indexes, using a provided {@link Connection}.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Assumes columns 1 ('id') and 4 ('creation_ts') are auto-generated
     *     Optional<Long> generatedId = JdbcUtil.prepareQuery(conn,
     *             "INSERT INTO events (message) VALUES (?)", new int[]{1, 4})
     *         .setString(1, "System startup")
     *         .insert();
     *     generatedId.ifPresent(id ->
     *         System.out.println("New event ID: " + id));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param generatedKeyColumnIndexes An array of 1-based column indexes of generated keys to return.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException if any argument is {@code null} or empty.
     * @throws SQLException if a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);

        return new PreparedQuery(prepareStatement(conn, sql, generatedKeyColumnIndexes));
    }

    /**
     * Prepares a SQL query to retrieve auto-generated keys from specified column names, using a provided {@link Connection}.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Assumes columns 'id' and 'creation_ts' are auto-generated
     *     Optional<Long> generatedId = JdbcUtil.prepareQuery(conn,
     *             "INSERT INTO events (message) VALUES (?)", new String[]{"id", "creation_ts"})
     *         .setString(1, "System shutdown")
     *         .insert();
     *     generatedId.ifPresent(id ->
     *         System.out.println("New event ID: " + id));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param generatedKeyColumnNames An array of column names of generated keys to return.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException if any argument is {@code null} or empty.
     * @throws SQLException if a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);

        return new PreparedQuery(prepareStatement(conn, sql, generatedKeyColumnNames));
    }

    /**
     * Prepares a SQL query using a custom {@link PreparedStatement} creator and a provided {@link Connection}.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Create a query with a specific fetch size and timeout
     *     try (Stream<AuditRecord> stream = JdbcUtil.prepareQuery(conn, "SELECT * FROM large_table",
     *             (c, s) -> {
     *                 PreparedStatement stmt = c.prepareStatement(s);
     *                 stmt.setFetchSize(100);
     *                 stmt.setQueryTimeout(30);   // 30 seconds
     *                 return stmt;
     *             })
     *         .stream(AuditRecord.class)) {
     *
     *         stream.forEach(System.out::println);
     *     }
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL statement to prepare.
     * @param stmtCreator A factory function to create the {@link PreparedStatement}.
     * @return A new {@link PreparedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code stmtCreator} is {@code null}, or if {@code sql} is {@code null} or empty.
     * @throws SQLException if a database access error occurs.
     */
    public static PreparedQuery prepareQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return new PreparedQuery(prepareStatement(conn, sql, stmtCreator));
    }

    /**
     * Prepares a SQL query optimized for processing large result sets from a {@link javax.sql.DataSource}.
     * This method configures the underlying {@link PreparedStatement} with a forward-only fetch direction
     * and a larger fetch size to improve performance when streaming or iterating over many rows.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code PreparedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Efficiently stream over a large table without loading everything into memory.
     * try (Stream<LogEntry> stream = JdbcUtil.prepareQueryForLargeResult(dataSource, "SELECT * FROM event_logs")
     *     .stream(LogEntry.class)) {
     *
     *     long count = stream
     *         .filter(entry -> entry.getLevel().equals("ERROR"))
     *         .count();
     *     System.out.println("Found " + count + " error entries.");
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL query to prepare.
     * @return A {@link PreparedQuery} instance optimized for large result sets.
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; see {@link #getConnection(javax.sql.DataSource)}
     *         for Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #prepareQueryForLargeResult(Connection, String)
     */
    @Beta
    public static PreparedQuery prepareQueryForLargeResult(final javax.sql.DataSource ds, final String sql) throws IllegalArgumentException, SQLException {
        return prepareQuery(ds, sql).configureStatement(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a SQL query optimized for large result sets using a provided {@link Connection}.
     * The caller is responsible for closing the connection. This method configures the statement
     * for efficient forward-only, large-fetch-size processing.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection();
     *         Stream<Audit> stream = JdbcUtil.prepareQueryForLargeResult(conn, "SELECT * FROM audit_trail")
     *             .stream(Audit.class)) {
     *     // Stream results without high memory usage
     *     stream.forEach(audit -> {
     *             // process audit record
     *         });
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL query to prepare.
     * @return A {@link PreparedQuery} instance optimized for large result sets.
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty.
     * @throws SQLException if a database access error occurs.
     * @see #prepareQueryForLargeResult(javax.sql.DataSource, String)
     */
    @Beta
    public static PreparedQuery prepareQueryForLargeResult(final Connection conn, final String sql) throws IllegalArgumentException, SQLException {
        return prepareQuery(conn, sql).configureStatement(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named-parameter SQL query for execution, returning a {@link NamedQuery} object.
     * Named parameters (e.g., {@code :firstName}) are used instead of traditional positional {@code ?} parameters.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE first_name = :firstName AND status = :status";
     * List<User> users = JdbcUtil.prepareNamedQuery(dataSource, sql)
     *     .setString("firstName", "John")
     *     .setString("status", "ACTIVE")
     *     .list(User.class);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null} or empty, or if {@code namedSql} contains positional
     *         (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see NamedQuery
     * @see #prepareNamedQuery(Connection, String)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final SqlTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Long> newUserId = JdbcUtil.prepareNamedQuery(dataSource,
     *         "INSERT INTO users (first_name, last_name, email) VALUES (:firstName, :lastName, :email)", true)
     *     .setString("firstName", "John")
     *     .setString("lastName", "Doe")
     *     .setString("email", "john.doe@example.com")
     *     .insert();
     *
     * if (newUserId.isPresent()) {
     *     System.out.println("New user created with ID: " + newUserId.get());
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link NamedQuery} instance configured to handle auto-generated keys.
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null} or empty, or if {@code namedSql} contains positional
     *         (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final SqlTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table where a trigger generates a UUID at column 1 and a timestamp at column 4 on insert
     * String insertSql = "INSERT INTO documents (title, content) VALUES (:title, :content)";
     *
     * // Use insert(RowMapper) to read the generated column(s)
     * Optional<String> generatedUuid = JdbcUtil.prepareNamedQuery(dataSource, insertSql, new int[]{1, 4})
     *     .setString("title", "Annual Report")
     *     .setString("content", "Report content...")
     *     .insert(rs -> rs.getString(1));
     *
     * generatedUuid.ifPresent(uuid ->
     *     System.out.println("Document created with UUID: " + uuid));
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param generatedKeyColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} contains positional (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);

        final SqlTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, generatedKeyColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, generatedKeyColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a table with an auto-incrementing 'id' and a 'created_at' column with a default value
     * String query = "INSERT INTO logs (message, severity) VALUES (:message, :severity)";
     *
     * Optional<Long> newId = JdbcUtil.prepareNamedQuery(dataSource, query, new String[]{"id", "created_at"})
     *     .setString("message", "User logged in")
     *     .setString("severity", "INFO")
     *     .insert();
     *
     * newId.ifPresent(id ->
     *     System.out.println("New log entry created with ID: " + id));
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param generatedKeyColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} contains positional (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);

        final SqlTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, generatedKeyColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, generatedKeyColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Creating a scrollable and updatable ResultSet with named parameters
     * List<User> users = JdbcUtil.prepareNamedQuery(dataSource,
     *         "SELECT * FROM users WHERE department = :dept",
     *         (conn, sql) -> conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE))
     *     .setString("dept", "Engineering")
     *     .list(User.class);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link NamedQuery} instance wrapping the custom-created statement.
     * @throws IllegalArgumentException if {@code ds} or {@code stmtCreator} is {@code null} , if {@code namedSql} is {@code null} or empty, or if
     *         {@code namedSql} contains positional (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final SqlTransaction tran = getTransaction(ds, namedSql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using the provided Connection and named SQL string.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * try {
     *     int updated = JdbcUtil.prepareNamedQuery(conn,
     *             "UPDATE users SET status = :status WHERE id = :id")
     *         .setString("status", "ACTIVE")
     *         .setLong("id", userId)
     *         .update();
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null} or empty, or if {@code namedSql} contains positional
     *         (unnamed) parameters.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql), parsedSql);
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided Connection.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     Optional<Long> newId = JdbcUtil.prepareNamedQuery(conn,
     *             "INSERT INTO products (name, price, category) VALUES (:name, :price, :category)", true)
     *         .setString("name", "Laptop")
     *         .setDouble("price", 999.99)
     *         .setString("category", "Electronics")
     *         .insert();
     *     System.out.println("New product ID: " + newId.orElse(null));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null} or empty, or if {@code namedSql} contains positional
     *         (unnamed) parameters.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, autoGeneratedKeys), parsedSql);
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided Connection.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Assumes columns 1 ('id') and 4 ('creation_ts') are auto-generated
     *     Optional<Long> generatedId = JdbcUtil.prepareNamedQuery(conn,
     *             "INSERT INTO events (event_type, message) VALUES (:type, :msg)", new int[]{1, 4})
     *         .setString("type", "SYSTEM")
     *         .setString("msg", "System startup")
     *         .insert();
     *     generatedId.ifPresent(id ->
     *         System.out.println("New event ID: " + id));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param generatedKeyColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} contains positional (unnamed) parameters.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, generatedKeyColumnIndexes), parsedSql);
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided Connection.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     Optional<Long> newId = JdbcUtil.prepareNamedQuery(conn,
     *             "INSERT INTO notifications (user_id, message) VALUES (:userId, :msg)",
     *             new String[]{"id", "created_at"})
     *         .setInt("userId", 123)
     *         .setString("msg", "Welcome to the system")
     *         .insert();
     *
     *     newId.ifPresent(id ->
     *         System.out.println("New notification ID: " + id));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param generatedKeyColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} contains positional (unnamed) parameters.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final String namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);

        final ParsedSql parsedSql = parseNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, parsedSql, generatedKeyColumnNames), parsedSql);
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided Connection.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Create a scrollable ResultSet with named parameters
     *     List<Order> orders = JdbcUtil.prepareNamedQuery(conn,
     *             "SELECT * FROM orders WHERE customer_id = :customerId",
     *             (c, sql) -> c.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY))
     *         .setInt("customerId", 456)
     *         .list(Order.class);
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code stmtCreator} is {@code null} , if {@code namedSql} is {@code null} or empty, or if
     *         {@code namedSql} contains positional (unnamed) parameters.
     * @throws SQLException if a database access error occurs.
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
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Reuse a pre-parsed named SQL (parsing once is cheaper when the query is run repeatedly).
     * ParsedSql psql = ParsedSql.parse("SELECT * FROM users WHERE first_name = :firstName AND status = :status");
     * List<User> users = JdbcUtil.prepareNamedQuery(dataSource, psql)
     *     .setString("firstName", "John")
     *     .setString("status", "ACTIVE")
     *     .list(User.class);
     *
     * // Passing a null ParsedSql throws IllegalArgumentException.
     * JdbcUtil.prepareNamedQuery(dataSource, (ParsedSql) null);   // throws IllegalArgumentException
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, or if {@code namedSql} is invalid.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        final SqlTransaction tran = getTransaction(ds, namedSql.parameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql psql = ParsedSql.parse("INSERT INTO products (name, price) VALUES (:name, :price)");
     * Optional<Long> newId = JdbcUtil.prepareNamedQuery(dataSource, psql, true)
     *     .setString("name", "Laptop")
     *     .setDouble("price", 999.99)
     *     .insert();   // returns the generated key
     * newId.ifPresent(id -> System.out.println("New product ID: " + id));
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, or if {@code namedSql} is invalid.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        final SqlTransaction tran = getTransaction(ds, namedSql.parameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Request the auto-generated key from column index 1 (the 'id' column).
     * ParsedSql psql = ParsedSql.parse("INSERT INTO events (event_type, message) VALUES (:type, :msg)");
     * Optional<Long> generatedId = JdbcUtil.prepareNamedQuery(dataSource, psql, new int[]{1})
     *     .setString("type", "SYSTEM")
     *     .setString("msg", "System startup")
     *     .insert();   // returns the generated key
     *
     * // An empty generatedKeyColumnIndexes array throws IllegalArgumentException.
     * JdbcUtil.prepareNamedQuery(dataSource, psql, new int[0]);   // throws IllegalArgumentException
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param generatedKeyColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} is invalid.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);
        validateNamedSql(namedSql);

        final SqlTransaction tran = getTransaction(ds, namedSql.parameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, generatedKeyColumnIndexes);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, generatedKeyColumnIndexes).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Request the auto-generated key by column name.
     * ParsedSql psql = ParsedSql.parse("INSERT INTO notifications (user_id, message) VALUES (:userId, :msg)");
     * Optional<Long> newId = JdbcUtil.prepareNamedQuery(dataSource, psql, new String[]{"id"})
     *     .setInt("userId", 123)
     *     .setString("msg", "Welcome to the system")
     *     .insert();   // returns the generated key
     *
     * // An empty generatedKeyColumnNames array throws IllegalArgumentException.
     * JdbcUtil.prepareNamedQuery(dataSource, psql, new String[0]);   // throws IllegalArgumentException
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param generatedKeyColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} is invalid.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);
        validateNamedSql(namedSql);

        final SqlTransaction tran = getTransaction(ds, namedSql.parameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, generatedKeyColumnNames);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, generatedKeyColumnNames).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided DataSource and ParsedSql object.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a scrollable ResultSet with named parameters.
     * ParsedSql psql = ParsedSql.parse("SELECT * FROM users WHERE department = :dept");
     * List<User> users = JdbcUtil.prepareNamedQuery(dataSource, psql,
     *         (conn, sql) -> conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY))
     *     .setString("dept", "Engineering")
     *     .list(User.class);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code ds}, {@code namedSql}, or {@code stmtCreator} is {@code null}, or if {@code namedSql} is invalid.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);
        validateNamedSql(namedSql);

        final SqlTransaction tran = getTransaction(ds, namedSql.parameterizedSql(), CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareNamedQuery(tran.connection(), namedSql, stmtCreator);
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, namedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a named SQL query using the provided Connection and ParsedSql object.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql psql = ParsedSql.parse("UPDATE users SET status = :status WHERE id = :id");
     * try (Connection conn = dataSource.getConnection()) {
     *     int updated = JdbcUtil.prepareNamedQuery(conn, psql)
     *         .setString("status", "ACTIVE")
     *         .setLong("id", userId)
     *         .update();   // returns the number of rows updated
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, or if {@code namedSql} is invalid.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql), namedSql);
    }

    /**
     * Prepares a named SQL query with auto-generated keys support using the provided Connection and ParsedSql object.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql psql = ParsedSql.parse("INSERT INTO products (name, price, category) VALUES (:name, :price, :category)");
     * try (Connection conn = dataSource.getConnection()) {
     *     Optional<Long> newId = JdbcUtil.prepareNamedQuery(conn, psql, true)
     *         .setString("name", "Laptop")
     *         .setDouble("price", 999.99)
     *         .setString("category", "Electronics")
     *         .insert();   // returns the generated key
     *     System.out.println("New product ID: " + newId.orElse(null));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, or if {@code namedSql} is invalid.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, autoGeneratedKeys), namedSql);
    }

    /**
     * Prepares a named SQL query with specific column indexes for auto-generated keys using the provided Connection and ParsedSql object.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql psql = ParsedSql.parse("INSERT INTO events (event_type, message) VALUES (:type, :msg)");
     * try (Connection conn = dataSource.getConnection()) {
     *     // Request the auto-generated key from column index 1 (the 'id' column).
     *     Optional<Long> generatedId = JdbcUtil.prepareNamedQuery(conn, psql, new int[]{1})
     *         .setString("type", "SYSTEM")
     *         .setString("msg", "System startup")
     *         .insert();   // returns the generated key
     *     generatedId.ifPresent(id -> System.out.println("New event ID: " + id));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param generatedKeyColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} is invalid.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, generatedKeyColumnIndexes), namedSql);
    }

    /**
     * Prepares a named SQL query with specific column names for auto-generated keys using the provided Connection and ParsedSql object.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql psql = ParsedSql.parse("INSERT INTO notifications (user_id, message) VALUES (:userId, :msg)");
     * try (Connection conn = dataSource.getConnection()) {
     *     Optional<Long> newId = JdbcUtil.prepareNamedQuery(conn, psql, new String[]{"id"})
     *         .setInt("userId", 123)
     *         .setString("msg", "Welcome to the system")
     *         .insert();   // returns the generated key
     *     newId.ifPresent(id -> System.out.println("New notification ID: " + id));
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param generatedKeyColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if any of the arguments are {@code null} or empty, or if {@code namedSql} is invalid.
     * @throws SQLException if a database access error occurs.
     */
    public static NamedQuery prepareNamedQuery(final Connection conn, final ParsedSql namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);
        validateNamedSql(namedSql);

        return new NamedQuery(prepareStatement(conn, namedSql, generatedKeyColumnNames), namedSql);
    }

    /**
     * Prepares a named SQL query using a custom statement creator with the provided Connection and ParsedSql object.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql psql = ParsedSql.parse("SELECT * FROM orders WHERE customer_id = :customerId");
     * try (Connection conn = dataSource.getConnection()) {
     *     // Create a scrollable ResultSet with named parameters.
     *     List<Order> orders = JdbcUtil.prepareNamedQuery(conn, psql,
     *             (c, sql) -> c.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY))
     *         .setInt("customerId", 456)
     *         .list(Order.class);
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link NamedQuery} instance.
     * @throws IllegalArgumentException if {@code conn}, {@code namedSql}, or {@code stmtCreator} is {@code null}, or if {@code namedSql} is invalid.
     * @throws SQLException if a database access error occurs.
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
     * Prepares a named SQL query optimized for large result sets using the provided DataSource.
     * This method sets the fetch direction to {@link ResultSet#FETCH_FORWARD} and a larger fetch size to improve performance when streaming many rows.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Efficiently stream over a large, filtered result set without loading everything into memory.
     * try (Stream<LogEntry> stream = JdbcUtil.prepareNamedQueryForLargeResult(dataSource,
     *             "SELECT * FROM event_logs WHERE level = :level")
     *     .setString("level", "ERROR")
     *     .stream(LogEntry.class)) {
     *
     *     long count = stream.count();
     *     System.out.println("Found " + count + " error entries.");
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @return A new {@link NamedQuery} instance configured for large result sets.
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null} or empty, or if {@code namedSql} contains positional
     *         (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; see {@link #getConnection(javax.sql.DataSource)}
     *         for Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     */
    @Beta
    public static NamedQuery prepareNamedQueryForLargeResult(final javax.sql.DataSource ds, final String namedSql)
            throws IllegalArgumentException, SQLException {
        return prepareNamedQuery(ds, namedSql).configureStatement(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query optimized for large result sets using the provided DataSource and ParsedSql object.
     * This method sets the fetch direction to {@link ResultSet#FETCH_FORWARD} and a larger fetch size to improve performance when streaming many rows.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code NamedQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Reuse a pre-parsed named SQL while streaming a large result set.
     * ParsedSql psql = ParsedSql.parse("SELECT * FROM event_logs WHERE level = :level");
     * try (Stream<LogEntry> stream = JdbcUtil.prepareNamedQueryForLargeResult(dataSource, psql)
     *     .setString("level", "ERROR")
     *     .stream(LogEntry.class)) {
     *
     *     long count = stream.count();
     *     System.out.println("Found " + count + " error entries.");
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @return A new {@link NamedQuery} instance configured for large result sets.
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, or if {@code namedSql} is invalid.
     * @throws UncheckedSQLException if acquiring a connection fails; see {@link #getConnection(javax.sql.DataSource)}
     *         for Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     */
    @Beta
    public static NamedQuery prepareNamedQueryForLargeResult(final javax.sql.DataSource ds, final ParsedSql namedSql)
            throws IllegalArgumentException, SQLException {
        return prepareNamedQuery(ds, namedSql).configureStatement(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query optimized for large result sets using the provided Connection.
     * This method sets the fetch direction to {@link ResultSet#FETCH_FORWARD} and a larger fetch size to improve performance when streaming many rows.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection();
     *         Stream<LogEntry> stream = JdbcUtil.prepareNamedQueryForLargeResult(conn,
     *                 "SELECT * FROM event_logs WHERE level = :level")
     *             .setString("level", "ERROR")
     *             .stream(LogEntry.class)) {
     *     // Stream a large, filtered result set on a caller-managed connection.
     *     long count = stream.count();
     *     System.out.println("Found " + count + " error entries.");
     * } catch (SQLException e) {
     *     // Handle exception
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The SQL query with named parameters (e.g., {@code :paramName}).
     * @return A new {@link NamedQuery} instance configured for large result sets.
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null} or empty, or if {@code namedSql} contains positional
     *         (unnamed) parameters.
     * @throws SQLException if a database access error occurs.
     */
    @Beta
    public static NamedQuery prepareNamedQueryForLargeResult(final Connection conn, final String namedSql) throws IllegalArgumentException, SQLException {
        return prepareNamedQuery(conn, namedSql).configureStatement(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a named SQL query optimized for large result sets using the provided Connection and a pre-parsed {@link ParsedSql}.
     * This method sets the fetch direction to {@link ResultSet#FETCH_FORWARD} and a larger fetch size to improve performance when streaming many rows.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p>This is the {@link ParsedSql} counterpart of {@link #prepareNamedQueryForLargeResult(Connection, String)}, completing
     * the {@code {DataSource, Connection} x {String, ParsedSql}} factory matrix that the non-large {@code prepareNamedQuery} already offers.</p>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param namedSql The parsed SQL object containing the named SQL query.
     * @return A new {@link NamedQuery} instance configured for large result sets.
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, or if {@code namedSql} is invalid.
     * @throws SQLException if a database access error occurs.
     */
    @Beta
    public static NamedQuery prepareNamedQueryForLargeResult(final Connection conn, final ParsedSql namedSql) throws IllegalArgumentException, SQLException {
        return prepareNamedQuery(conn, namedSql).configureStatement(stmtSetterForBigQueryResult);
    }

    /**
     * Prepares a callable SQL query (stored procedure) using the provided DataSource.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code CallableQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Jdbc.OutParamResult outResult = JdbcUtil.prepareCallableQuery(dataSource,
     *         "{call get_user_info(?, ?, ?)}")
     *     .setLong(1, userId)
     *     .registerOutParameter(2, Types.VARCHAR)
     *     .registerOutParameter(3, Types.DATE)
     *     .executeAndGetOutParameters();
     *
     * String name = outResult.getOutParamValue(2);
     * Date createdDate = outResult.getOutParamValue(3);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL string for the stored procedure call.
     * @return A new {@link CallableQuery} instance.
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql);
        } else {
            CallableQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareCallableQuery(conn, sql).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a callable SQL query using a custom statement creator with the provided DataSource.
     * This method allows for fine-grained control over the CallableStatement creation process,
     * enabling custom configurations such as result set type, concurrency, and holdability.
     *
     * <p>
     * This method intelligently manages connections: if a transaction is active on the current thread
     * (started via {@link #beginTransaction(javax.sql.DataSource)} or Spring's transactional support),
     * the transactional connection is used. Otherwise, a new connection is obtained from the
     * {@code DataSource} and will be automatically closed when the {@code CallableQuery} is closed.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a CallableStatement with specific result set type and concurrency
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(dataSource,
     *         "{call get_users(?, ?)}",
     *         (conn, sql) -> conn.prepareCall(sql,
     *             ResultSet.TYPE_SCROLL_INSENSITIVE,
     *             ResultSet.CONCUR_READ_ONLY))) {
     *
     *     query.setInt(1, departmentId);
     *     query.registerOutParameter(2, Types.INTEGER);
     *     Dataset result = query.query();
     *     // Process result that can be scrolled backward
     * }
     *
     * // Configure statement with specific timeout
     * Throwables.BiFunction<Connection, String, CallableStatement, SQLException> creator =
     *     (conn, sql) -> {
     *         CallableStatement stmt = conn.prepareCall(sql);
     *         stmt.setQueryTimeout(30);   // 30 seconds timeout
     *         return stmt;
     *     };
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(dataSource,
     *         "{call long_running_procedure(?)}", creator)) {
     *     query.setString(1, "param");
     *     query.execute();
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from. Must not be {@code null}.
     * @param sql The SQL string for the stored procedure call. Must not be {@code null} or empty.
     * @param stmtCreator A functional interface that creates a CallableStatement with custom configuration.
     *                    Receives the Connection and SQL string, and returns a configured CallableStatement.
     *                    Must not be {@code null}.
     * @return A new {@link CallableQuery} instance wrapping the custom-created statement.
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty, or if {@code stmtCreator} is {@code null}.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs.
     * @see #prepareCallableQuery(javax.sql.DataSource, String)
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return prepareCallableQuery(tran.connection(), sql, stmtCreator);
        } else {
            CallableQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareCallableQuery(conn, sql, stmtCreator).onClose(createCloseHandler(conn, ds));
            } finally {
                if (result == null) {
                    JdbcUtil.releaseConnection(conn, ds);
                }
            }

            return result;
        }
    }

    /**
     * Prepares a callable SQL query (stored procedure) using the provided Connection.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // The caller owns the connection and MUST close it.
     * Connection conn = dataSource.getConnection();
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(conn, "{call get_user_info(?, ?, ?)}")) {
     *     Jdbc.OutParamResult outResult = query.setLong(1, userId)
     *         .registerOutParameter(2, Types.VARCHAR)
     *         .registerOutParameter(3, Types.DATE)
     *         .executeAndGetOutParameters();
     *
     *     String name = outResult.getOutParamValue(2);
     *     Date createdDate = outResult.getOutParamValue(3);
     * } finally {
     *     conn.close();   // this method does not close the connection for you
     * }
     *
     * // A null connection or empty SQL is rejected.
     * JdbcUtil.prepareCallableQuery((Connection) null, "{call foo()}");   // throws IllegalArgumentException
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL string for the stored procedure call.
     * @return A new {@link CallableQuery} instance.
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty.
     * @throws SQLException if a database access error occurs.
     */
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        return new CallableQuery(prepareCallable(conn, sql));
    }

    /**
     * Prepares a callable SQL query using a custom statement creator with the provided Connection.
     *
     * <p>The supplied connection remains open. Its lifecycle stays with the caller or transaction manager,
     * which must close or release it when the owning scope completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom statement creator: configure a query timeout on the CallableStatement.
     * Connection conn = dataSource.getConnection();
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(conn, "{call get_users(?, ?)}",
     *         (c, sql) -> {
     *             CallableStatement stmt = c.prepareCall(sql);
     *             stmt.setQueryTimeout(30);   // 30 seconds
     *             return stmt;
     *         })) {
     *     query.setInt(1, departmentId)
     *          .registerOutParameter(2, Types.INTEGER)
     *          .execute();
     * } finally {
     *     conn.close();   // this method does not close the connection for you
     * }
     *
     * // A null stmtCreator (or conn, or empty sql) is rejected.
     * JdbcUtil.prepareCallableQuery(conn, "{call foo()}", null);   // throws IllegalArgumentException
     * }</pre>
     *
     * @param conn The database {@link Connection} to use. It will not be closed by this method.
     * @param sql The SQL string for the stored procedure call.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link CallableStatement}.
     * @return A new {@link CallableQuery} instance wrapping the custom-created statement.
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty, or if {@code stmtCreator} is {@code null}.
     * @throws SQLException if a database access error occurs.
     */
    public static CallableQuery prepareCallableQuery(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        return new CallableQuery(prepareCallable(conn, sql, stmtCreator));
    }

    /**
     * Creates a {@link PreparedStatement} for the given SQL, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL statement to prepare.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final String sql) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql);
    }

    /**
     * Creates a {@link PreparedStatement} for the given SQL, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL statement to prepare.
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final String sql, final boolean autoGeneratedKeys) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    /**
     * Creates a {@link PreparedStatement} for the given SQL, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL statement to prepare.
     * @param generatedKeyColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int[] generatedKeyColumnIndexes) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, generatedKeyColumnIndexes);
    }

    /**
     * Creates a {@link PreparedStatement} for the given SQL, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL statement to prepare.
     * @param generatedKeyColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final String sql, final String[] generatedKeyColumnNames) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, generatedKeyColumnNames);
    }

    /**
     * Creates a {@link PreparedStatement} for the given SQL with the specified result set type and concurrency, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL statement to prepare.
     * @param resultSetType A result set type; one of {@code ResultSet.TYPE_FORWARD_ONLY}, {@code ResultSet.TYPE_SCROLL_INSENSITIVE}, or {@code ResultSet.TYPE_SCROLL_SENSITIVE}.
     * @param resultSetConcurrency A concurrency type; one of {@code ResultSet.CONCUR_READ_ONLY} or {@code ResultSet.CONCUR_UPDATABLE}.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    /**
     * Creates a {@link PreparedStatement} for the given SQL with the specified result set type, concurrency, and holdability, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL statement to prepare.
     * @param resultSetType A result set type; one of {@code ResultSet.TYPE_FORWARD_ONLY}, {@code ResultSet.TYPE_SCROLL_INSENSITIVE}, or {@code ResultSet.TYPE_SCROLL_SENSITIVE}.
     * @param resultSetConcurrency A concurrency type; one of {@code ResultSet.CONCUR_READ_ONLY} or {@code ResultSet.CONCUR_UPDATABLE}.
     * @param resultSetHoldability A holdability type; one of {@code ResultSet.HOLD_CURSORS_OVER_COMMIT} or {@code ResultSet.CLOSE_CURSORS_AT_COMMIT}.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final String sql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    /**
     * Creates a {@link PreparedStatement} for the given SQL using the specified statement creator, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL statement to prepare.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(sql);

        return stmtCreator.apply(conn, sql);
    }

    /**
     * Creates a {@link PreparedStatement} for the given parsed SQL, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return conn.prepareStatement(parsedSql.parameterizedSql());
    }

    /**
     * Creates a {@link PreparedStatement} for the given parsed SQL, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @param autoGeneratedKeys A boolean flag; if {@code true}, the driver will be instructed to make generated keys available.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final boolean autoGeneratedKeys) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return conn.prepareStatement(parsedSql.parameterizedSql(), autoGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
    }

    /**
     * Creates a {@link PreparedStatement} for the given parsed SQL, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @param generatedKeyColumnIndexes An array of column indexes that should be made available for retrieval.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int[] generatedKeyColumnIndexes) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return conn.prepareStatement(parsedSql.parameterizedSql(), generatedKeyColumnIndexes);
    }

    /**
     * Creates a {@link PreparedStatement} for the given parsed SQL, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @param generatedKeyColumnNames An array of column names that should be made available for retrieval.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final String[] generatedKeyColumnNames) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return conn.prepareStatement(parsedSql.parameterizedSql(), generatedKeyColumnNames);
    }

    /**
     * Creates a {@link PreparedStatement} for the given parsed SQL with the specified result set type and concurrency, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @param resultSetType A result set type; one of {@code ResultSet.TYPE_FORWARD_ONLY}, {@code ResultSet.TYPE_SCROLL_INSENSITIVE}, or {@code ResultSet.TYPE_SCROLL_SENSITIVE}.
     * @param resultSetConcurrency A concurrency type; one of {@code ResultSet.CONCUR_READ_ONLY} or {@code ResultSet.CONCUR_UPDATABLE}.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency)
            throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return conn.prepareStatement(parsedSql.parameterizedSql(), resultSetType, resultSetConcurrency);
    }

    /**
     * Creates a {@link PreparedStatement} for the given parsed SQL with the specified result set type, concurrency, and holdability, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @param resultSetType A result set type; one of {@code ResultSet.TYPE_FORWARD_ONLY}, {@code ResultSet.TYPE_SCROLL_INSENSITIVE}, or {@code ResultSet.TYPE_SCROLL_SENSITIVE}.
     * @param resultSetConcurrency A concurrency type; one of {@code ResultSet.CONCUR_READ_ONLY} or {@code ResultSet.CONCUR_UPDATABLE}.
     * @param resultSetHoldability A holdability type; one of {@code ResultSet.HOLD_CURSORS_OVER_COMMIT} or {@code ResultSet.CLOSE_CURSORS_AT_COMMIT}.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql, final int resultSetType, final int resultSetConcurrency,
            final int resultSetHoldability) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return conn.prepareStatement(parsedSql.parameterizedSql(), resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    /**
     * Creates a {@link PreparedStatement} for the given parsed SQL using the specified statement creator, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link PreparedStatement}.
     * @return A new {@link PreparedStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static PreparedStatement prepareStatement(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return stmtCreator.apply(conn, parsedSql.parameterizedSql());
    }

    /**
     * Creates a {@link CallableStatement} for the given SQL, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL call statement to prepare.
     * @return A new {@link CallableStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static CallableStatement prepareCallable(final Connection conn, final String sql) throws SQLException {
        JdbcUtil.logSql(sql);

        return conn.prepareCall(sql);
    }

    /**
     * Creates a {@link CallableStatement} for the given SQL using the specified statement creator, logging the SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param sql The SQL call statement to prepare.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link CallableStatement}.
     * @return A new {@link CallableStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static CallableStatement prepareCallable(final Connection conn, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(sql);

        return stmtCreator.apply(conn, sql);
    }

    /**
     * Creates a {@link CallableStatement} for the given parsed SQL, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @return A new {@link CallableStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return conn.prepareCall(parsedSql.parameterizedSql());
    }

    /**
     * Creates a {@link CallableStatement} for the given parsed SQL using the specified statement creator, logging the original SQL first.
     *
     * @param conn The database {@link Connection} to use.
     * @param parsedSql The parsed SQL to prepare.
     * @param stmtCreator A function that takes a {@link Connection} and a SQL string and returns a new {@link CallableStatement}.
     * @return A new {@link CallableStatement}.
     * @throws SQLException if a database access error occurs.
     */
    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return stmtCreator.apply(conn, parsedSql.parameterizedSql());
    }

    /**
     * Prepares a PreparedStatement for the given SQL query and sets the provided parameters.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn The database connection to use.
     * @param sql The SQL statement, which may contain positional or named parameters.
     * @param parameters The parameter values to set on the prepared statement.
     * @return A PreparedStatement with parameters set, ready for execution.
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs or the SQL is invalid.
     */
    static PreparedStatement prepareStmt(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        try {
            // Always validate the parameter count. Skipping this call for a null/empty array
            // lets SQL containing placeholders reach the driver with unbound parameters,
            // even though setParameters has a deterministic validation error for that case.
            setParameters(parsedSql, stmt, parameters);
        } catch (final Exception e) {
            closeQuietly(stmt);
            throw e;
        }

        return stmt;
    }

    /**
     * Prepares a CallableStatement for executing stored procedures or functions with the given SQL and parameters.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn The database connection to use.
     * @param sql The SQL call statement, which may contain positional or named parameters.
     * @param parameters The parameter values to set on the callable statement.
     * @return A CallableStatement with parameters set, ready for execution.
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs or the SQL is invalid.
     */
    static CallableStatement prepareCall(final Connection conn, final String sql, final Object... parameters) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        // Callable placeholders may be OUT-only and therefore have no input value to bind.
        // Parameter direction is supplied later through registerOutParameter(), so an empty
        // array cannot be treated as a missing-input error here.
        if (N.notEmpty(parameters)) {
            try {
                setParameters(parsedSql, stmt, parameters);
            } catch (final Exception e) {
                closeQuietly(stmt);
                throw e;
            }
        }

        return stmt;
    }

    /**
     * Prepares a PreparedStatement for batch execution with multiple sets of parameters.
     * Each element in the parameters list represents one batch of parameters to be added to the statement.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn The database connection to use.
     * @param sql The SQL statement, which may contain positional or named parameters.
     * @param parametersList A list where each element contains parameter values for one batch operation.
     * @return A PreparedStatement with all batches added, ready for batch execution via executeBatch().
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs or the SQL is invalid.
     */
    static PreparedStatement prepareBatchStmt(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        try {
            for (final Object parameters : parametersList) {
                setParameters(parsedSql, stmt, N.asArray(parameters));
                stmt.addBatch();
            }
        } catch (final Exception e) {
            closeQuietly(stmt);
            throw e;
        }

        return stmt;
    }

    /**
     * Prepares a CallableStatement for batch execution of stored procedures or functions with multiple sets of parameters.
     * Each element in the parameters list represents one batch of parameters to be added to the statement.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     *
     * @param conn The database connection to use.
     * @param sql The SQL call statement, which may contain positional or named parameters.
     * @param parametersList A list where each element contains parameter values for one batch operation.
     * @return A CallableStatement with all batches added, ready for batch execution via executeBatch().
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs or the SQL is invalid.
     */
    static CallableStatement prepareBatchCall(final Connection conn, final String sql, final List<?> parametersList) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        try {
            for (final Object parameters : parametersList) {
                setParameters(parsedSql, stmt, N.asArray(parameters));
                stmt.addBatch();
            }
        } catch (final Exception e) {
            closeQuietly(stmt);
            throw e;
        }

        return stmt;
    }

    /**
     * Parses a named SQL statement into a ParsedSql object and validates that all parameters are named.
     * Named SQL uses parameters in the format :paramName or #{paramName}.
     *
     * @param namedSql the SQL statement containing named parameters
     * @return a ParsedSql object representing the parsed and validated named SQL
     * @throws IllegalArgumentException if the SQL is not a valid named SQL (contains positional parameters)
     */
    private static ParsedSql parseNamedSql(final String namedSql) {
        N.checkArgNotEmpty(namedSql, cs.namedSql);

        final ParsedSql parsedSql = ParsedSql.parse(namedSql);

        validateNamedSql(parsedSql);

        return parsedSql;
    }

    /**
     * Validates that every parameter of the parsed SQL is named.
     *
     * @param namedSql The parsed SQL to validate.
     * @throws IllegalArgumentException if the SQL contains positional or otherwise unnamed parameters.
     */
    private static void validateNamedSql(final ParsedSql namedSql) {
        if (namedSql.namedParameters().size() != namedSql.parameterCount()) {
            throw new IllegalArgumentException("Named SQL contains positional or otherwise unnamed parameters");
        }
    }

    /**
     * Returns the transaction active on the current thread for the given {@link javax.sql.DataSource} and
     * eligible for the given SQL statement, or {@code null} if there is none (or the transaction is for
     * update operations only and the statement is a {@code SELECT}).
     *
     * @param ds The {@link javax.sql.DataSource} whose transaction to look up.
     * @param sql The SQL statement to be executed within the transaction.
     * @param createdBy The component that would have created the transaction.
     * @return The active transaction, or {@code null} if none applies.
     */
    static SqlTransaction getTransaction(final javax.sql.DataSource ds, final String sql, final CreatedBy createdBy) {
        final SqlOperation sqlOperation = JdbcUtil.getSqlOperation(sql);
        final SqlTransaction tran = SqlTransaction.getTransaction(ds, createdBy);

        if (tran == null || (tran.isForUpdateOnly() && sqlOperation == SqlOperation.SELECT)) {
            return null;
        } else {
            return tran;
        }
    }

    /**
     * Executes a SQL query immediately with the provided parameters and returns all results as a {@link Dataset}.
     * This is a convenience method for one-time query execution where the result set is loaded entirely into memory.
     * For reusable queries or streaming large result sets, consider using {@link #prepareQuery(javax.sql.DataSource, String)} instead.
     *
     * <p>If a transaction is active in the current thread (started via {@link #beginTransaction(javax.sql.DataSource)}
     * or Spring's transactional support), the transaction's Connection will be used. Otherwise, a new Connection is
     * obtained from the DataSource and automatically released after query execution.</p>
     *
     * <p><b>Key Differences from prepareQuery():</b></p>
     * <ul>
     *   <li><b>executeQuery():</b> One-time execution, loads all results into memory, closes resources immediately</li>
     *   <li><b>prepareQuery():</b> Configurable and optionally reusable; terminal operations close it by default,
     *       returned streams must be closed, and callers that disable auto-close must close the query</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Simple query with parameters
     * Dataset result = JdbcUtil.executeQuery(dataSource,
     *     "SELECT * FROM users WHERE age > ? AND city = ?",
     *     18, "New York");
     *
     * for (int i = 0; i < result.size(); i++) {
     *     System.out.println(result.get(i, result.getColumnIndex("name")) + " - "
     *             + result.get(i, result.getColumnIndex("email")));
     * }
     *
     * // Query returning single value
     * Dataset countResult = JdbcUtil.executeQuery(dataSource,
     *     "SELECT COUNT(*) as total FROM orders WHERE status = ?",
     *     "PENDING");
     * long totalOrders = ((Number) countResult.get(0, 0)).longValue();
     *
     * // Query with IN clause using multiple parameters
     * Dataset products = JdbcUtil.executeQuery(dataSource,
     *     "SELECT * FROM products WHERE category IN (?, ?, ?)",
     *     "Electronics", "Books", "Clothing");
     *
     * // Iterate through results
     * for (int i = 0; i < products.size(); i++) {
     *     System.out.println(products.get(i, products.getColumnIndex("product_name")));
     * }
     *
     * // Query with no parameters
     * Dataset allUsers = JdbcUtil.executeQuery(dataSource,
     *     "SELECT id, name, email FROM users ORDER BY created_at DESC");
     *
     * // Working with column names
     * List<String> columnNames = allUsers.columnNames();
     * columnNames.forEach(System.out::println);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to obtain a connection from, must not be {@code null}.
     * @param sql The SQL SELECT statement to execute with optional {@code ?} parameter placeholders, must not be {@code null} or empty.
     * @param parameters Variable number of parameters to bind to the SQL statement, matching the {@code ?} placeholders in order.
     *                   Can be empty if the SQL has no parameters. Supports primitive types, Strings, Dates, and other JDBC-compatible types.
     * @return A {@link Dataset} object containing all query results loaded into memory with row and column access methods.
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs, the SQL is invalid, or parameter types are incompatible.
     * @see PreparedStatement#executeQuery()
     * @see #prepareQuery(javax.sql.DataSource, String)
     * @see Dataset
     */
    public static Dataset executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeQuery(tran.connection(), sql, parameters);
        } else {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeQuery(conn, sql, parameters);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        }
    }

    /**
     * Executes a SQL SELECT statement on the supplied {@link Connection} and returns all rows as a
     * {@link Dataset}.
     *
     * <p>The supplied connection remains open; its caller or transaction manager owns its lifecycle. A {@link PreparedStatement} is created, parameters are bound, the query is
     * executed, the {@link ResultSet} is fully consumed into a {@link Dataset}, and both the statement
     * and result set are closed before this method returns.</p>
     *
     * <p>The SQL string may use either positional ({@code ?}) or named ({@code :paramName})
     * parameter placeholders. When named parameters are used, a single entity, {@link Map}, or
     * {@link EntityId} may be passed in {@code parameters} to bind them by name.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * try {
     *     Dataset result = JdbcUtil.executeQuery(conn,
     *         "SELECT * FROM products WHERE price BETWEEN ? AND ?",
     *         10.0, 100.0);
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to use for the query; must not be {@code null} and not closed
     *             by this method.
     * @param sql The SQL SELECT statement to execute; must not be {@code null} or empty.
     * @param parameters Optional parameters bound to {@code ?} placeholders (or named parameters) in
     *                   the SQL; may be empty if the SQL has no parameters.
     * @return A {@link Dataset} containing all rows of the result set.
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs while executing the query.
     * @see PreparedStatement#executeQuery()
     * @see #executeQuery(javax.sql.DataSource, String, Object...)
     */
    public static Dataset executeQuery(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
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

    /**
     * Executes a SQL data modification statement (INSERT, UPDATE, DELETE) or DDL statement immediately
     * with the provided parameters and returns the number of affected rows.
     * This is a convenience method for one-time update execution. For reusable update statements,
     * consider using {@link #prepareQuery(javax.sql.DataSource, String)} instead.
     *
     * <p>If a transaction is active in the current thread (started via {@link #beginTransaction(javax.sql.DataSource)}
     * or Spring's transactional support), the transaction's Connection will be used. Otherwise, a new Connection is
     * obtained from the DataSource and automatically released after update execution.</p>
     *
     * <p><b>Supported SQL Operations:</b></p>
     * <ul>
     *   <li><b>INSERT:</b> Adds new rows to a table</li>
     *   <li><b>UPDATE:</b> Modifies existing rows based on WHERE conditions</li>
     *   <li><b>DELETE:</b> Removes rows based on WHERE conditions</li>
     *   <li><b>DDL:</b> Data Definition Language statements (CREATE, ALTER, DROP, etc.)</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // UPDATE: Modify existing records
     * int rowsUpdated = JdbcUtil.executeUpdate(dataSource,
     *     "UPDATE users SET status = ? WHERE last_login < ?",
     *     "INACTIVE", thirtyDaysAgo);
     * System.out.println("Deactivated " + rowsUpdated + " users");
     *
     * // INSERT: Add new record
     * int rowsInserted = JdbcUtil.executeUpdate(dataSource,
     *     "INSERT INTO audit_log (user_id, action, timestamp) VALUES (?, ?, ?)",
     *     userId, "LOGIN", new Timestamp(System.currentTimeMillis()));
     *
     * // DELETE: Remove records
     * int rowsDeleted = JdbcUtil.executeUpdate(dataSource,
     *     "DELETE FROM sessions WHERE expired_at < ?",
     *     new Date(System.currentTimeMillis()));
     *
     * // UPDATE with multiple conditions
     * int updated = JdbcUtil.executeUpdate(dataSource,
     *     "UPDATE products SET price = price * ? WHERE category = ? AND stock > ?",
     *     0.9, "Electronics", 100);
     *
     * // INSERT with multiple values
     * int inserted = JdbcUtil.executeUpdate(dataSource,
     *     "INSERT INTO user_preferences (user_id, theme, language, notifications) VALUES (?, ?, ?, ?)",
     *     userId, "DARK", "en_US", true);
     *
     * // DELETE all records (use with caution!)
     * int allDeleted = JdbcUtil.executeUpdate(dataSource,
     *     "DELETE FROM temp_data");   // No parameters needed
     *
     * // Conditional update
     * if (needsUpdate) {
     *     int count = JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ? AND quantity >= ?",
     *         orderQuantity, productId, orderQuantity);
     *
     *     if (count == 0) {
     *         throw new InsufficientInventoryException("Not enough stock");
     *     }
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to obtain a connection from, must not be {@code null}.
     * @param sql The SQL INSERT, UPDATE, DELETE, or DDL statement with optional {@code ?} parameter placeholders,
     *            must not be {@code null} or empty.
     * @param parameters Variable number of parameters to bind to the SQL statement, matching the {@code ?} placeholders in order.
     *                   Can be empty if the SQL has no parameters. Supports primitive types, Strings, Dates, and other JDBC-compatible types.
     * @return The number of rows affected by the statement. Returns 0 for DDL statements or when no rows match the WHERE clause.
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs, the SQL is invalid, or parameter types are incompatible.
     * @see PreparedStatement#executeUpdate()
     * @see #prepareQuery(javax.sql.DataSource, String)
     */
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeUpdate(tran.connection(), sql, parameters);
        } else {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeUpdate(conn, sql, parameters);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        }
    }

    /**
     * Executes a SQL update (INSERT, UPDATE, DELETE, or DDL) on the supplied {@link Connection} and
     * returns the number of affected rows.
     *
     * <p>The supplied connection remains open; its caller or transaction manager owns its lifecycle. The internally created {@link PreparedStatement} is always closed before
     * this method returns.</p>
     *
     * <p>The SQL string may use either positional ({@code ?}) or named ({@code :paramName})
     * parameter placeholders.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * try {
     *     int rowsDeleted = JdbcUtil.executeUpdate(conn,
     *         "DELETE FROM orders WHERE order_date < ? AND status = ?",
     *         cutoffDate, "CANCELLED");
     * } finally {
     *     conn.close();
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to use for the update; must not be {@code null} and not closed
     *             by this method.
     * @param sql The SQL INSERT/UPDATE/DELETE/DDL statement; must not be {@code null} or empty.
     * @param parameters Optional parameters bound to {@code ?} placeholders (or named parameters) in
     *                   the SQL; may be empty if the SQL has no parameters.
     * @return The number of rows affected by the update (0 for DDL or when no rows match).
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs while executing the update.
     * @see PreparedStatement#executeUpdate()
     * @see #executeUpdate(javax.sql.DataSource, String, Object...)
     */
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
     * Executes a batch SQL update using the provided DataSource with default batch size.
     * The default batch size is {@link #DEFAULT_BATCH_SIZE}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Object[]> batchParams = Arrays.asList(
     *     new Object[] {"John", 25},
     *     new Object[] {"Jane", 30},
     *     new Object[] {"Bob", 35}
     * );
     * int totalRows = JdbcUtil.executeBatchUpdate(dataSource,
     *     "INSERT INTO users (name, age) VALUES (?, ?)",
     *     batchParams);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to use for the batch update.
     * @param sql The SQL statement to execute.
     * @param listOfParameters A list of parameter sets for the batch update.
     * @return The total number of rows affected by the batch update across all batches.
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws SQLException if a database access error occurs while executing the batch update.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a batch SQL update using the provided DataSource with specified batch size.
     * Large lists will be automatically split into smaller batches for optimal performance.
     * When the number of parameter sets exceeds the batch size, a transaction is automatically
     * started to ensure atomicity.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Insert 10,000 records in batches of 500
     * List<Object[]> largeDataset = new ArrayList<>();
     * for (int i = 0; i < 10000; i++) {
     *     largeDataset.add(new Object[] {"User" + i, i % 100});
     * }
     * int totalRows = JdbcUtil.executeBatchUpdate(dataSource,
     *     "INSERT INTO users (name, age) VALUES (?, ?)",
     *     largeDataset,
     *     500);   // Process in batches of 500
     *
     * // Update records with custom batch size for better memory management
     * List<Object[]> updates = Arrays.asList(
     *     new Object[] {"active", 1},
     *     new Object[] {"active", 2},
     *     new Object[] {"inactive", 3}
     * );
     * int updated = JdbcUtil.executeBatchUpdate(dataSource,
     *     "UPDATE users SET status = ? WHERE id = ?",
     *     updates,
     *     100);
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to use for the batch update, must not be {@code null}.
     * @param sql The SQL statement to execute, must not be {@code null} or empty.
     * @param listOfParameters A list of parameter sets for the batch update. Each element should be
     *                        an Object array or a compatible collection representing one set of parameters.
     * @param batchSize The size of each batch, must be positive. Smaller batches use less memory
     *                  but may be slower; larger batches are faster but use more memory.
     * @return The total number of rows affected by the batch update across all batches.
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty, or if {@code batchSize} is not positive,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws SQLException if a database access error occurs while executing the batch update.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     * @see #executeBatchUpdate(javax.sql.DataSource, String, List)
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        } else {
            final SqlTransaction tran2 = JdbcUtil.beginTransaction(ds);
            int ret = 0;
            Throwable failure = null;

            try {
                ret = executeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                rollbackAfterTransactionCommand(tran2, failure);
            }

            return ret;
        }
    }

    /**
     * Executes a batch SQL update on the supplied {@link Connection} using the default batch size
     * ({@link #DEFAULT_BATCH_SIZE}).
     *
     * <p>This method does not close {@code conn} — the caller is responsible. See
     * {@link #executeBatchUpdate(Connection, String, List, int)} for the auto-commit / atomicity
     * semantics that apply when {@code listOfParameters.size() > 1}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Each element of the outer list supplies one row's parameters.
     * List<List<?>> rows = Arrays.asList(
     *         Arrays.asList("John", 25),
     *         Arrays.asList("Jane", 30));
     * try (Connection conn = dataSource.getConnection()) {
     *     int total = JdbcUtil.executeBatchUpdate(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", rows);   // returns 2
     *
     *     // An empty list is a no-op.
     *     int zero = JdbcUtil.executeBatchUpdate(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", Collections.emptyList()); // returns 0
     * }                                                                                    // caller closes conn; this method does not
     * }</pre>
     *
     * @param conn The {@link Connection} to use; must not be {@code null} and not closed by this method.
     * @param sql The SQL statement to execute; must not be {@code null} or empty.
     * @param listOfParameters A list of parameter sets for the batch update; may be empty (no-op returning {@code 0}).
     * @return The total number of rows affected by the batch update across all batches
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs while executing the batch.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     * @see #executeBatchUpdate(Connection, String, List, int)
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a batch SQL update using the provided Connection with specified batch size.
     * This method does not close the provided Connection after the batch update is executed.
     *
     * <p>If the connection is in auto-commit mode and more than one parameter set is supplied,
     * auto-commit is temporarily disabled so that all batches execute as a single transaction. The original
     * auto-commit setting is restored after the changes are committed (on success) or rolled back (on failure).
     * If rollback also fails, the connection remains in manual-commit mode so restoring auto-commit cannot
     * accidentally commit pending changes; the caller must recover or discard the connection.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Insert 10,000 rows in batches of 500, all within one transaction.
     * List<List<?>> data = new ArrayList<>();
     * for (int i = 0; i < 10_000; i++) {
     *     data.add(Arrays.asList("User" + i, i % 100));
     * }
     * final String sql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * try (Connection conn = dataSource.getConnection()) {
     *     int total = JdbcUtil.executeBatchUpdate(conn, sql, data, 500);   // returns 10000
     *
     *     // An empty list is a no-op; a non-positive batchSize is rejected.
     *     int zero = JdbcUtil.executeBatchUpdate(conn, sql, Collections.emptyList(), 500);   // returns 0
     *     JdbcUtil.executeBatchUpdate(conn, sql, data, 0);   // throws IllegalArgumentException
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for the batch update. It will not be closed by this method.
     * @param sql The SQL statement to execute.
     * @param listOfParameters A list of parameter sets for the batch update.
     * @param batchSize The size of each batch, must be positive.
     * @return The total number of rows affected by the batch update across all batches.
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty, or if {@code batchSize} is not positive,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs while executing the batch update.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;
        boolean autoCommitDisabled = false;
        Throwable primaryFailure = null;

        try {
            if (originalAutoCommit && listOfParameters.size() > 1) {
                conn.setAutoCommit(false);
                autoCommitDisabled = true;
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
                    res = addUpdatedRowsExact(res, sumUpdatedRows(executeBatch(stmt)));
                }
            }

            if (idx % batchSize != 0) {
                res = addUpdatedRowsExact(res, sumUpdatedRows(executeBatch(stmt)));
            }

            noException = true;

            return res;
        } catch (final SQLException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            if (autoCommitDisabled) {
                Throwable completionFailure = primaryFailure;
                boolean transactionCompleted = false;

                try {
                    if (noException) {
                        try {
                            conn.commit();
                            transactionCompleted = true;
                        } catch (final SQLException | RuntimeException | Error commitFailure) {
                            completionFailure = commitFailure;
                            // A failed COMMIT leaves the transaction outcome uncertain. Make the same
                            // best-effort rollback used by SqlTransaction and keep COMMIT as the primary
                            // failure if rollback also fails.
                            try {
                                conn.rollback();
                                transactionCompleted = true;
                            } catch (final SQLException | RuntimeException | Error rollbackFailure) {
                                addSuppressedIfDistinct(commitFailure, rollbackFailure);
                            }

                            throw commitFailure;
                        }
                    } else {
                        try {
                            conn.rollback();
                            transactionCompleted = true;
                        } catch (final SQLException | RuntimeException | Error e) {
                            // Don't mask the batch failure that is already propagating.
                            addSuppressedIfDistinct(primaryFailure, e);
                            logger.warn("Failed to roll back after batch failure", e);
                        }
                    }
                } finally {
                    try {
                        // Enabling auto-commit can commit pending work after a failed rollback.
                        if (transactionCompleted) {
                            conn.setAutoCommit(true);
                        }
                    } catch (final SQLException | RuntimeException | Error e) {
                        // A successful batch/commit must not be reported as successful while leaving the
                        // caller-owned connection in manual-commit mode. Preserve an earlier failure when
                        // there is one; otherwise make the restoration failure visible to the caller.
                        if (completionFailure != null) {
                            addSuppressedIfDistinct(completionFailure, e);
                        } else {
                            throw e;
                        }

                        logger.warn("Failed to restore auto-commit mode after batch execution", e);
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
     * Executes a large batch SQL update using the supplied DataSource and the default batch size
     * ({@link #DEFAULT_BATCH_SIZE}).
     *
     * <p>This method delegates to {@link PreparedStatement#executeLargeBatch()} and returns a
     * {@code long} sum of affected rows, suitable for updates whose total row counts may exceed
     * {@link Integer#MAX_VALUE}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<List<?>> rows = Arrays.asList(
     *         Arrays.asList("John", 25),
     *         Arrays.asList("Jane", 30));
     * long total = JdbcUtil.executeLargeBatchUpdate(dataSource,
     *         "INSERT INTO users (name, age) VALUES (?, ?)", rows);   // returns 2L
     *
     * // An empty list is a no-op.
     * long zero = JdbcUtil.executeLargeBatchUpdate(dataSource,
     *         "INSERT INTO users (name, age) VALUES (?, ?)", Collections.emptyList());   // returns 0L
     * }</pre>
     *
     * @param ds the {@link javax.sql.DataSource} to use for the batch update; must not be {@code null}.
     * @param sql the SQL statement to execute; must not be {@code null} or empty.
     * @param listOfParameters a list of parameter sets; each element supplies one set of parameter
     *                         values for one batch entry; may be empty (no-op returning {@code 0}).
     * @return the total number of rows affected by the batch update across all batches
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code ds} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws SQLException if a database access error occurs while executing the batch.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     * @see #executeLargeBatchUpdate(javax.sql.DataSource, String, List, int)
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeLargeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a large batch SQL update using the provided DataSource with specified batch size.
     * This method returns a {@code long} value to support updates affecting more than {@link Integer#MAX_VALUE} rows.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Suitable when the total affected-row count may exceed Integer.MAX_VALUE.
     * List<List<?>> data = new ArrayList<>();
     * for (int i = 0; i < 1_000_000; i++) {
     *     data.add(Arrays.asList("User" + i, i % 100));
     * }
     * final String sql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * // When data.size() > batchSize, a transaction is started automatically for atomicity.
     * long total = JdbcUtil.executeLargeBatchUpdate(dataSource, sql, data, 1000);
     *
     * // An empty list returns 0L; a non-positive batchSize is rejected.
     * long zero = JdbcUtil.executeLargeBatchUpdate(dataSource, sql, Collections.emptyList(), 1000);   // returns 0L
     * JdbcUtil.executeLargeBatchUpdate(dataSource, sql, data, 0);   // throws IllegalArgumentException
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to use for the batch update.
     * @param sql The SQL statement to execute.
     * @param listOfParameters A list of parameter sets for the batch update.
     * @param batchSize The size of each batch, must be positive.
     * @return The total number of rows affected by the batch update across all batches, as a long value
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty, or if {@code batchSize} is not positive,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws SQLException if a database access error occurs while executing the batch update.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return executeLargeBatchUpdate(tran.connection(), sql, listOfParameters, batchSize);
        } else if (listOfParameters.size() <= batchSize) {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return executeLargeBatchUpdate(conn, sql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        } else {
            final SqlTransaction tran2 = JdbcUtil.beginTransaction(ds);
            long ret = 0;
            Throwable failure = null;

            try {
                ret = executeLargeBatchUpdate(tran2.connection(), sql, listOfParameters, batchSize);
                tran2.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                rollbackAfterTransactionCommand(tran2, failure);
            }

            return ret;
        }
    }

    /**
     * Executes a large batch SQL update using the provided Connection with default batch size.
     * This method does not close the provided Connection after the batch update is executed.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<List<?>> rows = Arrays.asList(
     *         Arrays.asList("John", 25),
     *         Arrays.asList("Jane", 30));
     * try (Connection conn = dataSource.getConnection()) {
     *     long total = JdbcUtil.executeLargeBatchUpdate(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", rows);   // returns 2L
     *
     *     long zero = JdbcUtil.executeLargeBatchUpdate(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", Collections.emptyList()); // returns 0L
     * }                                                                                    // caller closes conn; this method does not
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for the batch update. It will not be closed by this method.
     * @param sql The SQL statement to execute.
     * @param listOfParameters A list of parameter sets for the batch update.
     * @return The total number of rows affected by the batch update across all batches, as a long value
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs while executing the batch update.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException {
        return executeLargeBatchUpdate(conn, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a large batch SQL update using the provided Connection with specified batch size.
     * This method does not close the provided Connection after the batch update is executed.
     *
     * <p>If the connection is in auto-commit mode and more than one parameter set is supplied,
     * auto-commit is temporarily disabled so that all batches execute as a single transaction. The original
     * auto-commit setting is restored after the changes are committed (on success) or rolled back (on failure).
     * If rollback also fails, the connection remains in manual-commit mode so restoring auto-commit cannot
     * accidentally commit pending changes; the caller must recover or discard the connection.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<List<?>> rows = Arrays.asList(
     *         Arrays.asList("c", 1), Arrays.asList("d", 2), Arrays.asList("e", 3));
     * try (Connection conn = dataSource.getConnection()) {
     *     // If conn is in auto-commit mode and more than one row is supplied,
     *     // all batches run as a single transaction.
     *     long total = JdbcUtil.executeLargeBatchUpdate(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", rows, 2);   // returns 3L
     *
     *     long zero = JdbcUtil.executeLargeBatchUpdate(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", Collections.emptyList(), 2);   // returns 0L
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for the batch update. It will not be closed by this method.
     * @param sql The SQL statement to execute.
     * @param listOfParameters A list of parameter sets for the batch update.
     * @param batchSize The size of each batch, must be positive.
     * @return The total number of rows affected by the batch update across all batches, as a long value
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty, or if {@code batchSize} is not positive,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs while executing the batch update.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final boolean originalAutoCommit = conn.getAutoCommit();
        PreparedStatement stmt = null;
        boolean noException = false;
        boolean autoCommitDisabled = false;
        Throwable primaryFailure = null;

        try {
            if (originalAutoCommit && listOfParameters.size() > 1) {
                conn.setAutoCommit(false);
                autoCommitDisabled = true;
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
                    res = addUpdatedRowsExact(res, sumUpdatedRows(executeLargeBatch(stmt)));
                }
            }

            if (idx % batchSize != 0) {
                res = addUpdatedRowsExact(res, sumUpdatedRows(executeLargeBatch(stmt)));
            }

            noException = true;

            return res;
        } catch (final SQLException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            if (autoCommitDisabled) {
                Throwable completionFailure = primaryFailure;
                boolean transactionCompleted = false;

                try {
                    if (noException) {
                        try {
                            conn.commit();
                            transactionCompleted = true;
                        } catch (final SQLException | RuntimeException | Error commitFailure) {
                            completionFailure = commitFailure;
                            try {
                                conn.rollback();
                                transactionCompleted = true;
                            } catch (final SQLException | RuntimeException | Error rollbackFailure) {
                                addSuppressedIfDistinct(commitFailure, rollbackFailure);
                            }

                            throw commitFailure;
                        }
                    } else {
                        try {
                            conn.rollback();
                            transactionCompleted = true;
                        } catch (final SQLException | RuntimeException | Error e) {
                            // Don't mask the batch failure that is already propagating.
                            addSuppressedIfDistinct(primaryFailure, e);
                            logger.warn("Failed to roll back after batch failure", e);
                        }
                    }
                } finally {
                    try {
                        // Enabling auto-commit can commit pending work after a failed rollback.
                        if (transactionCompleted) {
                            conn.setAutoCommit(true);
                        }
                    } catch (final SQLException | RuntimeException | Error e) {
                        // A successful batch/commit must not be reported as successful while leaving the
                        // caller-owned connection in manual-commit mode. Preserve an earlier failure when
                        // there is one; otherwise make the restoration failure visible to the caller.
                        if (completionFailure != null) {
                            addSuppressedIfDistinct(completionFailure, e);
                        } else {
                            throw e;
                        }

                        logger.warn("Failed to restore auto-commit mode after batch execution", e);
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
     * Executes a SQL statement of any kind using a connection obtained from the supplied DataSource,
     * returning the raw JDBC "first result" indicator of {@link PreparedStatement#execute()}.
     *
     * <p><b>WARNING — the returned {@code boolean} is NOT a success/failure flag.</b> Despite sitting next
     * to {@link #executeUpdate(javax.sql.DataSource, String, Object...) executeUpdate} (which returns an
     * affected-row count) and {@link #executeQuery(javax.sql.DataSource, String, Object...) executeQuery}
     * (which returns a {@link Dataset} of rows), this method's {@code boolean} carries the exact semantics
     * of {@link java.sql.PreparedStatement#execute()}: it reports the <i>shape of the statement's first result</i>,
     * not whether the statement "worked". A statement that completes without throwing has already
     * succeeded — failure is reported by a thrown {@link SQLException}, <b>never</b> by a {@code false}
     * return value.</p>
     *
     * <ul>
     *   <li>{@code true}  &mdash; the first result is a {@link ResultSet} (e.g. a {@code SELECT}, or any
     *       statement whose first result is a row set). Note: an empty result set still counts &mdash; a
     *       {@code SELECT} matching zero rows returns {@code true}.</li>
     *   <li>{@code false} &mdash; the first result is an update count, or there is no result at all (e.g. a
     *       <i>successful</i> {@code INSERT}/{@code UPDATE}/{@code DELETE}, or DDL such as
     *       {@code CREATE}/{@code DROP}).</li>
     * </ul>
     *
     * <p>So a perfectly successful {@code INSERT INTO ...} returns {@code false} (meaning "the first result
     * is not a ResultSet", <b>not</b> "the insert failed"). Never write
     * {@code if (JdbcUtil.execute(ds, sql)) { ...succeeded... }}.</p>
     *
     * <p><b>Choosing among the execute-family methods</b> (all share the same
     * {@code (source, sql, parameters)} shape):</p>
     * <table border="1">
     *   <caption>JdbcUtil execute-family return values</caption>
     *   <tr><th>Method</th><th>Returns</th><th>Use when</th></tr>
     *   <tr><td>{@link #executeQuery(javax.sql.DataSource, String, Object...) executeQuery}</td>
     *       <td>{@link Dataset} of the selected rows</td>
     *       <td>the SQL is a query and you want the rows</td></tr>
     *   <tr><td>{@link #executeUpdate(javax.sql.DataSource, String, Object...) executeUpdate}</td>
     *       <td>{@code int} affected-row count</td>
     *       <td>the SQL is an INSERT/UPDATE/DELETE and you want the count</td></tr>
     *   <tr><td>{@code execute} (this method)</td>
     *       <td>{@code boolean}: first-result-is-a-{@code ResultSet}</td>
     *       <td>you do not know the statement type in advance, or you specifically need the raw JDBC
     *           dispatch flag</td></tr>
     * </table>
     *
     * <p>This is a thin wrapper over {@link PreparedStatement#execute()} that handles connection
     * acquisition, transaction participation (an active transaction's connection is reused), parameter
     * binding, and resource cleanup. Because the internal statement is closed before this method returns,
     * any {@link ResultSet} that caused a {@code true} return is already closed and cannot be read here; use
     * {@link #executeQuery(javax.sql.DataSource, String, Object...) executeQuery} if you actually need the
     * rows.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // SELECT: the first result is a ResultSet -> returns true (even when it matches no rows).
     * boolean firstResultIsResultSet = JdbcUtil.execute(dataSource,
     *         "SELECT * FROM users WHERE age > ?", 18);   // true
     *
     * // A SUCCESSFUL INSERT/UPDATE/DELETE: the first result is an update count -> returns false.
     * // false here means "not a ResultSet", NOT "the insert failed".
     * boolean firstResultIsResultSet2 = JdbcUtil.execute(dataSource,
     *         "INSERT INTO users (name, age) VALUES (?, ?)", "John", 25);   // false
     *
     * // Want the affected-row count instead? Use executeUpdate:
     * int inserted = JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO users (name, age) VALUES (?, ?)", "John", 25);   // 1
     *
     * // A null DataSource is rejected.
     * JdbcUtil.execute((javax.sql.DataSource) null, "SELECT 1");   // throws IllegalArgumentException
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to obtain a connection from; must not be {@code null}.
     * @param sql The SQL statement to execute; must not be {@code null} or empty.
     * @param parameters Optional parameters bound to {@code ?} placeholders (or named parameters) in
     *                   the SQL; may be empty.
     * @return {@code true} if the statement's first result is a {@link ResultSet}; {@code false} if it is an
     *         update count or there is no result. This mirrors {@link java.sql.PreparedStatement#execute()} and is
     *         <b>not</b> a success indicator &mdash; a failed statement throws {@link SQLException} instead.
     * @throws IllegalArgumentException if {@code ds} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if a database access error occurs while executing the statement.
     * @see PreparedStatement#execute()
     * @see #executeQuery(javax.sql.DataSource, String, Object...)
     * @see #executeUpdate(javax.sql.DataSource, String, Object...)
     */
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final SqlTransaction tran = getTransaction(ds, sql, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return execute(tran.connection(), sql, parameters);
        } else {
            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return execute(conn, sql, parameters);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        }
    }

    /**
     * Executes a SQL statement of any kind on the supplied {@link Connection}, returning the raw JDBC
     * "first result" indicator of {@link PreparedStatement#execute()}.
     *
     * <p><b>WARNING — the returned {@code boolean} is NOT a success/failure flag.</b> Despite sitting next
     * to {@link #executeUpdate(Connection, String, Object...) executeUpdate} (which returns an affected-row
     * count) and {@link #executeQuery(Connection, String, Object...) executeQuery} (which returns a
     * {@link Dataset} of rows), this method's {@code boolean} carries the exact semantics of
     * {@link java.sql.PreparedStatement#execute()}: it reports the <i>shape of the statement's first result</i>, not
     * whether the statement "worked". A statement that completes without throwing has already succeeded —
     * failure is reported by a thrown {@link SQLException}, <b>never</b> by a {@code false} return value.</p>
     *
     * <ul>
     *   <li>{@code true}  &mdash; the first result is a {@link ResultSet} (e.g. a {@code SELECT}). An empty
     *       result set still counts: a {@code SELECT} matching zero rows returns {@code true}.</li>
     *   <li>{@code false} &mdash; the first result is an update count, or there is no result at all (e.g. a
     *       <i>successful</i> {@code INSERT}/{@code UPDATE}/{@code DELETE}, or DDL such as
     *       {@code CREATE}/{@code DROP}).</li>
     * </ul>
     *
     * <p>So a perfectly successful {@code INSERT INTO ...} returns {@code false} (meaning "the first result
     * is not a ResultSet", <b>not</b> "the insert failed"). Never write
     * {@code if (JdbcUtil.execute(conn, sql)) { ...succeeded... }}.</p>
     *
     * <p><b>Choosing among the execute-family methods</b> (all share the same
     * {@code (source, sql, parameters)} shape):</p>
     * <table border="1">
     *   <caption>JdbcUtil execute-family return values</caption>
     *   <tr><th>Method</th><th>Returns</th><th>Use when</th></tr>
     *   <tr><td>{@link #executeQuery(Connection, String, Object...) executeQuery}</td>
     *       <td>{@link Dataset} of the selected rows</td>
     *       <td>the SQL is a query and you want the rows</td></tr>
     *   <tr><td>{@link #executeUpdate(Connection, String, Object...) executeUpdate}</td>
     *       <td>{@code int} affected-row count</td>
     *       <td>the SQL is an INSERT/UPDATE/DELETE and you want the count</td></tr>
     *   <tr><td>{@code execute} (this method)</td>
     *       <td>{@code boolean}: first-result-is-a-{@code ResultSet}</td>
     *       <td>you do not know the statement type in advance, or you specifically need the raw JDBC
     *           dispatch flag</td></tr>
     * </table>
     *
     * <p>The supplied connection remains open; its caller or transaction manager owns its lifecycle. The internally created {@link PreparedStatement} is always closed before this
     * method returns; consequently any {@link ResultSet} that caused a {@code true} return is already closed
     * and cannot be read here. Use {@link #executeQuery(Connection, String, Object...) executeQuery} if you
     * actually need the rows.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // SELECT -> first result is a ResultSet -> returns true (even when it matches no rows).
     *     boolean firstResultIsResultSet = JdbcUtil.execute(conn,
     *             "SELECT * FROM users WHERE age > ?", 18);   // true
     *
     *     // SUCCESSFUL INSERT -> first result is an update count, not a ResultSet -> returns false.
     *     // false here means "not a ResultSet", NOT "the insert failed".
     *     boolean firstResultIsResultSet2 = JdbcUtil.execute(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", "John", 25);   // false
     *
     *     // DDL -> no ResultSet -> returns false.
     *     boolean ddl = JdbcUtil.execute(conn, "CREATE TABLE tmp_x (id INT)");   // false
     *
     *     // Want the affected-row count instead? Use executeUpdate:
     *     int inserted = JdbcUtil.executeUpdate(conn,
     *             "INSERT INTO users (name, age) VALUES (?, ?)", "Jane", 30);   // 1
     * }
     * }</pre>
     *
     * @param conn The {@link Connection} to use; must not be {@code null} and not closed by this method.
     * @param sql The SQL statement to execute; must not be {@code null} or empty.
     * @param parameters Optional parameters bound to {@code ?} placeholders (or named parameters) in
     *                   the SQL; may be empty.
     * @return {@code true} if the statement's first result is a {@link ResultSet}; {@code false} if it is an
     *         update count or there is no result. This mirrors {@link java.sql.PreparedStatement#execute()} and is
     *         <b>not</b> a success indicator &mdash; a failed statement throws {@link SQLException} instead.
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code sql} is {@code null} or empty,
     *         or a supplied parameter set cannot satisfy the SQL's required positional or named parameters.
     * @throws SQLException if a database access error occurs while executing the statement.
     * @see PreparedStatement#execute()
     * @see #executeQuery(Connection, String, Object...)
     * @see #executeUpdate(Connection, String, Object...)
     */
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

    /**
     * Executes the given {@link PreparedStatement} as a query and returns the result set, handling SQL
     * performance logging when enabled. The statement's parameters are cleared after execution.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return The {@link ResultSet} produced by the query.
     * @throws SQLException if a database access error occurs.
     */
    static ResultSet executeQuery(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTimeMillis = System.currentTimeMillis();
            final long startTimeNanos = System.nanoTime();

            try {
                // return stmt.executeQuery();
                // For better performance.
                return ResultSetProxy.wrap(stmt.executeQuery());
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);

                clearParameters(stmt);
            }
        } else {
            try {
                // return stmt.executeQuery();
                // For better performance.
                return ResultSetProxy.wrap(stmt.executeQuery());
            } finally {
                clearParameters(stmt);
            }
        }
    }

    /**
     * Executes the given {@link PreparedStatement} as an update, handling SQL performance logging when
     * enabled. The statement's parameters are cleared after execution.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return The number of rows affected by the statement.
     * @throws SQLException if a database access error occurs.
     */
    static int executeUpdate(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTimeMillis = System.currentTimeMillis();
            final long startTimeNanos = System.nanoTime();

            try {
                return stmt.executeUpdate();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);

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

    /**
     * Executes the given {@link PreparedStatement} as a large update, handling SQL performance logging
     * when enabled. The statement's parameters are cleared after execution.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return The number of rows affected by the statement, as a long value.
     * @throws SQLException if a database access error occurs.
     */
    static long executeLargeUpdate(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTimeMillis = System.currentTimeMillis();
            final long startTimeNanos = System.nanoTime();

            try {
                return stmt.executeLargeUpdate();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);

                clearParameters(stmt);
            }
        } else {
            try {
                return stmt.executeLargeUpdate();
            } finally {
                clearParameters(stmt);
            }
        }
    }

    /**
     * Executes the given {@link Statement}'s batch, handling SQL performance logging when enabled. The
     * statement's batch is cleared after execution.
     *
     * @param stmt The {@link Statement} whose batch to execute.
     * @return An array of update counts, one per batch entry.
     * @throws SQLException if a database access error occurs.
     */
    static int[] executeBatch(final Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTimeMillis = System.currentTimeMillis();
            final long startTimeNanos = System.nanoTime();

            try {
                return stmt.executeBatch();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.warn(e, "Failed to clear batch parameters after executeBatch");
                }
            }
        } else {
            try {
                return stmt.executeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.warn(e, "Failed to clear batch parameters after executeBatch");
                }
            }
        }
    }

    /**
     * Executes the given {@link Statement}'s batch as a large batch, handling SQL performance logging
     * when enabled. The statement's batch is cleared after execution.
     *
     * @param stmt The {@link Statement} whose batch to execute.
     * @return An array of update counts as long values, one per batch entry.
     * @throws SQLException if a database access error occurs.
     */
    static long[] executeLargeBatch(final Statement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTimeMillis = System.currentTimeMillis();
            final long startTimeNanos = System.nanoTime();

            try {
                return stmt.executeLargeBatch();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);

                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.warn(e, "Failed to clear batch parameters after executeLargeBatch");
                }
            }
        } else {
            try {
                return stmt.executeLargeBatch();
            } finally {
                try {
                    stmt.clearBatch();
                } catch (final SQLException e) {
                    logger.warn(e, "Failed to clear batch parameters after executeLargeBatch");
                }
            }
        }
    }

    /**
     * Sums the positive entries of a batch update-count array; non-positive driver status codes
     * (e.g., {@link Statement#SUCCESS_NO_INFO}) are ignored.
     *
     * @param updateCounts The per-element update counts returned by a batch execution.
     * @return The total number of updated rows.
     * @throws ArithmeticException if the total exceeds {@link Integer#MAX_VALUE}.
     */
    private static int sumUpdatedRows(final int[] updateCounts) {
        int result = 0;

        for (final int updateCount : updateCounts) {
            if (updateCount > 0) {
                result = addUpdatedRowsExact(result, updateCount);
            }
        }

        return result;
    }

    /**
     * Adds one batch update count to a running total, throwing a descriptive {@link ArithmeticException}
     * (advising {@code executeLargeBatchUpdate}) when the total would overflow an {@code int}.
     * @throws ArithmeticException if adding a positive increment exceeds {@link Integer#MAX_VALUE}.
     */
    private static int addUpdatedRowsExact(final int current, final int increment) {
        try {
            return Math.addExact(current, increment);
        } catch (final ArithmeticException e) {
            final ArithmeticException overflow = new ArithmeticException(
                    "Batch update count exceeds Integer.MAX_VALUE; use executeLargeBatchUpdate for large batch results");
            overflow.initCause(e);
            throw overflow;
        }
    }

    /**
     * Sums the positive entries of a large-batch update-count array; non-positive driver status codes
     * (e.g., {@link Statement#SUCCESS_NO_INFO}) are ignored.
     *
     * @param updateCounts The per-element update counts returned by a large batch execution.
     * @return The total number of updated rows.
     * @throws ArithmeticException if the total exceeds {@link Long#MAX_VALUE}.
     */
    private static long sumUpdatedRows(final long[] updateCounts) {
        long result = 0;

        for (final long updateCount : updateCounts) {
            if (updateCount > 0) {
                result = addUpdatedRowsExact(result, updateCount);
            }
        }

        return result;
    }

    /**
     * Adds one batch update count to a running total, throwing a descriptive {@link ArithmeticException}
     * when the total would overflow a {@code long}.
     * @throws ArithmeticException if adding a positive increment exceeds {@link Long#MAX_VALUE}.
     */
    private static long addUpdatedRowsExact(final long current, final long increment) {
        try {
            return Math.addExact(current, increment);
        } catch (final ArithmeticException e) {
            final ArithmeticException overflow = new ArithmeticException("Batch update count exceeds Long.MAX_VALUE");
            overflow.initCause(e);
            throw overflow;
        }
    }

    /**
     * Executes the given {@link PreparedStatement}, handling SQL performance logging when enabled. The
     * statement's parameters are cleared after execution.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return {@code true} if the first result is a {@link ResultSet}; {@code false} if it is an update count or there is no result.
     * @throws SQLException if a database access error occurs.
     */
    static boolean execute(final PreparedStatement stmt) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTimeMillis = System.currentTimeMillis();
            final long startTimeNanos = System.nanoTime();

            try {
                return stmt.execute();
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);

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

    /**
     * Clears the parameters of the given {@link PreparedStatement}, ignoring any failure. Does nothing
     * for {@code null} statements and {@link CallableStatement}s (clearing parameters would also remove
     * registered out parameters).
     *
     * @param stmt The {@link PreparedStatement} whose parameters to clear. Can be {@code null}.
     */
    static void clearParameters(final PreparedStatement stmt) {
        // calling clearParameters() will impact/remove registered out parameters in CallableStatement.
        if (stmt == null || stmt instanceof CallableStatement) {
            // no
        } else {
            try {
                stmt.clearParameters();
            } catch (final SQLException e) {
                logger.warn(e, "Failed to clear parameters after execution");
            }
        }
    }

    /**
     * Sets the given parameters on the specified {@link PreparedStatement} according to the parsed SQL.
     * When the SQL uses named parameters, a single entity, {@link Map}, or {@link EntityId} may be passed
     * to bind them by name.
     *
     * @param parsedSql The parsed SQL statement containing parameter information.
     * @param stmt The {@link PreparedStatement} to set parameters on.
     * @param parameters The parameter values to set.
     * @throws IllegalArgumentException if the number of provided parameters is less than the SQL requires, or a named parameter has no matching value.
     * @throws SQLException if a database access error occurs.
     */
    static void setParameters(final ParsedSql parsedSql, final PreparedStatement stmt, final Object[] parameters) throws SQLException {
        final int parameterCount = parsedSql.parameterCount();

        if (parameterCount == 0) {
            return;
        } else if (N.isEmpty(parameters)) {
            throw new IllegalArgumentException("SQL requires " + parsedSql.parameterCount() + " parameter(s), but the specified parameters are null or empty");
        }

        @SuppressWarnings("rawtypes")
        Type[] parameterTypes = null;
        Object[] parameterValues = null;

        if (isEntityOrMapParameter(parsedSql, parameters)) {
            final List<String> namedParameters = parsedSql.namedParameters();
            final Object parameter_0 = parameters[0];
            final Class<?> cls = parameter_0.getClass();

            parameterValues = new Object[parameterCount];

            if (Beans.isBeanClass(cls) || Beans.isRecordClass(cls)) {
                final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
                parameterTypes = new Type[parameterCount];
                PropInfo propInfo = null;

                for (int i = 0; i < parameterCount; i++) {
                    propInfo = entityInfo.getPropInfo(namedParameters.get(i));

                    if (propInfo == null) {
                        throw new IllegalArgumentException(
                                "No property found with name: " + namedParameters.get(i) + " in class: " + ClassUtil.getCanonicalClassName(cls));
                    }

                    parameterValues[i] = propInfo.getPropValue(parameter_0);
                    parameterTypes[i] = propInfo.dbType;
                }
            } else if (parameter_0 instanceof Map) {
                final Map<String, Object> m = (Map<String, Object>) parameter_0;

                for (int i = 0; i < parameterCount; i++) {
                    parameterValues[i] = m.get(namedParameters.get(i));

                    if ((parameterValues[i] == null) && !m.containsKey(namedParameters.get(i))) {
                        throw new IllegalArgumentException("Missing parameter for property: '" + namedParameters.get(i) + "'");
                    }
                }
            } else {
                final EntityId entityId = (EntityId) parameter_0;

                for (int i = 0; i < parameterCount; i++) {
                    parameterValues[i] = entityId.get(namedParameters.get(i));

                    if ((parameterValues[i] == null) && !entityId.containsKey(namedParameters.get(i))) {
                        throw new IllegalArgumentException("Missing parameter for property: '" + namedParameters.get(i) + "'");
                    }
                }
            }
        } else {
            parameterValues = getParameterValues(parsedSql, parameters);
        }

        if (parameterValues.length < parameterCount) {
            throw new IllegalArgumentException("SQL requires " + parameterCount + " parameter(s), but only " + parameterValues.length + " specified");
        }

        setParameters(stmt, parameterCount, parameterValues, parameterTypes);
    }

    /**
     * Sets the given parameter values on the specified {@link PreparedStatement}, using the provided
     * {@link Type}s for the binding when available.
     *
     * @param stmt The {@link PreparedStatement} to set parameters on.
     * @param parameterCount The number of parameters to set.
     * @param parameters The parameter values to set.
     * @param parameterTypes The {@link Type}s used to bind the parameters; may be {@code null} or empty, in which case the type is inferred from each value.
     * @throws SQLException if a database access error occurs.
     */
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
                    Type.<Object> of(parameters[i].getClass()).set(stmt, i + 1, parameters[i]);
                }
            }
        }
    }

    /**
     * Extracts and returns parameter values from the provided parameters array.
     * If a single parameter is provided and it's an array or collection with sufficient elements,
     * this method unwraps it to use its contents as the actual parameter values.
     *
     * @param parsedSql The parsed SQL statement containing parameter information.
     * @param parameters The parameters provided, which may be individual values or a single array/collection.
     * @return An array of parameter values ready to be set on a PreparedStatement.
     */
    static Object[] getParameterValues(final ParsedSql parsedSql, final Object... parameters) {
        if ((parameters.length == 1) && (parameters[0] != null)) {
            if (parameters[0] instanceof Object[] && ((((Object[]) parameters[0]).length) >= parsedSql.parameterCount())) {
                return (Object[]) parameters[0];
            } else if (parameters[0] instanceof final Collection<?> c && (c.size() >= parsedSql.parameterCount())) {
                return c.toArray(new Object[0]);
            }
        }

        return parameters;
    }

    /**
     * Returns whether the given parameters consist of a single entity, {@link Map}, or {@link EntityId}
     * to be bound to the named parameters of the parsed SQL by name.
     *
     * @param parsedSql The parsed SQL statement containing parameter information.
     * @param parameters The parameters provided.
     * @return {@code true} if the SQL has named parameters and exactly one non-null entity, {@code Map}, or {@code EntityId} parameter is provided.
     */
    static boolean isEntityOrMapParameter(final ParsedSql parsedSql, final Object... parameters) {
        if (N.isEmpty(parsedSql.namedParameters()) || N.isEmpty(parameters) || (parameters.length != 1) || (parameters[0] == null)) {
            return false;
        }

        final Class<?> cls = parameters[0].getClass();

        return Beans.isBeanClass(cls) || Beans.isRecordClass(cls) || Map.class.isAssignableFrom(cls) || EntityId.class.isAssignableFrom(cls);
    }

    /**
     * Sentinel {@link RowFilter} meaning "no filter supplied"; compared by identity inside the {@code extractData}
     * methods and never actually applied.
     */
    static final RowFilter INTERNAL_DUMMY_ROW_FILTER = RowFilter.ALWAYS_TRUE;

    /**
     * Sentinel {@link RowExtractor} meaning "no extractor supplied"; compared by identity inside the
     * {@code extractData} methods and throws {@link UnsupportedOperationException} if ever invoked.
     */
    static final RowExtractor INTERNAL_DUMMY_ROW_EXTRACTOR = (rs, outputRow) -> {
        throw new UnsupportedOperationException("DO NOT CALL ME.");
    };

    /**
     * Extracts all rows from the provided {@link ResultSet} and returns them as a {@link Dataset}.
     *
     * <p>Reading starts at the {@code ResultSet}'s current cursor position. The cursor is advanced row
     * by row via {@link ResultSet#next()}; the {@code ResultSet} is not closed by this method.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
     *     Dataset dataset = JdbcUtil.extractData(rs);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from; must not be {@code null}.
     * @return A {@link Dataset} containing the extracted data; column names are taken from the
     *         {@link ResultSetMetaData} labels (or column names if no label is set).
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs while extracting data.
     * @see #extractData(ResultSet, boolean)
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs) throws SQLException {
        return extractData(rs, false);
    }

    /**
     * Extracts data from the provided ResultSet starting from the specified offset and up to the specified count.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
     *     Dataset dataset = JdbcUtil.extractData(rs, 10, 50);   // Skip 10 rows, get next 50
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from.
     * @param offset The starting position in the ResultSet (0-based).
     * @param count The maximum number of rows to extract.
     * @return A {@link Dataset} containing the extracted data.
     * @throws IllegalArgumentException if {@code rs} is {@code null} or offset/count are negative.
     * @throws SQLException if a database access error occurs while extracting data.
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count) throws SQLException {
        return extractData(rs, offset, count, false);
    }

    /**
     * Extracts data from the provided ResultSet using the specified RowFilter.
     * Only rows that pass the filter will be included in the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter rows where age is greater than 18
     * RowFilter adultFilter = resultSet -> resultSet.getInt("age") > 18;
     * try (ResultSet rs = stmt.executeQuery("SELECT name, age, email FROM users")) {
     *     Dataset adults = JdbcUtil.extractData(rs, adultFilter);
     * }
     *
     * // Filter rows based on multiple conditions
     * RowFilter activeUsersFilter = resultSet ->
     *     resultSet.getBoolean("is_active") &&
     *     resultSet.getString("status").equals("VERIFIED");
     * try (ResultSet rs = stmt.executeQuery("SELECT name, is_active, status FROM users")) {
     *     Dataset activeUsers = JdbcUtil.extractData(rs, activeUsersFilter);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from, must not be {@code null}.
     * @param filter The RowFilter to apply while extracting data. This is a functional interface that tests each row;
     *               only rows for which {@code filter.test(rs)} returns {@code true} will be included in the result.
     *               Must not be {@code null}.
     * @return A {@link Dataset} containing the filtered data.
     * @throws IllegalArgumentException if {@code rs} or {@code filter} is {@code null}.
     * @throws SQLException if a database access error occurs while extracting data.
     * @see RowFilter
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowFilter filter) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(filter, cs.filter);

        return extractData(rs, 0, Integer.MAX_VALUE, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, false);
    }

    /**
     * Extracts data from the provided ResultSet using the specified RowExtractor.
     * The RowExtractor can transform or manipulate each row during extraction, allowing you to
     * modify column values before they are added to the resulting Dataset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Transform email addresses to lowercase during extraction
     * RowExtractor emailNormalizer = RowExtractor.builder()
     *              // email is the 3rd column (1-based index 3)
     *              .get(3, (rs, col) -> rs.getString(col).toLowerCase()).build();
     *
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, email FROM users")) {
     *     Dataset normalizedData = JdbcUtil.extractData(rs, emailNormalizer);
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from, must not be {@code null}.
     * @param rowExtractor The RowExtractor to apply while extracting data. This is a functional interface
     *                     that receives the current ResultSet and an output row array, allowing modification
     *                     of the row data before it's added to the Dataset. Must not be {@code null}.
     * @return A {@link Dataset} containing the extracted and transformed data.
     * @throws IllegalArgumentException if {@code rs} or {@code rowExtractor} is {@code null}.
     * @throws SQLException if a database access error occurs while extracting data.
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowExtractor rowExtractor) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rowExtractor, cs.rowExtractor);

        return extractData(rs, 0, Integer.MAX_VALUE, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, false);
    }

    /**
     * Extracts data from the provided ResultSet using both RowFilter and RowExtractor.
     * This method combines filtering and transformation: first, rows are filtered based on the
     * {@code RowFilter}, then the remaining rows are transformed using the {@code RowExtractor}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter rows and transform a column in a single pass.
     * RowFilter expensive = row -> row.getInt("price") >= 30;
     * RowExtractor upperName = RowExtractor.builder().get(2, (rs, columnIndex) -> rs.getString(columnIndex).toUpperCase()).build();
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, price FROM item ORDER BY id")) {
     *     Dataset data = JdbcUtil.extractData(rs, expensive, upperName);   // keeps only rows with price >= 30
     * }
     *
     * // A filter that matches nothing yields an empty Dataset (column names still present).
     * RowFilter none = row -> false;
     * try (ResultSet emptyRs = stmt.executeQuery("SELECT id, name, price FROM item ORDER BY id")) {
     *     Dataset empty = JdbcUtil.extractData(emptyRs, none, RowExtractor.builder().build());   // empty.size() == 0
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from, must not be {@code null}.
     * @param filter The RowFilter to apply for filtering rows. Only rows for which {@code filter.test(rs)}
     *               returns {@code true} will be processed by the extractor. Must not be {@code null}.
     * @param rowExtractor The RowExtractor applied to extract data from the current row of the {@code ResultSet} and populate the {@code outputRow} array.
     *                     Must not be {@code null}.
     * @return A {@link Dataset} containing the filtered and transformed data.
     * @throws IllegalArgumentException if {@code rs}, {@code filter}, or {@code rowExtractor} is {@code null}.
     * @throws SQLException if a database access error occurs while extracting data.
     * @see RowFilter
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter)
     * @see #extractData(ResultSet, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowFilter filter, final RowExtractor rowExtractor)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(filter, cs.filter);
        N.checkArgNotNull(rowExtractor, cs.rowExtractor);

        return extractData(rs, 0, Integer.MAX_VALUE, filter, rowExtractor, false);
    }

    /**
     * Extracts data from the provided ResultSet and returns it as a Dataset.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Extract all rows and let the method close the ResultSet for you.
     * try (Statement stmt = connection.createStatement()) {
     *     Dataset all = JdbcUtil.extractData(
     *             stmt.executeQuery("SELECT id, name, price FROM item"), true);
     *     // 'all' holds every selected row; the underlying ResultSet has been closed.
     * }
     *
     * // Keep the ResultSet open for further use.
     * try (PreparedStatement stmt = connection.prepareStatement(
     *         "SELECT id, name FROM item WHERE id = ?")) {
     *     stmt.setLong(1, itemId);
     *     try (ResultSet rs = stmt.executeQuery()) {
     *         Dataset data = JdbcUtil.extractData(rs, false);   // rs remains open
     *     }
     * }
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from.
     * @param closeResultSet Whether to close the ResultSet after extraction.
     * @return A {@link Dataset} containing the extracted data.
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if a database access error occurs while extracting data.
     */
    public static Dataset extractData(final ResultSet rs, final boolean closeResultSet) throws SQLException {
        return extractData(rs, 0, Integer.MAX_VALUE, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with specified offset and count.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Skip the first row, then take the next two (paging over the ResultSet).
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, price FROM item ORDER BY id")) {
     *     Dataset page = JdbcUtil.extractData(rs, 1, 2, false);   // returns a Dataset with 2 rows
     * }
     *
     * // An offset beyond the available rows yields an empty Dataset; here the method closes the ResultSet.
     * Dataset beyond = JdbcUtil.extractData(stmt.executeQuery("SELECT id FROM item"), 1000, 50, true);   // beyond.size() == 0
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from.
     * @param offset The starting position in the ResultSet (0-based).
     * @param count The maximum number of rows to extract.
     * @param closeResultSet Whether to close the ResultSet after extraction.
     * @return A {@link Dataset} containing the extracted data.
     * @throws IllegalArgumentException if {@code rs} is {@code null} or offset/count are negative.
     * @throws SQLException if a database access error occurs while extracting data.
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final boolean closeResultSet) throws SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with offset, count, and filter.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Filter rows, then cap the result to at most `count` MATCHING rows.
     * RowFilter atLeast20 = row -> row.getInt("price") >= 20;
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, price FROM item ORDER BY id")) {
     *     Dataset data = JdbcUtil.extractData(rs, 0, 2, atLeast20, false);   // at most 2 rows that pass the filter
     * }
     *
     * // A filter matching nothing yields an empty Dataset; here the method closes the ResultSet.
     * Dataset empty = JdbcUtil.extractData(stmt.executeQuery("SELECT id, name, price FROM item"),
     *         0, Integer.MAX_VALUE, row -> false, true);   // empty.size() == 0
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from, must not be {@code null}.
     * @param offset The starting position (0-based) in the ResultSet, must be non-negative.
     * @param count The maximum number of rows to extract, must be non-negative.
     * @param filter The RowFilter to apply while extracting data. Only rows for which {@code filter.test(rs)}
     *               returns {@code true} will be included. Must not be {@code null}.
     * @param closeResultSet Whether to close the ResultSet after extraction.
     * @return A {@link Dataset} containing the extracted data.
     * @throws IllegalArgumentException if {@code rs} or {@code filter} is {@code null}, or if {@code offset} or {@code count} is negative.
     * @throws SQLException if a database access error occurs while extracting data.
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowFilter filter, final boolean closeResultSet)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(filter, cs.filter);

        return extractData(rs, offset, count, filter, INTERNAL_DUMMY_ROW_EXTRACTOR, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with offset, count, and extractor.
     * This method allows specifying whether to close the ResultSet after extraction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Transform the name column (index 2) while extracting the first 3 rows.
     * RowExtractor upperName = RowExtractor.builder().get(2, (rs, columnIndex) -> rs.getString(columnIndex).toUpperCase()).build();
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, price FROM item ORDER BY id")) {
     *     Dataset data = JdbcUtil.extractData(rs, 0, 3, upperName, false);   // returns a Dataset with up to 3 rows
     * }
     *
     * // count == 0 returns an empty Dataset; the extractor is never invoked; here the ResultSet is closed.
     * Dataset none = JdbcUtil.extractData(stmt.executeQuery("SELECT id, name FROM item"), 0, 0, upperName, true);   // none.size() == 0
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from, must not be {@code null}.
     * @param offset The starting position (0-based) in the ResultSet, must be non-negative.
     * @param count The maximum number of rows to extract, must be non-negative.
     * @param rowExtractor The RowExtractor applied to extract data from the current row of the {@code ResultSet} and populate the {@code outputRow} array.
     *                     Must not be {@code null}.
     * @param closeResultSet Whether to close the ResultSet after extraction.
     * @return A {@link Dataset} containing the extracted and transformed data.
     * @throws IllegalArgumentException if {@code rs} or {@code rowExtractor} is {@code null}, or if {@code offset} or {@code count} is negative.
     * @throws SQLException if a database access error occurs while extracting data.
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowExtractor rowExtractor, final boolean closeResultSet)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rowExtractor, cs.rowExtractor);

        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with all extraction options.
     * This is the most comprehensive extraction method providing full control over the extraction process,
     * including pagination (offset/count), filtering (RowFilter), and transformation (RowExtractor).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Page + filter + transform in a single pass, leaving the ResultSet open.
     * RowFilter cheap = row -> row.getInt("price") <= 30;
     * RowExtractor upperName = RowExtractor.builder().get(2, (rs, columnIndex) -> rs.getString(columnIndex).toUpperCase()).build();
     * try (ResultSet rs = stmt.executeQuery("SELECT id, name, price FROM item ORDER BY id")) {
     *     Dataset data = JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, cheap, upperName, false);
     *     // 'data' holds the rows with price <= 30, name column upper-cased
     * }
     * try (ResultSet pageRs = stmt.executeQuery("SELECT id, name, price FROM item ORDER BY id")) {
     *     // Skip the first row, then take up to one matching row.
     *     Dataset one = JdbcUtil.extractData(pageRs, 1, 1, cheap, upperName, false);   // one.size() <= 1
     * }
     *
     * // Any null argument or negative offset/count throws IllegalArgumentException.
     * JdbcUtil.extractData((ResultSet) null, 0, 1, cheap, upperName, false);   // throws IllegalArgumentException
     * }</pre>
     *
     * @param rs The {@link ResultSet} to extract data from, must not be {@code null}.
     * @param offset The starting position (0-based) in the ResultSet, must be non-negative.
     * @param count The maximum number of rows to extract, must be non-negative.
     * @param filter The RowFilter to apply for filtering rows. Only rows for which {@code filter.test(rs)}
     *               returns {@code true} will be processed. Must not be {@code null}.
     * @param rowExtractor The RowExtractor applied to extract data from the current row of the {@code ResultSet} and populate the {@code outputRow} array.
     *                     Must not be {@code null}.
     * @param closeResultSet Whether to close the ResultSet after extraction completes (or if an error occurs).
     * @return A {@link Dataset} containing the filtered and transformed data.
     * @throws IllegalArgumentException if {@code rs} , {@code filter} , or {@code rowExtractor} is {@code null} , or if {@code offset} or
     *         {@code count} is negative.
     * @throws SQLException if a database access error occurs while extracting data.
     * @see RowFilter
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowFilter filter, final RowExtractor rowExtractor,
            final boolean closeResultSet) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNegative(offset, cs.offset);
        N.checkArgNotNegative(count, cs.count);
        N.checkArgNotNull(filter, cs.filter);
        N.checkArgNotNull(rowExtractor, cs.rowExtractor);
        // A ResultSetProxy already normalizes date/time types itself, so getColumnValue ignores this flag for proxies.
        // Skip the DatabaseMetaData lookup in that case (behavior-identical, avoids a potential server round-trip).
        final boolean checkDateType = !(rs instanceof ResultSetProxy) && checkDateType(rs);

        try {
            return JdbcUtil.extractResultSetToDataset(rs, offset, count, filter, rowExtractor, checkDateType);
        } finally {
            if (closeResultSet) {
                closeQuietly(rs);
            }
        }
    }

    /**
     * Extracts rows from the given {@link ResultSet} into a {@link Dataset}, applying the specified
     * offset, row count limit, row filter, and row extractor. The {@code ResultSet} is not closed by
     * this method.
     *
     * @param rs The {@link ResultSet} to extract data from.
     * @param offset The number of rows to skip before extracting.
     * @param count The maximum number of rows to extract.
     * @param filter The {@link RowFilter} to apply while extracting data.
     * @param rowExtractor The {@link RowExtractor} to apply while extracting data.
     * @param checkDateType Whether to normalize database-specific date/time types to standard Java types.
     * @return A {@link Dataset} containing the extracted data.
     * @throws SQLException if a database access error occurs.
     */
    static Dataset extractResultSetToDataset(final ResultSet rs, final int offset, int count, final RowFilter filter, final RowExtractor rowExtractor,
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

        // return new RowDataset(null, entityClass, columnNameList, columnList);
        return new RowDataset(columnNameList, columnList);
    }

    /**
     * Applies the given {@link ResultExtractor} to the {@link ResultSet} and then closes the result set.
     *
     * @param <R> The type of the extraction result.
     * @param rs The {@link ResultSet} to extract from. It is closed before this method returns.
     * @param resultExtractor The extractor that produces the result from the {@link ResultSet}.
     * @return The extraction result.
     * @throws UnsupportedOperationException if {@code resultExtractor} returns a {@link ResultSet}, which would be closed before it could be used.
     * @throws SQLException if a database access error occurs.
     */
    static <R> R extractAndCloseResultSet(final ResultSet rs, final ResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs));
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     * Applies the given {@link BiResultExtractor} to the {@link ResultSet} and its column labels, and
     * then closes the result set.
     *
     * @param <R> The type of the extraction result.
     * @param rs The {@link ResultSet} to extract from. It is closed before this method returns.
     * @param resultExtractor The extractor that produces the result from the {@link ResultSet} and its column labels.
     * @return The extraction result.
     * @throws UnsupportedOperationException if {@code resultExtractor} returns a {@link ResultSet}, which would be closed before it could be used.
     * @throws SQLException if a database access error occurs.
     */
    static <R> R extractAndCloseResultSet(final ResultSet rs, final BiResultExtractor<? extends R> resultExtractor) throws SQLException {
        try {
            return checkNotResultSet(resultExtractor.apply(rs, getColumnLabels(rs)));
        } finally {
            closeQuietly(rs);
        }
    }

    /**
     * Creates a lazy {@link Stream} over the rows of the provided {@link ResultSet}, with each row
     * mapped to an {@code Object[]} containing the values of all columns of that row.
     *
     * <p>The {@code ResultSet} is consumed lazily as the stream is traversed; if you only consume a
     * prefix of the stream the remaining rows remain unread. The caller is responsible for closing the
     * input {@code ResultSet} — typically by attaching {@code onClose(Fn.closeQuietly(rs))} to the
     * returned stream and closing it with try-with-resources. Close handlers run when the stream is
     * closed — explicitly, through try-with-resources, or automatically after a terminal operation
     * completes; try-with-resources also covers streams abandoned before any terminal operation.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     *         Stream<Object[]> rows = JdbcUtil.stream(rs)) {
     *     rows.forEach(row -> System.out.println(Arrays.toString(row)));
     * }
     *
     * // Or with auto-close:
     * try (Stream<Object[]> rows = JdbcUtil.stream(resultSet)
     *         .onClose(Fn.closeQuietly(resultSet))) {
     *     rows.forEach(row -> processRow(row));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param rs The {@link ResultSet} to stream; must not be {@code null}.
     * @return A {@link Stream} of {@code Object[]}, each array containing the column values of one row.
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     */
    public static Stream<Object[]> stream(final ResultSet rs) {
        return stream(rs, Object[].class);
    }

    /**
     * Creates a stream from the provided ResultSet, mapping each row to the specified target class.
     * It's the user's responsibility to close the ResultSet after the stream is finished.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultSet rs = stmt.executeQuery("SELECT * FROM users");
     * try (Stream<User> users = JdbcUtil.stream(rs, User.class)
     *         .onClose(Fn.closeQuietly(rs))) {
     *     users.filter(user -> user.getAge() > 18)
     *          .forEach(user -> processUser(user));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param <T> The type of the result extracted from the ResultSet.
     * @param rs The {@link ResultSet} to create a stream from.
     * @param targetClass The class of the result type. Column names from the ResultSet will be mapped to properties of this class.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if the provided arguments are invalid.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final Class<? extends T> targetClass) throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNull(targetClass, cs.targetClass);

        return stream(rs, BiRowMapper.to(targetClass));
    }

    /**
     * Creates a stream from the provided ResultSet using the specified RowMapper.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished. One option is to attach
     * {@code onClose(Fn.closeQuietly(resultSet))} and close the returned stream with try-with-resources.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowMapper<String> nameMapper = rs -> rs.getString("name");
     * try (Stream<String> names = JdbcUtil.stream(resultSet, nameMapper)
     *         .onClose(Fn.closeQuietly(resultSet))) {
     *     names.forEach(name -> System.out.println(name));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param <T> The type of the result extracted from the ResultSet.
     * @param rs The {@link ResultSet} to create a stream from.
     * @param rowMapper The RowMapper to apply while extracting data. This mapper is called for each row in the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code rs} or {@code rowMapper} is {@code null}.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final RowMapper<? extends T> rowMapper) throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(rs, rowMapper, null));
    }

    /**
     * Creates an {@link ObjIteratorEx} over the rows of the given {@link ResultSet}, mapping each row
     * with the specified {@link RowMapper}.
     *
     * <p>The iterator propagates database access failures as {@link UncheckedSQLException}; calling
     * {@code next()} after exhaustion throws {@link NoSuchElementException}.</p>
     *
     * @param <T> The type of the mapped row elements.
     * @param resultSet The {@link ResultSet} to iterate over.
     * @param rowMapper The {@link RowMapper} used to map each row.
     * @param onClose An optional action to run when the iterator is closed. Can be {@code null}.
     * @return an iterator over the mapped rows that remembers exhaustion without advancing the JDBC cursor again
     */
    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final RowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new ObjIteratorEx<>() {
            private boolean hasNext;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (!hasNext && !exhausted) {
                    try {
                        hasNext = resultSet.next();
                        exhausted = !hasNext;
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

                if (n == 0 || exhausted) {
                    return;
                }

                final long rowsToSkip = hasNext ? n - 1 : n;

                try {
                    exhausted = JdbcUtil.skip(resultSet, rowsToSkip) < rowsToSkip;
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                hasNext = false;
            }

            @Override
            public long count() {
                if (exhausted) {
                    return 0;
                }

                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                try {
                    while (resultSet.next()) {
                        cnt++;
                    }

                    exhausted = true;
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                return cnt;
            }

            private boolean isClosed = false;

            @Override
            public void closeResource() {
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
     * Only rows that pass the filter will be included in the stream.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowFilter ageFilter = rs -> rs.getInt("age") > 18;
     * RowMapper<User> userMapper = rs -> new User(rs.getString("name"), rs.getInt("age"));
     * try (Stream<User> users = JdbcUtil.stream(resultSet, ageFilter, userMapper)
     *         .onClose(Fn.closeQuietly(resultSet))) {
     *     users.forEach(user -> processAdultUser(user));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param <T> The type of the result extracted from the ResultSet.
     * @param rs The {@link ResultSet} to create a stream from.
     * @param rowFilter The RowFilter to apply while filtering rows. Only rows for which this filter returns {@code true} will be included.
     * @param rowMapper The RowMapper to apply while extracting data from filtered rows.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code rs}, {@code rowFilter}, or {@code rowMapper} is {@code null}.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper) throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(rs, rowFilter, rowMapper, null));
    }

    /**
     * Creates an {@link ObjIteratorEx} over the rows of the given {@link ResultSet}, keeping only rows
     * that pass the specified {@link RowFilter} and mapping them with the specified {@link RowMapper}.
     *
     * <p>The iterator propagates database access failures as {@link UncheckedSQLException}; calling
     * {@code next()} after exhaustion throws {@link NoSuchElementException}.</p>
     *
     * @param <T> The type of the mapped row elements.
     * @param resultSet The {@link ResultSet} to iterate over.
     * @param rowFilter The {@link RowFilter} used to filter rows.
     * @param rowMapper The {@link RowMapper} used to map each filtered row.
     * @param onClose An optional action to run when the iterator is closed. Can be {@code null}.
     * @return an iterator over the filtered, mapped rows that remembers exhaustion without advancing the JDBC cursor again
     * @throws IllegalArgumentException if {@code rowFilter} or {@code rowMapper} is {@code null}.
     */
    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper, final Runnable onClose) {
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return new ObjIteratorEx<>() {
            private boolean hasNext;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (!hasNext && !exhausted) {
                    try {
                        while (resultSet.next()) {
                            if (rowFilter.test(resultSet)) {
                                hasNext = true;
                                break;
                            }
                        }

                        exhausted = !hasNext;
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
            public void closeResource() {
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
     * BiRowMapper receives both the ResultSet and column labels, allowing for more flexible mapping.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BiRowMapper<Map<String, Object>> mapMapper = (rs, columnLabels) -> {
     *     Map<String, Object> row = new HashMap<>();
     *     for (String label : columnLabels) {
     *         row.put(label, rs.getObject(label));
     *     }
     *     return row;
     * };
     * try (Stream<Map<String, Object>> rows = JdbcUtil.stream(resultSet, mapMapper)
     *         .onClose(Fn.closeQuietly(resultSet))) {
     *     rows.forEach(row -> System.out.println(row));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param <T> The type of the result extracted from the ResultSet.
     * @param rs The {@link ResultSet} to create a stream from.
     * @param rowMapper The BiRowMapper to apply while extracting data. This mapper receives both the ResultSet and column labels.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code rs} or {@code rowMapper} is {@code null}.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final BiRowMapper<? extends T> rowMapper) throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(rs, rowMapper, null));
    }

    /**
     * Creates an {@link ObjIteratorEx} over the rows of the given {@link ResultSet}, mapping each row
     * with the specified {@link BiRowMapper} (which also receives the column labels).
     *
     * <p>The iterator propagates database access failures as {@link UncheckedSQLException}; calling
     * {@code next()} after exhaustion throws {@link NoSuchElementException}.</p>
     *
     * @param <T> The type of the mapped row elements.
     * @param resultSet The {@link ResultSet} to iterate over.
     * @param rowMapper The {@link BiRowMapper} used to map each row.
     * @param onClose An optional action to run when the iterator is closed. Can be {@code null}.
     * @return an iterator over the mapped rows that remembers exhaustion without advancing the JDBC cursor again
     */
    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final BiRowMapper<? extends T> rowMapper, final Runnable onClose) {
        return new ObjIteratorEx<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (!hasNext && !exhausted) {
                    try {
                        hasNext = resultSet.next();
                        exhausted = !hasNext;
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
                        columnLabels = getColumnLabels(resultSet);
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

                if (n == 0 || exhausted) {
                    return;
                }

                final long rowsToSkip = hasNext ? n - 1 : n;

                try {
                    exhausted = JdbcUtil.skip(resultSet, rowsToSkip) < rowsToSkip;
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                hasNext = false;
            }

            @Override
            public long count() {
                if (exhausted) {
                    return 0;
                }

                long cnt = hasNext ? 1 : 0;
                hasNext = false;

                try {
                    while (resultSet.next()) {
                        cnt++;
                    }

                    exhausted = true;
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }

                return cnt;
            }

            private boolean isClosed = false;

            @Override
            public void closeResource() {
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
     * Both the filter and mapper receive the ResultSet and column labels for maximum flexibility.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BiRowFilter hasNonNullValues = (rs, columnLabels) -> {
     *     for (String label : columnLabels) {
     *         if (rs.getObject(label) != null) return true;
     *     }
     *     return false;
     * };
     * BiRowMapper<String> csvMapper = (rs, columnLabels) -> {
     *     StringBuilder sb = new StringBuilder();
     *     for (String label : columnLabels) {
     *         if (sb.length() > 0) {
     *             sb.append(',');
     *         }
     *         sb.append(rs.getString(label));
     *     }
     *     return sb.toString();
     * };
     *
     * try (Stream<String> rows = JdbcUtil.stream(resultSet, hasNonNullValues, csvMapper)
     *         .onClose(Fn.closeQuietly(resultSet))) {
     *     rows.forEach(csvRow -> System.out.println(csvRow));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param <T> The type of the result extracted from the ResultSet.
     * @param rs The {@link ResultSet} to create a stream from.
     * @param rowFilter The BiRowFilter to apply while filtering rows. Both ResultSet and column labels are provided.
     * @param rowMapper The BiRowMapper to apply while extracting data from filtered rows.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code rs}, {@code rowFilter}, or {@code rowMapper} is {@code null}.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper)
            throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return Stream.of(iterate(rs, rowFilter, rowMapper));
    }

    /**
     * Creates an {@link ObjIteratorEx} over the rows of the given {@link ResultSet}, keeping only rows
     * that pass the specified {@link BiRowFilter} and mapping them with the specified
     * {@link BiRowMapper} (both of which also receive the column labels).
     *
     * <p>The iterator propagates database access failures as {@link UncheckedSQLException}; calling
     * {@code next()} after exhaustion throws {@link NoSuchElementException}.</p>
     *
     * @param <T> The type of the mapped row elements.
     * @param resultSet The {@link ResultSet} to iterate over.
     * @param rowFilter The {@link BiRowFilter} used to filter rows.
     * @param rowMapper The {@link BiRowMapper} used to map each filtered row.
     * @return an iterator over the filtered, mapped rows that remembers exhaustion without advancing the JDBC cursor again
     */
    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper) {
        return new ObjIteratorEx<>() {
            private List<String> columnLabels = null;
            private boolean hasNext;
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                if (columnLabels == null) {
                    try {
                        columnLabels = JdbcUtil.getColumnLabels(resultSet);
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                }

                if (!hasNext && !exhausted) {
                    try {
                        while (resultSet.next()) {
                            if (rowFilter.test(resultSet, columnLabels)) {
                                hasNext = true;
                                break;
                            }
                        }

                        exhausted = !hasNext;
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
     * This is useful when you only need values from a single column.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream all names from the first column
     * try (Stream<String> names = JdbcUtil.stream(resultSet, 1)
     *         .onClose(Fn.closeQuietly(resultSet))) {
     *     names.forEach(name -> System.out.println(name));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param <T> The type of the result extracted from the ResultSet.
     * @param rs The {@link ResultSet} to create a stream from.
     * @param columnIndex The index of the column to extract data from, starting from 1.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if the provided arguments are invalid.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final int columnIndex) throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgPositive(columnIndex, cs.columnIndex);

        final RowMapper<? extends T> rowMapper = new RowMapper<>() {
            private boolean checkDateTypeResolved = false;
            private boolean checkDateType = false;

            @Override
            public T apply(final ResultSet resultSet) throws SQLException {
                if (!checkDateTypeResolved) {
                    // Resolved lazily on the first row (like the columnLabel overload) so an unconsumed stream
                    // costs no metadata round-trip. getColumnValue ignores checkDateType for a ResultSetProxy
                    // (it self-normalizes), so skip the lookup then.
                    checkDateType = !(resultSet instanceof ResultSetProxy) && JdbcUtil.checkDateType(resultSet);
                    checkDateTypeResolved = true;
                }

                return (T) getColumnValue(resultSet, columnIndex, checkDateType);
            }
        };

        return stream(rs, rowMapper);
    }

    /**
     * Creates a stream from the provided ResultSet using the specified column name.
     * This is useful when you only need values from a single column identified by name.
     * It's the user's responsibility to close the input {@code resultSet} after the stream is finished.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream all email addresses
     * try (Stream<String> emails = JdbcUtil.stream(resultSet, "email")
     *         .onClose(Fn.closeQuietly(resultSet))) {
     *     emails.filter(email -> email != null && email.contains("@"))
     *           .forEach(email -> sendNewsletter(email));
     * }
     * }</pre>
     *
     * <p>If the result set has rows but does not contain {@code columnLabel}, traversal throws
     * {@link IllegalArgumentException}.</p>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param <T> The type of the result extracted from the ResultSet.
     * @param rs The {@link ResultSet} to create a stream from.
     * @param columnLabel The label (or name) of the column to extract data from.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if the provided arguments are invalid.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final String columnLabel) throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotEmpty(columnLabel, cs.columnLabel);

        final RowMapper<? extends T> rowMapper = new RowMapper<>() {
            private int columnIndex = 0;
            private boolean checkDateType = true;

            @Override
            public T apply(final ResultSet resultSet) throws SQLException {
                if (columnIndex == 0) {
                    columnIndex = getColumnIndex(resultSet, columnLabel);

                    if (columnIndex < 1) {
                        throw new IllegalArgumentException("Column not found: '" + columnLabel + "'");
                    }

                    // getColumnValue ignores checkDateType for a ResultSetProxy (it self-normalizes), so skip the metadata lookup then.
                    checkDateType = !(resultSet instanceof ResultSetProxy) && JdbcUtil.checkDateType(resultSet);
                }

                return (T) getColumnValue(resultSet, columnIndex, checkDateType);
            }
        };

        return stream(rs, rowMapper);
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream of Dataset.
     * This is useful when executing stored procedures that return multiple result sets.
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (CallableStatement stmt = conn.prepareCall("{call sp_get_multiple_results()}")) {
     *     stmt.execute(); // the statement must be executed before its result sets can be streamed
     *     try (Stream<Dataset> datasets = JdbcUtil.streamAllResultSets(stmt)) {
     *         datasets.forEach(dataset -> {
     *             System.out.println("Result set with " + dataset.size() + " rows");
     *             dataset.println();
     *         });
     *     }
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param stmt The Statement to extract ResultSets from.
     * @return A Stream of Dataset containing the extracted ResultSets.
     * @throws IllegalArgumentException if {@code stmt} is {@code null}.
     */
    public static Stream<Dataset> streamAllResultSets(final Statement stmt) throws IllegalArgumentException {
        return streamAllResultSets(stmt, ResultExtractor.TO_DATASET);
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream.
     * Each ResultSet is processed by the provided ResultExtractor.
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished.
     * Closing the returned stream closes any result set that the iterator advanced to but did not
     * deliver because traversal stopped early; it does not close {@code stmt}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultExtractor<List<String>> namesExtractor = rs -> {
     *     List<String> names = new ArrayList<>();
     *     while (rs.next()) {
     *         names.add(rs.getString("name"));
     *     }
     *     return names;
     * };
     *
     * try (Stream<List<String>> resultSets = JdbcUtil.streamAllResultSets(stmt, namesExtractor)
     *         .onClose(Fn.closeQuietly(stmt))) {
     *     resultSets.forEach(names -> System.out.println("Found " + names.size() + " names"));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * <p>Traversal throws {@link UnsupportedOperationException} if the extractor returns a {@link ResultSet},
     * which cannot be retained after its owning query is closed.</p>
     *
     * @param <R> The type of the result extracted from the ResultSet.
     * @param stmt The Statement to extract ResultSets from.
     * @param resultExtractor The ResultExtractor to apply while extracting data from each ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code stmt} or {@code resultExtractor} is {@code null}.
     */
    @SuppressWarnings("resource")
    public static <R> Stream<R> streamAllResultSets(final Statement stmt, final ResultExtractor<R> resultExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(stmt, cs.stmt);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));

        return Stream.just(supplier)
                .onClose(() -> supplier.get().closeResource())
                .flatMap(it -> Stream.of(it.get()))
                .map(Fn.ff(rs -> extractAndCloseResultSet(rs, resultExtractor)));
    }

    /**
     * Extracts all ResultSets from the provided Statement and returns them as a Stream.
     * Each ResultSet is processed by the provided BiResultExtractor which also receives column labels.
     * It's the user's responsibility to close the input {@code stmt} after the stream is finished.
     * Closing the returned stream closes any result set that the iterator advanced to but did not
     * deliver because traversal stopped early; it does not close {@code stmt}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BiResultExtractor<Map<String, List<Object>>> columnarExtractor = (rs, columnLabels) -> {
     *     Map<String, List<Object>> columns = new HashMap<>();
     *     for (String label : columnLabels) {
     *         columns.put(label, new ArrayList<>());
     *     }
     *     while (rs.next()) {
     *         for (String label : columnLabels) {
     *             columns.get(label).add(rs.getObject(label));
     *         }
     *     }
     *     return columns;
     * };
     *
     * try (Stream<Map<String, List<Object>>> resultSets = JdbcUtil.streamAllResultSets(stmt, columnarExtractor)
     *         .onClose(Fn.closeQuietly(stmt))) {
     *     resultSets.forEach(columnsMap -> columnsMap.forEach((col, values) ->
     *         System.out.println(col + ": " + values.size() + " values")));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * <p>Traversal throws {@link UnsupportedOperationException} if the extractor returns a {@link ResultSet},
     * which cannot be retained after its owning query is closed.</p>
     *
     * @param <R> The type of the result extracted from the ResultSet.
     * @param stmt The Statement to extract ResultSets from.
     * @param resultExtractor The BiResultExtractor to apply while extracting data.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code stmt} or {@code resultExtractor} is {@code null}.
     */
    @SuppressWarnings("resource")
    public static <R> Stream<R> streamAllResultSets(final Statement stmt, final BiResultExtractor<R> resultExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(stmt, cs.stmt);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final Supplier<ObjIteratorEx<ResultSet>> supplier = Fn.memoize(() -> iterateAllResultSets(stmt, true));

        return Stream.just(supplier)
                .onClose(() -> supplier.get().closeResource())
                .flatMap(it -> Stream.of(it.get()))
                .map(Fn.ff(rs -> extractAndCloseResultSet(rs, resultExtractor)));
    }

    /**
     * Creates an {@link ObjIteratorEx} over all result sets of an executed {@link Statement} (for stored
     * procedures or statements returning multiple result sets). Closing the iterator closes any result
     * set it advanced to but did not deliver.
     *
     * <p>The iterator propagates database access failures as {@link UncheckedSQLException}; calling
     * {@code next()} after exhaustion throws {@link NoSuchElementException}.</p>
     *
     * @param stmt The executed {@link Statement} to extract result sets from.
     * @param isFirstResultSet Whether the statement's first result is a result set.
     * @return An {@link ObjIteratorEx} over the statement's result sets.
     */
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
                                final ResultSet currentRs = stmt.getResultSet();
                                isNextResultSet = false;

                                if (currentRs != null) {
                                    // Wrapped for parity with single-ResultSet reads (executeQuery): consumers see
                                    // normalized values (Oracle TIMESTAMP -> java.sql.Timestamp, materialized LOBs).
                                    resultSetHolder.setValue(ResultSetProxy.wrap(currentRs));
                                    break;
                                }
                                // First-result claim was wrong (driver returned null, meaning the
                                // first result was actually an update count or no result). Fall
                                // through to advance via getMoreResults/getUpdateCount below
                                // instead of terminating the iteration early.
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

                final ResultSet rs = resultSetHolder.getAndSet(null);

                try {
                    isNextResultSet = stmt.getMoreResults(Statement.KEEP_CURRENT_RESULT);
                } catch (final SQLException e) {
                    // The caller never received `rs`, so they cannot close it. Release it
                    // here instead of leaking it to be cleaned up when the statement closes.
                    closeQuietly(rs);
                    throw new UncheckedSQLException(e);
                }

                return rs;
            }

            private boolean isClosed = false;

            @Override
            public void closeResource() {
                if (isClosed) {
                    return;
                }

                isClosed = true;

                if (resultSetHolder.isNotNull()) {
                    JdbcUtil.closeQuietly(resultSetHolder.getAndSet(null));
                } else if (isNextResultSet && !noMoreResult) {
                    // next() advances with KEEP_CURRENT_RESULT before returning the prior result set.
                    // If traversal stops at that point, the newly-current result set has not yet
                    // been put in resultSetHolder and would otherwise remain open until the caller
                    // eventually closes the Statement.
                    try {
                        JdbcUtil.closeQuietly(stmt.getResultSet());
                    } catch (final SQLException e) {
                        logger.warn(e, "Failed to close unconsumed ResultSet while closing result-set iterator");
                    }
                }

                isNextResultSet = false;
                noMoreResult = true;
            }
        };
    }

    /**
     * Returns whether the given iterator has another result set, unwrapping any
     * {@link UncheckedSQLException} thrown by the iterator into an {@link SQLException}.
     *
     * @param iter The result-set iterator to test.
     * @return {@code true} if another result set is available.
     * @throws SQLException if a database access error occurs.
     */
    static boolean hasNextResultSet(final ObjIteratorEx<ResultSet> iter) throws SQLException {
        try {
            return iter.hasNext();
        } catch (final UncheckedSQLException e) {
            throw e.getCause();
        }
    }

    /**
     * Returns the next result set from the given iterator, unwrapping any {@link UncheckedSQLException}
     * thrown by the iterator into an {@link SQLException}.
     *
     * @param iter The result-set iterator to advance.
     * @return The next {@link ResultSet}.
     * @throws SQLException if a database access error occurs.
     */
    static ResultSet nextResultSet(final ObjIteratorEx<ResultSet> iter) throws SQLException {
        try {
            return iter.next();
        } catch (final UncheckedSQLException e) {
            throw e.getCause();
        }
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * The query must be ordered by at least one key/id and have a result size limitation (e.g., LIMIT pageSize).
     * This method is useful for processing large result sets in manageable chunks.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String query = "SELECT * FROM users WHERE id > ? ORDER BY id LIMIT 1000";
     * JdbcUtil.queryByPage(dataSource, query, 1000, (preparedQuery, previousPage) -> {
     *     if (previousPage == null) {
     *         preparedQuery.setLong(1, 0);
     *     } else {
     *         long lastId = previousPage.moveToRow(previousPage.size() - 1).getLong("id");
     *         preparedQuery.setLong(1, lastId);
     *     }
     * }).forEach(page -> {
     *     System.out.println("Processing " + page.size() + " records");
     *     // Process the page
     * });
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL query to run for each page. Must include ORDER BY and LIMIT/FETCH clauses.
     * @param pageSize The number of rows to fetch per page.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the {@link Dataset} returned by the previous page (or {@code null} for the first page).
     * @return A Stream of Dataset, each representing a page of results.
     * @throws IllegalArgumentException if {@code ds} or {@code parametersSetter} is {@code null}, {@code sql} is empty, or {@code pageSize} is not positive.
     */
    @SuppressWarnings("rawtypes")
    public static Stream<Dataset> queryByPage(final javax.sql.DataSource ds, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, Dataset> parametersSetter) {
        N.checkArgNotNull(parametersSetter, cs.parametersSetter);

        return queryByPage(ds, sql, pageSize, parametersSetter, Jdbc.ResultExtractor.TO_DATASET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * The query must be ordered by at least one key/id and have a result size limitation.
     * Each page is processed by the provided ResultExtractor.
     *
     * <p>The stream ends when a page's extracted result is empty: a {@code Dataset}, {@code Collection},
     * {@code Map}, {@code Iterable} or {@code Iterator} result is checked for emptiness; any other
     * non-null result is treated as non-empty. The extractor should therefore return a container of the
     * page's rows — with a scalar result the stream never ends on its own.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String query = "SELECT * FROM orders WHERE order_id > ? ORDER BY order_id LIMIT 500";
     * ResultExtractor<List<Order>> ordersExtractor = rs -> {
     *     List<Order> orders = new ArrayList<>();
     *     while (rs.next()) {
     *         orders.add(new Order(rs.getLong("order_id"), rs.getDate("order_date")));
     *     }
     *     return orders;
     * };
     *
     * JdbcUtil.queryByPage(dataSource, query, 500, (preparedQuery, previousOrders) -> {
     *     if (previousOrders == null) {
     *         preparedQuery.setLong(1, 0L);
     *     } else {
     *         Order lastOrder = previousOrders.get(previousOrders.size() - 1);
     *         preparedQuery.setLong(1, lastOrder.getOrderId());
     *     }
     * }, ordersExtractor)
     * .forEach(orders -> processOrderBatch(orders));
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * <p>Traversal throws {@link UnsupportedOperationException} if the extractor returns a {@link ResultSet},
     * which cannot be retained after its owning query is closed.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL query to run for each page.
     * @param pageSize The number of rows to fetch per page.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The ResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code ds} , {@code parametersSetter} , or {@code resultExtractor} is {@code null} , {@code sql} is empty,
     *         or {@code pageSize} is not positive.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.ResultExtractor<R> resultExtractor) {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(pageSize, cs.pageSize);
        N.checkArgNotNull(parametersSetter, cs.parametersSetter);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final boolean isNamedQuery = ParsedSql.parse(sql).namedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, sql) : JdbcUtil.prepareQuery(ds, sql)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), parametersSetter)
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
     * The query must be ordered by at least one key/id and have a result size limitation.
     * Each page is processed by the provided BiResultExtractor.
     *
     * <p>The stream ends when a page's extracted result is empty: a {@code Dataset}, {@code Collection},
     * {@code Map}, {@code Iterable} or {@code Iterator} result is checked for emptiness; any other
     * non-null result is treated as non-empty. The extractor should therefore return a container of the
     * page's rows — with a scalar result the stream never ends on its own.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Keyset pagination: extract each page with a BiResultExtractor and use the last id as the cursor.
     * String query = "SELECT id, name FROM item WHERE id > ? ORDER BY id LIMIT 500";
     * Jdbc.BiResultExtractor<List<Long>> toIds = (rs, columnLabels) -> {
     *     List<Long> ids = new ArrayList<>();
     *     while (rs.next()) {
     *         ids.add(rs.getLong("id"));
     *     }
     *     return ids;
     * };
     *
     * JdbcUtil.queryByPage(dataSource, query, 500, (preparedQuery, previousIds) -> {
     *     long lastId = (previousIds == null || previousIds.isEmpty()) ? 0L : previousIds.get(previousIds.size() - 1);
     *     preparedQuery.setLong(1, lastId);   // first page: id > 0, then id > lastId
     * }, toIds)
     * .forEach(ids -> System.out.println("Fetched " + ids.size() + " ids"));
     * // Iteration stops automatically once a page comes back empty.
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * <p>Traversal throws {@link UnsupportedOperationException} if the extractor returns a {@link ResultSet},
     * which cannot be retained after its owning query is closed.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL query to run for each page.
     * @param pageSize The number of rows to fetch per page.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The BiResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code ds} , {@code parametersSetter} , or {@code resultExtractor} is {@code null} , {@code sql} is empty,
     *         or {@code pageSize} is not positive.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(pageSize, cs.pageSize);
        N.checkArgNotNull(parametersSetter, cs.parametersSetter);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final boolean isNamedQuery = ParsedSql.parse(sql).namedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(ds, sql) : JdbcUtil.prepareQuery(ds, sql)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), parametersSetter)
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
     * Similar to the DataSource version but uses an existing Connection.
     * The query must be ordered by at least one key/id and have a result size limitation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Page through results on an existing Connection; each page is a Dataset.
     * String query = "SELECT id, name FROM item WHERE id > ? ORDER BY id LIMIT 1000";
     * try (Connection conn = dataSource.getConnection()) {
     *     JdbcUtil.queryByPage(conn, query, 1000, (preparedQuery, previousPage) -> {
     *         if (previousPage == null) {
     *             preparedQuery.setLong(1, 0L);                       // first page: id > 0
     *         } else {
     *             // Read the id from the last row of the previous page.
     *             long lastId = previousPage.moveToRow(previousPage.size() - 1).getLong("id");
     *             preparedQuery.setLong(1, lastId);                   // next page: id > lastId
     *         }
     *     }).forEach(page -> System.out.println("Processing " + page.size() + " records"));
     * }
     * // An empty table yields zero pages.
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * @param conn The database {@link Connection} to use for queries.
     * @param sql The SQL query to run for each page.
     * @param pageSize The number of rows to fetch per page.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the {@link Dataset} returned by the previous page (or {@code null} for the first page).
     * @return A Stream of Dataset, each representing a page of results.
     * @throws IllegalArgumentException if {@code conn} or {@code parametersSetter} is {@code null}, {@code sql} is empty, or {@code pageSize} is not positive.
     */
    @SuppressWarnings("rawtypes")
    public static Stream<Dataset> queryByPage(final Connection conn, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, Dataset> parametersSetter) {
        N.checkArgNotNull(parametersSetter, cs.parametersSetter);

        return queryByPage(conn, sql, pageSize, parametersSetter, Jdbc.ResultExtractor.TO_DATASET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * Similar to the DataSource version but uses an existing Connection.
     * Each page is processed by the provided ResultExtractor.
     *
     * <p>The stream ends when a page's extracted result is empty: a {@code Dataset}, {@code Collection},
     * {@code Map}, {@code Iterable} or {@code Iterator} result is checked for emptiness; any other
     * non-null result is treated as non-empty. The extractor should therefore return a container of the
     * page's rows — with a scalar result the stream never ends on its own.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Page through results on a Connection, extracting each page with a ResultExtractor.
     * String query = "SELECT id, name FROM item WHERE id > ? ORDER BY id LIMIT 500";
     * Jdbc.ResultExtractor<List<Long>> toIds = rs -> {
     *     List<Long> ids = new ArrayList<>();
     *     while (rs.next()) {
     *         ids.add(rs.getLong("id"));
     *     }
     *     return ids;
     * };
     *
     * try (Connection conn = dataSource.getConnection()) {
     *     JdbcUtil.queryByPage(conn, query, 500, (preparedQuery, previousIds) -> {
     *         long lastId = (previousIds == null || previousIds.isEmpty()) ? 0L : previousIds.get(previousIds.size() - 1);
     *         preparedQuery.setLong(1, lastId);
     *     }, toIds)
     *     .forEach(ids -> System.out.println("Got " + ids.size() + " ids"));
     * }
     * // Iteration stops when a page returns no rows.
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * <p>Traversal throws {@link UnsupportedOperationException} if the extractor returns a {@link ResultSet},
     * which cannot be retained after its owning query is closed.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param conn The database {@link Connection} to use for queries.
     * @param sql The SQL query to run for each page.
     * @param pageSize The number of rows to fetch per page.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The ResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code conn} , {@code parametersSetter} , or {@code resultExtractor} is {@code null} , {@code sql} is
     *         empty, or {@code pageSize} is not positive.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.ResultExtractor<R> resultExtractor) {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(pageSize, cs.pageSize);
        N.checkArgNotNull(parametersSetter, cs.parametersSetter);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final boolean isNamedQuery = ParsedSql.parse(sql).namedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, sql) : JdbcUtil.prepareQuery(conn, sql)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), parametersSetter)
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
     * Similar to the DataSource version but uses an existing Connection.
     * Each page is processed by the provided BiResultExtractor.
     *
     * <p>The stream ends when a page's extracted result is empty: a {@code Dataset}, {@code Collection},
     * {@code Map}, {@code Iterable} or {@code Iterator} result is checked for emptiness; any other
     * non-null result is treated as non-empty. The extractor should therefore return a container of the
     * page's rows — with a scalar result the stream never ends on its own.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Page through results on a Connection using a BiResultExtractor (also receives the column labels).
     * String query = "SELECT id, name FROM item WHERE id > ? ORDER BY id LIMIT 500";
     * Jdbc.BiResultExtractor<List<Long>> toIds = (rs, columnLabels) -> {
     *     List<Long> ids = new ArrayList<>();
     *     while (rs.next()) {
     *         ids.add(rs.getLong("id"));
     *     }
     *     return ids;
     * };
     *
     * try (Connection conn = dataSource.getConnection()) {
     *     JdbcUtil.queryByPage(conn, query, 500, (preparedQuery, previousIds) -> {
     *         long lastId = (previousIds == null || previousIds.isEmpty()) ? 0L : previousIds.get(previousIds.size() - 1);
     *         preparedQuery.setLong(1, lastId);
     *     }, toIds)
     *     .forEach(ids -> System.out.println("Fetched " + ids.size() + " ids"));
     * }
     * }</pre>
     *
     * <p>Database access errors encountered while traversing the returned stream are propagated as
     * {@link UncheckedSQLException}.</p>
     *
     * <p>Traversal throws {@link UnsupportedOperationException} if the extractor returns a {@link ResultSet},
     * which cannot be retained after its owning query is closed.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param conn The database {@link Connection} to use for queries.
     * @param sql The SQL query to run for each page.
     * @param pageSize The number of rows to fetch per page.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The BiResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code conn} , {@code parametersSetter} , or {@code resultExtractor} is {@code null} , {@code sql} is
     *         empty, or {@code pageSize} is not positive.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.BiResultExtractor<R> resultExtractor) {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(pageSize, cs.pageSize);
        N.checkArgNotNull(parametersSetter, cs.parametersSetter);
        N.checkArgNotNull(resultExtractor, cs.resultExtractor);

        final boolean isNamedQuery = ParsedSql.parse(sql).namedParameters().size() > 0;

        return Stream.of(Holder.of((R) null)) //
                .cycled()
                .map(it -> {
                    try {
                        final R ret = (isNamedQuery ? JdbcUtil.prepareNamedQuery(conn, sql) : JdbcUtil.prepareQuery(conn, sql)) //
                                .setFetchDirectionToForward()
                                .setFetchSize(pageSize)
                                .settParameters(it.value(), parametersSetter)
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
     * Returns whether the given page result is non-empty. A {@code Dataset}, {@link Collection},
     * {@link Map}, {@link Iterable}, or {@link Iterator} is checked for emptiness; any other non-null
     * result is treated as non-empty.
     *
     * @param ret The page result to test. Can be {@code null}.
     * @return {@code true} if {@code ret} is non-null and non-empty.
     */
    @SuppressWarnings("rawtypes")
    static boolean isNotEmptyResult(final Object ret) {
        if (ret == null) {
            return false;
        }

        if (ret instanceof Dataset) {
            return N.notEmpty((Dataset) ret);
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

    /**
     * Verifies that the given extraction result is not a {@link ResultSet} and returns it.
     *
     * @param <R> The type of the extraction result.
     * @param result The extraction result to check.
     * @return The given {@code result}.
     * @throws UnsupportedOperationException if {@code result} is a {@link ResultSet}.
     */
    static <R> R checkNotResultSet(final R result) {
        if (result instanceof ResultSet) {
            throw new UnsupportedOperationException("The result value of ResultExtractor/BiResultExtractor.apply cannot be ResultSet");
        }

        return result;
    }

    /**
     * Returns whether column values read from the given {@link ResultSet} should be checked for
     * database-specific date/time types (i.e., whether the underlying database is Oracle).
     *
     * @param rs The {@link ResultSet} to check.
     * @return {@code true} if date/time values should be normalized, or if the check fails.
     */
    static boolean checkDateType(final ResultSet rs) {
        try {
            return checkDateType(rs.getStatement());
        } catch (final Exception e) {
            return true;
        }
    }

    /**
     * Returns whether values read through the given {@link Statement} should be checked for
     * database-specific date/time types (i.e., whether the underlying database is Oracle).
     *
     * @param stmt The {@link Statement} to check.
     * @return {@code true} if date/time values should be normalized, or if the check fails.
     * @throws UncheckedSQLException if reading the connection's database product metadata fails; failures from
     *         {@link Statement#getConnection()} are caught and result in {@code true}.
     */
    static boolean checkDateType(final Statement stmt) {
        try {
            return Strings.containsIgnoreCase(JdbcUtil.getDBProductInfo(stmt.getConnection()).name(), "Oracle");
        } catch (final SQLException e) {
            return true;
        }
    }

    /**
     * Reads the value of a stored procedure's out parameter from a {@link CallableStatement}.
     */
    interface OutParameterGetter {

        /**
         * Reads the out parameter at the given 1-based parameter index.
         *
         * @param stmt The {@link CallableStatement} to read from.
         * @param outParameterIndex The 1-based index of the out parameter.
         * @return The out parameter value.
         * @throws SQLException if a database access error occurs.
         */
        Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException;

        /**
         * Reads the out parameter with the given parameter name.
         *
         * @param stmt The {@link CallableStatement} to read from.
         * @param outParameterName The name of the out parameter.
         * @return The out parameter value.
         * @throws SQLException if a database access error occurs.
         */
        Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException;
    }

    /**
     * Maps {@link java.sql.Types} constants to the {@link OutParameterGetter} that reads the matching
     * typed value from a {@link CallableStatement}.
     */
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
                return stmt.getBoolean(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getBoolean(outParameterName);
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
            // Per JDBC spec (Appendix B), SQL FLOAT is an 8-byte, double-precision type that maps to
            // Java double (it is the synonym of Types.DOUBLE); SQL REAL is the 4-byte type that maps to
            // Java float. Reading a Types.FLOAT out parameter with getFloat() would narrow the value to
            // single precision (losing precision and overflowing to Float.POSITIVE_INFINITY beyond ~3.4e38).
            // Use getDouble() for parity with the Types.DOUBLE getter and the input-side convention in
            // AbstractQuery.setFloat (which uses Types.REAL for Java float).
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getDouble(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getDouble(outParameterName);
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

        sqlTypeGetterMap.put(Types.REAL, new OutParameterGetter() {
            @Override
            public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
                return stmt.getFloat(outParameterIndex);
            }

            @Override
            public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
                return stmt.getFloat(outParameterName);
            }
        });

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

        sqlTypeGetterMap.put(Types.NUMERIC, sqlTypeGetterMap.get(Types.DECIMAL));

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

    /**
     * Fallback {@link OutParameterGetter} reading via {@code CallableStatement.getObject}, used for SQL types
     * absent from {@link #sqlTypeGetterMap}.
     */
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
     * Checks whether a table exists in the database referenced by the given {@link javax.sql.DataSource}.
     *
     * <p>The lookup first consults {@link DatabaseMetaData#getTables} (trying the connection's current schema
     * — and, for an unqualified name, all schemas — using the driver's canonical storage case for undelimited parts.
     * Delimited and case-folded parts are matched case-sensitively; upper- and lower-case table variants
     * are tried only when the driver reports no canonical identifier case).
     * Schema and table parts are treated as literal names, with metadata search-pattern characters escaped.
     * If metadata lookup yields no match and all parts are unquoted simple identifiers, the method falls back to
     * executing {@code SELECT 1 FROM <table> WHERE 1 > 2} — a SQL error from that query
     * that is recognized as a "table not found" error (by SQLState, vendor error code, or message) returns
     * {@code false}; any other SQL error is propagated.</p>
     *
     * <p>The {@code tableName} may be a simple identifier or a qualified name like {@code schema.table}
     * or {@code catalog.schema.table}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.tableExists(ds, "users")) {
     *     System.out.println("Users table exists");
     * } else {
     *     System.out.println("Users table does not exist");
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to obtain a connection from; must not be {@code null}.
     * @param tableName The table name (optionally qualified, e.g., {@code schema.table} or {@code catalog.schema.table}); must not be blank.
     * @return {@code true} if the table exists, {@code false} otherwise.
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or if {@code tableName} is blank or otherwise invalid.
     * @throws UncheckedSQLException if a database error occurs that is not a "table not found" error.
     * @see #tableExists(Connection, String)
     */
    public static boolean tableExists(final javax.sql.DataSource ds, final String tableName) {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotBlank(tableName, cs.tableName);

        Connection conn = null;

        try {
            conn = getConnection(ds);
            return tableExists(conn, tableName);
        } finally {
            releaseConnection(conn, ds);
        }
    }

    /**
     * Checks whether a table exists on the given {@link Connection}.
     *
     * <p>The lookup first consults {@link DatabaseMetaData#getTables} (trying the connection's current schema
     * — and, for an unqualified name, all schemas — using the driver's canonical storage case for undelimited parts.
     * Delimited and case-folded parts are matched case-sensitively; upper- and lower-case table variants
     * are tried only when the driver reports no canonical identifier case).
     * If metadata lookup yields no match and all parts are unquoted simple identifiers, the method falls back to
     * executing {@code SELECT 1 FROM <table> WHERE 1 > 2} — a SQL error from that query
     * that is recognized as a "table not found" error (by SQLState, vendor error code, or message) returns
     * {@code false}; any other SQL error is propagated.</p>
     *
     * <p>The {@code tableName} may be a simple identifier or a qualified name like {@code schema.table}
     * or {@code catalog.schema.table}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.tableExists(connection, "users")) {
     *     System.out.println("Users table exists");
     * } else {
     *     System.out.println("Users table does not exist");
     * }
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for checking table existence.
     * @param tableName The table name (optionally qualified); must not be blank.
     * @return {@code true} if the table exists, {@code false} otherwise.
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code tableName} is blank or otherwise invalid.
     * @throws UncheckedSQLException if a database error occurs that is not a "table not found" error.
     */
    public static boolean tableExists(final Connection conn, final String tableName) {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(tableName, cs.tableName);

        try {
            final String[] nameParts = splitQualifiedSqlIdentifier(tableName, cs.tableName);
            final boolean[] delimitedNameParts = SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(tableName, nameParts.length);
            final DatabaseMetaData metadata = conn.getMetaData();
            normalizeMetadataIdentifierParts(metadata, nameParts, delimitedNameParts);
            final String catalog;
            final String schema;
            final String table;
            final boolean catalogDelimited;
            final boolean schemaDelimited;
            final boolean tableDelimited;

            if (nameParts.length == 1) {
                catalog = conn.getCatalog();
                schema = null;
                table = nameParts[0];
                catalogDelimited = false;
                schemaDelimited = false;
                tableDelimited = delimitedNameParts[0];
            } else if (nameParts.length == 2) {
                catalog = conn.getCatalog();
                schema = nameParts[0];
                table = nameParts[1];
                catalogDelimited = false;
                schemaDelimited = delimitedNameParts[0];
                tableDelimited = delimitedNameParts[1];
            } else if (nameParts.length == 3) {
                catalog = nameParts[0];
                schema = nameParts[1];
                table = nameParts[2];
                catalogDelimited = delimitedNameParts[0];
                schemaDelimited = delimitedNameParts[1];
                tableDelimited = delimitedNameParts[2];
            } else {
                throw new IllegalArgumentException("Invalid table name: " + tableName);
            }

            if (Strings.isEmpty(table)) {
                throw new IllegalArgumentException("Invalid table name: " + tableName);
            }

            String schemaToUse = schema;

            if (schemaToUse == null) {
                try {
                    schemaToUse = conn.getSchema();
                } catch (final SQLException e) {
                    // Connection.getSchema() is JDBC 4.1 and optional; fall back to a schema-less lookup.
                }
            }

            // The null-schema retries broaden an unqualified lookup to all schemas; they only add anything
            // when a current schema was actually used above.
            final boolean retryWithAllSchemas = schema == null && schemaToUse != null;
            final String upperCaseTable = table.toUpperCase(Locale.ROOT);
            final String lowerCaseTable = table.toLowerCase(Locale.ROOT);

            if (tableExists(metadata, catalog, schemaToUse, table, catalogDelimited, schemaDelimited, tableDelimited)
                    || (retryWithAllSchemas && tableExists(metadata, catalog, null, table, catalogDelimited, false, tableDelimited))) {
                return true;
            }

            if (!tableDelimited && (tableExists(metadata, catalog, schemaToUse, upperCaseTable, catalogDelimited, schemaDelimited, false)
                    || tableExists(metadata, catalog, schemaToUse, lowerCaseTable, catalogDelimited, schemaDelimited, false))) {
                return true;
            }

            if (!tableDelimited && retryWithAllSchemas && (tableExists(metadata, catalog, null, upperCaseTable, catalogDelimited, false, false)
                    || tableExists(metadata, catalog, null, lowerCaseTable, catalogDelimited, false, false))) {
                return true;
            }

            // Use the current catalog only to scope metadata lookup. The fallback SQL must retain the
            // caller's original qualification: on databases such as PostgreSQL and SQL Server,
            // "catalog.table" is not equivalent to an unqualified table in the current catalog.
            final String safeQualifiedTableName = buildSimpleQualifiedTableName(nameParts.length == 3 ? catalog : null, schema, table);

            // splitQualifiedSqlIdentifier removes delimiters. Never feed those stripped parts to
            // unquoted fallback SQL: "mixedCase" and mixedCase can identify different tables.
            if (!hasDelimitedIdentifierPart(tableName) && Strings.isNotEmpty(safeQualifiedTableName)) {
                try {
                    execute(conn, "SELECT 1 FROM " + safeQualifiedTableName + " WHERE 1 > 2");
                    return true;
                } catch (final SQLException e) {
                    if (isTableNotExistsException(e)) {
                        return false;
                    }

                    throw e;
                }
            }

            return false;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Tests whether {@link DatabaseMetaData#getTables(String, String, String, String[])} returns the requested table.
     * Pattern wildcard matches are verified against the concrete metadata row; explicitly delimited identifier
     * parts request case-sensitive comparison even when their text contains no wildcard.
     *
     * @param metadata The database metadata to query.
     * @param catalog The requested catalog, or {@code null} to accept any catalog.
     * @param schemaPattern The requested schema, or {@code null} to accept any schema.
     * @param tableNamePattern The requested table name.
     * @param catalogCaseSensitive Whether the catalog comparison is case-sensitive.
     * @param schemaCaseSensitive Whether the schema comparison is case-sensitive.
     * @param tableCaseSensitive Whether the table comparison is case-sensitive.
     * @return {@code true} if a matching table is reported; otherwise {@code false}.
     * @throws SQLException if a database access error occurs.
     */
    private static boolean tableExists(final DatabaseMetaData metadata, final String catalog, final String schemaPattern, final String tableNamePattern,
            final boolean catalogCaseSensitive, final boolean schemaCaseSensitive, final boolean tableCaseSensitive) throws SQLException {
        final ResultSet rs = metadata.getTables(catalog, escapeMetadataPattern(metadata, schemaPattern), escapeMetadataPattern(metadata, tableNamePattern),
                null);

        if (rs == null) {
            return false;
        }

        // DatabaseMetaData.getTables treats '_' and '%' in the schemaPattern/tableNamePattern arguments
        // as wildcards. A bare rows.next() can therefore return true for an unrelated table whose name
        // (or schema) happens to match the wildcard expansion (e.g., looking up "users_log" matches
        // "usersXlog"; looking up "my_app.users" matches "myXapp.users"). When either argument contains
        // a wildcard meta-character, verify the returned TABLE_NAME and TABLE_SCHEM actually match
        // (case-insensitive) the requested values. Explicitly delimited identifiers must also be
        // compared to the returned metadata exactly, even when no wildcard is present.
        final boolean tableNameHasWildcard = tableNamePattern != null && (tableNamePattern.indexOf('_') >= 0 || tableNamePattern.indexOf('%') >= 0);
        final boolean schemaHasWildcard = schemaPattern != null && (schemaPattern.indexOf('_') >= 0 || schemaPattern.indexOf('%') >= 0);
        final boolean verifyTableName = tableNameHasWildcard || tableCaseSensitive;
        final boolean verifySchema = schemaHasWildcard || schemaCaseSensitive;

        try (ResultSet rows = rs) {
            if (!verifyTableName && !verifySchema && !catalogCaseSensitive) {
                return rows.next();
            }

            while (rows.next()) {
                if (verifyTableName) {
                    final String tableNameInMetadata = rows.getString("TABLE_NAME");

                    if (!identifierMatches(tableNamePattern, tableNameInMetadata, tableCaseSensitive)) {
                        continue;
                    }
                }

                if (verifySchema) {
                    final String schemaInMetadata = rows.getString("TABLE_SCHEM");

                    if (!identifierMatches(schemaPattern, schemaInMetadata, schemaCaseSensitive)) {
                        continue;
                    }
                }

                if (catalogCaseSensitive) {
                    final String catalogInMetadata = rows.getString("TABLE_CAT");

                    if (!identifierMatches(catalog, catalogInMetadata, true)) {
                        continue;
                    }
                }

                return true;
            }

            return false;
        }
    }

    /**
     * Assembles a dotted {@code catalog.schema.table} name from the non-empty parts.
     *
     * @param catalog The catalog part, or {@code null}/empty to omit.
     * @param schema The schema part, or {@code null}/empty to omit.
     * @param tableName The table name part; required.
     * @return The qualified name, or {@code null} if any present part cannot be embedded in SQL safely without quoting
     *         (so callers skip the SQL-based existence probe).
     */
    private static String buildSimpleQualifiedTableName(final String catalog, final String schema, final String tableName) {
        if (!isUnquotedSafeIdentifier(tableName)) {
            return null;
        }

        final StringBuilder sb = new StringBuilder(64);

        if (Strings.isNotEmpty(catalog)) {
            if (!isUnquotedSafeIdentifier(catalog)) {
                return null;
            }

            sb.append(catalog).append('.');
        }

        if (Strings.isNotEmpty(schema)) {
            if (!isUnquotedSafeIdentifier(schema)) {
                return null;
            }

            sb.append(schema).append('.');
        }

        sb.append(tableName);

        return sb.toString();
    }

    /**
     * Tests whether an identifier can be embedded in generated SQL without delimiters. Deliberately
     * broader than {@link SqlIdentifierUtil#isSimpleSqlIdentifier(String)} (which decides how a
     * user-supplied name is <i>rendered</i>): here the only requirement is that the text cannot carry
     * SQL punctuation into a statement this class assembles for a metadata probe.
     */
    private static boolean isUnquotedSafeIdentifier(final String identifier) {
        if (Strings.isEmpty(identifier)) {
            return false;
        }

        for (int i = 0, len = identifier.length(); i < len; i++) {
            final char ch = identifier.charAt(i);

            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) {
                return false;
            }
        }

        return true;
    }

    /**
     * Splits a one-, two-, or three-part SQL identifier, preserving dots inside delimited parts and
     * unescaping doubled delimiters. Once a part's opening delimiter ({@code "}, {@code `}, or
     * {@code [}) is closed, only whitespace or the separating dot may follow it.
     * @throws IllegalArgumentException if {@code qualifiedName} is {@code null}, blank, has empty components,
     *         unclosed or misplaced delimiters, or more than three components.
     */
    static String[] splitQualifiedSqlIdentifier(final String qualifiedName, final String argName) {
        N.checkArgNotBlank(qualifiedName, argName);

        final String trimmed = qualifiedName.trim();
        final List<String> parts = new ArrayList<>(3);
        final StringBuilder sb = new StringBuilder(trimmed.length());
        char closingQuote = 0;
        boolean quotedPartClosed = false;

        for (int i = 0, len = trimmed.length(); i < len; i++) {
            final char ch = trimmed.charAt(i);

            if (closingQuote == 0) {
                if (ch == '.') {
                    addQualifiedIdentifierPart(parts, sb, qualifiedName, argName);
                    quotedPartClosed = false;
                    continue;
                }

                if (quotedPartClosed && !Character.isWhitespace(ch)) {
                    throw new IllegalArgumentException("Invalid " + argName + ": " + qualifiedName);
                }

                if (!quotedPartClosed && Strings.isBlank(sb)) {
                    if (ch == '"' || ch == '`') {
                        closingQuote = ch;
                    } else if (ch == '[') {
                        closingQuote = ']';
                    }
                }

                sb.append(ch);
            } else {
                sb.append(ch);

                if (ch == closingQuote) {
                    if (i + 1 < len && trimmed.charAt(i + 1) == closingQuote) {
                        sb.append(trimmed.charAt(++i));
                    } else {
                        closingQuote = 0;
                        quotedPartClosed = true;
                    }
                }
            }
        }

        if (closingQuote != 0) {
            throw new IllegalArgumentException("Invalid " + argName + ": " + qualifiedName);
        }

        addQualifiedIdentifierPart(parts, sb, qualifiedName, argName);

        if (parts.isEmpty() || parts.size() > 3) {
            throw new IllegalArgumentException("Invalid " + argName + ": " + qualifiedName);
        }

        return parts.toArray(String[]::new);
    }

    /**
     * Returns {@code true} if any part of the dotted qualified name begins with an identifier delimiter
     * ({@code "}, {@code `}, or {@code [}).
     *
     * @param qualifiedName The qualified identifier to inspect.
     * @return {@code true} if at least one part is delimited.
     */
    private static boolean hasDelimitedIdentifierPart(final String qualifiedName) {
        boolean atPartStart = true;

        for (int i = 0, len = qualifiedName.length(); i < len; i++) {
            final char ch = qualifiedName.charAt(i);

            if (atPartStart) {
                if (Character.isWhitespace(ch)) {
                    continue;
                }

                if (ch == '"' || ch == '`' || ch == '[') {
                    return true;
                }

                atPartStart = false;
            } else if (ch == '.') {
                atPartStart = true;
            }
        }

        return false;
    }

    /**
     * Strips delimiters from the identifier part accumulated in {@code sb}, adds it to {@code parts},
     * and resets the buffer.
     *
     * @param parts The list collecting the identifier parts.
     * @param sb The buffer holding the current part; cleared by this method.
     * @param qualifiedName The original qualified name, used in error messages.
     * @param argName The argument name used in error messages.
     * @throws IllegalArgumentException if the part is empty after stripping delimiters.
     */
    private static void addQualifiedIdentifierPart(final List<String> parts, final StringBuilder sb, final String qualifiedName, final String argName) {
        final String part = SqlIdentifierUtil.stripIdentifierDelimiters(sb.toString());

        if (Strings.isEmpty(part)) {
            throw new IllegalArgumentException("Invalid " + argName + ": " + qualifiedName);
        }

        parts.add(part);
        sb.setLength(0);
    }

    /**
     * Converts a one-, two-, or three-part SQL identifier into a delimited (quoted) qualified identifier,
     * using the identifier quote string reported by the connection's metadata. Undelimited parts are first
     * folded to the database's storage case so quoting does not change which identifier they reference.
     * Explicitly delimited parts retain their original case. When the database does not
     * support delimited identifiers, each part must be an unquoted-safe identifier.
     *
     * @param conn The database {@link Connection} used to determine the identifier quote string.
     * @param qualifiedName The qualified identifier to convert (e.g., {@code schema.table}).
     * @param argName The argument name used in error messages.
     * @return The delimited qualified identifier.
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code qualifiedName} is blank or otherwise invalid.
     * @throws SQLException if a database access error occurs.
     */
    static String toQualifiedSqlIdentifier(final Connection conn, final String qualifiedName, final String argName) throws SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(qualifiedName, argName);

        final String[] rawNameParts = splitQualifiedSqlIdentifier(qualifiedName, argName);
        final DatabaseMetaData metadata = conn.getMetaData();
        normalizeMetadataIdentifierParts(metadata, rawNameParts, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(qualifiedName, rawNameParts.length));
        final String identifierQuote = normalizeIdentifierQuote(metadata);
        final StringBuilder sb = new StringBuilder(qualifiedName.length() + 6);

        for (int i = 0, len = rawNameParts.length; i < len; i++) {
            final String part = rawNameParts[i];

            if (Strings.isEmpty(part)) {
                throw new IllegalArgumentException("Invalid " + argName + ": " + qualifiedName);
            }

            if (i > 0) {
                sb.append('.');
            }

            if (identifierQuote == null) {
                if (!isUnquotedSafeIdentifier(part)) {
                    throw new IllegalArgumentException("Invalid " + argName + ": " + qualifiedName);
                }

                sb.append(part);
            } else {
                sb.append(identifierQuote).append(escapeIdentifierPart(part, identifierQuote)).append(identifierQuote);
            }
        }

        return sb.toString();
    }

    /**
     * Folds caller-supplied undelimited identifier parts to the case used by JDBC metadata.
     * Once a database reports a canonical storage case, the resulting names must match exactly:
     * retrying another case could select a different, explicitly quoted table or schema.
     * Connection-provided catalog/schema names are already resolved and are not passed here.
     *
     * @param metadata the database metadata describing identifier storage
     * @param parts decoded identifier parts, updated in place
     * @param exactMatches explicit-delimiter flags, updated to require exact matching after case folding
     * @throws SQLException if the driver cannot report its identifier storage rules
     */
    private static void normalizeMetadataIdentifierParts(final DatabaseMetaData metadata, final String[] parts, final boolean[] exactMatches)
            throws SQLException {
        final boolean upperCase = metadata.storesUpperCaseIdentifiers();
        final boolean lowerCase = !upperCase && metadata.storesLowerCaseIdentifiers();

        if (!upperCase && !lowerCase) {
            return;
        }

        for (int i = 0; i < parts.length; i++) {
            if (!exactMatches[i]) {
                parts[i] = upperCase ? parts[i].toUpperCase(Locale.ROOT) : parts[i].toLowerCase(Locale.ROOT);
                exactMatches[i] = true;
            }
        }
    }

    /**
     * Returns the database's identifier quote string, or {@code null} when the database reports none
     * ({@code null}, blank, or a space, meaning delimited identifiers are unsupported).
     *
     * @param metadata The database metadata to query.
     * @return The trimmed identifier quote string, or {@code null}.
     * @throws SQLException if a database access error occurs.
     */
    private static String normalizeIdentifierQuote(final DatabaseMetaData metadata) throws SQLException {
        final String quoteString = metadata.getIdentifierQuoteString();
        final String normalized = quoteString == null ? null : quoteString.trim();

        return Strings.isEmpty(normalized) ? null : normalized;
    }

    /**
     * Escapes an identifier part for embedding between delimiters by doubling every occurrence of the quote string.
     *
     * @param identifierPart The identifier part to escape.
     * @param identifierQuote The quote string to double.
     * @return The escaped identifier part.
     */
    private static String escapeIdentifierPart(final String identifierPart, final String identifierQuote) {
        return identifierPart.replace(identifierQuote, identifierQuote + identifierQuote);
    }

    /**
     * Creates a table if it does not already exist.
     *
     * <p>The method first checks for existence via {@link #tableExists(Connection, String)} and only
     * executes the supplied {@code schema} statement when the table is missing. If the {@code CREATE} fails
     * because the table was created concurrently by another process, this method returns {@code false}
     * rather than rethrowing; any other SQL error is wrapped as {@link UncheckedSQLException}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String schema = "CREATE TABLE users (" +
     *                 "id BIGINT PRIMARY KEY, " +
     *                 "name VARCHAR(100), " +
     *                 "email VARCHAR(255))";
     * boolean created = JdbcUtil.createTableIfNotExists(connection, "users", schema);
     * System.out.println(created ? "Table created" : "Table already exists");
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for creating the table.
     * @param tableName The name of the table to create (optionally qualified); must not be blank.
     * @param schema The SQL DDL statement (typically {@code CREATE TABLE ...}) used to create the table; must not be {@code null} or empty.
     * @return {@code true} if this call created the table; {@code false} if the table already existed
     *         when checked, or was created concurrently while this call was running.
     * @throws IllegalArgumentException if {@code conn} is {@code null} , {@code tableName} is blank or otherwise invalid, or {@code schema} is
     *         {@code null} or empty.
     * @throws UncheckedSQLException if the {@code CREATE} fails for a reason other than the table already existing.
     */
    public static boolean createTableIfNotExists(final Connection conn, final String tableName, final String schema) {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(tableName, cs.tableName);
        N.checkArgNotEmpty(schema, cs.schema);

        if (tableExists(conn, tableName)) {
            logger.debug("Table already exists(tableName={})", tableName);
            return false;
        }

        try {
            execute(conn, schema);

            logger.info("Created table(tableName={})", tableName);

            return true;
        } catch (final SQLException e) {
            // The table may have been created concurrently by another thread/process
            if (tableExists(conn, tableName)) {
                logger.debug("Table was created concurrently(tableName={})", tableName);
                return false;
            }

            logger.warn(e, "Failed to create table(tableName={})", tableName);
            throw new UncheckedSQLException("Failed to create table: " + tableName, e);
        }
    }

    /**
     * Drops the specified table if it exists.
     *
     * <p>The method first checks for existence via {@link #tableExists(Connection, String)} and only
     * issues a {@code DROP TABLE} if the table is found. If the drop itself fails because the table no
     * longer exists (for example, a concurrent drop), the method returns {@code false}; any other SQL
     * error is wrapped and rethrown as {@link UncheckedSQLException}. For an unquoted simple name, a
     * case-folded, unquoted retry may be attempted when a database stores unquoted identifiers in a
     * canonical case. An explicitly delimited name is never retried unquoted, preserving its exact identity.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * boolean dropped = JdbcUtil.dropTableIfExists(connection, "temp_users");
     * System.out.println(dropped ? "Table dropped" : "Table did not exist");
     * }</pre>
     *
     * @param conn The database {@link Connection} to use for dropping the table.
     * @param tableName The name of the table to drop (optionally qualified); must not be blank.
     * @return {@code true} if the table was dropped by this call; {@code false} if the table did not exist
     *         (either at the time of the existence check or by the time the {@code DROP} executed).
     * @throws IllegalArgumentException if {@code conn} is {@code null} or {@code tableName} is blank or otherwise invalid.
     * @throws UncheckedSQLException if a database error other than "table not found" occurs during the drop.
     */
    public static boolean dropTableIfExists(final Connection conn, final String tableName) {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(tableName, cs.tableName);

        final String sqlTableName;

        try {
            sqlTableName = toQualifiedSqlIdentifier(conn, tableName, cs.tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }

        if (!tableExists(conn, tableName)) {
            logger.debug("Table does not exist(tableName={})", tableName);
            return false;
        }

        try {
            execute(conn, "DROP TABLE " + sqlTableName);

            logger.info("Dropped table(tableName={})", tableName);

            return true;
        } catch (final SQLException e) {
            if (isTableNotExistsException(e)) {
                // Quoting makes the identifier case-exact while tableExists matches case-tolerantly:
                // on case-folding databases (H2/Oracle/DB2, ...) a table created unquoted is stored
                // upper-case, so the quoted as-supplied name may miss it. Retry unquoted so the
                // database applies its own case folding.
                final String simpleTableName = buildSimpleQualifiedName(tableName);

                if (!hasDelimitedIdentifierPart(tableName) && simpleTableName != null && !simpleTableName.equals(sqlTableName)
                        && tableExists(conn, tableName)) {
                    try {
                        execute(conn, "DROP TABLE " + simpleTableName);

                        logger.info("Dropped table(tableName={})", tableName);

                        return true;
                    } catch (final SQLException e2) {
                        if (isTableNotExistsException(e2)) {
                            logger.debug("Table was dropped concurrently(tableName={})", tableName);
                            return false;
                        }

                        logger.warn(e2, "Failed to drop table(tableName={})", tableName);
                        throw new UncheckedSQLException("Failed to drop table: " + tableName, e2);
                    }
                }

                // The table may have been dropped concurrently by another thread/process
                logger.debug("Table was dropped concurrently(tableName={})", tableName);
                return false;
            }

            logger.warn(e, "Failed to drop table(tableName={})", tableName);
            throw new UncheckedSQLException("Failed to drop table: " + tableName, e);
        }
    }

    /**
     * Returns {@code qualifiedName} with delimiters stripped and parts joined by {@code '.'} when every
     * part is a plain (unquoted-safe) SQL identifier; returns {@code null} otherwise.
     */
    private static String buildSimpleQualifiedName(final String qualifiedName) {
        final String[] parts = splitQualifiedSqlIdentifier(qualifiedName, cs.tableName);

        for (final String part : parts) {
            if (!isUnquotedSafeIdentifier(part)) {
                return null;
            }
        }

        return String.join(".", parts);
    }

    /**
     * Creates a new {@link DBLock} backed by the specified database table for implementing
     * cross-process / cross-JVM advisory locks.
     *
     * <p>The lock table is created automatically when it does not exist. A successful
     * {@code tryLock(target)} call returns a unique lock code that the caller must pass back to
     * {@code unlock(target, code)} to release the lock. Close the returned instance when it is no
     * longer needed so its refresh task stops and any remaining locks are released.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBLock dbLock = JdbcUtil.createDBLock(dataSource, "distributed_locks");
     * try {
     *     String lockCode = dbLock.tryLock("job_processor");
     *     if (lockCode != null) {
     *         try {
     *             // Perform exclusive operation
     *         } finally {
     *             dbLock.unlock("job_processor", lockCode);
     *         }
     *     }
     * } finally {
     *     dbLock.close();
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to use for acquiring connections; must not be {@code null}.
     * @param tableName The name of the table that stores lock records; created when absent. Must not be blank.
     * @return A new {@link DBLock} instance bound to {@code ds} and {@code tableName}.
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or if {@code tableName} is blank or otherwise invalid.
     * @throws UncheckedSQLException if a database operation fails while initializing the lock table.
     * @see DBLock
     */
    public static DBLock createDBLock(final javax.sql.DataSource ds, final String tableName) {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotBlank(tableName, cs.tableName);

        return new DBLock(ds, tableName);
    }

    /**
     * Checks if the given exception indicates a "table not exists" error.
     *
     * @param e The throwable to check.
     * @return {@code true} if the exception indicates a table does not exist, {@code false} otherwise.
     */
    static boolean isTableNotExistsException(final Throwable e) {
        if (e instanceof final SQLException sqlException) {
            if (sqlException.getSQLState() != null && sqlStateForTableNotExists.contains(sqlException.getSQLState())) {
                return true;
            }

            // Vendor-specific error codes for "table does not exist". These are checked because
            // Oracle and SQL Server do not always populate SQLState reliably.
            final int errorCode = sqlException.getErrorCode();
            if (errorCode == 942 // Oracle ORA-00942
                    || errorCode == 208 // SQL Server: Invalid object name
                    || errorCode == 1146) { // MySQL ER_NO_SUCH_TABLE
                return true;
            }

            // Locale-independent lowercase to avoid Turkish-locale `I` ambiguity.
            final String msg = N.defaultIfNull(e.getMessage(), "").toLowerCase(Locale.ROOT);
            return Strings.isNotEmpty(msg) && (msg.contains("not exist") || msg.contains("doesn't exist") || msg.contains("not found"));
        }

        return false;
    }

    /**
     * Shared predicate testing whether a value is a default/unset ID property value;
     * delegates to {@link #isDefaultIdPropValue(Object)}.
     */
    static final com.landawn.abacus.util.function.Predicate<Object> defaultIdTester = JdbcUtil::isDefaultIdPropValue;

    /**
     * Checks if the given value is a default/unset ID property value (null, zero for numbers, or a bean/record with all default ID values).
     *
     * @param value The value to check.
     * @return {@code true} if the value is a default ID property value, {@code false} otherwise.
     * @deprecated This method is intended for internal use only and is not part of the public API.
     */
    @Deprecated
    @Internal
    static boolean isDefaultIdPropValue(final Object value) {
        if (value == null) {
            return true;
        } else if (value instanceof Number number) {
            return isZeroNumber(number);
        } else if (value instanceof EntityId) {
            return N.allMatch(((EntityId) value).entrySet(), it -> JdbcUtil.isDefaultIdPropValue(it.getValue()));
        } else if (Beans.isBeanClass(value.getClass()) || Beans.isRecordClass(value.getClass())) {
            final Class<?> entityClass = value.getClass();
            final List<String> idPropNameList = QueryUtil.idPropNames(entityClass);

            if (N.isEmpty(idPropNameList)) {
                return true;
            } else {
                final BeanInfo idBeanInfo = ParserUtil.getBeanInfo(entityClass);
                return N.allMatch(idPropNameList, idName -> JdbcUtil.isDefaultIdPropValue(idBeanInfo.getPropValue(value, idName)));
            }
        }

        return false;
    }

    /**
     * Returns {@code true} if the given number equals zero, comparing {@link BigDecimal} and {@link BigInteger}
     * exactly and all other numeric types via {@link Number#doubleValue()}.
     *
     * @param value The number to test.
     * @return {@code true} if {@code value} is numerically zero.
     */
    private static boolean isZeroNumber(final Number value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal.compareTo(BigDecimal.ZERO) == 0;
        } else if (value instanceof BigInteger bigInteger) {
            return bigInteger.signum() == 0;
        }

        return value.doubleValue() == 0;
    }

    /**
     * Returns whether the given list of IDs is non-empty and every ID is a default/unset ID value.
     *
     * @param ids The list of IDs to check.
     * @return {@code true} if {@code ids} is non-empty and all its elements are default/unset ID values.
     */
    static boolean isAllNullIds(final List<?> ids) {
        return isAllNullIds(ids, defaultIdTester);
    }

    /**
     * Returns whether the given list of IDs is non-empty and every ID is accepted by the given
     * default-ID tester.
     *
     * @param ids The list of IDs to check.
     * @param isDefaultIdTester The predicate used to test whether an ID is a default/unset ID value.
     * @return {@code true} if {@code ids} is non-empty and all its elements are accepted by {@code isDefaultIdTester}.
     */
    static boolean isAllNullIds(final List<?> ids, final Predicate<Object> isDefaultIdTester) {
        return N.notEmpty(ids) && ids.stream().allMatch(isDefaultIdTester);
    }

    /**
     * Asynchronously runs the specified SQL action in a separate thread.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<Void> future = JdbcUtil.runAsync(() -> {
     *     // Perform database operations
     *     JdbcUtil.executeUpdate(dataSource, "UPDATE users SET status = ? WHERE id = ?", "active", userId);
     * });
     *
     * future.thenRunAsync(() -> System.out.println("Update completed")).get();
     * }</pre>
     *
     * @param sqlAction The SQL action to be executed asynchronously.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static ContinuableFuture<Void> runAsync(final Throwables.Runnable<Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Asynchronously runs two SQL actions in separate threads.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> futures = JdbcUtil.runAsync(
     *     () -> JdbcUtil.executeUpdate(dataSource, "UPDATE users SET status = ?", "active"),
     *     () -> JdbcUtil.executeUpdate(dataSource, "UPDATE orders SET processed = ?", true)
     * );
     *
     * Futures.allOf(futures._1, futures._2).thenRunAsync(() ->
     *     System.out.println("Both updates completed")
     * ).get();
     * }</pre>
     *
     * @param sqlAction1 The first SQL action to be executed asynchronously.
     * @param sqlAction2 The second SQL action to be executed asynchronously.
     * @return A Tuple2 containing two ContinuableFuture objects representing the results of the asynchronous computations.
     * @throws IllegalArgumentException if {@code sqlAction1} or {@code sqlAction2} is {@code null}.
     */
    @Beta
    public static Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> runAsync(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Asynchronously runs three SQL actions in separate threads.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> futures =
     *     JdbcUtil.runAsync(
     *         () -> JdbcUtil.executeUpdate(dataSource, "UPDATE users SET status = ?", "active"),
     *         () -> JdbcUtil.executeUpdate(dataSource, "UPDATE orders SET processed = ?", true),
     *         () -> JdbcUtil.executeUpdate(dataSource, "UPDATE inventory SET updated = ?", new Date())
     *     );
     *
     * Futures.allOf(futures._1, futures._2, futures._3).thenRunAsync(() ->
     *     System.out.println("All updates completed")
     * ).get();
     * }</pre>
     *
     * @param sqlAction1 The first SQL action to be executed asynchronously.
     * @param sqlAction2 The second SQL action to be executed asynchronously.
     * @param sqlAction3 The third SQL action to be executed asynchronously.
     * @return A Tuple3 containing three ContinuableFuture objects representing the results of the asynchronous computations.
     * @throws IllegalArgumentException if {@code sqlAction1}, {@code sqlAction2}, or {@code sqlAction3} is {@code null}.
     */
    @Beta
    public static Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> runAsync(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2, final Throwables.Runnable<Exception> sqlAction3) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);
        N.checkArgNotNull(sqlAction3, cs.sqlAction3);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
    }

    /**
     * Asynchronously runs the specified SQL action with the given parameter.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User entity = new User(123, "John", "john@example.com");
     * ContinuableFuture<Void> future = JdbcUtil.runAsync(entity, e -> {
     *     JdbcUtil.executeUpdate(dataSource, "INSERT INTO users (name, email) VALUES (?, ?)", e.getName(), e.getEmail());
     * });
     *
     * future.thenRunAsync(() -> System.out.println("User inserted")).get();
     * }</pre>
     *
     * @param <T> The type of the parameter.
     * @param parameter The parameter to be passed to the SQL action.
     * @param sqlAction The SQL action to be executed with the parameter.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static <T> ContinuableFuture<Void> runAsync(final T parameter, final Throwables.Consumer<? super T, Exception> sqlAction)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(parameter));
    }

    /**
     * Asynchronously runs the specified SQL action with two parameters.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<Void> future = JdbcUtil.runAsync(userId, status,
     *     (id, st) -> JdbcUtil.executeUpdate(dataSource, "UPDATE users SET status = ? WHERE id = ?", st, id)
     * );
     *
     * future.thenRunAsync(() -> System.out.println("Status updated")).get();
     * }</pre>
     *
     * @param <T> The type of the first parameter.
     * @param <U> The type of the second parameter.
     * @param parameter1 The first parameter to be passed to the SQL action.
     * @param parameter2 The second parameter to be passed to the SQL action.
     * @param sqlAction The SQL action to be executed with the parameters.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static <T, U> ContinuableFuture<Void> runAsync(final T parameter1, final U parameter2,
            final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(parameter1, parameter2));
    }

    /**
     * Asynchronously runs the specified SQL action with three parameters.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<Void> future = JdbcUtil.runAsync(userId, orderId, status,
     *     (uid, oid, st) -> {
     *         JdbcUtil.executeUpdate(dataSource, "UPDATE orders SET status = ? WHERE user_id = ? AND order_id = ?",
     *                         st, uid, oid);
     *     }
     * );
     *
     * future.thenRunAsync(() -> System.out.println("Order status updated")).get();
     * }</pre>
     *
     * @param <A> The type of the first parameter.
     * @param <B> The type of the second parameter.
     * @param <C> The type of the third parameter.
     * @param parameter1 The first parameter to be passed to the SQL action.
     * @param parameter2 The second parameter to be passed to the SQL action.
     * @param parameter3 The third parameter to be passed to the SQL action.
     * @param sqlAction The SQL action to be executed with the parameters.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static <A, B, C> ContinuableFuture<Void> runAsync(final A parameter1, final B parameter2, final C parameter3,
            final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.accept(parameter1, parameter2, parameter3));
    }

    /**
     * Asynchronously calls the specified SQL action and returns a result.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<User> future = JdbcUtil.callAsync(() -> {
     *     return JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users WHERE id = ?").setLong(1, userId).findFirst(User.class).orElse(null);
     * });
     *
     * future.thenRunAsync(user -> System.out.println("Found user: " + (user != null ? user.getName() : "none"))).get();
     * }</pre>
     *
     * @param <R> The type of the result.
     * @param sqlAction The SQL action that produces a result.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static <R> ContinuableFuture<R> callAsync(final Callable<? extends R> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(sqlAction);
    }

    /**
     * Asynchronously calls two SQL actions and returns their results.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple2<ContinuableFuture<String>, ContinuableFuture<List<String>>> futures = JdbcUtil.callAsync(
     *     () -> JdbcUtil.prepareQuery(dataSource, "SELECT name FROM users WHERE id = ?").setLong(1, userId).queryForSingleValue(String.class).orElse(null),
     *     () -> JdbcUtil.prepareQuery(dataSource, "SELECT email FROM users WHERE age > ?").setInt(1, 18).list(String.class)
     * );
     *
     * futures._1.thenRunAsync(name -> System.out.println("User name: " + name)).get();
     * futures._2.thenRunAsync(results -> System.out.println("Found " + results.size() + " emails")).get();
     * }</pre>
     *
     * @param <R1> The type of the result from the first action.
     * @param <R2> The type of the result from the second action.
     * @param sqlAction1 The first SQL action that produces a result.
     * @param sqlAction2 The second SQL action that produces a result.
     * @return A Tuple2 containing two ContinuableFutures representing the results of the asynchronous computations.
     * @throws IllegalArgumentException if {@code sqlAction1} or {@code sqlAction2} is {@code null}.
     */
    @Beta
    public static <R1, R2> Tuple2<ContinuableFuture<R1>, ContinuableFuture<R2>> callAsync(final Callable<? extends R1> sqlAction1,
            final Callable<? extends R2> sqlAction2) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2));
    }

    /**
     * Asynchronously calls three SQL actions and returns their results.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Tuple3<ContinuableFuture<Long>, ContinuableFuture<BigDecimal>, ContinuableFuture<List<Product>>> futures =
     *     JdbcUtil.callAsync(
     *         () -> JdbcUtil.prepareQuery(dataSource, "SELECT COUNT(*) FROM orders").queryForSingleValue(Long.class).orElse(0L),
     *         () -> JdbcUtil.prepareQuery(dataSource, "SELECT SUM(total) FROM orders").queryForSingleValue(BigDecimal.class).orElse(BigDecimal.ZERO),
     *         () -> JdbcUtil.prepareQuery(dataSource, "SELECT * FROM products WHERE stock < ?").setInt(1, 10).list(Product.class)
     *     );
     *
     * Futures.allOf(futures._1, futures._2, futures._3).thenRunAsync(() -> {
     *     System.out.println("All queries completed");
     * }).get();
     * }</pre>
     *
     * @param <R1> The type of the result from the first action.
     * @param <R2> The type of the result from the second action.
     * @param <R3> The type of the result from the third action.
     * @param sqlAction1 The first SQL action that produces a result.
     * @param sqlAction2 The second SQL action that produces a result.
     * @param sqlAction3 The third SQL action that produces a result.
     * @return A Tuple3 containing three ContinuableFutures representing the results of the asynchronous computations.
     * @throws IllegalArgumentException if {@code sqlAction1}, {@code sqlAction2}, or {@code sqlAction3} is {@code null}.
     */
    @Beta
    public static <R1, R2, R3> Tuple3<ContinuableFuture<R1>, ContinuableFuture<R2>, ContinuableFuture<R3>> callAsync(final Callable<? extends R1> sqlAction1,
            final Callable<? extends R2> sqlAction2, final Callable<? extends R3> sqlAction3) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction1, cs.sqlAction1);
        N.checkArgNotNull(sqlAction2, cs.sqlAction2);
        N.checkArgNotNull(sqlAction3, cs.sqlAction3);

        return Tuple.of(asyncExecutor.execute(sqlAction1), asyncExecutor.execute(sqlAction2), asyncExecutor.execute(sqlAction3));
    }

    /**
     * Asynchronously calls the specified SQL action with one parameter and returns a result.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<User> future = JdbcUtil.callAsync(123L,
     *     param -> JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users WHERE id = ?").setLong(1, param).findFirst(User.class).orElse(null)
     * );
     *
     * future.thenRunAsync(user -> System.out.println("Found user: " + (user != null ? user.getName() : "none"))).get();
     * }</pre>
     *
     * @param <T> The type of the parameter.
     * @param <R> The type of the result.
     * @param parameter The parameter to pass to the SQL action.
     * @param sqlAction The SQL action that takes a parameter and produces a result.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static <T, R> ContinuableFuture<R> callAsync(final T parameter, final Throwables.Function<? super T, ? extends R, Exception> sqlAction)
            throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(parameter));
    }

    /**
     * Asynchronously calls the specified SQL action with two parameters and returns a result.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<List<Order>> future = JdbcUtil.callAsync(userId, status,
     *     (uid, st) -> JdbcUtil.prepareQuery(dataSource, "SELECT * FROM orders WHERE user_id = ? AND status = ?")
     *                          .setLong(1, uid).setString(2, st).list(Order.class)
     * );
     *
     * future.thenRunAsync(orders -> System.out.println("Found " + orders.size() + " orders")).get();
     * }</pre>
     *
     * @param <T> The type of the first parameter.
     * @param <U> The type of the second parameter.
     * @param <R> The type of the result.
     * @param parameter1 The first parameter to pass to the SQL action.
     * @param parameter2 The second parameter to pass to the SQL action.
     * @param sqlAction The SQL action that takes two parameters and produces a result.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static <T, U, R> ContinuableFuture<R> callAsync(final T parameter1, final U parameter2,
            final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(parameter1, parameter2));
    }

    /**
     * Asynchronously calls the specified SQL action with three parameters and returns a result.
     * <p>Note: Any transaction started in current thread won't be automatically applied to the SQL
     * action(s) which will be executed in another thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ContinuableFuture<BigDecimal> future = JdbcUtil.callAsync(startDate, endDate, category,
     *     (start, end, cat) -> JdbcUtil.prepareQuery(dataSource,
     *         "SELECT SUM(amount) FROM sales WHERE date BETWEEN ? AND ? AND category = ?")
     *         .setDate(1, start).setDate(2, end).setString(3, cat)
     *         .queryForSingleValue(BigDecimal.class).orElse(BigDecimal.ZERO)
     * );
     *
     * future.thenRunAsync(total -> System.out.println("Total sales: " + total)).get();
     * }</pre>
     *
     * @param <A> The type of the first parameter.
     * @param <B> The type of the second parameter.
     * @param <C> The type of the third parameter.
     * @param <R> The type of the result.
     * @param parameter1 The first parameter to pass to the SQL action.
     * @param parameter2 The second parameter to pass to the SQL action.
     * @param parameter3 The third parameter to pass to the SQL action.
     * @param sqlAction The SQL action that takes three parameters and produces a result.
     * @return A ContinuableFuture representing the result of the asynchronous computation.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     */
    @Beta
    public static <A, B, C, R> ContinuableFuture<R> callAsync(final A parameter1, final B parameter2, final C parameter3,
            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction) throws IllegalArgumentException {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        return asyncExecutor.execute(() -> sqlAction.apply(parameter1, parameter2, parameter3));
    }

    /**
     * {@link RowMapper} that extracts a single generated key from the first column of the generated-keys {@link ResultSet}.
     */
    static final RowMapper<Object> SINGLE_GENERATED_KEY_EXTRACTOR = rs -> getColumnValue(rs, 1);

    /**
     * {@link BiRowMapper} that extracts no generated key and always returns {@code null};
     * used for entities without an id property.
     */
    static final BiRowMapper<Object> NO_BI_GENERATED_KEY_EXTRACTOR = (rs, columnLabels) -> null;

    // Must be declared after NO_BI_GENERATED_KEY_EXTRACTOR: a qualified forward reference to it would
    // compile but read null while this class is still being initialized.
    /**
     * Placeholder (key extractor, id getter, id setter) triple for entities without an id property:
     * no key extraction, a {@code null}-returning id getter, and a no-op id setter.
     */
    @SuppressWarnings("rawtypes")
    private static final Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer> noIdGeneratorGetterSetter = Tuple
            .of(NO_BI_GENERATED_KEY_EXTRACTOR, entity -> null, BiConsumers.doNothing());

    /**
     * Cache of resolved {@link PropInfo} (or an empty {@link Optional} for unresolvable paths),
     * keyed by entity class and then by property name or dot-separated property path.
     */
    private static final Map<Class<?>, Map<String, Optional<PropInfo>>> entityPropInfoQueueMap = new ConcurrentHashMap<>();

    /**
     * Returns the {@link PropInfo} for a possibly nested property path (e.g., {@code address.street}) of
     * the given entity class, or {@code null} if the path does not resolve to a property.
     *
     * @param entityClass The entity class to inspect.
     * @param propName The property name or dot-separated property path.
     * @return The {@link PropInfo} for the resolved property, or {@code null} if not found.
     */
    static PropInfo getSubPropInfo(final Class<?> entityClass, final String propName) {
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
        final Map<String, Optional<PropInfo>> propInfoQueueMap = entityPropInfoQueueMap.computeIfAbsent(entityClass,
                cls -> new ConcurrentCacheMap<>((entityInfo.propInfoList.size() + 1) * 2));
        Optional<PropInfo> propInfoHolder = propInfoQueueMap.get(propName);
        PropInfo propInfo = null;

        if (propInfoHolder == null) {
            final String[] strs = Splitter.with('.').splitToArray(propName);

            if (strs.length > 1) {
                Class<?> propClass = entityClass;
                BeanInfo propBeanInfo = null;

                for (int i = 0, len = strs.length; i < len; i++) {
                    propBeanInfo = Beans.isBeanClass(propClass) ? ParserUtil.getBeanInfo(propClass) : null;
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
                        propClass = propInfo.type.elementType().javaType();
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

    /**
     * Returns the parameters of the given {@link SP} as an array.
     *
     * @param sp The {@link SP} whose parameters to return.
     * @return An array of the parameters, or an empty array if there are none.
     */
    static Object[] getParameterArray(final SP sp) {
        return N.isEmpty(sp.parameters()) ? N.EMPTY_OBJECT_ARRAY : sp.parameters().toArray();
    }

    /**
     * Adapts the given {@link RowMapper} to a {@link BiRowMapper} that ignores the column labels.
     *
     * @param <R> The type of the mapped row elements.
     * @param rowMapper The {@link RowMapper} to adapt.
     * @return A {@link BiRowMapper} that delegates to {@code rowMapper}.
     */
    static <R> BiRowMapper<R> toBiRowMapper(final RowMapper<R> rowMapper) {
        return (rs, columnLabels) -> rowMapper.apply(rs);
    }

    /**
     * Retrieves the output parameters from the given CallableStatement.
     * This method extracts the values of output parameters after executing a stored procedure.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "{call get_user_info(?, ?, ?)}";
     * try (CallableStatement stmt = connection.prepareCall(sql)) {
     *     stmt.setLong(1, userId);
     *     stmt.registerOutParameter(2, Types.VARCHAR);
     *     stmt.registerOutParameter(3, Types.INTEGER);
     *     stmt.execute();
     *
     *     List<OutParam> outParams = Arrays.asList(
     *         OutParam.of(2, Types.VARCHAR),
     *         OutParam.of(3, Types.INTEGER)
     *     );
     *
     *     OutParamResult result = JdbcUtil.getOutParameters(stmt, outParams);
     *     String name = (String) result.getOutParamValue(2);
     *     Integer age = (Integer) result.getOutParamValue(3);
     * }
     * }</pre>
     *
     * <p>Special handling: any output parameter that returns a {@link ResultSet}, {@link Blob}, or
     * {@link Clob} is converted to a more convenient Java representation (a {@link Dataset},
     * {@code byte[]}, and {@link String} respectively) and the underlying LOB or {@code ResultSet} is
     * freed/closed automatically.</p>
     *
     * @param stmt The {@link CallableStatement} from which to retrieve the output parameters; must not
     *             be {@code null}.
     * @param outParams The list of {@link OutParam} objects describing the output parameters to retrieve;
     *                  if {@code null} or empty, an empty {@link OutParamResult} is returned.
     * @return An {@link OutParamResult} containing the retrieved output parameter values keyed by
     *         parameter index (for index-based out params) or parameter name (for name-based out params).
     * @throws IllegalArgumentException if {@code stmt} is {@code null}.
     * @throws SQLException if a database access error occurs while reading any output parameter, or if a
     *         returned {@link Blob}/{@link Clob} exceeds {@link Integer#MAX_VALUE} bytes/characters.
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
            outParameterGetter = sqlTypeGetterMap.getOrDefault(outParam.sqlType(), objOutParameterGetter);

            if (outParam.parameterIndex() > 0) {
                key = outParam.parameterIndex();
                value = outParameterGetter.getOutParameter(stmt, outParam.parameterIndex());
            } else {
                key = outParam.parameterName();
                value = outParameterGetter.getOutParameter(stmt, outParam.parameterName());
            }

            // Primitive getters (getInt, getBoolean, ...) return 0/false for SQL NULL; only wasNull()
            // distinguishes. It reports on the last parameter read, so one check covers every type.
            if (value != null && stmt.wasNull()) {
                value = null;
            }

            if (value instanceof final ResultSet rs) {
                try {
                    value = JdbcUtil.extractData(rs);
                } finally {
                    JdbcUtil.closeQuietly(rs);
                }
            } else if (value instanceof final Blob blob) {
                value = materializeBlob(blob);
            } else if (value instanceof final Clob clob) {
                value = materializeClob(clob);
            }

            outParamValues.put(key, value);
        }

        return new OutParamResult(outParams, outParamValues);
    }

    /**
     * Extracts the named parameters from the given SQL string.
     * Named parameters are placeholders in SQL that start with ':' followed by the parameter name.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE name = :name AND age > :age AND city = :city";
     * List<String> params = JdbcUtil.getNamedParameters(sql);
     * // Returns: ["name", "age", "city"]
     * }</pre>
     *
     * @param sql The SQL string containing named parameters (e.g., :paramName).
     * @return A list of named parameter names found in the SQL string (without the ':' prefix).
     * @throws IllegalArgumentException if {@code sql} is {@code null}, empty, or blank.
     */
    public static List<String> getNamedParameters(final String sql) {
        return ParsedSql.parse(sql).namedParameters();
    }

    /**
     * Parses the given SQL string and returns a ParsedSql object.
     * This method analyzes SQL statements to extract information about parameters and structure.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE name = :name AND age > ?";
     * ParsedSql parsedSql = JdbcUtil.parseSql(sql);
     * List<String> namedParams = parsedSql.namedParameters();   // ["name"]
     * String convertedSql = parsedSql.parameterizedSql();       // SQL with named params converted to ?
     * }</pre>
     *
     * @param sql The SQL string to be parsed.
     * @return A ParsedSql object containing parsed information about the SQL string.
     * @throws IllegalArgumentException if {@code sql} is {@code null}, empty, or blank.
     * @see ParsedSql#parse(String)
     */
    public static ParsedSql parseSql(final String sql) {
        return ParsedSql.parse(sql);
    }

    /**
     * Returns the property names suitable for INSERT operations for the given entity class.
     * This method analyzes the class structure to determine which properties should be
     * included in INSERT statements. Relationship properties annotated with {@link JoinedBy}
     * are excluded because they are populated from other tables rather than persisted in the
     * entity's base table.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Collection<String> propNames = JdbcUtil.getInsertPropNames(User.class);
     * // Returns property names that should be included in INSERT statement for User entities
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @return A collection of property names suitable for INSERT operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     */
    public static Collection<String> getInsertPropNames(final Class<?> entityClass) {
        return getInsertPropNames(entityClass, null);
    }

    /**
     * Returns the property names suitable for INSERT operations for the given entity class,
     * excluding the specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<String> excludedProps = N.asSet("id", "version");
     * Collection<String> propNames = JdbcUtil.getInsertPropNames(User.class, excludedProps);
     * // Returns property names for INSERT, excluding specified properties
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @param excludedPropNames Property names to exclude from the result.
     * @return A collection of property names suitable for INSERT operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     */
    public static Collection<String> getInsertPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.insertPropNames(entityClass, withJoinedByPropertiesExcluded(entityClass, excludedPropNames));
    }

    /**
     * Gets the property names suitable for SELECT operations for the given entity class.
     * This method returns all property names that should be included in a SELECT statement,
     * excluding properties marked with {@code @Transient}, {@link JoinedBy}, or other exclusion annotations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Collection<String> propNames = JdbcUtil.getSelectPropNames(User.class);
     * // Returns property names that should be included in SELECT statement
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @return A collection of property names suitable for SELECT operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass) {
        return getSelectPropNames(entityClass, null);
    }

    /**
     * Gets the property names suitable for SELECT operations for the given entity class,
     * excluding the specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<String> excludedProps = N.asSet("password", "secretKey");
     * Collection<String> propNames = JdbcUtil.getSelectPropNames(User.class, excludedProps);
     * // Returns property names for SELECT, excluding sensitive properties
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @param excludedPropNames Property names to exclude from the result.
     * @return A collection of property names suitable for SELECT operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return getSelectPropNames(entityClass, false, excludedPropNames);
    }

    /**
     * Gets the property names suitable for SELECT operations for the given entity class,
     * with an option to include sub-entity properties and exclude specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Include properties of related entities (e.g., user.address.street)
     * Collection<String> propNames = JdbcUtil.getSelectPropNames(User.class, true, null);
     * // Returns property names including sub-entity properties
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @param includeSubEntityProperties Whether to include properties of sub-entities.
     * @param excludedPropNames Property names to exclude from the result.
     * @return A collection of property names suitable for SELECT operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final boolean includeSubEntityProperties,
            final Set<String> excludedPropNames) {
        return QueryUtil.selectPropNames(entityClass, includeSubEntityProperties, withJoinedByPropertiesExcluded(entityClass, excludedPropNames));
    }

    /**
     * Gets the property names suitable for UPDATE operations for the given entity class.
     * This method returns all property names that should be included in an UPDATE statement,
     * excluding properties marked with {@code @ReadOnly}, {@code @NonUpdatable}, {@code @Id},
     * {@link JoinedBy}, etc.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Collection<String> propNames = JdbcUtil.getUpdatePropNames(User.class);
     * // Returns property names that can be updated
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @return A collection of property names suitable for UPDATE operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     */
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass) {
        return getUpdatePropNames(entityClass, null);
    }

    /**
     * Gets the property names suitable for UPDATE operations for the given entity class,
     * excluding the specified property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<String> excludedProps = N.asSet("createdTime", "createdBy");
     * Collection<String> propNames = JdbcUtil.getUpdatePropNames(User.class, excludedProps);
     * // Returns property names for UPDATE, excluding specified properties
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @param excludedPropNames Property names to exclude from the result.
     * @return A collection of property names suitable for UPDATE operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null}.
     */
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass, final Set<String> excludedPropNames) {
        return QueryUtil.updatePropNames(entityClass, withJoinedByPropertiesExcluded(entityClass, excludedPropNames));
    }

    /**
     * Adds relationship properties to an operation's exclusion set without mutating the caller's set.
     * A {@code @JoinedBy} property represents data read from a related table, even when its Java type
     * (notably {@link Map}) would otherwise be considered a persistable scalar by {@link QueryUtil}.
     */
    private static Set<String> withJoinedByPropertiesExcluded(final Class<?> entityClass, final Set<String> excludedPropNames) {
        if (entityClass == null) {
            return excludedPropNames;
        }

        Set<String> result = null;

        for (final PropInfo propInfo : ParserUtil.getBeanInfo(entityClass).propInfoList) {
            if (propInfo.isAnnotationPresent(JoinedBy.class)) {
                if (result == null) {
                    result = N.isEmpty(excludedPropNames) ? N.newHashSet() : N.newHashSet(excludedPropNames);
                }

                result.add(propInfo.name);
            }
        }

        return result == null ? excludedPropNames : result;
    }

    /**
     * Converts a Blob to a String using UTF-8 encoding and frees the Blob resources.
     * This method reads all bytes from the Blob and converts them to a String.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = resultSet.getBlob("data");
     * String content = JdbcUtil.blobToString(blob);
     * // The blob is automatically freed after conversion
     * }</pre>
     *
     * @param blob The Blob object to be converted to a String.
     * @return The String representation of the Blob content, or {@code null} if {@code blob} is {@code null}.
     * @throws SQLException if a database access error occurs while accessing the Blob.
     */
    public static String blobToString(final Blob blob) throws SQLException {
        if (blob == null) {
            return null;
        }

        return new String(materializeBlob(blob), Charsets.UTF_8);
    }

    /**
     * Converts a Blob to a String using the specified character encoding and frees the Blob resources.
     * This method reads all bytes from the Blob and converts them to a String using the given charset.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = resultSet.getBlob("data");
     * String content = JdbcUtil.blobToString(blob, Charsets.ISO_8859_1);
     * // The blob is automatically freed after conversion
     * }</pre>
     *
     * @param blob The Blob object to be converted to a String.
     * @param charset The character encoding to use for the conversion. Must not be {@code null} when {@code blob} is not {@code null}.
     * @return The String representation of the Blob content, or {@code null} if {@code blob} is {@code null}.
     * @throws IllegalArgumentException if {@code charset} is {@code null} (when {@code blob} is not {@code null}).
     * @throws SQLException if a database access error occurs while accessing the Blob.
     */
    public static String blobToString(final Blob blob, final Charset charset) throws IllegalArgumentException, SQLException {
        if (blob == null) {
            return null;
        }

        N.checkArgNotNull(charset, cs.charset);

        return new String(materializeBlob(blob), charset);
    }

    /**
     * Writes the content of a Blob to a file and frees the Blob resources.
     * This method streams the Blob content directly to the specified file.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Blob blob = resultSet.getBlob("document");
     * File outputFile = new File("document.pdf");
     * long bytesWritten = JdbcUtil.writeBlobToFile(blob, outputFile);
     * // The blob is automatically freed after writing
     * }</pre>
     *
     * @param blob The Blob object containing the data to be written.
     * @param output The File object representing the output file. Must not be {@code null} when {@code blob} is not {@code null}.
     * @return The number of bytes written to the file, or {@code 0} if {@code blob} is {@code null}.
     * @throws IllegalArgumentException if {@code output} is {@code null} (when {@code blob} is not {@code null}).
     * @throws SQLException if a database access error occurs while accessing the Blob.
     * @throws IOException if an I/O error occurs while writing to the file.
     */
    public static long writeBlobToFile(final Blob blob, final File output) throws IllegalArgumentException, SQLException, IOException {
        if (blob == null) {
            return 0;
        }

        N.checkArgNotNull(output, cs.output);

        Throwable primaryFailure = null;

        try {
            // IOUtil.write(InputStream, File) closes only the output FileOutputStream — the input
            // stream is the caller's responsibility, so wrap it in try-with-resources to avoid leak.
            try (java.io.InputStream in = blob.getBinaryStream()) {
                return IOUtil.write(in, output);
            }
        } catch (final SQLException | IOException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            freeBlob(blob, primaryFailure);
        }
    }

    /**
     * Converts a Clob to a String and frees the Clob resources.
     * This method reads all characters from the Clob and returns them as a String.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Clob clob = resultSet.getClob("description");
     * String content = JdbcUtil.clobToString(clob);
     * // The clob is automatically freed after conversion
     * }</pre>
     *
     * @param clob The Clob object to be converted to a String.
     * @return The String representation of the Clob content, or {@code null} if {@code clob} is {@code null}.
     * @throws SQLException if a database access error occurs while accessing the Clob.
     */
    public static String clobToString(final Clob clob) throws SQLException {
        if (clob == null) {
            return null;
        }

        return materializeClob(clob);
    }

    /**
     * Writes the content of a Clob to a file and frees the Clob resources.
     * This method streams the Clob content directly to the specified file.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Clob clob = resultSet.getClob("article");
     * File outputFile = new File("article.txt");
     * long charsWritten = JdbcUtil.writeClobToFile(clob, outputFile);
     * // The clob is automatically freed after writing
     * }</pre>
     *
     * @param clob The Clob object containing the data to be written.
     * @param output The File object representing the output file. Must not be {@code null} when {@code clob} is not {@code null}.
     * @return The number of characters written to the file, or {@code 0} if {@code clob} is {@code null}.
     * @throws IllegalArgumentException if {@code output} is {@code null} (when {@code clob} is not {@code null}).
     * @throws SQLException if a database access error occurs while accessing the Clob.
     * @throws IOException if an I/O error occurs while writing to the file.
     */
    public static long writeClobToFile(final Clob clob, final File output) throws IllegalArgumentException, SQLException, IOException {
        if (clob == null) {
            return 0;
        }

        N.checkArgNotNull(output, cs.output);

        Throwable primaryFailure = null;

        try {
            // IOUtil.write(Reader, File) closes only the output writer — the input reader is the
            // caller's responsibility, so wrap it in try-with-resources to avoid leak.
            try (java.io.Reader reader = clob.getCharacterStream()) {
                return IOUtil.write(reader, output);
            }
        } catch (final SQLException | IOException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            freeClob(clob, primaryFailure);
        }
    }

    /**
     * Checks if the given value is {@code null} or equals the default value for its type.
     * A value is considered "null or default" when it is {@code null}, a numeric zero, the
     * {@link Boolean} {@code false}, or otherwise {@link N#equals(Object, Object) equals} the
     * {@link N#defaultValueOf default value} of its runtime class. {@link BigDecimal} and
     * {@link BigInteger} values are compared exactly, without converting them to floating point.
     * Reference types (such as {@link String} or any collection) are only considered default when
     * {@code null}; an empty {@code String} or empty collection is <em>not</em> default.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.isNullOrDefault(null);    // true
     * JdbcUtil.isNullOrDefault(0);       // true
     * JdbcUtil.isNullOrDefault(0.0);     // true
     * JdbcUtil.isNullOrDefault(false);   // true
     * JdbcUtil.isNullOrDefault("");      // false (empty string is not default)
     * JdbcUtil.isNullOrDefault(1);       // false
     * }</pre>
     *
     * @param value The value to check.
     * @return {@code true} if the value is {@code null} or the default value for its type, {@code false} otherwise.
     */
    public static boolean isNullOrDefault(final Object value) {
        return (value == null) || (value instanceof Number num && isZeroNumber(num)) || (value instanceof Boolean b && !b)
                || N.equals(value, N.defaultValueOf(value.getClass()));
    }

    /**
     * Associates the given value with the given key, merging it with any existing value (including an
     * existing {@code null} value) via the given remapping function.
     *
     * @param <K> The type of the map keys.
     * @param <V> The type of the map values.
     * @param map The map to update.
     * @param key The key whose value to merge.
     * @param value The value to merge with the existing value.
     * @param remappingFunction The function used to merge an existing value with the new value.
     */
    static <K, V> void merge(final Map<K, V> map, final K key, final V value, final BinaryOperator<V> remappingFunction) {
        final V oldValue = map.get(key);

        if (oldValue == null && !map.containsKey(key)) {
            map.put(key, value);
        } else {
            map.put(key, remappingFunction.apply(oldValue, value));
        }
    }

    /**
     * Resolves the prefix of a dot-qualified column name (e.g., {@code address.street}) against the
     * properties of the given entity, remapping it via {@code prefixAndPropNameMap} when necessary.
     *
     * @param entityInfo The {@link BeanInfo} of the entity class.
     * @param columnName The column name to check.
     * @param prefixAndPropNameMap A map from column-name prefixes to entity field names. Can be {@code null}.
     * @param columnLabelList The list of all column labels in the result set.
     * @return The column name, possibly with its prefix remapped to the matching property name.
     */
    static String checkPrefix(final BeanInfo entityInfo, final String columnName, final Map<String, String> prefixAndPropNameMap,
            final List<String> columnLabelList) {

        final int idx = columnName.indexOf('.');

        if (idx <= 0) {
            return columnName;
        }

        final String prefix = columnName.substring(0, idx);
        PropInfo propInfo = entityInfo.getPropInfo(prefix);

        if (propInfo != null) {
            return columnName;
        }

        if (N.notEmpty(prefixAndPropNameMap) && prefixAndPropNameMap.containsKey(prefix)) {
            propInfo = entityInfo.getPropInfo(prefixAndPropNameMap.get(prefix));

            if (propInfo != null) {
                return propInfo.name + columnName.substring(idx);
            }
        }

        propInfo = entityInfo.getPropInfo(prefix + "s"); // Trying to do something smart?
        final int len = prefix.length() + 1;

        if (propInfo != null && (propInfo.type.isBean() || (propInfo.type.isCollection() && propInfo.type.elementType().isBean()))
                && N.noneMatch(columnLabelList, it -> it.length() > len && it.charAt(len) == '.' && Strings.startsWithIgnoreCase(it, prefix + "s."))) {
            // good
        } else {
            propInfo = entityInfo.getPropInfo(prefix + "es"); // Trying to do something smart?
            final int len2 = prefix.length() + 2;

            if (propInfo != null && (propInfo.type.isBean() || (propInfo.type.isCollection() && propInfo.type.elementType().isBean()))
                    && N.noneMatch(columnLabelList, it -> it.length() > len2 && it.charAt(len2) == '.' && Strings.startsWithIgnoreCase(it, prefix + "es."))) {
                // good
            } else {
                // Sorry, have done all I can do.
                propInfo = null;
            }
        }

        if (propInfo != null) {
            return propInfo.name + columnName.substring(idx);
        }

        return columnName;
    }

    // <<==============================================Jdbc Context=======================================================

    /**
     * Globally disables SQL logging across all threads in the application.
     * Once called, SQL statements will not be logged regardless of thread-local settings.
     * This setting cannot be reversed during the application lifecycle.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Disable SQL logging for production environment
     * JdbcUtil.disableSqlLogGlobally();
     * }</pre>
     *
     */
    public static void disableSqlLogGlobally() {
        isSqlLogAllowed = false;
    }

    /**
     * Globally disables SQL performance logging across all threads in the application.
     * Once called, SQL execution times will not be logged regardless of thread-local settings.
     * This setting cannot be reversed during the application lifecycle.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Disable SQL performance logging for production
     * JdbcUtil.disableSqlPerfLogGlobally();
     * }</pre>
     *
     */
    public static void disableSqlPerfLogGlobally() {
        isSqlPerfLogAllowed = false;
    }

    /**
     * Globally disables DAO method performance logging across all threads in the application.
     * Once called, DAO method execution times will not be logged regardless of thread-local settings.
     * This setting cannot be reversed during the application lifecycle.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Disable DAO performance logging
     * JdbcUtil.disableDaoMethodPerfLogGlobally();
     * }</pre>
     *
     */
    public static void disableDaoMethodPerfLogGlobally() {
        isDaoMethodPerfLogAllowed = false;
    }

    /**
     * Sets the current thread's SQL-logging state and maximum log length. This is the shared implementation
     * behind the public {@link #enableSqlLog(int)} / {@link #disableSqlLog()} entry points.
     *
     * @param enabled {@code true} to enable SQL logging, {@code false} to disable it.
     * @param maxSqlLogLength The maximum length of the SQL log. A value {@code <= 0} falls back to
     *        {@link #DEFAULT_MAX_SQL_LOG_LENGTH}; values of 1-3 are raised to 4, the smallest length
     *        the truncation marker supports.
     */
    static void setSqlLogEnabled(final boolean enabled, final int maxSqlLogLength) {
        final SqlLogConfig config = isSQLLogEnabled_TL.get();

        if (logger.isDebugEnabled() && config.isEnabled != enabled) {
            if (enabled) {
                logger.debug("Enabled SQL logging(maxSqlLogLength={})", maxSqlLogLength);
            } else {
                logger.debug("Disabled SQL logging");
            }
        }

        config.setSqlLogEnabled(enabled, maxSqlLogLength);
    }

    /**
     * Enables SQL logging on the current thread with the default maximum log length
     * ({@link #DEFAULT_MAX_SQL_LOG_LENGTH} = 1024 characters).
     *
     * <p>Once enabled, every SQL statement prepared on this thread is written to the
     * {@code com.landawn.abacus.SQL} logger at DEBUG level. Statements longer than the maximum log
     * length are abbreviated. The flag is stored in a {@link ThreadLocal}, so it only affects the
     * thread that called this method — do not rely on it propagating to threads you submit to an
     * {@link java.util.concurrent.Executor}.</p>
     *
     * <p>Has no effect if SQL logging has been globally disabled via {@link #disableSqlLogGlobally()}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.enableSqlLog();
     * try {
     *     // Execute SQL operations - they will be logged
     * } finally {
     *     JdbcUtil.disableSqlLog();
     * }
     * }</pre>
     *
     * @see #enableSqlLog(int)
     * @see #disableSqlLog()
     * @see #isSqlLogEnabled()
     */
    public static void enableSqlLog() {
        enableSqlLog(DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Enables SQL logging for the current thread with a specified maximum log length.
     * SQL statements longer than the specified length will be truncated in the logs.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.enableSqlLog(2048);   // Allow longer SQL statements in logs
     * // Execute SQL operations
     * // ...
     * JdbcUtil.disableSqlLog();
     * }</pre>
     *
     * @param maxSqlLogLength the maximum length of SQL statements in logs. A value {@code <= 0} falls
     *        back to {@link #DEFAULT_MAX_SQL_LOG_LENGTH}; values of 1-3 are raised to 4, the smallest
     *        length the truncation marker supports.
     */
    public static void enableSqlLog(final int maxSqlLogLength) {
        setSqlLogEnabled(true, maxSqlLogLength);
    }

    /**
     * Disables SQL logging for the current thread.
     * After calling this method, SQL statements executed in the current thread will not be logged.
     * The maximum SQL log length setting is preserved for when logging is re-enabled.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.enableSqlLog();
     * // SQL operations here will be logged
     *
     * JdbcUtil.disableSqlLog();
     * // SQL operations here will NOT be logged
     * }</pre>
     *
     */
    public static void disableSqlLog() {
        setSqlLogEnabled(false, isSQLLogEnabled_TL.get().maxSqlLogLength);
    }

    /**
     * Checks if SQL logging is enabled for the current thread.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.isSqlLogEnabled()) {
     *     System.out.println("SQL logging is active");
     * }
     * }</pre>
     *
     * @return {@code true} if SQL logging is enabled in the current thread, {@code false} otherwise.
     */
    public static boolean isSqlLogEnabled() {
        return isSQLLogEnabled_TL.get().isEnabled;
    }

    /**
     * Writes the given SQL statement to the SQL log if SQL logging is enabled on the current thread,
     * abbreviating it to the configured maximum length when necessary.
     *
     * @param sql The SQL statement to log.
     */
    static void logSql(final String sql) {
        if (!isSqlLogAllowed || !sqlLogger.isDebugEnabled()) {
            return;
        }

        final SqlLogConfig sqlLogConfig = isSQLLogEnabled_TL.get();

        if (sqlLogConfig.isEnabled) {
            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.debug(Strings.concat("[SQL]: ", sql));
            } else {
                sqlLogger.debug(Strings.concat("[SQL]: ", Strings.abbreviate(sql, sqlLogConfig.maxSqlLogLength)));
            }
        }
    }

    /**
     * Writes the {@code [SQL-PERF]} log entry for a completed statement execution if it took at least
     * {@link SqlLogConfig#sqlPerfLogThresholdMillis} milliseconds, and notifies the SQL log handler
     * registered via {@link #setSqlLogHandler(TriConsumer)}, if any.
     *
     * <p>Invoked from a {@code finally} block right after the statement finishes, so it must never throw:
     * a {@code RuntimeException} from the handler is logged and swallowed rather than allowed to mask the
     * statement's own outcome.</p>
     *
     * <p>The start time is deliberately captured on two clocks because neither can be derived from the other:
     * {@code startTimeNanos} ({@link System#nanoTime()}) is monotonic and yields a duration that stays correct
     * even if the wall clock is adjusted while the statement is running, while {@code startTimeMillis}
     * ({@link System#currentTimeMillis()}) anchors the epoch-based {@code (startTimeMillis, endTimeMillis)}
     * contract of the SQL log handler. The end timestamp passed to the handler is reconstructed as
     * {@code startTimeMillis + elapsed} so both values sit on the same epoch timeline.</p>
     *
     * @param stmt The statement that was just executed; SQL text is extracted from it for logging.
     * @param sqlLogConfig The per-thread perf-log configuration captured before execution.
     * @param startTimeMillis Wall-clock time ({@link System#currentTimeMillis()}) captured just before execution; reported to the SQL log handler.
     * @param startTimeNanos Monotonic time ({@link System#nanoTime()}) captured just before execution; used to compute the elapsed duration.
     */
    static void handleSqlLog(final Statement stmt, final SqlLogConfig sqlLogConfig, final long startTimeMillis, final long startTimeNanos) {
        final long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
        final long endTimeMillis = startTimeMillis + elapsedTime;
        String sql = null;

        final Throwables.Function<Statement, String, SQLException> sqlExtractor = N.defaultIfNull(JdbcUtil._sqlExtractor, JdbcUtil.DEFAULT_SQL_EXTRACTOR);

        if (isSqlPerfLogAllowed && sqlLogger.isInfoEnabled() && sqlLogConfig.sqlPerfLogThresholdMillis >= 0
                && elapsedTime >= sqlLogConfig.sqlPerfLogThresholdMillis) {
            sql = extractSqlForLog(stmt, sqlExtractor);

            if (sql.length() <= sqlLogConfig.maxSqlLogLength) {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), " ms, ", sql));
            } else {
                sqlLogger.info(Strings.concat("[SQL-PERF]: ", String.valueOf(elapsedTime), " ms, ", Strings.abbreviate(sql, sqlLogConfig.maxSqlLogLength)));
            }
        }

        final TriConsumer<String, Long, Long> sqlLogHandler = _sqlLogHandler;

        if (sqlLogHandler != null) {
            if (sql == null) {
                sql = extractSqlForLog(stmt, sqlExtractor);
            }

            try {
                sqlLogHandler.accept(sql, startTimeMillis, endTimeMillis);
            } catch (final RuntimeException e) {
                // Observability hooks must not turn a successful database operation into a failure or
                // replace the SQLException thrown by the JDBC driver.
                logger.warn(e, "Failed to invoke SQL log handler");
            }
        }
    }

    /**
     * Extracts the SQL text of a statement for logging, never throwing: returns an empty string if the
     * extractor fails or yields {@code null}, so logging can never mask a JDBC failure.
     *
     * @param stmt The statement whose SQL to extract.
     * @param sqlExtractor The extractor function to apply.
     * @return The extracted SQL text, or an empty string when unavailable.
     */
    private static String extractSqlForLog(final Statement stmt, final Throwables.Function<Statement, String, SQLException> sqlExtractor) {
        try {
            // A custom extractor registered via setSqlExtractor may return null.
            return N.defaultIfNull(sqlExtractor.apply(stmt), "");
        } catch (final Exception e) {
            // SQL extraction is diagnostic only. In particular, do not let it mask a JDBC failure
            // while this method is executing from a finally block.
            logger.warn(e, "Failed to extract SQL for logging");

            return "";
        }
    }

    /**
     * Returns whether SQL log handling (performance logging or a registered SQL log handler) needs to
     * run for the given configuration.
     *
     * @param sqlLogConfig The per-thread perf-log configuration.
     * @return {@code true} if a SQL log handler is registered or SQL performance logging is active.
     */
    static boolean isToHandleSqlLog(final SqlLogConfig sqlLogConfig) {
        return _sqlLogHandler != null || (isSqlPerfLogAllowed && sqlLogConfig.sqlPerfLogThresholdMillis >= 0 && sqlLogger.isInfoEnabled());
    }

    /**
     * Retrieves the current SQL extractor function used to extract SQL statements from Statement objects.
     * The SQL extractor is used internally for logging and monitoring purposes.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Throwables.Function<Statement, String, SQLException> extractor = JdbcUtil.getSqlExtractor();
     * // Use the extractor to get SQL from a statement
     * String sql = extractor.apply(statement);
     * }</pre>
     *
     * @return The current SQL extractor function; never {@code null}
     */
    public static Throwables.Function<Statement, String, SQLException> getSqlExtractor() {
        return JdbcUtil._sqlExtractor;
    }

    /**
     * Sets a custom SQL extractor function to extract SQL statements from Statement objects.
     * This is useful when using custom Statement implementations or when the default
     * extraction method doesn't work for your JDBC driver.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.setSqlExtractor(statement -> {
     *     if (statement instanceof MyCustomStatement) {
     *         return ((MyCustomStatement) statement).getOriginalSql();
     *     }
     *     return statement.toString();
     * });
     * }</pre>
     *
     * @param sqlExtractor The SQL extractor function to set; must not be {@code null}.
     * @throws IllegalArgumentException if {@code sqlExtractor} is {@code null}
     */
    public static void setSqlExtractor(final Throwables.Function<Statement, String, SQLException> sqlExtractor) {
        N.checkArgNotNull(sqlExtractor, cs.sqlExtractor);

        JdbcUtil._sqlExtractor = sqlExtractor;
    }

    /**
     * Retrieves the current SQL log handler that processes SQL statements and their execution times.
     * The handler receives the SQL statement, start time, and end time of execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * TriConsumer<String, Long, Long> handler = JdbcUtil.getSqlLogHandler();
     * if (handler != null) {
     *     // Handler is configured
     * }
     * }</pre>
     *
     * @return The current SQL log handler, or {@code null} if none is set.
     */
    public static TriConsumer<String, Long, Long> getSqlLogHandler() {
        return _sqlLogHandler;
    }

    /**
     * Sets a custom SQL log handler to process SQL statements and their execution times.
     * This allows for custom logging, monitoring, or alerting based on SQL execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.setSqlLogHandler((sql, startTime, endTime) -> {
     *     long duration = endTime - startTime;
     *     if (duration > 1000) { // Log slow queries
     *         logger.warn("Slow query ({}ms): {}", duration, sql);
     *     }
     *     // Send metrics to monitoring system
     *     metricsCollector.recordSqlExecution(sql, duration);
     * });
     * }</pre>
     *
     * @param sqlLogHandler The handler that receives: SQL statement, start time (ms), end time (ms); must not be {@code null}.
     * @throws IllegalArgumentException if {@code sqlLogHandler} is {@code null}
     */
    public static void setSqlLogHandler(final TriConsumer<String, Long, Long> sqlLogHandler) {
        N.checkArgNotNull(sqlLogHandler, cs.sqlLogHandler);

        _sqlLogHandler = sqlLogHandler;
    }

    /**
     * Sets the minimum execution time threshold for SQL performance logging in the current thread.
     * Only SQL statements that take at least this long to execute will be logged for performance monitoring.
     * Uses the default maximum SQL log length of {@value #DEFAULT_MAX_SQL_LOG_LENGTH} characters.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Log SQL statements that take 500ms or longer
     * JdbcUtil.setSqlPerfLogThresholdMillis(500);
     *
     * // Disable performance logging
     * JdbcUtil.setSqlPerfLogThresholdMillis(-1);
     * }</pre>
     *
     * @param sqlPerfLogThresholdMillis The minimum execution time in milliseconds (use a negative value to disable).
     */
    public static void setSqlPerfLogThresholdMillis(final long sqlPerfLogThresholdMillis) {
        setSqlPerfLogThresholdMillis(sqlPerfLogThresholdMillis, DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    /**
     * Sets the minimum execution time threshold for SQL performance logging in the current thread
     * with a specified maximum SQL log length.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Log SQL statements that take more than 1 second, with longer log length
     * JdbcUtil.setSqlPerfLogThresholdMillis(1000, 2048);
     *
     * // Disable performance logging
     * JdbcUtil.setSqlPerfLogThresholdMillis(-1);
     * }</pre>
     *
     * @param sqlPerfLogThresholdMillis The minimum execution time in milliseconds (use a negative value to disable).
     * @param maxSqlLogLength the maximum length of SQL statements in performance logs. A value {@code <= 0}
     *        falls back to {@link #DEFAULT_MAX_SQL_LOG_LENGTH}; values of 1-3 are raised to 4, the smallest
     *        length the truncation marker supports.
     */
    public static void setSqlPerfLogThresholdMillis(final long sqlPerfLogThresholdMillis, final int maxSqlLogLength) {
        final SqlLogConfig config = sqlPerfLogThresholdMillis_TL.get();
        // synchronized (sqlPerfLogThresholdMillis_TL) {
        if (logger.isDebugEnabled() && config.sqlPerfLogThresholdMillis != sqlPerfLogThresholdMillis) {
            if (sqlPerfLogThresholdMillis >= 0) {
                logger.debug("Set SQL performance logging threshold(minExecutionTime={}, maxSqlLogLength={})", sqlPerfLogThresholdMillis, maxSqlLogLength);
            } else {
                logger.debug("Disabled SQL performance logging");
            }
        }

        config.setSqlPerfLogThresholdMillis(sqlPerfLogThresholdMillis, maxSqlLogLength);
        // }
    }

    /**
     * Gets the current minimum execution time threshold for SQL performance logging in the current thread.
     * SQL statements that execute faster than this threshold will not be logged for performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * long threshold = JdbcUtil.getSqlPerfLogThresholdMillis();
     * System.out.println("Performance logging threshold: " + threshold + "ms");
     * }</pre>
     *
     * @return The minimum execution time in milliseconds (default is 1000ms).
     */
    public static long getSqlPerfLogThresholdMillis() {
        return sqlPerfLogThresholdMillis_TL.get().sqlPerfLogThresholdMillis;
    }

    /**
     * Executes the specified action with the standard SQL log temporarily disabled on the current thread.
     *
     * <p><b>&#9888; Warning:</b> This does not disable SQL performance logging or a configured SQL log handler,
     * and it does not redact SQL. Do not treat it as a security boundary for sensitive statements. The setting
     * is thread-local and is not inherited by work dispatched to another thread.</p>
     *
     * <p>The prior enabled/disabled state and maximum SQL log length are restored when the action finishes,
     * even if the action changes them or throws.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JdbcUtil.runWithSqlLogDisabled(() -> {
     *     // Execute sensitive SQL operations without logging
     *     JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE users SET password = ? WHERE id = ?",
     *         password, userId);
     * });
     * }</pre>
     *
     * @param <E> The type of exception that the action may throw.
     * @param sqlAction The action to execute without the standard SQL log, must not be {@code null}.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     * @throws E if the action throws an exception.
     */
    public static <E extends Exception> void runWithSqlLogDisabled(final Throwables.Runnable<E> sqlAction) throws E {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        final SqlLogConfig config = isSQLLogEnabled_TL.get();
        final boolean savedEnabled = config.isEnabled;
        final int savedMaxSqlLogLength = config.maxSqlLogLength;

        if (savedEnabled) {
            disableSqlLog();
        }

        try {
            sqlAction.run();
        } finally {
            // A scoped helper must not leak logging changes made by the callback into its caller.
            setSqlLogEnabled(savedEnabled, savedMaxSqlLogLength);
        }
    }

    /**
     * Executes the specified callable with the standard SQL log temporarily disabled on the current thread.
     *
     * <p><b>&#9888; Warning:</b> This does not disable SQL performance logging or a configured SQL log handler,
     * and it does not redact SQL. Do not treat it as a security boundary for sensitive statements. The setting
     * is thread-local and is not inherited by work dispatched to another thread.</p>
     *
     * <p>The prior enabled/disabled state and maximum SQL log length are restored when the callable finishes,
     * even if the callable changes them or throws.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String result = JdbcUtil.callWithSqlLogDisabled(() -> {
     *     // Execute sensitive query without logging
     *     return JdbcUtil.prepareQuery(dataSource, "SELECT email FROM users WHERE id = ?")
     *                    .setLong(1, userId)
     *                    .queryForString()
     *                    .orElse(null);
     * });
     * }</pre>
     *
     * @param <R> The type of result returned by the callable.
     * @param <E> The type of exception that the callable may throw.
     * @param sqlAction The callable to execute without the standard SQL log, must not be {@code null}.
     * @return The result of the callable.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     * @throws E if the callable throws an exception.
     */
    public static <R, E extends Exception> R callWithSqlLogDisabled(final Throwables.Callable<? extends R, E> sqlAction) throws E {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

        final SqlLogConfig config = isSQLLogEnabled_TL.get();
        final boolean savedEnabled = config.isEnabled;
        final int savedMaxSqlLogLength = config.maxSqlLogLength;

        if (savedEnabled) {
            disableSqlLog();
        }

        try {
            return sqlAction.call();
        } finally {
            // Restore the entire public SQL-log configuration rather than only the enabled flag.
            setSqlLogEnabled(savedEnabled, savedMaxSqlLogLength);
        }
    }

    /**
     * Returns whether an active transaction exists for the given {@link javax.sql.DataSource} on the
     * current thread.
     *
     * <p>This includes:</p>
     * <ul>
     *   <li>Transactions started by this library via {@link #beginTransaction(javax.sql.DataSource)}
     *       (or {@link #runInTransaction} / {@link #callInTransaction}).</li>
     *   <li>Spring-managed transactions, when Spring is on the classpath and Spring transaction
     *       participation is not disabled on this thread (see
     *       {@link #runIgnoringSpringTransaction(Throwables.Runnable)}).</li>
     * </ul>
     *
     * <p>For the Spring check, this method may briefly acquire and release a {@link Connection} from
     * {@code ds}, so callers should not assume it is side-effect-free. A {@link LinkageError} from a
     * mismatched Spring classpath is handled silently, but a connection-acquisition failure propagates
     * as an unchecked exception.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (JdbcUtil.isInTransaction(dataSource)) {
     *     // Execute operations within the existing transaction
     * } else {
     *     // Start a new transaction
     *     try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *         // Execute operations in the new transaction
     *         tran.commit();
     *     }
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} to check.
     * @return {@code true} if a transaction is active on the current thread for {@code ds};
     *         {@code false} otherwise.
     */
    public static boolean isInTransaction(final javax.sql.DataSource ds) {
        if (SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL) != null) {
            return true;
        }

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            Connection conn = null;
            boolean springLinkageFailure = false;

            try {
                // Acquire and release through the same Spring integration path. In particular, do
                // not switch the global flag before releasing a transaction-bound connection.
                conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);

                return org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(conn, ds);
            } catch (final LinkageError e) {
                // Catch any LinkageError (NoClassDefFoundError, NoSuchMethodError, etc.) so that
                // mismatched Spring versions or partial classpaths fall back gracefully instead of
                // propagating a fatal error.
                springLinkageFailure = true;
            } finally {
                if (conn != null) {
                    try {
                        org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
                    } catch (final LinkageError e) {
                        springLinkageFailure = true;
                        logger.warn(e, "Failed to release connection through Spring after checking transaction state");
                    }
                }

                if (springLinkageFailure) {
                    isInSpring = false;
                }
            }
        }

        return false;
    }

    /**
     * Begins a new database transaction with the default isolation level for the specified DataSource.
     * All subsequent JDBC operations in the same thread using this DataSource will participate in this transaction
     * until it is committed or rolled back. This provides ACID guarantees for multi-statement operations.
     *
     * <p>The transaction must be explicitly committed via {@code commit()} to persist changes, or rolled back
     * via {@code rollback()} or {@code rollbackIfNotCommitted()} to discard changes. Always use a try-finally
     * block to ensure the transaction is properly completed even if exceptions occur.</p>
     *
     * <p><b>Transaction Scope and Sharing:</b></p>
     * <p>The transaction is bound to the current thread and DataSource. All JDBC operations executed through
     * methods like {@code prepareQuery()}, {@code executeUpdate()}, etc. in the same thread with the same
     * DataSource will automatically use this transaction's connection. This includes operations in called methods,
     * enabling transactional behavior across method boundaries.</p>
     *
     * <p><b>Spring Integration:</b></p>
     * <p>Connection acquisition uses {@link #getConnection(javax.sql.DataSource)}, which can return a
     * Spring-bound connection. This method still creates a {@code SqlTransaction} scope, whose outermost
     * commit or rollback directly completes that connection. Avoid mixing explicit JdbcUtil transaction
     * scopes with Spring-owned transactions; use the surrounding Spring scope to control completion.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Basic transaction usage
     * SqlTransaction basicTran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO orders (customer_id, total) VALUES (?, ?)",
     *         customerId, total);
     *
     *     JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?",
     *         quantity, productId);
     *
     *     basicTran.commit();
     * } finally {
     *     basicTran.rollbackIfNotCommitted();
     * }
     *
     * // Transaction with conditional rollback
     * SqlTransaction conditionalTran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     int updatedRows = JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE accounts SET balance = balance - ? WHERE id = ? AND balance >= ?",
     *         amount, accountId, amount);
     *
     *     if (updatedRows == 0) {
     *         throw new InsufficientFundsException("Insufficient balance");
     *     }
     *
     *     JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO transactions (account_id, amount, type) VALUES (?, ?, ?)",
     *         accountId, amount, "DEBIT");
     *
     *     conditionalTran.commit();
     * } catch (Exception e) {
     *     // Transaction automatically rolled back in finally block
     *     logger.error("Transaction failed: " + e.getMessage());
     *     throw e;
     * } finally {
     *     conditionalTran.rollbackIfNotCommitted();
     * }
     *
     * // Transaction shared across method calls
     * public void processOrder(Order order) {
     *     SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     *     try {
     *         createOrder(order);   // Shares this transaction
     *         updateInventory(order);   // Shares this transaction
     *         sendNotification(order);   // Shares this transaction
     *         tran.commit();
     *     } finally {
     *         tran.rollbackIfNotCommitted();
     *     }
     * }
     *
     * private void createOrder(Order order) {
     *     // This automatically uses the transaction from processOrder()
     *     JdbcUtil.executeUpdate(dataSource,
     *         "INSERT INTO orders (id, customer_id, total) VALUES (?, ?, ?)",
     *         order.getId(), order.getCustomerId(), order.getTotal());
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} for which to begin the transaction, must not be {@code null}.
     * @return A {@link SqlTransaction} object representing the new transaction that must be committed or rolled back.
     * @throws IllegalArgumentException if {@code ds} is {@code null}.
     * @throws UncheckedSQLException if a database access error occurs while beginning the transaction.
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel)
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     * @see SqlTransaction#commit()
     * @see SqlTransaction#rollback()
     * @see SqlTransaction#rollbackIfNotCommitted()
     */
    public static SqlTransaction beginTransaction(final javax.sql.DataSource ds) throws UncheckedSQLException {
        return beginTransaction(ds, IsolationLevel.DEFAULT);
    }

    /**
     * Begins a new transaction with the specified isolation level for the given DataSource.
     * The transaction must be explicitly committed or rolled back.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
     * try {
     *     // Perform database operations with READ_COMMITTED isolation
     *     JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE accounts SET balance = balance + ? WHERE id = ?",
     *         amount, accountId);
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} for which to begin the transaction.
     * @param isolationLevel The isolation level for the transaction.
     * @return A {@link SqlTransaction} object representing the new transaction.
     * @throws IllegalArgumentException if {@code ds} or {@code isolationLevel} is {@code null}, or if
     *         {@code isolationLevel} is {@link IsolationLevel#NONE}, which is not a usable transaction isolation level.
     * @throws UncheckedSQLException if a database access error occurs while beginning the transaction.
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
     */
    public static SqlTransaction beginTransaction(final javax.sql.DataSource ds, final IsolationLevel isolationLevel) throws UncheckedSQLException {
        return beginTransaction(ds, isolationLevel, false);
    }

    /**
     * Starts a global transaction which will be shared by all in-line database queries with the same DataSource
     * in the same thread. This includes methods like prepareQuery, prepareNamedQuery, and prepareCallableQuery.
     *
     * <p>Spring Transaction is supported and integrated. If a Spring transaction is already active
     * with the specified DataSource, the Connection from the Spring transaction will be used.</p>
     *
     * <p><b>Example of transaction sharing:</b></p>
     * <pre>{@code
     * public void doSomethingA() {
     *     final SqlTransaction tranA = JdbcUtil.beginTransaction(dataSource1, IsolationLevel.DEFAULT, false);
     *     try {
     *         // Operations here share tranA
     *         doSomethingB();   // Shares tranA (same thread, same dataSource1)
     *         tranA.commit();
     *     } finally {
     *         tranA.rollbackIfNotCommitted();
     *     }
     * }
     *
     * public void doSomethingB() {
     *     final SqlTransaction tranB = JdbcUtil.beginTransaction(dataSource1, IsolationLevel.DEFAULT, false);
     *     try {
     *         // This reuses tranA from doSomethingA()
     *         tranB.commit();
     *     } finally {
     *         tranB.rollbackIfNotCommitted();
     *     }
     * }
     * }</pre>
     *
     * @param ds The {@link javax.sql.DataSource} for which to begin the transaction.
     * @param isolationLevel The isolation level for the transaction.
     * @param isForUpdateOnly Whether this transaction is only for update operations.
     * @return A {@link SqlTransaction} object representing the transaction.
     * @throws IllegalArgumentException if {@code ds} or {@code isolationLevel} is {@code null}, or if
     *         {@code isolationLevel} is {@link IsolationLevel#NONE}, which is not a usable transaction isolation level.
     * @throws UncheckedSQLException if a database access error occurs while beginning the transaction.
     * @see JdbcUtil#getConnection(javax.sql.DataSource)
     * @see JdbcUtil#releaseConnection(Connection, javax.sql.DataSource)
     */
    public static SqlTransaction beginTransaction(final javax.sql.DataSource ds, final IsolationLevel isolationLevel, final boolean isForUpdateOnly)
            throws UncheckedSQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(isolationLevel, cs.isolationLevel);

        SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran == null) {
            Connection conn = null;
            boolean noException = false;

            try { //NOSONAR
                conn = JdbcUtil.getConnection(ds);
                tran = new SqlTransaction(ds, conn, isolationLevel, CreatedBy.JDBC_UTIL, true); //NOSONAR
                tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);

                noException = true;
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                if (!noException) {
                    if (tran != null) {
                        // Constructor succeeded (so conn was mutated to autoCommit=false / requested isolation) but a
                        // later step (incrementAndGetRef) failed — restore the connection's original state via
                        // SqlTransaction's reset path before it goes back to the pool.
                        tran.resetAndCloseConnection();
                    } else {
                        JdbcUtil.releaseConnection(conn, ds);
                    }
                }
            }

            logger.debug("Created new SqlTransaction(id={})", tran.id());
            SqlTransaction.putTransaction(tran);
        } else {
            logger.debug("Reusing existing SqlTransaction(id={})", tran.id());
            tran.incrementAndGetRef(isolationLevel, isForUpdateOnly);
        }

        return tran;
    }

    /**
     * Executes the given callable within a transaction and returns its result.
     * The transaction is committed if the callable completes normally; it is rolled back
     * if the callable throws any exception.
     *
     * <p>This is a convenience alternative to the {@code beginTransaction} / {@code commit} /
     * {@code rollbackIfNotCommitted} pattern. It uses the {@link IsolationLevel#DEFAULT} isolation
     * level (i.e., the database's own default).</p>
     *
     * <p><b>Nested calls:</b> If a transaction for {@code ds} is already active on the current
     * thread when this method is invoked, the callable participates in that existing transaction
     * (the reference count is incremented). The underlying {@code Connection} is not actually
     * committed until the outermost transaction scope closes.</p>
     *
     * <p><b>Spring integration:</b> Connection acquisition can reuse a Spring-bound connection, but this
     * method completes its own {@code SqlTransaction} scope. See the transaction-ownership guidance on
     * {@link #beginTransaction(javax.sql.DataSource)} before using it inside a Spring-owned transaction.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Simple transactional operation returning a value
     * long newOrderId = JdbcUtil.callInTransaction(dataSource, () -> {
     *     JdbcUtil.executeUpdate(dataSource,
     *         "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?",
     *         quantity, productId);
     *     return JdbcUtil.prepareQuery(dataSource,
     *         "INSERT INTO orders (customer_id, total) VALUES (?, ?)", true)   // true: return generated keys
     *         .setLong(1, customerId)
     *         .setBigDecimal(2, total)
     *         .<Long> insert()
     *         .orElse(0L);
     * });
     *
     * // Nested call — participates in the outer transaction
     * SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     JdbcUtil.callInTransaction(dataSource, () -> {
     *         // Runs inside 'outer'; no new connection is created
     *         orderDao.save(order);
     *         return order.getId();
     *     });
     *     outer.commit();
     * } finally {
     *     outer.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param <R> The type of the result returned by the callable.
     * @param <E> The type of exception that the callable may throw.
     * @param ds The {@link javax.sql.DataSource} for the transaction, must not be {@code null}.
     * @param cmd The callable to execute within the transaction, must not be {@code null}.
     * @return The result returned by {@code cmd}.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it.
     * @see #runInTransaction(javax.sql.DataSource, Throwables.Runnable)
     * @see #beginTransaction(javax.sql.DataSource)
     */
    @Beta
    public static <R, E extends Throwable> R callInTransaction(final javax.sql.DataSource ds, final Throwables.Callable<? extends R, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        R result = null;
        Throwable failure = null;

        try {
            result = cmd.call();
            tran.commit();
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            rollbackAfterTransactionCommand(tran, failure);
        }

        return result;
    }

    /**
     * Executes the given function within a transaction, passing the transaction's {@link Connection}
     * as an argument, and returns its result.
     * The transaction is committed if the function completes normally; it is rolled back if the
     * function throws any exception.
     *
     * <p>Use this overload when the callable logic needs direct access to the {@link Connection}
     * (e.g., to create {@link java.sql.PreparedStatement}s manually or call APIs that require a
     * {@link Connection} parameter). The supplied connection is the same one used by the enclosing
     * transaction — do <em>not</em> close it.</p>
     *
     * <p><b>Nested calls:</b> If a transaction for {@code ds} is already active on the current
     * thread, the function participates in that existing transaction. The connection passed to
     * {@code cmd} is the existing transaction's connection.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Access the raw Connection inside a transaction
     * Dataset result = JdbcUtil.callInTransaction(dataSource, conn -> {
     *     JdbcUtil.executeUpdate(conn,
     *         "UPDATE accounts SET balance = balance - ? WHERE id = ?",
     *         amount, fromAccountId);
     *     JdbcUtil.executeUpdate(conn,
     *         "UPDATE accounts SET balance = balance + ? WHERE id = ?",
     *         amount, toAccountId);
     *     return JdbcUtil.executeQuery(conn,
     *         "SELECT id, balance FROM accounts WHERE id IN (?, ?)",
     *         fromAccountId, toAccountId);
     * });
     * }</pre>
     *
     * @param <T> The type of the result returned by the function.
     * @param <E> The type of exception that the function may throw.
     * @param ds The {@link javax.sql.DataSource} for the transaction, must not be {@code null}.
     * @param cmd The function to execute with the transaction's {@link Connection}, must not be {@code null};
     *            the connection must not be closed by the caller.
     * @return The result returned by {@code cmd}.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it.
     * @see #runInTransaction(javax.sql.DataSource, Throwables.Consumer)
     * @see #callInTransaction(javax.sql.DataSource, Throwables.Callable)
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Function<Connection, T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        T result = null;
        Throwable failure = null;

        try {
            result = cmd.apply(tran.connection());
            tran.commit();
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            rollbackAfterTransactionCommand(tran, failure);
        }

        return result;
    }

    /**
     * Executes the given runnable within a transaction.
     * The transaction is committed if the runnable completes normally; it is rolled back
     * if the runnable throws any exception.
     *
     * <p>This is the void counterpart of
     * {@link #callInTransaction(javax.sql.DataSource, Throwables.Callable)}. It uses the
     * {@link IsolationLevel#DEFAULT} isolation level (the database's own default).</p>
     *
     * <p><b>Nested calls:</b> If a transaction for {@code ds} is already active on the current
     * thread, the runnable participates in that existing transaction (the reference count is
     * incremented). The underlying {@code Connection} is not actually committed until the
     * outermost transaction scope closes.</p>
     *
     * <p><b>Spring integration:</b> Connection acquisition can reuse a Spring-bound connection, but this
     * method completes its own {@code SqlTransaction} scope. See the transaction-ownership guidance on
     * {@link #beginTransaction(javax.sql.DataSource)} before using it inside a Spring-owned transaction.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Atomically insert a user and write an audit record
     * JdbcUtil.runInTransaction(dataSource, () -> {
     *     userDao.insert(user);
     *     auditDao.logUserCreation(user);
     * });
     *
     * // Nested call inside an existing transaction
     * SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     JdbcUtil.runInTransaction(dataSource, () -> {
     *         // Participates in 'outer'; no new connection is created
     *         inventoryDao.decrement(productId, quantity);
     *     });
     *     outer.commit();
     * } finally {
     *     outer.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param <E> The type of exception that the runnable may throw.
     * @param ds The {@link javax.sql.DataSource} for the transaction, must not be {@code null}.
     * @param cmd The runnable to execute within the transaction, must not be {@code null}.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it.
     * @see #callInTransaction(javax.sql.DataSource, Throwables.Callable)
     * @see #beginTransaction(javax.sql.DataSource)
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        Throwable failure = null;

        try {
            cmd.run();
            tran.commit();
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            rollbackAfterTransactionCommand(tran, failure);
        }
    }

    /**
     * Executes the given consumer within a transaction, passing the transaction's {@link Connection}
     * as an argument.
     * The transaction is committed if the consumer completes normally; it is rolled back if the
     * consumer throws any exception.
     *
     * <p>Use this overload when the transactional logic needs direct access to the {@link Connection}
     * (e.g., to call APIs that require a {@link Connection} parameter). The supplied connection is
     * the same one used by the enclosing transaction — do <em>not</em> close it.</p>
     *
     * <p><b>Nested calls:</b> If a transaction for {@code ds} is already active on the current
     * thread, the consumer participates in that existing transaction. The connection passed to
     * {@code cmd} is the existing transaction's connection.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Batch-update using the raw Connection
     * JdbcUtil.runInTransaction(dataSource, conn -> {
     *     try (PreparedStatement ps = conn.prepareStatement(
     *             "UPDATE orders SET status = ? WHERE id = ?")) {
     *         for (Order order : ordersToClose) {
     *             ps.setString(1, "CLOSED");
     *             ps.setLong(2, order.getId());
     *             ps.addBatch();
     *         }
     *         ps.executeBatch();
     *     }
     * });
     * }</pre>
     *
     * @param <E> The type of exception that the consumer may throw.
     * @param ds The {@link javax.sql.DataSource} for the transaction, must not be {@code null}.
     * @param cmd The consumer to execute with the transaction's {@link Connection}, must not be {@code null};
     *            the connection must not be closed by the caller.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it.
     * @see #callInTransaction(javax.sql.DataSource, Throwables.Function)
     * @see #runInTransaction(javax.sql.DataSource, Throwables.Runnable)
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<Connection, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        Throwable failure = null;

        try {
            cmd.accept(tran.connection());
            tran.commit();
        } catch (final Throwable e) { //NOSONAR
            failure = e;
            throw e;
        } finally {
            rollbackAfterTransactionCommand(tran, failure);
        }
    }

    /**
     * Rolls back the given transaction if it has not been committed, attaching any rollback failure to
     * the command failure (if any) as a suppressed exception so it cannot mask the original failure.
     *
     * @param tran The transaction to roll back.
     * @param commandFailure The failure thrown by the transaction command, or {@code null} if it completed normally.
     */
    static void rollbackAfterTransactionCommand(final SqlTransaction tran, final Throwable commandFailure) {
        try {
            tran.rollbackIfNotCommitted();
        } catch (final RuntimeException | Error rollbackFailure) {
            if (commandFailure == null) {
                throw rollbackFailure;
            }

            addSuppressedIfDistinct(commandFailure, rollbackFailure);
        }
    }

    /**
     * Executes the given callable outside any active transaction bound to {@code ds} on the
     * current thread, and returns its result.
     *
     * <p><b>Behaviour depends on whether a transaction is currently active:</b></p>
     * <ul>
     *   <li><b>No active transaction:</b> {@code cmd} is invoked directly; no transaction or connection
     *       is created by this wrapper. Database operations inside it acquire connections as needed.</li>
     *   <li><b>Active transaction:</b> the current transaction is temporarily suspended
     *       (removed from the thread-local map) for the duration of {@code cmd}. The callable
     *       therefore runs on a <em>separate</em> connection that is <em>not</em> part of the
     *       surrounding transaction. When {@code cmd} finishes (normally or exceptionally) the
     *       original transaction is restored. If another transaction is opened and not closed
     *       inside {@code cmd}, an {@link IllegalStateException} is thrown.</li>
     * </ul>
     *
     * <p>This is useful for operations that must be committed immediately and independently of
     * any surrounding transaction — for example, writing an audit log entry or updating a status
     * flag that should survive even if the outer transaction is later rolled back.</p>
     *
     * <p><b>Spring integration:</b> When running inside a Spring-managed transaction context,
     * Spring's transaction participation is also temporarily disabled for the duration of
     * {@code cmd}, so the callable does not join any Spring {@code @Transactional} transaction
     * either.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Persist an audit record that must not be rolled back with the main transaction
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     orderDao.save(order);   // part of tran
     *
     *     String auditId = JdbcUtil.callOutsideTransaction(dataSource, () -> {
     *         // Runs on a separate connection; committed independently of 'tran'
     *         return auditDao.insertAndReturnId("ORDER_CREATED", order.getId());
     *     });
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     *
     * // No active transaction — runs directly with a fresh connection
     * String token = JdbcUtil.callOutsideTransaction(dataSource, () ->
     *     tokenStore.generateAndPersist(userId));
     * }</pre>
     *
     * @param <R> The type of the result returned by the callable.
     * @param <E> The type of exception that the callable may throw.
     * @param ds The {@link javax.sql.DataSource} whose active transaction (if any) should be
     *           suspended, must not be {@code null}.
     * @param cmd The callable to execute outside any active transaction, must not be {@code null}.
     * @return The result returned by {@code cmd}.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws E if {@code cmd} throws an exception.
     * @throws IllegalStateException if another transaction is opened but not closed inside {@code cmd}.
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)
     * @see SqlTransaction#callOutsideTransaction(Throwables.Callable)
     */
    @Beta
    public static <R, E extends Throwable> R callOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Callable<? extends R, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    return cmd.call();
                } else {
                    return tran.callOutsideTransaction(cmd);
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.call();
            } else {
                return tran.callOutsideTransaction(cmd);
            }
        }
    }

    /**
     * Executes the given function outside any active transaction bound to {@code ds} on the
     * current thread, passing {@code ds} as an argument, and returns its result.
     *
     * <p>This overload is identical in semantics to
     * {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}, except that
     * the {@link javax.sql.DataSource} is forwarded to the function so that the function body
     * can obtain its own connections directly from the pool.</p>
     *
     * <p><b>Behaviour depends on whether a transaction is currently active:</b></p>
     * <ul>
     *   <li><b>No active transaction:</b> {@code cmd.apply(ds)} is called immediately.</li>
     *   <li><b>Active transaction:</b> the current transaction is temporarily suspended for
     *       the duration of {@code cmd}. Connections obtained from {@code ds} inside the
     *       function are <em>not</em> part of the surrounding transaction. The original
     *       transaction is restored when {@code cmd} finishes.</li>
     * </ul>
     *
     * <p><b>Note:</b> Any {@link Connection} obtained directly from {@code ds} inside
     * {@code cmd} must be closed by the caller (e.g., via try-with-resources) to avoid
     * connection-pool leaks.</p>
     *
     * <p><b>Spring integration:</b> Spring's transaction participation is also temporarily
     * disabled for the duration of {@code cmd}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Use the DataSource directly to perform work outside the active transaction
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     orderDao.save(order);   // part of tran
     *
     *     String snapshot = JdbcUtil.callOutsideTransaction(dataSource, ds -> {
     *         // 'ds' here is the same DataSource, but connections it vends are
     *         // NOT part of 'tran'
     *         return JdbcUtil.prepareQuery(ds, "SELECT snapshot FROM config WHERE key = ?")
     *             .setString(1, "ORDER_TEMPLATE")
     *             .queryForString()
     *             .orElse(null);
     *     });
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param <T> The type of the result returned by the function.
     * @param <E> The type of exception that the function may throw.
     * @param ds The {@link javax.sql.DataSource} whose active transaction (if any) should be
     *           suspended, and which is passed as the argument to {@code cmd}; must not be {@code null}.
     * @param cmd The function to execute outside any active transaction, must not be {@code null}.
     * @return The result returned by {@code cmd}.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws E if {@code cmd} throws an exception.
     * @throws IllegalStateException if another transaction is opened but not closed inside {@code cmd}.
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Consumer)
     */
    @Beta
    public static <T, E extends Throwable> T callOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Function<javax.sql.DataSource, T, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    return cmd.apply(ds);
                } else {
                    return tran.callOutsideTransaction(() -> cmd.apply(ds));
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.apply(ds);
            } else {
                return tran.callOutsideTransaction(() -> cmd.apply(ds));
            }
        }
    }

    /**
     * Executes the given runnable outside any active transaction bound to {@code ds} on the
     * current thread.
     *
     * <p>This is the void counterpart of
     * {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}.
     * The same suspension semantics apply:</p>
     * <ul>
     *   <li><b>No active transaction:</b> {@code cmd} is invoked directly; no transaction or connection
     *       is created by this wrapper. Database operations inside it acquire connections as needed.</li>
     *   <li><b>Active transaction:</b> the current transaction is temporarily suspended for the
     *       duration of {@code cmd}. The runnable runs on a <em>separate</em> connection that is
     *       <em>not</em> part of the surrounding transaction. The original transaction is restored
     *       when {@code cmd} finishes (normally or exceptionally).</li>
     * </ul>
     *
     * <p>Typical use cases include writing records that must survive a potential outer rollback
     * (e.g., error logs, audit events, distributed-lock releases) and refreshing caches or
     * external systems that should not be deferred until the outer transaction commits.</p>
     *
     * <p><b>Spring integration:</b> Spring's transaction participation is also temporarily
     * disabled for the duration of {@code cmd}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Log an event that should persist even if the enclosing transaction rolls back
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     inventoryDao.reserve(itemId, qty);   // part of tran
     *
     *     JdbcUtil.runOutsideTransaction(dataSource, () -> {
     *         // Committed immediately on a separate connection; survives 'tran' rollback
     *         eventBus.persistEvent("RESERVATION_ATTEMPTED", itemId);
     *     });
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param <E> The type of exception that the runnable may throw.
     * @param ds The {@link javax.sql.DataSource} whose active transaction (if any) should be
     *           suspended, must not be {@code null}.
     * @param cmd The runnable to execute outside any active transaction, must not be {@code null}.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws E if {@code cmd} throws an exception.
     * @throws IllegalStateException if another transaction is opened but not closed inside {@code cmd}.
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)
     * @see SqlTransaction#runOutsideTransaction(Throwables.Runnable)
     */
    @Beta
    public static <E extends Throwable> void runOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    cmd.run();
                } else {
                    tran.runOutsideTransaction(cmd);
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.run();
            } else {
                tran.runOutsideTransaction(cmd);
            }
        }
    }

    /**
     * Executes the given consumer outside any active transaction bound to {@code ds} on the
     * current thread, passing {@code ds} as an argument.
     *
     * <p>This is the void counterpart of
     * {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Function)}.
     * It is semantically identical to
     * {@link #runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)}, except that
     * the {@link javax.sql.DataSource} is forwarded to the consumer so that the consumer body
     * can obtain its own connections directly from the pool.</p>
     *
     * <p><b>Behaviour depends on whether a transaction is currently active:</b></p>
     * <ul>
     *   <li><b>No active transaction:</b> {@code cmd.accept(ds)} is called immediately.</li>
     *   <li><b>Active transaction:</b> the current transaction is temporarily suspended for
     *       the duration of {@code cmd}. Connections obtained from {@code ds} inside the
     *       consumer are <em>not</em> part of the surrounding transaction.</li>
     * </ul>
     *
     * <p><b>Note:</b> Any {@link Connection} obtained directly from {@code ds} inside
     * {@code cmd} must be closed by the caller (e.g., via try-with-resources) to avoid
     * connection-pool leaks.</p>
     *
     * <p><b>Spring integration:</b> Spring's transaction participation is also temporarily
     * disabled for the duration of {@code cmd}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Invalidate a cache entry using a separate connection, outside the active transaction
     * JdbcUtil.runOutsideTransaction(dataSource, ds -> {
     *     JdbcUtil.executeUpdate(ds,
     *         "DELETE FROM query_cache WHERE entity_type = ? AND entity_id = ?",
     *         "ORDER", order.getId());
     * });
     * }</pre>
     *
     * @param <E> The type of exception that the consumer may throw.
     * @param ds The {@link javax.sql.DataSource} whose active transaction (if any) should be
     *           suspended, and which is passed as the argument to {@code cmd}; must not be {@code null}.
     * @param cmd The consumer to execute outside any active transaction, must not be {@code null}.
     * @throws IllegalArgumentException if {@code ds} or {@code cmd} is {@code null}.
     * @throws E if {@code cmd} throws an exception.
     * @throws IllegalStateException if another transaction is opened but not closed inside {@code cmd}.
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Function)
     */
    @Beta
    public static <E extends Throwable> void runOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd)
            throws IllegalArgumentException, E {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        if (isInSpring && !isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            doNotUseSpringTransactional(true);

            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            try {
                if (tran == null) {
                    cmd.accept(ds);
                } else {
                    tran.runOutsideTransaction(() -> cmd.accept(ds));
                }
            } finally {
                doNotUseSpringTransactional(false);
            }
        } else {
            final SqlTransaction tran = SqlTransaction.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.accept(ds);
            } else {
                tran.runOutsideTransaction(() -> cmd.accept(ds));
            }
        }
    }

    /**
     * Executes the given runnable with Spring's transaction management temporarily disabled for
     * the current thread.
     *
     * <p>When this library is used inside a Spring application, JDBC connections are normally
     * obtained via {@code DataSourceUtils.getConnection()}, which participates in any
     * Spring-managed ({@code @Transactional}) transaction that is active on the calling thread.
     * This method sets a thread-local flag that prevents that participation for the duration of
     * {@code sqlAction}, so connections obtained from a {@link javax.sql.DataSource} during
     * execution come from the pool directly instead of from Spring's transaction synchronization.</p>
     *
     * <p><b>Scope:</b> this method only affects Spring's transaction binding. It does <em>not</em>
     * suspend a transaction that was started via {@link #beginTransaction} or
     * {@link #runInTransaction}. To escape this library's own transactions, use
     * {@link #runOutsideTransaction} instead.</p>
     *
     * <p><b>No-op when Spring is absent:</b> if Spring's framework classes are not on the
     * classpath, or if Spring transaction management is already disabled on this thread, the
     * runnable is executed directly without any flag manipulation.</p>
     *
     * <p><b>&#9888; Warning:</b> The flag is stored in a {@link ThreadLocal}. Do <em>not</em>
     * pass {@code sqlAction} to another thread (e.g., via {@code CompletableFuture} or an
     * {@link java.util.concurrent.Executor}) — the receiving thread will not inherit the
     * disabled state.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Inside a Spring @Transactional service method, perform one operation
     * // that must use its own fresh connection rather than the Spring-managed one
     * @Transactional
     * public void processOrder(Order order) {
     *     orderRepository.save(order);   // uses Spring transaction
     *
     *     JdbcUtil.runIgnoringSpringTransaction(() -> {
     *         // Acquires a fresh connection; NOT part of the Spring transaction above
     *         auditDao.recordImmediately("ORDER_PROCESSING_STARTED", order.getId());
     *     });
     * }
     * }</pre>
     *
     * @param <E> The type of exception that the runnable may throw.
     * @param sqlAction The runnable to execute with Spring transaction participation disabled,
     *                  must not be {@code null}; must not be dispatched to another thread.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     * @throws E if {@code sqlAction} throws an exception.
     * @see #callIgnoringSpringTransaction(Throwables.Callable)
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)
     */
    public static <E extends Exception> void runIgnoringSpringTransaction(final Throwables.Runnable<E> sqlAction) throws E {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

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
     * Executes the given callable with Spring's transaction management temporarily disabled for
     * the current thread, and returns its result.
     *
     * <p>This is the value-returning counterpart of
     * {@link #runIgnoringSpringTransaction(Throwables.Runnable)}.
     * The same semantics apply — see that method for a full explanation of the Spring-bypass
     * mechanism, scope, no-op conditions, and thread-safety constraints.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Inside a Spring @Transactional method, read committed data that is not yet
     * // visible in the current transaction (using a separate connection)
     * @Transactional
     * public OrderSummary createAndSummarize(Order order) {
     *     orderRepository.save(order);
     *
     *     // Read the pre-existing aggregate using a fresh, non-transactional connection
     *     BigDecimal runningTotal = JdbcUtil.callIgnoringSpringTransaction(() ->
     *         JdbcUtil.prepareQuery(dataSource,
     *             "SELECT SUM(total) FROM orders WHERE customer_id = ?")
     *             .setLong(1, order.getCustomerId())
     *             .queryForBigDecimal()
     *             .orElse(BigDecimal.ZERO));
     *
     *     return new OrderSummary(order, runningTotal);
     * }
     * }</pre>
     *
     * @param <R> The type of the result returned by the callable.
     * @param <E> The type of exception that the callable may throw.
     * @param sqlAction The callable to execute with Spring transaction participation disabled,
     *                  must not be {@code null}; must not be dispatched to another thread.
     * @return The result returned by {@code sqlAction}.
     * @throws IllegalArgumentException if {@code sqlAction} is {@code null}.
     * @throws E if {@code sqlAction} throws an exception.
     * @see #runIgnoringSpringTransaction(Throwables.Runnable)
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)
     */
    public static <R, E extends Exception> R callIgnoringSpringTransaction(final Throwables.Callable<? extends R, E> sqlAction) throws E {
        N.checkArgNotNull(sqlAction, cs.sqlAction);

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

    /**
     * Disable {@code Spring Transactional} integration in the current thread.
     *
     * {@code Spring Transactional} won't be used when fetching a Connection if it's disabled.
     *
     * @param b {@code true} to disable, {@code false} to re-enable.
     */
    static void doNotUseSpringTransactional(final boolean b) {
        if (isInSpring) {
            // DEBUG, not WARN: toggling is the intended behavior of the public
            // runIgnoringSpringTransaction/callIgnoringSpringTransaction API, invoked per call.
            if (logger.isDebugEnabled() && isSpringTransactionalDisabled_TL.get() != b) { //NOSONAR
                if (b) {
                    logger.debug("Disabled Spring Transactional integration");
                } else {
                    logger.debug("Re-enabled Spring Transactional integration");
                }
            }

            if (b) {
                isSpringTransactionalDisabled_TL.set(true);
            } else {
                isSpringTransactionalDisabled_TL.remove();
            }
        } else {
            logger.debug("Spring framework not detected or unable to retrieve Spring transaction context");
        }
    }

    /**
     * Checks whether {@code Spring Transactional} integration is disabled in the current thread.
     *
     * @return {@code true} if Spring Transactional integration is disabled (or Spring is not present), otherwise {@code false}.
     */
    static boolean isSpringTransactionalNotUsed() {
        return !isInSpring || isSpringTransactionalDisabled_TL.get();
    }

    /**
     * Returns the tuple of (generated-key extractor, ID getter, ID setter) used to populate entity IDs
     * from JDBC generated keys for the given DAO interface and entity class.
     *
     * @param <ID> The ID type.
     * @param daoInterface The DAO interface class.
     * @param entityClass The entity class.
     * @param namingPolicy The naming policy used to map between property and column names.
     * @param idType The ID type.
     * @return A {@link Tuple3} of the key extractor, ID getter, and ID setter.
     */
    @SuppressWarnings({ "rawtypes", "deprecation", "null" })
    static synchronized <ID> Tuple3<BiRowMapper<ID>, com.landawn.abacus.util.function.Function<Object, ID>, com.landawn.abacus.util.function.BiConsumer<ID, Object>> getIdGeneratorGetterSetter(
            final Class<? extends DaoBase> daoInterface, final Class<?> entityClass, final NamingPolicy namingPolicy, final Class<?> idType) {
        if (!Beans.isBeanClass(entityClass)) {
            return (Tuple3) noIdGeneratorGetterSetter;
        }

        final Tuple3<Class<?>, Class<?>, Class<?>> key = Tuple.of(daoInterface, entityClass, idType);

        Map<NamingPolicy, Tuple3<BiRowMapper, com.landawn.abacus.util.function.Function, com.landawn.abacus.util.function.BiConsumer>> map = idGeneratorGetterSetterPool
                .get(key);

        if (map == null) {
            final List<String> idPropNameList = QueryUtil.idPropNames(entityClass);
            final boolean isNoId = N.isEmpty(idPropNameList);
            final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
            final BeanInfo entityInfo = isNoId ? null : ParserUtil.getBeanInfo(entityClass);
            final List<PropInfo> idPropInfoList = isNoId ? null : Stream.of(idPropNameList).map(entityInfo::getPropInfo).toList();
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = !isNoId && idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);
            final BeanInfo idBeanInfo = Beans.isBeanClass(idType) ? ParserUtil.getBeanInfo(idType) : null;

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
                                    Beans.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) idBeanInfo.finishBeanResult(ret);
                            }));

            final com.landawn.abacus.util.function.BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> idPropInfo.setPropValue(entity, id) //
                            : (isEntityId ? (id, entity) -> {
                                if (id instanceof final EntityId entityId) {
                                    PropInfo propInfo = null;

                                    for (final String propName : entityId.keySet()) {
                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null) {
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                        }
                                    }
                                } else {
                                    logger.warn("Cannot set generated keys for unsupported id type(idType={})",
                                            id == null ? "null" : ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            } : (id, entity) -> {
                                if (id != null && Beans.isBeanClass(id.getClass())) {
                                    @SuppressWarnings("UnnecessaryLocalVariable")
                                    final Object entityId = id;

                                    for (final PropInfo propInfo : idPropInfoList) {
                                        propInfo.setPropValue(entity, Beans.getPropValue(entityId, propInfo.name));
                                    }
                                } else {
                                    logger.warn("Cannot set generated keys for unsupported id type(idType={})",
                                            id == null ? "null" : ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            }));

            map = new EnumMap<>(NamingPolicy.class);

            for (final NamingPolicy np : NamingPolicy.values()) {
                final ImmutableMap<String, String> propColumnNameMap = QueryUtil.propToColumnNameMap(entityClass, np);

                final ImmutableMap<String, String> columnPropNameMap = EntryStream.of(propColumnNameMap)
                        .inverted()
                        .flatmapKey(e -> N.asList(e, e.toLowerCase(Locale.ROOT), e.toUpperCase(Locale.ROOT)))
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
                                                        id.set(columnName, JdbcUtil.getColumnValue(rs, i + 1));
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

    /**
     * Registers a custom ID extractor for the specified {@link CrudDao} interface.
     *
     * <p>The supplied {@link RowMapper} is invoked against the generated-keys {@link ResultSet} returned
     * by JDBC after an {@code INSERT} so that the DAO can populate the entity's ID property. Register a
     * custom extractor when the default extraction (single column → ID) does not fit your schema — for
     * example, when the entity has a composite ID that is returned across several generated columns.</p>
     *
     * <p>The registration is process-wide and is captured when a DAO proxy is initialized.
     * Register the extractor before the first {@link #createDao} call for this interface. Replacing a
     * registration affects newly initialized proxies; existing proxies, including those returned from
     * the factory cache, retain their previously captured extractor.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom ID extraction for composite keys
     * JdbcUtil.setIdExtractorForDao(UserDao.class, rs -> {
     *     return new UserId(rs.getLong("tenant_id"), rs.getLong("user_id"));
     * });
     * }</pre>
     *
     * @param <T> The entity type managed by the DAO.
     * @param <ID> The ID type returned by the extractor.
     * @param <TD> The concrete {@link CrudDao} subtype.
     * @param daoInterface The DAO interface class, must not be {@code null}.
     * @param idExtractor The {@link RowMapper} used to read the generated key(s) from the generated-keys
     *                    {@code ResultSet}, must not be {@code null}.
     * @throws IllegalArgumentException if {@code daoInterface} or {@code idExtractor} is {@code null}.
     * @see #setIdExtractorForDao(Class, BiRowMapper)
     */
    public static synchronized <T, ID, TD extends CrudDao<T, ID, TD>> void setIdExtractorForDao(final Class<? extends CrudDao<T, ID, TD>> daoInterface,
            final RowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

        idExtractorPool.put(daoInterface, (rs, cls) -> idExtractor.apply(rs));

        // Drop cached key extractors built before this registration so future createDao calls pick it up.
        idGeneratorGetterSetterPool.keySet().removeIf(key -> key._1.equals(daoInterface));
    }

    /**
     * Registers a custom ID extractor for the specified {@link CrudDao} interface using a
     * {@link BiRowMapper}, which additionally receives the column labels of the generated-keys
     * {@link ResultSet}.
     *
     * <p>Use this overload when extraction logic needs to dispatch on the names/positions of the
     * generated columns (e.g., the driver returns a different column set depending on which auto-generated
     * keys are configured).</p>
     *
     * <p>The registration is process-wide and is captured when a DAO proxy is initialized.
     * Register the extractor before the first {@link #createDao} call for this interface. Replacing a
     * registration affects newly initialized proxies; existing proxies, including those returned from
     * the factory cache, retain their previously captured extractor.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Custom ID extraction with column label awareness
     * JdbcUtil.setIdExtractorForDao(UserDao.class, (rs, columnLabels) -> {
     *     if (columnLabels.contains("composite_id")) {
     *         return UserIdParser.parse(rs.getString("composite_id"));
     *     }
     *     return rs.getLong("id");
     * });
     * }</pre>
     *
     * @param <T> The entity type managed by the DAO.
     * @param <ID> The ID type returned by the extractor.
     * @param <TD> The concrete {@link CrudDao} subtype.
     * @param daoInterface The DAO interface class, must not be {@code null}.
     * @param idExtractor The {@link BiRowMapper} used to read the generated key(s) from the
     *                    generated-keys {@code ResultSet}; receives the {@code ResultSet} and the list of
     *                    column labels; must not be {@code null}.
     * @throws IllegalArgumentException if {@code daoInterface} or {@code idExtractor} is {@code null}.
     * @see #setIdExtractorForDao(Class, RowMapper)
     */
    public static synchronized <T, ID, TD extends CrudDao<T, ID, TD>> void setIdExtractorForDao(final Class<? extends CrudDao<T, ID, TD>> daoInterface,
            final BiRowMapper<? extends ID> idExtractor) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(idExtractor, cs.idExtractor);

        idExtractorPool.put(daoInterface, idExtractor);

        // Drop cached key extractors built before this registration so future createDao calls pick it up.
        idGeneratorGetterSetterPool.keySet().removeIf(key -> key._1.equals(daoInterface));
    }

    /**
     * Creates a dynamic Data Access Object (DAO) implementation for the specified interface and DataSource.
     * This method generates a runtime proxy that implements all methods in the DAO interface, automatically
     * handling CRUD operations, query execution, and transaction management based on method signatures and annotations.
     *
     * <p>The created DAO provides type-safe database access with automatic SQL generation for standard CRUD operations,
     * custom query support via annotations or SQL files, and seamless integration with the JdbcUtil transaction management.
     * All CRUD operations are automatically implemented based on the entity class and its annotations.</p>
     *
     * <p><b>Key Features of Generated DAOs:</b></p>
     * <ul>
     *   <li>Automatic CRUD operations (save, update, deleteById, get/getOrNull, list, etc.)</li>
     *   <li>Custom query methods defined by annotations or external SQL files</li>
     *   <li>Batch operation support for high-throughput processing</li>
     *   <li>Transaction-aware operations that participate in active transactions</li>
     *   <li>Type-safe parameter binding and result mapping</li>
     *   <li>Asynchronous operation support via the inherited {@code runAsync}/{@code callAsync} methods (returning {@link ContinuableFuture})</li>
     *   <li>Stream-based result processing for large datasets</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Define a DAO interface extending CrudDao or Dao
     * public interface UserDao extends CrudDao<User, Long, UserDao> {
     *     // Automatic CRUD methods are inherited:
     *     // - save(User user)
     *     // - batchSave(Collection<User> users)
     *     // - getOrNull(Long id)
     *     // - update(User user)
     *     // - deleteById(Long id)
     *     // - list(Condition cond)
     *     // - findFirst(Condition cond)
     *
     *     // Custom query methods
     *     @Query("SELECT * FROM users WHERE email = ?")
     *     Optional<User> findByEmail(String email) throws SQLException;
     *
     *     @Query("SELECT * FROM users WHERE status = ? ORDER BY created_at DESC")
     *     List<User> findByStatus(String status) throws SQLException;
     *
     *     @Query("SELECT * FROM users WHERE age >= :minAge AND city = :city")
     *     Stream<User> findByAgeAndCity(@Bind("minAge") int minAge, @Bind("city") String city);
     * }
     *
     * // Create and use the DAO
     * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
     *
     * // Use inherited CRUD operations
     * User newUser = new User("john@example.com", "John Doe");
     * userDao.save(newUser);
     *
     * // Use custom query methods
     * Optional<User> user = userDao.findByEmail("john@example.com");
     * if (user.isPresent()) {
     *     System.out.println("Found: " + user.get().getName());
     * }
     *
     * // List all active users
     * List<User> activeUsers = userDao.findByStatus("ACTIVE");
     *
     * // Stream results for large datasets
     * try (Stream<User> stream = userDao.findByAgeAndCity(25, "New York")) {
     *     long count = stream
     *         .filter(u -> u.getEmail().endsWith("@company.com"))
     *         .count();
     * }
     *
     * // Batch operations
     * List<User> users = Arrays.asList(user1, user2, user3);
     * userDao.batchSave(users);
     *
     * // Update operations
     * user.get().setStatus("INACTIVE");
     * userDao.update(user.get());
     *
     * // Delete operations
     * userDao.deleteById(userId);
     * }</pre>
     *
     * <p><b>Advanced DAO Features:</b></p>
     * <pre>{@code
     * // Define a DAO with complex queries
     * public interface OrderDao extends CrudDao<Order, Long, OrderDao> {
     *     // Aggregate queries
     *     @Query("SELECT COUNT(*) FROM orders WHERE status = ?")
     *     long countByStatus(String status) throws SQLException;
     *
     *     @Query("SELECT SUM(total_amount) FROM orders WHERE customer_id = ?")
     *     Optional<BigDecimal> getTotalByCustomer(Long customerId) throws SQLException;
     *
     *     // Complex joins (SQL defined externally in SQL mapper file)
     *     @Query(id = "findOrdersWithCustomerDetails")
     *     List<OrderWithCustomer> findOrdersWithCustomerDetails(@Bind("startDate") Date start) throws SQLException;
     * }
     *
     * OrderDao orderDao = JdbcUtil.createDao(OrderDao.class, dataSource);
     *
     * // Use aggregate queries
     * long pendingCount = orderDao.countByStatus("PENDING");
     *
     * // Async operations (inherited from the DAO root interface)
     * ContinuableFuture<Optional<Order>> future = orderDao.callAsync(dao -> dao.get(orderId));
     * future.thenRunAsync(order -> {
     *     order.ifPresent(o -> System.out.println("Order: " + o.getId()));
     * }).get();
     * }</pre>
     *
     * <p><b>Initialization and reuse:</b> Building a DAO inspects its inherited methods, parses query
     * definitions, and prepares invokers, parameter binders, result extractors, and entity metadata.
     * Startup time and retained memory depend on the interface, entity graph, configuration, and runtime;
     * this factory does not guarantee a fixed cost per DAO.</p>
     *
     * <p>Keep and reuse the returned proxy. The factory also caches proxies using the identity of their
     * configuration objects, so repeated calls with the same interface and {@code DataSource} reuse the
     * cached proxy. Each proxy remains bound to its original {@code DataSource}; use a separate factory
     * call for a different source. When sharing a proxy across threads, its configured handlers, custom
     * extractors, cache, and {@code DataSource} must also support concurrent use.</p>
     *
     * <p>Interfaces that inherit fewer operations require fewer invokers to initialize. When startup
     * cost matters, measure DAO creation with the application's actual interfaces and configuration,
     * and defer creation of optional DAOs until they are needed.</p>
     *
     * @param <TD> The DAO interface type, must extend {@link DaoBase}.
     * @param daoInterface The DAO interface class to implement, must not be {@code null}. The interface should
     *                     extend {@link Dao}, {@link CrudDao}, or another {@link DaoBase}-based DAO facade and define
     *                     the entity type and ID type when applicable.
     * @param ds The {@link javax.sql.DataSource} to use for all database operations, must not be {@code null}.
     * @return a dynamically generated DAO instance implementing the specified interface.
     *         Cache and reuse this instance; do not call {@code createDao} per request.
     * @throws IllegalArgumentException if {@code daoInterface} or {@code ds} is {@code null}, the DAO type or entity/ID mapping is invalid,
     *         or the SQL dialect, query metadata, or other DAO configuration is invalid.
     * @throws UnsupportedOperationException if DAO annotations, method signatures, SQL operations, return types, or cache settings are unsupported.
     * @throws UncheckedSQLException if obtaining database product metadata fails while initializing the DAO.
     * @see Dao
     * @see CrudDao
     * @see #createDao(Class, javax.sql.DataSource, SqlDialect)
     * @see #createDao(Class, javax.sql.DataSource, DaoCreationOptions)
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) {
        return DaoImpl.createDao(daoInterface, null, ds, Dsl.PSC, null, null, JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a dynamic DAO implementation for the specified interface and {@link javax.sql.DataSource},
     * generating its CRUD SQL for the given {@link SqlDialect}.
     *
     * <p>This behaves exactly like {@link #createDao(Class, javax.sql.DataSource)} except that the SQL
     * builder used to generate the DAO's CRUD statements is derived from {@code sqlDialect} (via
     * {@link Dsl#forDialect(SqlDialect)}) instead of the default {@link Dsl#PSC}. Use it when the target
     * database needs dialect-specific SQL generation. The supplied dialect must use a parameterized
     * (positional {@code ?}) SQL policy; named-SQL dialects are rejected.</p>
     *
     * <p>All behavior, performance characteristics, and best practices documented on
     * {@link #createDao(Class, javax.sql.DataSource)} apply equally here.</p>
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * // 'sqlDialect' is a com.landawn.abacus.query.SqlDialect selected for the target database
     * UserDao dao = JdbcUtil.createDao(UserDao.class, dataSource, sqlDialect);
     * }</pre>
     *
     * @param <TD> The DAO interface type, must extend {@link DaoBase}.
     * @param daoInterface The DAO interface class to implement, must not be {@code null} and must be an interface.
     * @param ds The {@link javax.sql.DataSource} to use for all database operations, must not be {@code null}.
     * @param sqlDialect The SQL dialect used to generate the DAO's CRUD SQL, must not be {@code null}; its SQL
     *                   policy must be {@code null} or
     *                   {@link com.landawn.abacus.query.SqlDialect.SqlPolicy#PARAMETERIZED_SQL PARAMETERIZED_SQL}.
     * @return a dynamically generated DAO instance implementing the specified interface. Cache and reuse this
     *         instance; do not call {@code createDao} per request.
     * @throws IllegalArgumentException if {@code daoInterface} or {@code ds} is {@code null}, the DAO type or entity/ID mapping is invalid,
     *         or the SQL dialect, query metadata, or other DAO configuration is invalid.
     * @throws UnsupportedOperationException if DAO annotations, method signatures, SQL operations, return types, or cache settings are unsupported.
     * @throws UncheckedSQLException if obtaining database product metadata fails while initializing the DAO.
     * @see #createDao(Class, javax.sql.DataSource)
     * @see #createDao(Class, javax.sql.DataSource, DaoCreationOptions)
     * @see Dsl#forDialect(SqlDialect)
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SqlDialect sqlDialect) {
        N.checkArgNotNull(sqlDialect, cs.sqlDialect);

        return DaoImpl.createDao(daoInterface, null, ds, dslPool.computeIfAbsent(sqlDialect, Dsl::forDialect), null, null,
                JdbcUtil.asyncExecutor.getExecutor());
    }

    /**
     * Creates a dynamic DAO implementation for the specified interface and {@link javax.sql.DataSource},
     * customized by the supplied {@link DaoCreationOptions}.
     *
     * <p>This is the configurable counterpart to {@link #createDao(Class, javax.sql.DataSource)}: it accepts a
     * {@link DaoCreationOptions} bundle carrying the optional settings below. Any option left unset (or a
     * {@code null} {@code daoCreationOptions}) falls back to the same default as the two-argument overload:</p>
     * <ul>
     *   <li>{@code targetTableName} — overrides the table the DAO operates on; defaults to the table derived
     *       from the entity class.</li>
     *   <li>{@code dsl} — the SQL builder dialect used to generate CRUD SQL; defaults to {@link Dsl#PSC}. Its
     *       {@code sqlDialect().sqlPolicy()} must be {@code null} or
     *       {@link com.landawn.abacus.query.SqlDialect.SqlPolicy#PARAMETERIZED_SQL PARAMETERIZED_SQL}; named-SQL
     *       dialects are rejected.</li>
     *   <li>{@code sqlMapper} — externalized, pre-defined SQL keyed by ID for {@code @Query(id = ...)} methods;
     *       defaults to none.</li>
     *   <li>{@code cache} — a {@link Jdbc.DaoCache} for {@code @CacheResult} methods. When unset,
     *       cache annotations use their configured implementation or the default DAO cache. Only permitted
     *       for cacheable DAO interfaces such as {@code ReadOnlyDao} and {@code NonUpdateDao} (see below).</li>
     *   <li>{@code executor} — the {@link Executor} backing the DAO's asynchronous methods; defaults to the
     *       shared async executor.</li>
     * </ul>
     *
     * <p>See {@link DaoCreationOptions} for the full description of each option.</p>
     *
     * <p>All behavior, performance characteristics, and best practices documented on
     * {@link #createDao(Class, javax.sql.DataSource)} apply equally here.</p>
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * UserDao dao = JdbcUtil.createDao(UserDao.class, dataSource,
     *         JdbcUtil.DaoCreationOptions.builder()
     *                 .targetTableName("app_users")
     *                 .sqlMapper(sqlMapper)
     *                 .build());
     * }</pre>
     *
     * @param <TD> The DAO interface type, must extend {@link DaoBase}.
     * @param daoInterface The DAO interface class to implement, must not be {@code null} and must be an interface.
     * @param ds The {@link javax.sql.DataSource} to use for all database operations, must not be {@code null}.
     * @param daoCreationOptions The creation options; when {@code null}, all defaults are applied (equivalent
     *                           to {@link #createDao(Class, javax.sql.DataSource)}).
     * @return a dynamically generated DAO instance implementing the specified interface. Cache and reuse this
     *         instance; do not call {@code createDao} per request.
     * @throws IllegalArgumentException if {@code daoInterface} or {@code ds} is {@code null}, the DAO type or entity/ID mapping is invalid,
     *         or the SQL dialect, query metadata, or other DAO configuration is invalid.
     * @throws UnsupportedOperationException if DAO annotations, method signatures, SQL operations, return types, or cache settings are unsupported.
     * @throws UncheckedSQLException if obtaining database product metadata fails while initializing the DAO.
     * @see #createDao(Class, javax.sql.DataSource)
     * @see #createDao(Class, javax.sql.DataSource, SqlDialect)
     * @see DaoCreationOptions
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final DaoCreationOptions daoCreationOptions) {
        final DaoCreationOptions options = daoCreationOptions == null ? DaoCreationOptions.builder().build() : daoCreationOptions;
        final Dsl dsl = options.dsl() == null ? Dsl.PSC : options.dsl();
        final Executor executor = options.executor() == null ? JdbcUtil.asyncExecutor.getExecutor() : options.executor();

        return DaoImpl.createDao(daoInterface, options.targetTableName(), ds, dsl, options.sqlMapper(), options.cache(), executor);
    }

    /**
     * Immutable bundle of optional settings for {@link JdbcUtil#createDao(Class, javax.sql.DataSource, DaoCreationOptions)}.
     *
     * <p>Every option is optional; an unset option uses the same default as
     * {@link JdbcUtil#createDao(Class, javax.sql.DataSource)}. Instances are created via the generated
     * builder and are immutable.</p>
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * DaoCreationOptions options = DaoCreationOptions.builder()
     *         .targetTableName("app_users")
     *         .dsl(Dsl.PSC)
     *         .sqlMapper(sqlMapper)
     *         .build();
     * }</pre>
     *
     * @see JdbcUtil#createDao(Class, javax.sql.DataSource, DaoCreationOptions)
     */
    @Value
    @Accessors(fluent = true)
    public static class DaoCreationOptions {
        /**
         * The database table name the DAO operates on. When {@code null} (the default), the table name is
         * derived from the entity class associated with the DAO interface.
         */
        private String targetTableName;

        /**
         * The SQL builder dialect used to generate CRUD SQL. When {@code null} (the default), {@link Dsl#PSC}
         * is used. Its {@code sqlDialect().sqlPolicy()} must be {@code null} or
         * {@link com.landawn.abacus.query.SqlDialect.SqlPolicy#PARAMETERIZED_SQL PARAMETERIZED_SQL}; supplying a
         * named-SQL dialect causes {@link JdbcUtil#createDao(Class, javax.sql.DataSource, DaoCreationOptions)} to
         * fail with {@link IllegalArgumentException}.
         */
        private Dsl dsl;

        /**
         * An optional {@link SqlMapper} providing externalized, pre-defined SQL statements keyed by ID.
         * May be {@code null}.
         */
        private SqlMapper sqlMapper;

        /**
         * An optional {@link Jdbc.DaoCache} for caching results of methods annotated with {@code @CacheResult}.
         * May be {@code null}. Supplying a non-{@code null} cache is only permitted for cacheable read-only /
         * non-update DAO interfaces (such as {@code ReadOnlyDao}, {@code NonUpdateCrudDao}, and the unchecked
         * variants); otherwise DAO creation fails with {@link UnsupportedOperationException}.
         */
        private Jdbc.DaoCache cache;

        /**
         * An optional {@link Executor} for the DAO's asynchronous operations. When {@code null} (the default),
         * the shared async executor is used.
         */
        private Executor executor;

        /**
         * Creates an immutable bundle of DAO creation options.
         *
         * @param targetTableName The target table name, or {@code null} to derive it from the DAO entity.
         * @param dsl The SQL builder dialect, or {@code null} to use {@link Dsl#PSC}.
         * @param sqlMapper The external SQL mapper, or {@code null} when external SQL is not used.
         * @param cache The DAO result cache, or {@code null} when no explicit cache is configured.
         * @param executor The asynchronous executor, or {@code null} to use the shared executor.
         */
        @Builder
        public DaoCreationOptions(final String targetTableName, final Dsl dsl, final SqlMapper sqlMapper, final Jdbc.DaoCache cache, final Executor executor) {
            this.targetTableName = targetTableName;
            this.dsl = dsl;
            this.sqlMapper = sqlMapper;
            this.cache = cache;
            this.executor = executor;
        }
    }

    /**
     * Opens a DAO-cache scope for the current thread using a new map-backed cache.
     * Closing the returned scope clears that internally created cache and restores the cache that
     * was active before the scope was opened, making this method safe to nest.
     *
     * <p><b>Usage Example:</b></p>
     * <pre>{@code
     * try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
     *     // DAO operations here share scope.cache().
     * }
     * }</pre>
     *
     * @return A scope containing the newly created cache.
     * @see #openDaoCacheScope(Jdbc.DaoCache)
     * @see Jdbc.DaoCache#createByMap()
     */
    public static DaoCacheScope openDaoCacheScope() {
        return openDaoCacheScope(Jdbc.DaoCache.createByMap(), true);
    }

    /**
     * Opens a DAO-cache scope for the current thread using the specified cache.
     * Closing the returned scope restores the cache that was active before the scope was opened,
     * or removes the thread-local value if there was no previous cache. The scope controls only
     * the thread-local binding; it neither clears nor otherwise manages the lifetime of the supplied
     * cache.
     *
     * @param localThreadCache The cache to use in the scope, must not be {@code null}.
     * @return A scope containing {@code localThreadCache}.
     * @throws IllegalArgumentException if {@code localThreadCache} is {@code null}.
     * @see #openDaoCacheScope()
     */
    public static DaoCacheScope openDaoCacheScope(final Jdbc.DaoCache localThreadCache) throws IllegalArgumentException {
        N.checkArgNotNull(localThreadCache, cs.localThreadCache);

        return openDaoCacheScope(localThreadCache, false);
    }

    /**
     * Binds {@code localThreadCache} to the current thread and returns a scope that restores the previous
     * binding when closed. The previously bound cache and scope are captured so nested scopes unwind in order.
     *
     * @param localThreadCache The cache to bind to the current thread.
     * @param clearCacheOnClose Whether {@link DaoCacheScope#close()} should clear the cache before restoring the previous binding.
     * @return The opened scope.
     */
    private static DaoCacheScope openDaoCacheScope(final Jdbc.DaoCache localThreadCache, final boolean clearCacheOnClose) {

        final Jdbc.DaoCache previousCache = localThreadCache_TL.get();
        final DaoCacheScope previousScope = daoCacheScope_TL.get();
        final DaoCacheScope scope = new DaoCacheScope(localThreadCache, previousCache, previousScope, clearCacheOnClose);

        localThreadCache_TL.set(localThreadCache);
        daoCacheScope_TL.set(scope);

        return scope;
    }

    /**
     * A current-thread DAO-cache binding that restores the previous binding when closed.
     * Instances are created by {@link JdbcUtil#openDaoCacheScope()} or
     * {@link JdbcUtil#openDaoCacheScope(Jdbc.DaoCache)} and must be closed on the thread that opened them.
     * Nested scopes must be closed in reverse order, as happens naturally with try-with-resources.
     * A scope created by the no-argument factory owns and clears its cache; a scope created with a
     * caller-supplied cache only restores the binding.
     */
    public static final class DaoCacheScope implements AutoCloseable {
        /** The cache this scope binds to the current thread. */
        private final Jdbc.DaoCache cache;
        /** The cache binding to restore on {@link #close()}, or {@code null} if there was none. */
        private final Jdbc.DaoCache previousCache;
        /** The enclosing scope to restore on {@link #close()}, or {@code null} if this scope is the outermost one. */
        private final DaoCacheScope previousScope;
        /** The thread that opened this scope; {@link #close()} must be invoked on it. */
        private final Thread ownerThread;
        /** Whether {@link #close()} clears {@link #cache} before restoring the previous binding. */
        private final boolean clearCacheOnClose;
        /** Whether this scope has already been closed; makes {@link #close()} idempotent. */
        private boolean closed;

        /**
         * Creates a scope for the given cache, capturing the current thread as the owner.
         *
         * @param cache The cache bound by this scope.
         * @param previousCache The cache binding to restore on close, or {@code null} if there was none.
         * @param previousScope The enclosing scope to restore on close, or {@code null} if there was none.
         * @param clearCacheOnClose Whether closing this scope clears {@code cache}.
         */
        private DaoCacheScope(final Jdbc.DaoCache cache, final Jdbc.DaoCache previousCache, final DaoCacheScope previousScope,
                final boolean clearCacheOnClose) {
            this.cache = cache;
            this.previousCache = previousCache;
            this.previousScope = previousScope;
            this.clearCacheOnClose = clearCacheOnClose;
            ownerThread = Thread.currentThread();
        }

        /**
         * Returns the cache bound to the current thread by this scope.
         *
         * @return The scope's cache.
         */
        public Jdbc.DaoCache cache() {
            return cache;
        }

        /**
         * Restores the DAO-cache binding that preceded this scope. Repeated calls have no effect.
         * Nested scopes must be closed in reverse order. If this scope was created by
         * {@link JdbcUtil#openDaoCacheScope()}, its internally created cache is cleared first;
         * caller-supplied caches are not cleared.
         *
         * @throws IllegalStateException if called from a different thread or while a nested scope is still open.
         */
        @Override
        public void close() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("DaoCacheScope must be closed on the thread that opened it");
            }

            if (closed) {
                return;
            }

            if (daoCacheScope_TL.get() != this) {
                throw new IllegalStateException("Nested DaoCacheScope instances must be closed in reverse order");
            }

            closed = true;

            try {
                if (clearCacheOnClose) {
                    cache.clear();
                }
            } finally {
                // Restore both thread-local bindings even if an owned cache fails while clearing.
                if (previousCache == null) {
                    localThreadCache_TL.remove();
                } else {
                    localThreadCache_TL.set(previousCache);
                }

                if (previousScope == null) {
                    daoCacheScope_TL.remove();
                } else {
                    daoCacheScope_TL.set(previousScope);
                }
            }
        }
    }

    /**
     * Enables DAO query result caching for the current thread.
     * Creates a new thread-local cache that will be used by all DAOs in the current thread.
     * This legacy method must be paired with {@link #closeDaoCacheOnCurrentThread()} in a
     * {@code finally} block and does not restore a cache binding that it replaces.
     *
     * <p>Use {@link #openDaoCacheScope()} for automatic cleanup and nest-safe restoration:</p>
     * <pre>{@code
     * try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
     *     // DAO operations here will use the cache
     *     userDao.findById(1L);   // First call hits database
     *     userDao.findById(1L);   // Second call uses cache
     * }
     * }</pre>
     *
     * @return The created DaoCache for the current thread.
     * @throws IllegalStateException if a {@link DaoCacheScope} is active on the current thread.
     * @deprecated Use {@link #openDaoCacheScope()} with try-with-resources.
     * @see Jdbc.DaoCache#createByMap()
     * @see #openDaoCacheScope()
     * @see #closeDaoCacheOnCurrentThread()
     */
    @Deprecated
    public static Jdbc.DaoCache openDaoCacheOnCurrentThread() {
        checkNoActiveDaoCacheScopeForLegacyOperation();

        final Jdbc.DaoCache localThreadCache = Jdbc.DaoCache.createByMap();

        localThreadCache_TL.set(localThreadCache);

        return localThreadCache;
    }

    /**
     * Enables the specified DAO cache for the current thread.
     * The provided cache will be used by all DAOs in the current thread.
     * This legacy method must be paired with {@link #closeDaoCacheOnCurrentThread()} in a
     * {@code finally} block and does not restore a cache binding that it replaces.
     *
     * <p>Use {@link #openDaoCacheScope(Jdbc.DaoCache)} for automatic cleanup and nest-safe restoration:</p>
     * <pre>{@code
     * // Use a custom cache implementation
     * Map<String, Object> cacheMap = new ConcurrentHashMap<>();
     * Jdbc.DaoCache cache = Jdbc.DaoCache.createByMap(cacheMap);
     *
     * try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope(cache)) {
     *     // DAO operations use the custom cache
     *     productDao.findPopular();
     * }
     * }</pre>
     *
     * @param localThreadCache The cache to use for the current thread, must not be {@code null}.
     * @return The specified {@code localThreadCache}.
     * @throws IllegalArgumentException if {@code localThreadCache} is {@code null}.
     * @throws IllegalStateException if a {@link DaoCacheScope} is active on the current thread.
     * @deprecated Use {@link #openDaoCacheScope(Jdbc.DaoCache)} with try-with-resources.
     * @see Jdbc.DaoCache#createByMap()
     * @see Jdbc.DaoCache#createByMap(Map)
     * @see #openDaoCacheScope(Jdbc.DaoCache)
     * @see #closeDaoCacheOnCurrentThread()
     */
    @Deprecated
    public static Jdbc.DaoCache openDaoCacheOnCurrentThread(final Jdbc.DaoCache localThreadCache) throws IllegalArgumentException {
        N.checkArgNotNull(localThreadCache, cs.localThreadCache);
        checkNoActiveDaoCacheScopeForLegacyOperation();

        localThreadCache_TL.set(localThreadCache);

        return localThreadCache;
    }

    /**
     * Removes the DAO-cache binding for the current thread.
     * This method does not close the cache object or restore a binding replaced by
     * {@link #openDaoCacheOnCurrentThread()}; use {@link #openDaoCacheScope()} for nest-safe lifecycle management.
     *
     * @throws IllegalStateException if a {@link DaoCacheScope} is active on the current thread.
     * @deprecated Close the {@link DaoCacheScope} returned by {@link #openDaoCacheScope()}, preferably
     *             through try-with-resources.
     * @see #openDaoCacheOnCurrentThread()
     * @see #openDaoCacheOnCurrentThread(Jdbc.DaoCache)
     * @see #openDaoCacheScope()
     */
    @Deprecated
    public static void closeDaoCacheOnCurrentThread() {
        checkNoActiveDaoCacheScopeForLegacyOperation();
        localThreadCache_TL.remove();
    }

    /**
     * Throws {@link IllegalStateException} if a {@link DaoCacheScope} is active on the current thread, because the
     * legacy open/close cache methods would break the scope's restore-on-close bookkeeping.
     * @throws IllegalStateException if a {@link DaoCacheScope} is active on the current thread.
     */
    private static void checkNoActiveDaoCacheScopeForLegacyOperation() {
        if (daoCacheScope_TL.get() != null) {
            throw new IllegalStateException("Legacy DAO-cache lifecycle methods cannot be used while a DaoCacheScope is active");
        }
    }

    /**
     * Builds the cache key used to store/query cached DAO results for the given method invocation.
     * Returns {@code null} when a key cannot be generated (in which case the result is not cached).
     *
     * @param tableName The name of the table the DAO operates on.
     * @param fullClassMethodName The fully qualified name of the DAO method being invoked.
     * @param args The method arguments. Can be {@code null}.
     * @param daoLogger The logger of the DAO, used to report key-generation failures.
     * @return The cache key, or {@code null} if it cannot be generated.
     */
    static String createCacheKey(final String tableName, final String fullClassMethodName, final Object[] args, final Logger daoLogger) {
        String paramKey = null;

        final Object[] cacheKeyArgs = args == null ? new Object[0] : args;

        if (kryoParser != null) {
            try {
                paramKey = kryoParser.serialize(cacheKeyArgs);
            } catch (final Exception e) {
                daoLogger.warn(e, "Failed to generate cache key; result will not be cached(method={})", fullClassMethodName);
            }
        } else {
            final List<Object> newArgs = Stream.of(cacheKeyArgs).map(it -> {
                if (it == null) {
                    return null;
                }

                final Type<?> type = N.typeOf(it.getClass());

                if (type.isSerializable() || type.isCollection() || type.isMap() || type.isArray() || type.isBean() || type.isEntityId()) {
                    return it;
                } else {
                    return it.toString();
                }
            }).toList();

            try {
                paramKey = N.toJson(newArgs);
            } catch (final Exception e) {
                daoLogger.warn(e, "Failed to generate cache key; result will not be cached(method={})", fullClassMethodName);
            }
        }

        if (paramKey == null) {
            return null;
        }

        return Strings.concat(fullClassMethodName, CACHE_KEY_SEPARATOR, tableName, CACHE_KEY_SEPARATOR, paramKey);
    }

    // ==============================================Jdbc Context=======================================================>>
}
