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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
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
import java.util.IdentityHashMap;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.exception.ParsingException;
import com.landawn.abacus.exception.UncheckedIOException;
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
import com.landawn.abacus.query.Dsl;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SqlDialect;
import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.query.SqlMapper;
import com.landawn.abacus.type.AsciiStreamType;
import com.landawn.abacus.type.BinaryStreamType;
import com.landawn.abacus.type.BlobInputStreamType;
import com.landawn.abacus.type.CharacterStreamType;
import com.landawn.abacus.type.ClobAsciiStreamType;
import com.landawn.abacus.type.ClobReaderType;
import com.landawn.abacus.type.InputStreamType;
import com.landawn.abacus.type.NCharacterStreamType;
import com.landawn.abacus.type.NClobReaderType;
import com.landawn.abacus.type.ReaderType;
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
 * <p>Direct named-SQL execution and batches accept Map, bean, record and EntityId parameters.
 * The stream/reader binding helpers in this utility are also shared by {@link AbstractQuery},
 * {@link NamedQuery} and {@link CallableQuery}.
 * A repeated name handled by a built-in stream/reader type is buffered in memory once per parameter
 * set and bound with independent cursors. Single occurrences and positional values remain streamed;
 * custom Type handlers keep their own semantics. The caller retains ownership of the original resource.</p>
 *
 * <p>Database work in Spring completion callbacks must use an independent transaction, such as Spring's
 * {@code PROPAGATION_REQUIRES_NEW} or DAO {@link Propagation#REQUIRES_NEW}. Spring gives this guidance for both
 * <a href="https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()">afterCommit</a>
 * and
 * <a href="https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCompletion(int)">afterCompletion</a>:
 * resources may still be accessible although the transaction has already committed or rolled back.</p>
 *
 * <p>Statement execution does not check Spring's completion phase. SQL on a completing Spring transaction is delegated
 * to JDBC without a safety or cache-consistency guarantee, whether or not {@link SpringTransactionTracker} is registered.
 * A callback's additional work is not covered by the original outcome: it may commit during cleanup, remain pending, or
 * be discarded, depending on the driver and transaction manager. Even immediate cache invalidation cannot prevent another
 * thread from caching old rows before a later callback write commits. Participating {@link SqlTransaction} scopes and
 * queries associated with them retain their lifecycle checks; removing statement-level enforcement does not make a
 * completed scope usable again. Opening a scope on a Spring transaction requires tracking; scope-free SQL does not.
 * Query construction does not inspect Spring connection ownership. Queries associated with a {@code JdbcUtil} transaction
 * reject {@code callAsync}/{@code runAsync}; scope-free queries do not enforce that restriction for Spring connections.
 * Async submission does not propagate Spring's thread-bound transaction or guarantee connection lifetime or exclusive use.
 * For transactional asynchronous work, start the transaction and create the query inside the worker task, using a
 * connection owned by that task.
 * See {@link #beginTransaction(javax.sql.DataSource)} for participation requirements.</p>
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
 * List<User> users;
 * try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource,
 *         "SELECT * FROM users WHERE department = ?")) {
 *     users = query.setString(1, "Engineering").list(User.class);
 * }
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
 * try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource,
 *         "SELECT * FROM products WHERE category = ?");
 *         Stream<Product> stream = query.setString(1, category).stream(Product.class)) {
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
     * Exact built-in types that consume an {@link InputStream}; custom Type subclasses keep their own binding semantics.
     */
    private static final Set<Class<?>> STREAM_TYPES = Set.of(InputStreamType.class, BlobInputStreamType.class, AsciiStreamType.class, BinaryStreamType.class,
            ClobAsciiStreamType.class);

    /**
     * Exact built-in types that consume a {@link Reader}; custom Type subclasses may require the original reader.
     */
    private static final Set<Class<?>> READER_TYPES = Set.of(ReaderType.class, ClobReaderType.class, NClobReaderType.class, CharacterStreamType.class,
            NCharacterStreamType.class);

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
     * that complete in less than this threshold do not produce an {@code [SQL-PERF]} log entry.
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
     * for JDBC operations on the current thread, on every data source (see {@link #runIgnoringSpringTransaction(Throwables.Runnable)}).
     */
    static final ThreadLocal<Boolean> isSpringTransactionalDisabled_TL = ThreadLocal.withInitial(() -> false);

    /**
     * The data sources for which participation in Spring-managed transactions is suspended on the current thread, by
     * {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)} and its siblings, compared by identity after
     * unwrapping Spring {@code TransactionAwareDataSourceProxy} layers; {@code null} when there is none.
     */
    private static final ThreadLocal<Map<javax.sql.DataSource, SpringSuspension>> springSuspendedDataSources_TL = new ThreadLocal<>();

    /**
     * A suspension of this library's use of Spring-managed transactions for a data source (see
     * {@link #suspendSpringTransactional(javax.sql.DataSource)}): the resource Spring bound to it then (the connection holder
     * of the transaction left, or {@code null}), and the enclosing suspension of the same data source, if any.
     */
    private record SpringSuspension(Object resource, SpringSuspension enclosing) {
    }

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
     * Whether Spring's JDBC transaction support can be used: decided once, during class initialization, by initializing Spring's
     * {@code DataSourceUtils}, and never changed afterwards. A {@link LinkageError} thrown at run time, typically by a JDBC
     * driver that fails to load, propagates instead of turning Spring support off.
     */
    static final boolean isInSpring = isSpringTransactionSupportLoadable();

    /**
     * Optional global callback invoked after each statement execution with the SQL text, the start time in
     * milliseconds, and the end time in milliseconds; {@code null} when no handler is registered.
     */
    static volatile TriConsumer<String, Long, Long> _sqlLogHandler = null; //NOSONAR

    // Keyed by (daoInterface, entityClass, idType): the cached key extractor depends on the DAO interface's
    // registration in idExtractorPool, so DAOs sharing the same entity/id types must not share entries.
    /**
     * Cache of per-DAO (generated-key extractor, id getter, id setter) triples, keyed by
     * (DAO interface, entity class, id type) and then by {@link NamingPolicy}.
     */
    @SuppressWarnings("rawtypes")
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
     * Separator ({@code "#"}) used to join the segments (full method name, table name, DAO identity, parameter key) of generated
     * DAO cache keys. The table name is always the segment between the first and the second separator.
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

    /**
     * Returns whether Spring's {@code DataSourceUtils} can be loaded and initialized through this class's class loader, which
     * resolves this class's references to Spring. Spring is an optional ({@code provided}) dependency. Initializing
     * {@code DataSourceUtils} links it against the rest of Spring it needs (spring-tx and spring-core, through its exception
     * types), so a partial installation counts as absent, with a warning, instead of failing when a connection is acquired.
     *
     * @return {@code true} if Spring's JDBC transaction support can be used
     */
    private static boolean isSpringTransactionSupportLoadable() {
        final ClassLoader classLoader = JdbcUtil.class.getClassLoader();
        final String className = "org.springframework.jdbc.datasource.DataSourceUtils";

        try {
            Class.forName(className, false, classLoader);
        } catch (final Exception | LinkageError e) {
            return false; // Spring JDBC is not on the class path.
        }

        try {
            Class.forName(className, true, classLoader);

            return true;
        } catch (final Exception | LinkageError e) {
            logger.warn("Spring JDBC is on the class path, but its transaction support cannot be loaded ({}): Spring-managed transactions are ignored",
                    e.toString());

            return false;
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
     *         {@code null}, empty, or blank product name.
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
     *         {@code null}, empty, or blank product name.
     * @throws UncheckedSQLException if reading the connection's database product name or version fails.
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
     * @throws RuntimeException if HikariCP rejects the configuration or cannot start the pool, for example because no registered JDBC driver
     *         accepts {@code url} or the initial connection attempt fails ({@code HikariPool.PoolInitializationException}).
     * @see #createHikariDataSource(String, String, String, int, int)
     * @see com.zaxxer.hikari.HikariDataSource
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password)
            throws IllegalArgumentException, RuntimeException {
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
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty, {@code minIdle} is negative, or {@code maxPoolSize} is not positive.
     * @throws RuntimeException if HikariCP rejects the configuration or cannot start the pool, for example because no registered JDBC driver
     *         accepts {@code url} or the initial connection attempt fails ({@code HikariPool.PoolInitializationException}).
     * @see #createHikariDataSource(String, String, String)
     * @see com.zaxxer.hikari.HikariConfig
     */
    public static javax.sql.DataSource createHikariDataSource(final String url, final String user, final String password, final int minIdle,
            final int maxPoolSize) throws IllegalArgumentException, RuntimeException {
        N.checkArgNotEmpty(url, cs.url);
        N.checkArgNotNegative(minIdle, cs.minIdle);
        N.checkArgPositive(maxPoolSize, cs.maxPoolSize);

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
     * @throws RuntimeException if constructing or configuring the C3P0 connection pool fails.
     * @see #createC3p0DataSource(String, String, String, int, int)
     * @see com.mchange.v2.c3p0.ComboPooledDataSource
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password)
            throws IllegalArgumentException, RuntimeException {
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
     * @param maxPoolSize The maximum number of connections the pool will allow; must be positive.
     * @return A {@code javax.sql.DataSource} instance configured with C3P0 and custom pool settings.
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty, or {@code maxPoolSize} is not positive.
     * @throws RuntimeException if constructing or configuring the C3P0 connection pool fails.
     * @see #createC3p0DataSource(String, String, String)
     * @see com.mchange.v2.c3p0.ComboPooledDataSource
     */
    @Beta
    public static javax.sql.DataSource createC3p0DataSource(final String url, final String user, final String password, final int minPoolSize,
            final int maxPoolSize) throws IllegalArgumentException, RuntimeException {
        N.checkArgNotEmpty(url, cs.url);
        // C3P0 accepts a non-positive max pool size without complaint, and getConnection() then waits forever
        // (default checkoutTimeout 0) for a connection the pool may never create. HikariCP rejects it eagerly.
        N.checkArgPositive(maxPoolSize, cs.maxPoolSize);

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
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty, its driver cannot be determined or loaded, or the driver class is abstract
     *         or has no supported constructor.
     * @throws RuntimeException if reflective construction of the JDBC driver fails.
     * @throws UncheckedSQLException if registering the JDBC driver or opening the connection fails.
     * @see #createConnection(String, String, String, String)
     * @see DriverManager#getConnection(String, String, String)
     */
    public static Connection createConnection(final String url, final String user, final String password)
            throws IllegalArgumentException, RuntimeException, UncheckedSQLException {
        return createConnection(getDriverClassByUrl(url), url, user, password);
    }

    /**
     * Creates a new database {@link Connection} using an explicitly specified driver class.
     * This method is useful when the JDBC driver cannot be automatically determined from the URL
     * or when the driver class must be loaded explicitly before asking {@link DriverManager} for a connection.
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
     * @throws IllegalArgumentException if {@code driverClass} or {@code url} is {@code null} or empty, or the driver class cannot be loaded, is abstract,
     *         or has no supported constructor.
     * @throws RuntimeException if reflective construction of the JDBC driver fails.
     * @throws ClassCastException if the named class does not implement {@link Driver}.
     * @throws UncheckedSQLException if registering the JDBC driver or opening the connection fails.
     * @see #createConnection(Class, String, String, String)
     */
    public static Connection createConnection(final String driverClass, final String url, final String user, final String password)
            throws IllegalArgumentException, RuntimeException, ClassCastException, UncheckedSQLException {
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
     * @throws IllegalArgumentException if {@code driverClass} is {@code null}, {@code url} is {@code null} or empty, or the driver class is abstract
     *         or has no supported constructor.
     * @throws RuntimeException if reflective construction of the JDBC driver fails.
     * @throws UncheckedSQLException if registering the JDBC driver or opening the connection fails.
     * @see #createConnection(String, String, String, String)
     * @see DriverManager#registerDriver(Driver)
     */
    public static Connection createConnection(final Class<? extends Driver> driverClass, final String url, final String user, final String password)
            throws IllegalArgumentException, RuntimeException, UncheckedSQLException {
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
     * @throws IllegalArgumentException if {@code url} is {@code null} or empty, does not match any supported driver,
     *         or the matched driver class cannot be loaded.
     */
    private static Class<? extends Driver> getDriverClassByUrl(final String url) throws IllegalArgumentException {
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
     * This method is aware of Spring-managed transactions. If Spring JDBC is on the classpath (and its
     * integration is neither disabled for the current thread, e.g. by {@link #runIgnoringSpringTransaction(Throwables.Runnable)},
     * nor suspended for {@code ds}, e.g. by {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}),
     * the connection is obtained through Spring's {@code DataSourceUtils}, so a connection bound to an active Spring
     * transaction is returned. Otherwise, it retrieves a new connection from the DataSource; from the target data source of a
     * Spring {@code TransactionAwareDataSourceProxy}, whose own connections are those of the current Spring transaction.
     *
     * <p>Whether Spring is on the classpath is decided once, when this class is initialized: an error thrown by the data
     * source, such as a {@link LinkageError} from a JDBC driver that cannot be loaded, propagates as it is and does not change
     * how connections are obtained.</p>
     *
     * <p>A {@link SqlTransaction} started by {@link #beginTransaction(javax.sql.DataSource)} is <i>not</i> consulted
     * here; use {@link SqlTransaction#connection()} to work with that transaction's connection.</p>
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
     * @throws UncheckedSQLException if acquiring a connection directly from {@code ds} fails outside the Spring acquisition path;
     *         on the Spring path, such a failure propagates as Spring's unchecked {@code CannotGetJdbcConnectionException}
     *         (not declared, so that this class does not name a Spring type).
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     * @see org.springframework.jdbc.datasource.DataSourceUtils#getConnection(javax.sql.DataSource)
     */
    public static Connection getConnection(final javax.sql.DataSource ds) throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotNull(ds, cs.ds);

        if (isSpringTransactionalUsed(ds)) {
            // A LinkageError is not caught: DataSourceUtils was initialized with this class (isInSpring), so it comes from ds or
            // its driver (a native library or a driver class that fails to load), which DataSourceUtils passes on. Caught, it
            // turned Spring support off for the whole JVM, and the connection was taken from ds directly: from then on, on every
            // thread and for every data source, JdbcUtil work in a Spring transaction ran on connections of its own, a transaction
            // begun through a TransactionAwareDataSourceProxy committed the Spring transaction's connection, and work meant to
            // run outside the Spring transaction ran in it.
            final Connection conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);

            // So that the Spring transaction's afterCommit callbacks see it completing (see isSpringTransactionCompleting): only a
            // transaction this library had begun a scope in was recognized, and a transaction begun in its afterCommit callbacks
            // joined it, after a statement run through JdbcUtil, and was committed by Spring's cleanup also when it rolled back.
            // Given ds, not its unwrapped key: unwrapping links TransactionAwareDataSourceProxy, whose superclass needs spring-beans,
            // so it is unwrapped inside watchCompletion's catch. (Without spring-beans, Spring's own transaction synchronization
            // fails in DataSourceUtils.getConnection: no transaction manager can run there anyway.)
            SpringRollbackActions.watchCompletion(ds);

            return conn;
        } else {
            try {
                return dataSourceOutsideSpringTransaction(ds).getConnection();
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }

    /**
     * Returns the data source to take a connection from when it must not be the connection of a Spring-managed transaction:
     * {@code ds}, or the target of Spring {@code TransactionAwareDataSourceProxy} layers, whose connections delegate to the
     * connection bound to the current Spring transaction. Used while Spring participation is disabled on the thread (work
     * outside the transaction, {@link #runIgnoringSpringTransaction(Throwables.Runnable)}, DAO {@code REQUIRES_NEW} and
     * {@code NOT_SUPPORTED}): through the proxy, such work ran in the Spring transaction, so a nested commit committed the
     * Spring transaction's earlier work, and work meant to commit on its own was rolled back with it.
     *
     * <p>{@code isInSpring} is decided once, when this class is initialized, so a failure elsewhere (a JDBC driver that fails
     * to load) cannot make this method skip the unwrapping. A {@link LinkageError} while checking for the proxy means that
     * Spring's proxy class cannot be loaded, so {@code ds} cannot be such a proxy: it is returned as it is.</p>
     *
     * @param ds the data source
     * @return the data source that hands out connections of their own
     */
    static javax.sql.DataSource dataSourceOutsideSpringTransaction(final javax.sql.DataSource ds) {
        if (!isInSpring) {
            return ds;
        }

        try {
            return unwrapSpringDataSource(ds);
        } catch (final LinkageError e) {
            // TransactionAwareDataSourceProxy cannot be loaded (a partial Spring installation), so ds is not one.
            return ds;
        }
    }

    /**
     * Releases the given {@link Connection} back to the {@link javax.sql.DataSource}.
     * This method correctly handles connections in Spring-managed transactions, ensuring that
     * connections are not prematurely closed. If no transaction is active, it closes the connection.
     * It is crucial to call this method in a {@code finally} block to prevent connection leaks.
     *
     * <p>Close failures are logged, never thrown. Spring binds a transaction's connection to the thread that
     * started the transaction: release a connection on the thread that obtained it, and not inside a
     * {@link #runIgnoringSpringTransaction(Throwables.Runnable)} scope, or work run outside the transaction on {@code ds}
     * ({@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}, {@link SqlTransaction#callOutsideTransaction},
     * a DAO {@code @Transactional(REQUIRES_NEW)} or {@code NOT_SUPPORTED} method), unless it was obtained there too.
     * Otherwise a transaction-bound connection is closed instead of being left to its transaction.</p>
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
     * @param ds The {@link javax.sql.DataSource} from which the connection was obtained. If {@code null}, the connection is closed directly.
     * @see #getConnection(javax.sql.DataSource)
     * @see org.springframework.jdbc.datasource.DataSourceUtils#releaseConnection(Connection, javax.sql.DataSource)
     */
    public static void releaseConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        if (ds != null && isSpringTransactionalUsed(ds)) {
            try {
                org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
            } catch (final LinkageError e) {
                // Spring's releaseConnection handles any failure of closing the connection, so this is a Spring class that fails
                // to link (a mismatched installation): the connection is closed directly. Spring support stays on (see isInSpring).
                JdbcUtil.closeQuietly(conn);
            }
        } else {
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Releases a connection obtained through Spring's {@code DataSourceUtils} for a transaction participating in a
     * Spring-managed transaction, regardless of whether Spring participation is disabled on the current thread at release
     * time: closing it instead would close the Spring transaction's connection.
     *
     * @param conn The connection to release; ignored if {@code null}.
     * @param ds The data source the connection was obtained from.
     */
    static void releaseSpringManagedConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (conn == null) {
            return;
        }

        try {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, ds);
        } catch (final LinkageError e) {
            // As in releaseConnection: a Spring class that fails to link. Spring support stays on.
            JdbcUtil.closeQuietly(conn);
        }
    }

    /**
     * Releases the connection of a transaction participating in a Spring-managed transaction while Spring binds another
     * resource to the data source, or none: that transaction is suspended ({@code REQUIRES_NEW}, {@code NOT_SUPPORTED}) or
     * has completed. The connection is left to that transaction, whose completion releases it: released through
     * {@code DataSourceUtils}, which finds another holder (or none) bound, it was closed while that transaction still used it.
     * Only the request this library made of the transaction's connection holder is released, as {@code DataSourceUtils}
     * would.
     *
     * @param ds the data source the connection was obtained from
     * @param participatedResource the connection holder of the transaction participated in
     */
    static void releaseUnboundParticipatingConnection(final javax.sql.DataSource ds, final Object participatedResource) {
        try {
            // Through a TransactionAwareDataSourceProxy, DataSourceUtils requested the connection of a holder bound for the proxy,
            // which Spring's completion releases.
            if (unwrapSpringDataSource(ds) == ds && participatedResource instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder) {
                holder.released();
            }
        } catch (final LinkageError e) {
            // A Spring class that fails to link: nothing to release then, the transaction's completion releases the connection.
        }
    }

    /**
     * Takes {@code conn}, which a transaction of its own aborted after its rollback failed, from the connection holder Spring
     * bound to {@code ds} for synchronization when the connection was acquired (in a {@code SUPPORTS} scope, or while a Spring
     * transaction runs on another data source), and closes it, as Spring no longer releases it at the end of its scope; for a
     * {@code TransactionAwareDataSourceProxy}, the proxy's target connection is taken from the holder bound to the target data
     * source too. Spring handed the aborted connection to later work in its scope, which failed on it: the holder fetches a
     * new connection for that work now. The holder of a Spring-managed transaction is left alone, and so is everything when
     * Spring's holder can't be changed (its {@code setConnection} is not accessible): the later work then fails as before.
     *
     * @param conn the aborted connection, as {@link #getConnection(javax.sql.DataSource)} returned it
     * @param ds the data source it was acquired from
     */
    static void detachAbortedSpringConnection(final Connection conn, final javax.sql.DataSource ds) {
        if (!isInSpring || conn == null || ds == null) {
            return;
        }

        try {
            final javax.sql.DataSource target = unwrapSpringDataSource(ds);
            // Read before the proxy is closed, which releases its target connection.
            final Connection targetConn = target != ds && conn instanceof final org.springframework.jdbc.datasource.ConnectionProxy proxy
                    ? proxy.getTargetConnection()
                    : null;

            if (SpringConnectionHolderAccess.detachSynchronizedConnection(ds, conn)) {
                closeQuietly(conn);
            }

            if (targetConn != null && SpringConnectionHolderAccess.detachSynchronizedConnection(target, targetConn)) {
                closeQuietly(targetConn);
            }
        } catch (final LinkageError | RuntimeException e) {
            logger.warn(e, "Cannot take an aborted connection from the holder Spring bound to the data source: later work in the Spring scope gets it");
        }
    }

    /**
     * Creates a Runnable that releases the given connection back to the DataSource when invoked.
     *
     * <p>A connection that Spring had bound to the acquiring thread (for a transaction, or for synchronization) when it was
     * obtained is released only on that thread. Released from another thread (e.g. after {@code callAsync}/{@code runAsync}),
     * Spring finds no binding there and would close the connection while its transaction still uses it; it is left to
     * Spring's transaction completion, which releases it. On the acquiring thread it is released to Spring even inside a
     * {@link #runIgnoringSpringTransaction(Throwables.Runnable)} scope, or work run outside the transaction on {@code ds}
     * ({@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}): it is Spring's connection, whatever the
     * thread's setting at close time. If its original holder is suspended or has been unbound, release only that holder's
     * request and leave the physical connection to its transaction, as for a participating scope.</p>
     *
     * @param conn The database Connection to release.
     * @param ds The DataSource from which the connection was obtained.
     * @return A Runnable that releases the connection back to the DataSource.
     */
    static Runnable createCloseHandler(final Connection conn, final javax.sql.DataSource ds) {
        if (conn != null && ds != null && isSpringTransactionalUsed(ds) && isBoundBySpring(conn, ds)) {
            final Thread acquiringThread = Thread.currentThread();
            final Object acquiringResource = boundSpringResource(ds);

            return () -> {
                if (Thread.currentThread() == acquiringThread) {
                    // Not releaseConnection: that re-reads the thread's Spring setting at close time and, inside a
                    // runIgnoringSpringTransaction scope, closed the Spring transaction's connection mid-transaction.
                    if (boundSpringResource(ds) == acquiringResource) {
                        releaseSpringManagedConnection(conn, ds);
                    } else {
                        // DataSourceUtils would close the outer connection while REQUIRES_NEW has another holder bound.
                        releaseUnboundParticipatingConnection(ds, acquiringResource);
                    }
                } else if (logger.isDebugEnabled()) {
                    logger.debug("Not releasing the Spring-bound connection on thread '{}': it was obtained on thread '{}', and Spring's transaction"
                            + " completion releases it", Thread.currentThread().getName(), acquiringThread.getName());
                }
            };
        }

        return () -> JdbcUtil.releaseConnection(conn, ds);
    }

    /**
     * Returns whether {@code conn} is a connection Spring has bound to the current thread for {@code ds}, directly or, for a
     * {@code TransactionAwareDataSourceProxy}, through the target data source's binding that the proxy's connections use.
     *
     * @param conn The connection obtained from {@code ds}.
     * @param ds The data source.
     * @return {@code true} if Spring's transaction completion releases the connection.
     */
    private static boolean isBoundBySpring(final Connection conn, final javax.sql.DataSource ds) {
        try {
            if (org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(conn, ds)) {
                return true;
            }

            final javax.sql.DataSource target = unwrapSpringDataSource(ds);

            return target != ds && org.springframework.transaction.support.TransactionSynchronizationManager
                    .getResource(target) instanceof org.springframework.jdbc.datasource.ConnectionHolder;
        } catch (final LinkageError e) {
            return false;
        }
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
     * @throws UncheckedSQLException if closing {@code rs} fails with an SQL exception.
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
     * // openOrders(...) creates a Statement and hands over only the ResultSet produced by it.
     * ResultSet rs = openOrders(connection);
     * try {
     *     // ... process results
     * } finally {
     *     // Closes rs, then the Statement that created it (located through rs.getStatement()).
     *     JdbcUtil.close(rs, true);
     * }
     * }</pre>
     *
     * <p>The statement is located through the result set, so it is not closed when {@code rs} is {@code null},
     * for example when executing the query failed. When the statement handle is available, prefer
     * {@link #close(ResultSet, Statement)}, which also closes the statement in that case.</p>
     *
     * @param rs The {@link ResultSet} to close. If {@code null}, no action is taken.
     * @param closeStatement If {@code true}, the {@link Statement} that created the {@code ResultSet} will also be closed.
     * @throws UncheckedSQLException if locating the result set's statement (only when {@code closeStatement} is {@code true}),
     *         or closing the result set or that statement, fails with an SQL exception.
     * @see #close(ResultSet, Statement)
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
     * // openProducts(...) opens a Connection and Statement and hands over only the ResultSet.
     * ResultSet rs = openProducts(url, user, pass);
     * try {
     *     // ... process results
     * } finally {
     *     // Closes rs, then the Statement and Connection it came from.
     *     JdbcUtil.close(rs, true, true);
     * }
     * }</pre>
     *
     * <p>The statement and connection are located through the result set, so they are not closed when
     * {@code rs} is {@code null}, for example when executing the query failed. When those handles are
     * available, prefer {@link #close(ResultSet, Statement, Connection)}.</p>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the {@link Statement} from the {@code ResultSet} is also closed.
     * @param closeConnection If {@code true}, the {@link Connection} from the {@code Statement} is also closed.
     *        This requires {@code closeStatement} to be {@code true}.
     * @throws IllegalArgumentException if {@code closeConnection} is {@code true} but {@code closeStatement} is {@code false}.
     * @throws UncheckedSQLException if locating a requested associated statement or connection, or closing any requested JDBC resource, fails with an SQL exception.
     * @see #close(ResultSet, Statement, Connection)
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
     * @throws UncheckedSQLException if closing {@code stmt} fails with an SQL exception.
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
     * @throws UncheckedSQLException if closing {@code conn} fails with an SQL exception.
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
     * @throws IllegalStateException if {@code failure} is {@code null} or is neither an SQL exception, a runtime exception, nor an error.
     */
    private static void throwCloseFailure(final Throwable failure) throws UncheckedSQLException, IllegalStateException {
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
     * @throws UncheckedSQLException if closing one of the supplied JDBC resources fails with an SQL exception.
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
     * @throws UncheckedSQLException if closing one of the supplied JDBC resources fails with an SQL exception.
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
     * @throws UncheckedSQLException if closing one of the supplied JDBC resources fails with an SQL exception.
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
     * Unconditionally closes a {@link ResultSet}; any exception thrown while closing it, checked or unchecked,
     * is logged and ignored. This method is useful in {@code finally} blocks where you need to ensure a resource is
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
     * Any exception thrown while locating the statement or closing either resource, checked or unchecked,
     * is logged and ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // openProducts(...) creates a Statement and hands over only the ResultSet produced by it.
     * ResultSet rs = openProducts(connection);
     * try {
     *     // ...
     * } finally {
     *     // Quietly closes rs, then the Statement that created it.
     *     JdbcUtil.closeQuietly(rs, true);
     * }
     * }</pre>
     *
     * <p>The statement is located through the result set, so it is not closed when {@code rs} is {@code null}.
     * When the statement handle is available, prefer {@link #closeQuietly(ResultSet, Statement)}.</p>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the {@link Statement} associated with the {@code ResultSet} will also be closed quietly.
     * @see #closeQuietly(ResultSet, Statement)
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
     * // openLogs(...) opens a Connection and Statement and hands over only the ResultSet.
     * ResultSet rs = openLogs(dataSource);
     * try {
     *     // ...
     * } finally {
     *     // Quietly closes rs, then the Statement and Connection it came from.
     *     JdbcUtil.closeQuietly(rs, true, true);
     * }
     * }</pre>
     *
     * <p>The statement and connection are located through the result set, so they are not closed when
     * {@code rs} is {@code null}. When those handles are available, prefer
     * {@link #closeQuietly(ResultSet, Statement, Connection)}.</p>
     *
     * @param rs The {@link ResultSet} to close. Can be {@code null}.
     * @param closeStatement If {@code true}, the associated {@link Statement} is also closed quietly.
     * @param closeConnection If {@code true}, the associated {@link Connection} is also closed quietly.
     *        Requires {@code closeStatement} to be {@code true}.
     * @throws IllegalArgumentException if {@code closeConnection} is {@code true} but {@code closeStatement} is {@code false}.
     * @see #closeQuietly(ResultSet, Statement, Connection)
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
        } catch (final Exception e) {
            // Quiet contract: like closeQuietly(ResultSet, Statement, Connection), swallow unchecked driver failures too.
            logger.warn(e, "Failed to get Statement or Connection from ResultSet(closeStatement={}, closeConnection={})", closeStatement, closeConnection);
        } finally {
            closeQuietly(rs, stmt, conn);
        }
    }

    /**
     * Unconditionally closes a {@link Statement}; any exception thrown while closing it, checked or unchecked,
     * is logged and ignored. This is useful for ensuring a statement is closed in a {@code finally} block without
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
     * Unconditionally closes a {@link ResultSet} and a {@link Statement}. Both closes are attempted; any exception
     * thrown while closing, checked or unchecked, is logged and ignored. This method is useful for
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
     * Unconditionally closes a {@link Statement} and a {@link Connection}. Both closes are attempted; any exception
     * thrown while closing, checked or unchecked, is logged and ignored. This is primarily for cleanup
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
     * Unconditionally closes a {@link ResultSet}, a {@link Statement}, and a {@link Connection}, in that order.
     * Every close is attempted; any exception thrown while closing, checked or unchecked, is logged and ignored.
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
     * @throws IllegalArgumentException if {@code rs} is {@code null} and {@code rowsToSkip} is positive.
     * @throws SQLException if reading the cursor position or advancing the result set fails.
     * @see #skip(ResultSet, long)
     */
    public static int skip(final ResultSet rs, final int rowsToSkip) throws IllegalArgumentException, SQLException {
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
     * result set is {@link ResultSet#TYPE_FORWARD_ONLY}, when the skip count exceeds {@link Integer#MAX_VALUE},
     * when adding it to the current row would overflow, when the driver cannot report the current row,
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
     * @throws IllegalArgumentException if {@code rs} is {@code null} and {@code rowsToSkip} is positive.
     * @throws SQLException if reading the cursor position or advancing the result set fails.
     * @see ResultSet#absolute(int)
     */
    public static long skip(final ResultSet rs, long rowsToSkip) throws IllegalArgumentException, SQLException {
        if (rowsToSkip > 0) {
            N.checkArgNotNull(rs, cs.rs);
        }

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

            // absolute()/last() are only defined for scrollable result sets. Some drivers (e.g. H2) let absolute()
            // overshoot a forward-only cursor to after-last and only throw on the subsequent last(), which would
            // consume the remaining rows while reporting 0 skipped, so iterate forward-only result sets manually.
            if ((rowsToSkip > Integer.MAX_VALUE) || (rowsToSkip > Integer.MAX_VALUE - currentRow) || isForwardOnly(rs)
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
     * Returns {@code true} if the result set reports {@link ResultSet#TYPE_FORWARD_ONLY}; a driver that cannot
     * report its type is treated as scrollable so that {@link ResultSet#absolute(int)} is still attempted.
     */
    private static boolean isForwardOnly(final ResultSet rs) {
        try {
            return rs.getType() == ResultSet.TYPE_FORWARD_ONLY;
        } catch (final SQLException e) {
            return false;
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if obtaining result-set metadata or reading its column count fails.
     * @see ResultSet#getMetaData()
     * @see ResultSetMetaData#getColumnCount()
     */
    public static int getColumnCount(final ResultSet rs) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);

        return rs.getMetaData().getColumnCount();
    }

    /**
     * Returns an ordered list of column names for a specified table.
     *
     * <p>This method first consults {@link DatabaseMetaData#getColumns} (with the table name as supplied,
     * and upper- and lower-case variants only when the driver reports no canonical identifier case). Undelimited parts are
     * folded to the storage case reported by the driver; delimited and case-folded parts are
     * matched case-sensitively. As in {@link #tableExists(Connection, String)}, an unqualified name is looked up only in
     * the connection's current schema ({@link Connection#getSchema()}) and catalog; if the current schema cannot be
     * determined on a database that supports schemas, the metadata lookup is skipped for an unqualified name. A delimited or
     * quote-dependent unqualified name, which the fallback query below cannot check, is also looked up in the other schemas of
     * the database's search path, in search order (PostgreSQL-compatible databases and H2). If the
     * metadata lookup yields no match and all name parts
     * satisfy the unquoted identifier policy, it falls back to running an
     * empty-result query of the form {@code SELECT * FROM <table> WHERE 1 > 2} to extract column
     * names from the result set metadata; that query follows the database's own name resolution (such as the
     * PostgreSQL search path). On PostgreSQL and PostgreSQL-compatible databases, when the connection has auto-commit
     * disabled, the fallback query runs under a savepoint that is rolled back if the query fails, so a missing table does
     * not abort the caller's transaction; if no savepoint can be set there, or rolling back to it fails, the propagated
     * failure may have left that transaction aborted. Generated identifier quoting is disabled: this fallback accepts only
     * simple ASCII names beginning with a letter or underscore, excluding common SQL keywords. Delimited and
     * quote-dependent names remain available through exact JDBC metadata lookup but have no SQL fallback.</p>
     *
     * <p>The {@code tableName} may be a simple identifier or a qualified name like
     * {@code schema.table} or {@code catalog.schema.table}. On databases whose driver supports catalogs but not schemas
     * in table definitions, a two-part name is interpreted as {@code catalog.table}. Schema and table parts are literal
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
     * @throws SQLException if reading connection or column metadata fails, the fallback table query fails (on a database where a
     *         failed statement aborts the transaction, possibly leaving the connection's open transaction aborted when no savepoint
     *         could be set, or rolling back to it failed), or no columns can be found for the table.
     * @see #getColumnLabels(ResultSet)
     */
    public static List<String> getColumnNames(final Connection conn, final String tableName) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(tableName, cs.tableName);

        final String[] nameParts = splitQualifiedSqlIdentifier(tableName, cs.tableName);
        final boolean[] delimitedNameParts = SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(tableName, nameParts.length);
        final DatabaseMetaData metadata = conn.getMetaData();
        final boolean caseFolded = normalizeMetadataIdentifierParts(metadata, nameParts, delimitedNameParts);
        final boolean catalogQualified = nameParts.length == 2 && !metadata.supportsSchemasInTableDefinitions()
                && metadata.supportsCatalogsInTableDefinitions();
        final String catalog;
        final String schema;
        final String table;
        final boolean catalogDelimited;
        final boolean schemaDelimited;
        final boolean tableDelimited;

        if (nameParts.length == 1) {
            catalog = conn.getCatalog();
            schema = null; // unqualified: look in the connection's current schema only
            table = nameParts[0];
            catalogDelimited = false;
            schemaDelimited = false;
            tableDelimited = delimitedNameParts[0];
        } else if (nameParts.length == 2) {
            // On catalog-only databases, database.table must query the named catalog, not the connection's current database.
            catalog = catalogQualified ? nameParts[0] : conn.getCatalog();
            schema = catalogQualified ? null : nameParts[0];
            table = nameParts[1];
            catalogDelimited = catalogQualified && delimitedNameParts[0];
            schemaDelimited = !catalogQualified && delimitedNameParts[0];
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
        boolean lookUpMetadata = true;

        if (schemaToUse == null && !catalogQualified) {
            // Same rule as tableExists: for an unqualified name, a metadata match counts only in the current schema/catalog;
            // when that namespace cannot be pinned down, only the probe below (the database's own name resolution) is used.
            schemaToUse = getCurrentSchema(conn);
            lookUpMetadata = schemaToUse != null || isCurrentNamespaceLookup(metadata, catalog);
        }

        List<String> columnNameList = lookUpMetadata
                ? getColumnNamesInSchema(metadata, catalog, schemaToUse, table, catalogDelimited, schemaDelimited, tableDelimited)
                : null;

        if (N.isEmpty(columnNameList)) {
            // The same probe name and gate as tableExists (keep the two decision tables identical): the gate is computed from
            // the same quote-aware probe name as tableExists so the two decisions stay textually identical. The probe builder
            // excludes quote-dependent names while quoting is disabled; if enabled, canonical (case-folded) parts are quoted
            // without changing the intended table, including names that are SQL keywords. Gating on the unquoted name was
            // behaviorally equivalent while quoting is disabled (the quote only affects rendering, not whether a name is
            // excluded); the unification removes the drift once quoting is enabled.
            final String probeQuote = caseFolded && !hasDelimitedIdentifierPart(tableName) ? normalizeIdentifierQuote(metadata) : null;
            final String safeQualifiedTableName = buildSimpleQualifiedTableName(nameParts.length == 3 || catalogQualified ? catalog : null, schema, table,
                    probeQuote);
            // splitQualifiedSqlIdentifier removes delimiters. Never feed those stripped parts to unquoted fallback SQL.
            final boolean probeable = !hasDelimitedIdentifierPart(tableName) && Strings.isNotEmpty(safeQualifiedTableName);

            if (!probeable && schema == null && !catalogQualified) {
                // As in tableExists: a name the fallback query can't check is looked up in the other schemas of the search path too.
                for (final String searchPathSchema : getSearchPathSchemas(conn, metadata, schemaToUse)) {
                    columnNameList = getColumnNamesInSchema(metadata, catalog, searchPathSchema, table, catalogDelimited, true, tableDelimited);

                    if (N.notEmpty(columnNameList)) {
                        break;
                    }
                }
            }

            if (N.isEmpty(columnNameList) && probeable) {
                // As in tableExists: on PostgreSQL-like databases a failed probe (e.g. a missing table) would abort the caller's
                // open transaction, so probe under a savepoint there and roll back to it on failure. The failure is propagated
                // either way (there is no answer without the probe), also when no savepoint could be set in the transaction,
                // which then may have been left aborted: the same rule as tableExists and getSearchPathSchemas.
                final java.sql.Savepoint savepoint = abortsTransactionOnStatementError(metadata) ? setSavepointForTableProbe(conn) : null;

                try {
                    columnNameList = getColumnNamesBySelect(conn, safeQualifiedTableName);
                } catch (final SQLException e) {
                    if (savepoint != null) {
                        rollbackToProbeSavepoint(conn, savepoint, e);
                    }

                    throw e;
                }

                if (savepoint != null) {
                    releaseSavepointQuietly(conn, savepoint);
                }
            }
        }

        if (N.isEmpty(columnNameList)) {
            throw new SQLException("No columns found for table: " + tableName);
        }

        return columnNameList;
    }

    /**
     * Returns the column names of one table through {@link DatabaseMetaData#getColumns(String, String, String, String)}.
     * Because the metadata arguments are patterns, every returned row is checked against the requested
     * catalog, schema, and table before it is accepted (a row whose {@code TABLE_CAT} is {@code null}, which
     * JDBC permits, is not rejected on its catalog). If a pattern matches more than one table, columns
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
     * @throws SQLException if querying column metadata, reading its rows, or closing the metadata result set fails.
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
                // The catalog argument is an exact name the driver already filtered on, and JDBC allows
                // TABLE_CAT to be null (e.g. drivers that report Connection.getCatalog() but no row catalog),
                // so only a reported catalog that differs rejects the row.
                if (!identifierMatches(tableNamePattern, tableNameInMetadata, tableCaseSensitive)
                        || !identifierMatches(schemaPattern, schemaInMetadata, schemaCaseSensitive)
                        || (catalogInMetadata != null && !identifierMatches(catalog, catalogInMetadata, catalogCaseSensitive))) {
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
     * @throws SQLException if preparing or executing the metadata probe or reading its column metadata fails.
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if obtaining metadata or reading column labels and names fails.
     * @see #getColumnLabel(ResultSetMetaData, int)
     * @see ResultSetMetaData#getColumnLabel(int)
     */
    public static List<String> getColumnLabels(final ResultSet rs) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);

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
     * @throws IllegalArgumentException if {@code rsmd} is {@code null}.
     * @throws SQLException if reading the label or fallback name fails, including an invalid column index.
     * @see ResultSetMetaData#getColumnLabel(int)
     * @see ResultSetMetaData#getColumnName(int)
     */
    public static String getColumnLabel(final ResultSetMetaData rsmd, final int columnIndex) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rsmd, cs.rsmd);

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
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if obtaining metadata or reading column labels and names fails.
     * @see #getColumnIndex(ResultSetMetaData, String)
     */
    public static int getColumnIndex(final ResultSet rs, final String columnLabel) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);

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
     * @throws IllegalArgumentException if {@code rsmd} is {@code null}.
     * @throws SQLException if obtaining metadata or reading column labels and names fails.
     * @see #getColumnIndex(ResultSet, String)
     */
    public static int getColumnIndex(final ResultSetMetaData rsmd, final String columnLabel) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rsmd, cs.rsmd);

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
         * @throws SQLException if reading the requested column value from the result set fails.
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
         * @throws SQLException if reading the requested column value from the result set fails.
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if reading or converting the requested JDBC column, or materializing or freeing a returned LOB, fails.
     * @see #getColumnValue(ResultSet, String)
     */
    public static Object getColumnValue(final ResultSet rs, final int columnIndex) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);

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
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if reading or converting the requested JDBC column, or materializing or freeing a returned LOB, fails.
     */
    static Object getColumnValue(final ResultSet rs, final int columnIndex, final boolean checkDateType) throws NullPointerException, SQLException {
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if reading or converting the requested JDBC column, or materializing or freeing a returned LOB, fails.
     * @deprecated Use {@link #getColumnValue(ResultSet, int)} with a cached column index for better performance.
     * @see #getColumnValue(ResultSet, int)
     * @see #getColumnIndex(ResultSet, String)
     */
    @Deprecated
    public static Object getColumnValue(final ResultSet rs, final String columnLabel) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);

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
     * @throws NullPointerException if {@code rs} is {@code null}.
     * @throws SQLException if reading or converting the requested JDBC column, or materializing or freeing a returned LOB, fails.
     * @deprecated Use {@link #getColumnValue(ResultSet, int, boolean)} with a cached column index for better performance.
     */
    @Deprecated
    static Object getColumnValue(final ResultSet rs, final String columnLabel, final boolean checkDateType) throws NullPointerException, SQLException {
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

            // Position 1 is past the end of an empty Blob, and some implementations reject it: the JDK's SerialBlob
            // (also what CachedRowSet returns) throws SerialException for getBytes(1, 0).
            return len == 0 ? N.EMPTY_BYTE_ARRAY : blob.getBytes(1, (int) len);
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

            // As for an empty Blob: the JDK's SerialClob throws SerialException for getSubString(1, 0).
            return len == 0 ? Strings.EMPTY : clob.getSubString(1, (int) len);
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if advancing the result set, resolving its columns, or reading a requested value fails.
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> getAllColumnValues(final ResultSet rs, final int columnIndex) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);

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
     * @throws IllegalArgumentException if {@code rs} is {@code null} or no column matches {@code columnLabel}.
     * @throws SQLException if resolving column metadata, advancing the result set, or reading or converting a column value fails.
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
     * @throws IllegalArgumentException if {@code rs} or {@code targetClass} is {@code null}.
     * @throws SQLException if reading or converting the requested JDBC column, or materializing or freeing a returned LOB, fails.
     * @see #getColumnValue(ResultSet, String, Class)
     */
    public static <T> T getColumnValue(final ResultSet rs, final int columnIndex, final Class<? extends T> targetClass)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNull(targetClass, cs.targetClass);

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
     * @throws IllegalArgumentException if {@code rs} or {@code targetClass} is {@code null}.
     * @throws SQLException if reading or converting the requested JDBC column, or materializing or freeing a returned LOB, fails.
     * @deprecated Use {@link #getColumnValue(ResultSet, int, Class)} with a cached column index for better performance.
     * @see #getColumnIndex(ResultSet, String)
     */
    @Deprecated
    public static <T> T getColumnValue(final ResultSet rs, final String columnLabel, final Class<? extends T> targetClass)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotNull(targetClass, cs.targetClass);

        return Type.<T> of(targetClass).get(rs, columnLabel);
    }

    /**
     * Retrieves a mapping from database column names to entity field names for a given entity class.
     * This mapping is crucial for the automatic object-relational mapping (ORM) features,
     * allowing {@code JdbcUtil} to populate entity objects from a {@code ResultSet}.
     * Only properties that declare an explicit column name (e.g., {@code @Column("user_id")}) are included;
     * properties mapped purely by naming convention are not. Besides each declared column name, the map also
     * contains its lower- and upper-case variants for case-insensitive lookups (an exact declared spelling
     * always wins over a folded variant).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming User declares @Column("user_id") on 'userId' and @Column("user_name") on 'userName'
     * ImmutableMap<String, String> columnToPropNameMap = JdbcUtil.getColumnToPropNameMap(User.class);
     *
     * System.out.println(columnToPropNameMap.get("user_id"));     // Output: userId
     * System.out.println(columnToPropNameMap.get("USER_NAME"));   // Output: userName
     * }</pre>
     *
     * @param entityClass The entity class to analyze for column-to-field mappings; must not be {@code null}.
     * @return An {@link ImmutableMap} where keys are the declared database column names (plus their lower- and upper-case
     *         variants) and values are the corresponding entity field names.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a valid entity bean class.
     * @see com.landawn.abacus.annotation.Column
     * @see com.landawn.abacus.util.NamingPolicy
     */
    public static ImmutableMap<String, String> getColumnToPropNameMap(final Class<?> entityClass) throws IllegalArgumentException {
        return QueryUtil.columnToPropNameMap(entityClass);
    }

    /**
     * Returns whether a database product name denotes PostgreSQL or a derivative sharing its transaction semantics:
     * a failed statement aborts the enclosing transaction. CockroachDB, YugabyteDB, Aurora PostgreSQL and Greenplum
     * report "PostgreSQL" through pgjdbc.
     *
     * @param productName The product name reported by the driver.
     * @return {@code true} if the product is PostgreSQL-compatible.
     */
    private static boolean isPostgreSqlFamily(final String productName) {
        return Strings.containsAnyIgnoreCase(productName, "PostgreSQL", "EnterpriseDB", "Redshift", "Greenplum");
    }

    /**
     * Statement configurer for queries expected to return a large result set: sets the forward fetch-direction hint
     * and raises the fetch size to at least {@link #DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET}.
     * These are driver hints, not a heap bound: PostgreSQL cursor fetching also needs auto-commit disabled
     * and a forward-only, non-holdable result set. This configurer preserves the caller's transaction settings.
     */
    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);
        }
    };

    /**
     * Statement configurer for stream-based result processing: sets the forward fetch-direction hint
     * and raises the fetch size to at least {@link #DEFAULT_FETCH_SIZE_FOR_STREAM}.
     * The driver may still buffer results unless its cursor prerequisites are satisfied; transaction
     * ownership and commit behavior remain the caller's responsibility.
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
     * // Own the query before binding, so a binding failure also closes it.
     * try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users WHERE id = ?")) {
     *     Optional<User> user = query.setLong(1, userId).findFirst(User.class);
     *     if (user.isPresent()) {
     *         System.out.println("Found: " + user.get().getName());
     *     }
     * }
     *
     * // Query with multiple parameters returning a list
     * try (PreparedQuery query = JdbcUtil.prepareQuery(dataSource,
     *         "SELECT * FROM orders WHERE customer_id = ? AND status = ? AND order_date > ?")) {
     *     List<Order> orders = query.setLong(1, customerId)
     *             .setString(2, "PENDING")
     *             .setDate(3, Date.valueOf(lastWeek))
     *             .list(Order.class);
     *     orders.forEach(order -> System.out.println("Order #" + order.getId()));
     * }
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see PreparedQuery
     * @see #prepareQuery(Connection, String)
     * @see #executeQuery(javax.sql.DataSource, String, Object...)
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareQuery(tran.connection(), sql));
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see Statement#RETURN_GENERATED_KEYS
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareQuery(tran.connection(), sql, autoGeneratedKeys));
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or {@code sql} or {@code generatedKeyColumnIndexes} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see Connection#prepareStatement(String, int[])
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareQuery(tran.connection(), sql, generatedKeyColumnIndexes));
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or {@code sql} or {@code generatedKeyColumnNames} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see Connection#prepareStatement(String, String[])
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareQuery(tran.connection(), sql, generatedKeyColumnNames));
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
     * @throws IllegalArgumentException if {@code ds} or {@code stmtCreator} is {@code null}, if {@code sql} is {@code null} or empty, or if
     *         {@code stmtCreator} returns {@code null}.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
     */
    public static PreparedQuery prepareQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareQuery(tran.connection(), sql, stmtCreator));
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
     * try (Connection conn = dataSource.getConnection();
     *      PreparedQuery query = JdbcUtil.prepareQuery(conn, "SELECT * FROM users WHERE id = ?")) {
     *     User user = query.setLong(1, userId).findOnlyOneOrNull(User.class);
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * try (Connection conn = dataSource.getConnection();
     *      PreparedQuery query = JdbcUtil.prepareQuery(conn, "INSERT INTO users (name) VALUES (?)", true)) {
     *     Optional<Long> newId = query.setString(1, "New User").insert();
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}, or {@code sql} or {@code generatedKeyColumnIndexes} is {@code null} or empty.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}, or {@code sql} or {@code generatedKeyColumnNames} is {@code null} or empty.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * try (Connection conn = dataSource.getConnection();
     *      PreparedQuery query = JdbcUtil.prepareQuery(conn, "SELECT * FROM large_table",
     *              (c, s) -> c.prepareStatement(s, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY))) {
     *     query.setFetchSize(100).setQueryTimeout(30);   // Timeout in seconds
     *     try (Stream<AuditRecord> stream = query.stream(AuditRecord.class)) {
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
     * @throws IllegalArgumentException if {@code conn} or {@code stmtCreator} is {@code null}, if {@code sql} is {@code null} or empty, or if
     *         {@code stmtCreator} returns {@code null}.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
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
     * @throws SQLException if preparing the statement or configuring its fetch direction or fetch size fails.
     * @see #prepareQueryForLargeResult(Connection, String)
     */
    @Beta
    public static PreparedQuery prepareQueryForLargeResult(final javax.sql.DataSource ds, final String sql)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
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
     * @throws SQLException if preparing the statement or configuring its fetch direction or fetch size fails.
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see NamedQuery
     * @see #prepareNamedQuery(Connection, String)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        final ParsedSql parsedSql = parseNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), parsedSql));
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, parsedSql).onClose(createCloseHandler(conn, ds));
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        final ParsedSql parsedSql = parseNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), parsedSql, autoGeneratedKeys));
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, parsedSql, autoGeneratedKeys).onClose(createCloseHandler(conn, ds));
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters; or {@code generatedKeyColumnIndexes} is
     *         {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);
        final ParsedSql parsedSql = parseNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), parsedSql, generatedKeyColumnIndexes));
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, parsedSql, generatedKeyColumnIndexes).onClose(createCloseHandler(conn, ds));
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters; or {@code generatedKeyColumnNames} is
     *         {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);
        final ParsedSql parsedSql = parseNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), parsedSql, generatedKeyColumnNames));
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, parsedSql, generatedKeyColumnNames).onClose(createCloseHandler(conn, ds));
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters; or {@code stmtCreator} is {@code null} or
     *         returns {@code null}.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final String namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);
        final ParsedSql parsedSql = parseNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), parsedSql, stmtCreator));
        } else {
            NamedQuery result = null;
            Connection conn = null;

            try {
                conn = JdbcUtil.getConnection(ds);
                result = prepareNamedQuery(conn, parsedSql, stmtCreator).onClose(createCloseHandler(conn, ds));
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}, or {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}, or {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters; or {@code generatedKeyColumnIndexes} is
     *         {@code null} or empty.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters; or {@code generatedKeyColumnNames} is
     *         {@code null} or empty.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters; or {@code stmtCreator} is {@code null} or
     *         returns {@code null}.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
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
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, or if {@code namedSql} contains positional parameters without names.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), namedSql));
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
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, or if {@code namedSql} contains positional parameters without names.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final boolean autoGeneratedKeys)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        validateNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), namedSql, autoGeneratedKeys));
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
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, if {@code namedSql} contains positional parameters without
     *         names, or if {@code generatedKeyColumnIndexes} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnIndexes, cs.generatedKeyColumnIndexes);
        validateNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), namedSql, generatedKeyColumnIndexes));
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
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, if {@code namedSql} contains positional parameters without
     *         names, or if {@code generatedKeyColumnNames} is {@code null} or empty.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotEmpty(generatedKeyColumnNames, cs.generatedKeyColumnNames);
        validateNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), namedSql, generatedKeyColumnNames));
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
     * @throws IllegalArgumentException if {@code ds}, {@code namedSql}, or {@code stmtCreator} is {@code null}, if {@code namedSql} contains
     *         positional parameters without names, or if {@code stmtCreator} returns {@code null}.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static NamedQuery prepareNamedQuery(final javax.sql.DataSource ds, final ParsedSql namedSql,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(namedSql, cs.namedSql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);
        validateNamedSql(namedSql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareNamedQuery(tran.connection(), namedSql, stmtCreator));
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
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, or if {@code namedSql} contains positional parameters without names.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, or if {@code namedSql} contains positional parameters without names.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, if {@code namedSql} contains positional parameters
     *         without names, or if {@code generatedKeyColumnIndexes} is {@code null} or empty.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, if {@code namedSql} contains positional parameters
     *         without names, or if {@code generatedKeyColumnNames} is {@code null} or empty.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws IllegalArgumentException if {@code conn}, {@code namedSql}, or {@code stmtCreator} is {@code null}, if {@code namedSql} contains
     *         positional parameters without names, or if {@code stmtCreator} returns {@code null}.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; see {@link #getConnection(javax.sql.DataSource)}
     *         for Spring integration behavior.
     * @throws SQLException if preparing the statement or configuring its fetch direction or fetch size fails.
     */
    @Beta
    public static NamedQuery prepareNamedQueryForLargeResult(final javax.sql.DataSource ds, final String namedSql)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
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
     * @throws IllegalArgumentException if {@code ds} or {@code namedSql} is {@code null}, or if {@code namedSql} contains positional parameters without names.
     * @throws UncheckedSQLException if acquiring a connection fails; see {@link #getConnection(javax.sql.DataSource)}
     *         for Spring integration behavior.
     * @throws SQLException if preparing the statement or configuring its fetch direction or fetch size fails.
     */
    @Beta
    public static NamedQuery prepareNamedQueryForLargeResult(final javax.sql.DataSource ds, final ParsedSql namedSql)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}, or {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles,
     *         contains a malformed parameter placeholder, or contains positional (unnamed) parameters.
     * @throws SQLException if preparing the statement or configuring its fetch direction or fetch size fails.
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
     * @throws IllegalArgumentException if {@code conn} or {@code namedSql} is {@code null}, or if {@code namedSql} contains positional parameters without names.
     * @throws SQLException if preparing the statement or configuring its fetch direction or fetch size fails.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareCallableQuery(tran.connection(), sql));
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
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(dataSource,
     *         "{call long_running_procedure(?)}", (conn, sql) -> conn.prepareCall(sql))) {
     *     query.setQueryTimeout(30);   // Configure after the query owns the statement
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
     * @throws IllegalArgumentException if {@code ds} or {@code sql} is {@code null} or empty, or if {@code stmtCreator} is {@code null} or returns
     *         {@code null}.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
     * @see #prepareCallableQuery(javax.sql.DataSource, String)
     * @see #getConnection(javax.sql.DataSource)
     * @see #releaseConnection(Connection, javax.sql.DataSource)
     */
    public static CallableQuery prepareCallableQuery(final javax.sql.DataSource ds, final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgNotNull(stmtCreator, cs.stmtCreator);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            return inTransaction(tran, prepareCallableQuery(tran.connection(), sql, stmtCreator));
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * // Custom statement creator with a query timeout configured after ownership is established.
     * Connection conn = dataSource.getConnection();
     * try (CallableQuery query = JdbcUtil.prepareCallableQuery(conn, "{call get_users(?, ?)}",
     *         (c, sql) -> c.prepareCall(sql))) {
     *     query.setQueryTimeout(30);   // 30 seconds
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
     * @throws IllegalArgumentException if {@code conn} or {@code sql} is {@code null} or empty, or if {@code stmtCreator} is {@code null} or returns
     *         {@code null}.
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
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
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL with the requested statement options.
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
     * @throws SQLException if the supplied statement creator throws while preparing the requested statement.
     */
    static CallableStatement prepareCallable(final Connection conn, final ParsedSql parsedSql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        JdbcUtil.logSql(parsedSql.originalSql());

        return stmtCreator.apply(conn, parsedSql.parameterizedSql());
    }

    /**
     * Prepares a PreparedStatement for the given SQL query and sets the provided parameters.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     * The new statement is closed if parameter binding fails; any close failure is suppressed on the binding failure.
     *
     * @param conn The database connection to use.
     * @param sql The SQL statement, which may contain positional or named parameters.
     * @param parameters The parameter values to set on the prepared statement.
     * @return A PreparedStatement with parameters set, ready for execution.
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL or bind a supplied parameter.
     */
    static PreparedStatement prepareStmt(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        try {
            // Always validate the parameter count. Skipping this call for a null/empty array
            // lets SQL containing placeholders reach the driver with unbound parameters,
            // even though setParameters has a deterministic validation error for that case.
            setParameters(parsedSql, stmt, parameters);
        } catch (final SQLException | RuntimeException | Error e) {
            closeAfterPreparationFailure(stmt, e);
            throw e;
        }

        return stmt;
    }

    /**
     * Prepares a CallableStatement for executing stored procedures or functions with the given SQL and parameters.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     * The new statement is closed if parameter binding fails; any close failure is suppressed on the binding failure.
     *
     * @param conn The database connection to use.
     * @param sql The SQL call statement, which may contain positional or named parameters.
     * @param parameters The parameter values to set on the callable statement.
     * @return A CallableStatement with parameters set, ready for execution.
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL or bind a supplied parameter.
     */
    static CallableStatement prepareCall(final Connection conn, final String sql, final Object... parameters) throws IllegalArgumentException, SQLException {
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
            } catch (final SQLException | RuntimeException | Error e) {
                closeAfterPreparationFailure(stmt, e);
                throw e;
            }
        }

        return stmt;
    }

    /**
     * Prepares a PreparedStatement for batch execution with multiple sets of parameters.
     * Each element in the parameters list represents one batch of parameters to be added to the statement.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     * The new statement is closed if binding or adding a batch fails; any close failure is suppressed on the original failure.
     *
     * @param conn The database connection to use.
     * @param sql The SQL statement, which may contain positional or named parameters.
     * @param parametersList A list where each element contains parameter values for one batch operation.
     * @return A PreparedStatement with all batches added, ready for batch execution via executeBatch().
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL, bind a supplied parameter, or add a batch.
     */
    static PreparedStatement prepareBatchStmt(final Connection conn, final String sql, final List<?> parametersList)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final PreparedStatement stmt = prepareStatement(conn, parsedSql);

        try {
            for (final Object parameters : parametersList) {
                setParameters(parsedSql, stmt, N.asArray(parameters));
                stmt.addBatch();
            }
        } catch (final SQLException | RuntimeException | Error e) {
            closeAfterPreparationFailure(stmt, e);
            throw e;
        }

        return stmt;
    }

    /**
     * Prepares a CallableStatement for batch execution of stored procedures or functions with multiple sets of parameters.
     * Each element in the parameters list represents one batch of parameters to be added to the statement.
     * The SQL string can contain either positional (?) or named parameters (:paramName).
     * The new statement is closed if binding or adding a batch fails; any close failure is suppressed on the original failure.
     *
     * @param conn The database connection to use.
     * @param sql The SQL call statement, which may contain positional or named parameters.
     * @param parametersList A list where each element contains parameter values for one batch operation.
     * @return A CallableStatement with all batches added, ready for batch execution via executeBatch().
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws SQLException if the connection is closed or the driver cannot prepare the SQL, bind a supplied parameter, or add a batch.
     */
    static CallableStatement prepareBatchCall(final Connection conn, final String sql, final List<?> parametersList)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        final CallableStatement stmt = prepareCallable(conn, parsedSql);

        try {
            for (final Object parameters : parametersList) {
                setParameters(parsedSql, stmt, N.asArray(parameters));
                stmt.addBatch();
            }
        } catch (final SQLException | RuntimeException | Error e) {
            closeAfterPreparationFailure(stmt, e);
            throw e;
        }

        return stmt;
    }

    /**
     * Closes a statement that could not be prepared completely, preserving the original binding or batch failure.
     *
     * @param stmt the statement created for the failed preparation
     * @param failure the original binding or batch failure that receives any close failure as a suppressed exception
     */
    private static void closeAfterPreparationFailure(final Statement stmt, final Throwable failure) {
        try {
            stmt.close();
        } catch (final SQLException | RuntimeException | Error closeFailure) {
            addSuppressedIfDistinct(failure, closeFailure);
        }
    }

    /**
     * Parses a named SQL statement into a ParsedSql object and validates that all parameters are named.
     * Named SQL uses parameters in the format :paramName or #{paramName}.
     *
     * @param namedSql the SQL statement containing named parameters
     * @return a ParsedSql object representing the parsed and validated named SQL
     * @throws IllegalArgumentException if {@code namedSql} is {@code null}, empty, or blank, mixes parameter styles, has a malformed placeholder, or
     *         contains unnamed parameters.
     */
    private static ParsedSql parseNamedSql(final String namedSql) throws IllegalArgumentException {
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
    private static void validateNamedSql(final ParsedSql namedSql) throws IllegalArgumentException {
        if (namedSql.namedParameters().size() != namedSql.parameterCount()) {
            throw new IllegalArgumentException("Named SQL contains positional or otherwise unnamed parameters");
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
     *     String productName = products.get(i, products.getColumnIndex("product_name"));
     *     System.out.println(productName);
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if preparing or binding the query, executing it, or reading its rows or metadata fails.
     * @see PreparedStatement#executeQuery()
     * @see #prepareQuery(javax.sql.DataSource, String)
     * @see Dataset
     */
    public static Dataset executeQuery(final javax.sql.DataSource ds, final String sql, final Object... parameters)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws SQLException if preparing or binding the query, executing it, or reading its rows or metadata fails.
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if preparing or binding the statement, or executing the requested SQL, fails.
     * @see PreparedStatement#executeUpdate()
     * @see #prepareQuery(javax.sql.DataSource, String)
     */
    public static int executeUpdate(final javax.sql.DataSource ds, final String sql, final Object... parameters)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws SQLException if preparing or binding the statement, or executing the requested SQL, fails.
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
     * <p>Connection and transaction handling follow {@link #executeBatchUpdate(javax.sql.DataSource, String, List, int)}: more
     * than one parameter set runs in a transaction scope (a nested scope of the transaction for {@code ds} active on this
     * thread, whose failure marks that transaction rollback-only, or else a transaction that this method begins and commits); a
     * single parameter set joins the active transaction, if there is one.</p>
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
     * @return The total number of rows affected by the batch update across all batches
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null} or empty; or, when {@code listOfParameters} is not
     *         empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter placeholder, or a supplied parameter set cannot
     *         satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws IllegalStateException if more than one parameter set is given and the transaction scope of the batch cannot be
     *         begun (see {@link #beginTransaction(javax.sql.DataSource)}).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or restoring an owned transaction fails.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, SQLException, ArithmeticException {
        return executeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a batch SQL update using the provided DataSource with specified batch size.
     * Large lists will be automatically split into smaller batches for optimal performance.
     *
     * <p>More than one parameter set runs in a transaction scope, even if all of them fit in one batch, so that all of them
     * commit or roll back together: if a transaction for {@code ds} is already active on the current thread, a nested scope of
     * it (see {@link #beginTransaction(javax.sql.DataSource)}), so a failure marks that transaction rollback-only and its
     * outermost {@code commit()} then rolls back and throws {@link RollbackOnlyException}; otherwise a transaction that this
     * method begins and commits (inside a Spring-managed transaction that owns {@code ds}, it participates in it and Spring
     * commits the work). A single parameter set runs on the connection of the transaction for {@code ds} active on the current
     * thread, if there is one, and otherwise on a newly acquired connection (see {@link #getConnection(javax.sql.DataSource)}:
     * inside a Spring-managed transaction, the connection Spring bound to {@code ds}, so the statement runs in that transaction);
     * as for any single statement, its failure does not mark the active transaction rollback-only. Every parameter set is
     * checked against the SQL's parameters before a connection is acquired or the scope begins, so a set that can't satisfy
     * them throws {@code IllegalArgumentException} without marking anything (a value the driver rejects when it is bound
     * fails in the scope, as a statement does).</p>
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
     *                        an Object array or a compatible collection representing one set of parameters
     *                        (for named-parameter SQL, an entity, {@link Map}, or {@link EntityId} is also accepted).
     * @param batchSize The size of each batch, must be positive. Smaller batches use less memory
     *                  but may be slower; larger batches are faster but use more memory.
     * @return The total number of rows affected by the batch update across all batches
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null} or empty; {@code batchSize} is not positive; or,
     *         when {@code listOfParameters} is not empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter
     *         placeholder, or a supplied parameter set cannot satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws IllegalStateException if more than one parameter set is given and the transaction scope of the batch cannot be
     *         begun (see {@link #beginTransaction(javax.sql.DataSource)}).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or restoring an owned transaction fails.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     * @see #executeBatchUpdate(javax.sql.DataSource, String, List)
     */
    public static int executeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, SQLException, ArithmeticException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        // Every parameter set is checked before a connection is taken or the scope begins. WHY: a set that can't satisfy the
        // SQL was found only when it was bound, inside the scope of a batch of several sets, whose failure then marked the
        // caller's transaction rollback-only although no statement had run (DAO batch operations check their entities first).
        final ParsedSql parsedSql = ParsedSql.parse(sql);
        checkBatchParameters(parsedSql, listOfParameters);

        if (listOfParameters.size() == 1) {
            final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran != null) {
                return runBatchUpdate(tran.connection(), parsedSql, listOfParameters, batchSize);
            }

            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return runBatchUpdate(conn, parsedSql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        } else {
            // A nested scope of the thread's transaction, if there is one, as for DAO batch operations: its failure marks that
            // transaction rollback-only. The batch used to run on the transaction's connection without a scope, so a caller that
            // caught its failure went on to commit the rows of the statements that had run, and the commit returned normally.
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
            int ret = 0;
            Throwable failure = null;

            try {
                ret = runBatchUpdate(tran.connection(), parsedSql, listOfParameters, batchSize);
                tran.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                rollbackAfterTransactionCommand(tran, failure);
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null} or empty; or, when {@code listOfParameters} is
     *         not empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter placeholder, or a supplied parameter set
     *         cannot satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or restoring an owned transaction fails.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     * @see #executeBatchUpdate(Connection, String, List, int)
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException, ArithmeticException {
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
     * @return The total number of rows affected by the batch update across all batches
     *         (batch entries for which the driver reports {@code Statement.SUCCESS_NO_INFO} contribute 0 to this total).
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null} or empty; {@code batchSize} is not positive; or,
     *         when {@code listOfParameters} is not empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter
     *         placeholder, or a supplied parameter set cannot satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or restoring an owned transaction fails.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Integer#MAX_VALUE} (use {@code executeLargeBatchUpdate} for
     *         large batch results).
     * @see PreparedStatement#executeBatch()
     */
    public static int executeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException, ArithmeticException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        checkBatchParameters(parsedSql, listOfParameters);

        return runBatchUpdate(conn, parsedSql, listOfParameters, batchSize);
    }

    /**
     * Runs the batch of {@link #executeBatchUpdate(Connection, String, List, int)} once its arguments have been checked.
     */
    private static int runBatchUpdate(final Connection conn, final ParsedSql parsedSql, final List<?> listOfParameters, final int batchSize)
            throws SQLException, ArithmeticException {
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
     * <p>Connection and transaction handling follow {@link #executeLargeBatchUpdate(javax.sql.DataSource, String, List, int)}: more
     * than one parameter set runs in a transaction scope (a nested scope of the transaction for {@code ds} active on this
     * thread, whose failure marks that transaction rollback-only, or else a transaction that this method begins and commits); a
     * single parameter set joins the active transaction, if there is one.</p>
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null} or empty; or, when {@code listOfParameters} is not
     *         empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter placeholder, or a supplied parameter set cannot
     *         satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws IllegalStateException if more than one parameter set is given and the transaction scope of the batch cannot be
     *         begun (see {@link #beginTransaction(javax.sql.DataSource)}).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or
     *         restoring an owned transaction fails.
     * @throws UnsupportedOperationException if the JDBC driver does not implement
     *         {@link Statement#executeLargeBatch()} (the JDBC 4.2 default implementation throws it);
     *         no batch is executed in that case, so use {@link #executeBatchUpdate(javax.sql.DataSource, String, List)} with such a driver.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     * @see #executeLargeBatchUpdate(javax.sql.DataSource, String, List, int)
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, SQLException, UnsupportedOperationException, ArithmeticException {
        return executeLargeBatchUpdate(ds, sql, listOfParameters, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes a large batch SQL update using the provided DataSource with specified batch size.
     * This method returns a {@code long} value to support updates affecting more than {@link Integer#MAX_VALUE} rows.
     *
     * <p>More than one parameter set runs in a transaction scope, even if all of them fit in one batch, so that all of them
     * commit or roll back together: if a transaction for {@code ds} is already active on the current thread, a nested scope of
     * it (see {@link #beginTransaction(javax.sql.DataSource)}), so a failure marks that transaction rollback-only and its
     * outermost {@code commit()} then rolls back and throws {@link RollbackOnlyException}; otherwise a transaction that this
     * method begins and commits (inside a Spring-managed transaction that owns {@code ds}, it participates in it and Spring
     * commits the work). A single parameter set runs on the connection of the transaction for {@code ds} active on the current
     * thread, if there is one, and otherwise on a newly acquired connection (see {@link #getConnection(javax.sql.DataSource)}:
     * inside a Spring-managed transaction, the connection Spring bound to {@code ds}, so the statement runs in that transaction);
     * as for any single statement, its failure does not mark the active transaction rollback-only. Every parameter set is
     * checked against the SQL's parameters before a connection is acquired or the scope begins, so a set that can't satisfy
     * them throws {@code IllegalArgumentException} without marking anything (a value the driver rejects when it is bound
     * fails in the scope, as a statement does).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Suitable when the total affected-row count may exceed Integer.MAX_VALUE.
     * List<List<?>> data = new ArrayList<>();
     * for (int i = 0; i < 1_000_000; i++) {
     *     data.add(Arrays.asList("User" + i, i % 100));
     * }
     * final String sql = "INSERT INTO users (name, age) VALUES (?, ?)";
     * // More than one parameter set runs in a transaction, for atomicity.
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null} or empty; {@code batchSize} is not positive; or,
     *         when {@code listOfParameters} is not empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter
     *         placeholder, or a supplied parameter set cannot satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws UncheckedSQLException if acquiring a connection fails, or if transaction setup or completion fails.
     *         Connection acquisition follows the Spring integration behavior of {@link #getConnection(javax.sql.DataSource)}.
     * @throws IllegalStateException if more than one parameter set is given and the transaction scope of the batch cannot be
     *         begun (see {@link #beginTransaction(javax.sql.DataSource)}).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or
     *         restoring an owned transaction fails.
     * @throws UnsupportedOperationException if the JDBC driver does not implement
     *         {@link Statement#executeLargeBatch()} (the JDBC 4.2 default implementation throws it);
     *         no batch is executed in that case, so use {@link #executeBatchUpdate(javax.sql.DataSource, String, List, int)} with such a driver.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final javax.sql.DataSource ds, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, SQLException, UnsupportedOperationException, ArithmeticException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        // Every parameter set is checked before a connection is taken or the scope begins. WHY: a set that can't satisfy the
        // SQL was found only when it was bound, inside the scope of a batch of several sets, whose failure then marked the
        // caller's transaction rollback-only although no statement had run (DAO batch operations check their entities first).
        final ParsedSql parsedSql = ParsedSql.parse(sql);
        checkBatchParameters(parsedSql, listOfParameters);

        if (listOfParameters.size() == 1) {
            final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran != null) {
                return runLargeBatchUpdate(tran.connection(), parsedSql, listOfParameters, batchSize);
            }

            final Connection conn = JdbcUtil.getConnection(ds);

            try {
                return runLargeBatchUpdate(conn, parsedSql, listOfParameters, batchSize);
            } finally {
                JdbcUtil.releaseConnection(conn, ds);
            }
        } else {
            // A nested scope of the thread's transaction, if there is one, as for DAO batch operations: its failure marks that
            // transaction rollback-only. The batch used to run on the transaction's connection without a scope, so a caller that
            // caught its failure went on to commit the rows of the statements that had run, and the commit returned normally.
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
            long ret = 0;
            Throwable failure = null;

            try {
                ret = runLargeBatchUpdate(tran.connection(), parsedSql, listOfParameters, batchSize);
                tran.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                rollbackAfterTransactionCommand(tran, failure);
            }

            return ret;
        }
    }

    /**
     * Executes a large batch SQL update using the provided Connection with default batch size.
     * This method does not close the provided Connection after the batch update is executed.
     * See {@link #executeLargeBatchUpdate(Connection, String, List, int)} for the auto-commit / atomicity
     * semantics that apply when {@code listOfParameters.size() > 1}.
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null} or empty; or, when {@code listOfParameters} is
     *         not empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter placeholder, or a supplied parameter set
     *         cannot satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or
     *         restoring an owned transaction fails.
     * @throws UnsupportedOperationException if the JDBC driver does not implement
     *         {@link Statement#executeLargeBatch()} (the JDBC 4.2 default implementation throws it);
     *         no batch is executed in that case, so use {@link #executeBatchUpdate(Connection, String, List)} with such a driver.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters)
            throws IllegalArgumentException, SQLException, UnsupportedOperationException, ArithmeticException {
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null} or empty; {@code batchSize} is not positive; or,
     *         when {@code listOfParameters} is not empty, {@code sql} is blank, mixes parameter styles, or contains a malformed parameter
     *         placeholder, or a supplied parameter set cannot satisfy the SQL's required positional or named parameters
     *         (every parameter set is checked before any of them is bound or a statement runs).
     * @throws SQLException if preparing, binding, or executing a batch, reading connection state, or completing or
     *         restoring an owned transaction fails.
     * @throws UnsupportedOperationException if the JDBC driver does not implement
     *         {@link Statement#executeLargeBatch()} (the JDBC 4.2 default implementation throws it);
     *         no batch is executed in that case, so use {@link #executeBatchUpdate(Connection, String, List, int)} with such a driver.
     * @throws ArithmeticException if the total number of affected rows exceeds {@link Long#MAX_VALUE}.
     * @see PreparedStatement#executeLargeBatch()
     */
    public static long executeLargeBatchUpdate(final Connection conn, final String sql, final List<?> listOfParameters, final int batchSize)
            throws IllegalArgumentException, SQLException, UnsupportedOperationException, ArithmeticException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotEmpty(sql, cs.sql);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(listOfParameters)) {
            return 0;
        }

        final ParsedSql parsedSql = ParsedSql.parse(sql);
        checkBatchParameters(parsedSql, listOfParameters);

        return runLargeBatchUpdate(conn, parsedSql, listOfParameters, batchSize);
    }

    /**
     * Runs the batch of {@link #executeLargeBatchUpdate(Connection, String, List, int)} once its arguments have been checked.
     */
    private static long runLargeBatchUpdate(final Connection conn, final ParsedSql parsedSql, final List<?> listOfParameters, final int batchSize)
            throws SQLException, UnsupportedOperationException, ArithmeticException {
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws UncheckedSQLException if acquiring a connection fails; connection acquisition follows
     *         {@link #getConnection(javax.sql.DataSource)}, including its Spring integration behavior.
     * @throws SQLException if preparing or binding the statement, or executing the requested SQL, fails.
     * @see PreparedStatement#execute()
     * @see #executeQuery(javax.sql.DataSource, String, Object...)
     * @see #executeUpdate(javax.sql.DataSource, String, Object...)
     */
    public static boolean execute(final javax.sql.DataSource ds, final String sql, final Object... parameters)
            throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotEmpty(sql, cs.sql);

        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

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
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes parameter styles, or
     *         contains a malformed parameter placeholder; or a supplied parameter set cannot satisfy the SQL's required positional or named
     *         parameters.
     * @throws SQLException if preparing or binding the statement, or executing the requested SQL, fails.
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
     * performance logging when enabled. Parameter clearing is attempted after execution except for
     * {@link CallableStatement}s, whose bindings and OUT registrations are retained.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return The {@link ResultSet} produced by the query.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
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
     * enabled. Parameter clearing is attempted after execution except for {@link CallableStatement}s,
     * whose bindings and OUT registrations are retained.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return The number of rows affected by the statement.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
     */
    static int executeUpdate(final PreparedStatement stmt) throws SQLException {
        return executeUpdate(stmt, null);
    }

    /**
     * Executes the given {@link PreparedStatement} as an update, as {@link #executeUpdate(PreparedStatement)} does, running
     * {@code afterExecution} as soon as the statement's execution returned: before the SQL log handler runs and before the
     * parameters are cleared, so that a failure of that cleanup (an {@link Error}, which {@link #clearParameters(PreparedStatement)}
     * does not swallow) is reported after the hook ran. The cleanup still runs when the hook throws.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @param afterExecution run once the statement's execution returned; {@code null} for none.
     * @return The number of rows affected by the statement.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
     */
    static int executeUpdate(final PreparedStatement stmt, final Runnable afterExecution) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();

        if (JdbcUtil.isToHandleSqlLog(sqlLogConfig)) {
            final long startTimeMillis = System.currentTimeMillis();
            final long startTimeNanos = System.nanoTime();

            try {
                final int updatedRows = stmt.executeUpdate();

                // Runs before the cleanup in the finally block: an insert's execution mark (see DaoImpl) has to be set once the
                // statement executed, or a cleanup failure thrown below looked like a failure before the execution.
                runAfterExecution(afterExecution);

                return updatedRows;
            } finally {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);

                clearParameters(stmt);
            }
        } else {
            try {
                final int updatedRows = stmt.executeUpdate();

                runAfterExecution(afterExecution);

                return updatedRows;
            } finally {
                clearParameters(stmt);
            }
        }
    }

    /**
     * Runs the after-execution hook of {@link #executeUpdate(PreparedStatement, Runnable)} / {@link #executeBatch(Statement, Runnable)}.
     *
     * @param afterExecution the hook; {@code null} for none
     */
    private static void runAfterExecution(final Runnable afterExecution) {
        if (afterExecution != null) {
            afterExecution.run();
        }
    }

    /**
     * Executes the given {@link PreparedStatement} as a large update, handling SQL performance logging
     * when enabled. Parameter clearing is attempted after execution except for {@link CallableStatement}s,
     * whose bindings and OUT registrations are retained.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return The number of rows affected by the statement, as a long value.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
     * @throws UnsupportedOperationException if the JDBC driver does not implement
     *         {@link PreparedStatement#executeLargeUpdate()} (the JDBC 4.2 default implementation throws it);
     *         the statement is not executed in that case, so use {@link #executeUpdate(PreparedStatement)} with such a driver.
     */
    static long executeLargeUpdate(final PreparedStatement stmt) throws SQLException, UnsupportedOperationException {
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
     * statement's batch clearing is attempted after execution. When execution fails, cleanup failures are
     * suppressed on that failure. After successful execution, SQL and runtime cleanup exceptions are logged
     * and ignored; cleanup {@link Error}s still propagate.
     *
     * @param stmt The {@link Statement} whose batch to execute.
     * @return An array of update counts, one per batch entry.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
     */
    static int[] executeBatch(final Statement stmt) throws SQLException {
        return executeBatch(stmt, null);
    }

    /**
     * Executes the given {@link Statement}'s batch, as {@link #executeBatch(Statement)} does, running {@code afterExecution}
     * as soon as the batch's execution returned: before the SQL log handler runs and before the batch is cleared, so that a
     * failure of that cleanup (an {@link Error} after a successful execution still propagates) is reported after the hook
     * ran. The cleanup still runs when the hook throws; a throwable the hook throws is treated like an execution failure
     * (cleanup failures are attached to it as suppressed).
     *
     * @param stmt The {@link Statement} whose batch to execute.
     * @param afterExecution run once the batch's execution returned; {@code null} for none.
     * @return An array of update counts, one per batch entry.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
     */
    static int[] executeBatch(final Statement stmt, final Runnable afterExecution) throws SQLException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
        final boolean logSql = JdbcUtil.isToHandleSqlLog(sqlLogConfig);
        final long startTimeMillis = logSql ? System.currentTimeMillis() : 0;
        final long startTimeNanos = logSql ? System.nanoTime() : 0;
        Throwable executionFailure = null;

        try {
            final int[] updateCounts = stmt.executeBatch();

            // Runs before the cleanup in the finally block: an insert's execution mark (see DaoImpl) has to be set once the
            // batch executed, or a cleanup Error thrown below looked like a failure before the execution.
            runAfterExecution(afterExecution);

            return updateCounts;
        } catch (final SQLException | RuntimeException | Error e) {
            executionFailure = e;
            throw e;
        } finally {
            if (logSql) {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);
            }

            clearBatchAfterExecution(stmt, executionFailure);
        }
    }

    /**
     * Executes the given {@link Statement}'s batch as a large batch, handling SQL performance logging
     * when enabled. The statement's batch clearing is attempted after execution. When execution fails,
     * cleanup failures are suppressed on that failure. After successful execution, SQL and runtime cleanup
     * exceptions are logged and ignored; cleanup {@link Error}s still propagate.
     *
     * @param stmt The {@link Statement} whose batch to execute.
     * @return An array of update counts as long values, one per batch entry.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
     * @throws UnsupportedOperationException if the JDBC driver does not implement
     *         {@link Statement#executeLargeBatch()} (the JDBC 4.2 default implementation throws it);
     *         the batch is not executed in that case but its entries are still cleared, so use
     *         {@link #executeBatch(Statement)} with such a driver instead of retrying on the same batch.
     */
    static long[] executeLargeBatch(final Statement stmt) throws SQLException, UnsupportedOperationException {
        final SqlLogConfig sqlLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
        final boolean logSql = JdbcUtil.isToHandleSqlLog(sqlLogConfig);
        final long startTimeMillis = logSql ? System.currentTimeMillis() : 0;
        final long startTimeNanos = logSql ? System.nanoTime() : 0;
        Throwable executionFailure = null;

        try {
            return stmt.executeLargeBatch();
        } catch (final SQLException | RuntimeException | Error e) {
            executionFailure = e;
            throw e;
        } finally {
            if (logSql) {
                JdbcUtil.handleSqlLog(stmt, sqlLogConfig, startTimeMillis, startTimeNanos);
            }

            clearBatchAfterExecution(stmt, executionFailure);
        }
    }

    /**
     * Keeps a driver's batch failure primary when clearing retained batch parameters also fails.
     * SQL and runtime cleanup exceptions after successful execution are logged and ignored; {@link Error}s still propagate.
     *
     * @param stmt the statement whose batch is cleared
     * @param executionFailure the primary execution failure, or {@code null} after successful execution
     */
    private static void clearBatchAfterExecution(final Statement stmt, final Throwable executionFailure) {
        try {
            stmt.clearBatch();
        } catch (final SQLException | RuntimeException e) {
            if (executionFailure == null) {
                // As with parameter cleanup, best-effort clearing must not discard completed batch counts.
                logger.warn(e, "Failed to clear batch parameters after execution");
            } else {
                addSuppressedIfDistinct(executionFailure, e);
            }
        } catch (final Error e) {
            if (executionFailure == null) {
                throw e;
            }

            addSuppressedIfDistinct(executionFailure, e);
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
    private static int sumUpdatedRows(final int[] updateCounts) throws ArithmeticException {
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
    private static int addUpdatedRowsExact(final int current, final int increment) throws ArithmeticException {
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
    private static long sumUpdatedRows(final long[] updateCounts) throws ArithmeticException {
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
    private static long addUpdatedRowsExact(final long current, final long increment) throws ArithmeticException {
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
     * statement's parameter clearing is attempted after execution except for {@link CallableStatement}s,
     * whose bindings and OUT registrations are retained.
     *
     * @param stmt The {@link PreparedStatement} to execute.
     * @return {@code true} if the first result is a {@link ResultSet}; {@code false} if it is an update count or there is no result.
     * @throws SQLException if the supplied statement is closed or its JDBC execution operation fails.
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
     * Clears the parameters of the given {@link PreparedStatement}, logging and ignoring SQL and runtime exceptions.
     * Errors still propagate. Does nothing for {@code null} statements and {@link CallableStatement}s
     * (clearing parameters would also remove registered out parameters).
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
            } catch (final SQLException | RuntimeException e) {
                // Best-effort cleanup must not hide an execution failure or discard a successful query's ResultSet.
                logger.warn(e, "Failed to clear parameters after execution");
            }
        }
    }

    /**
     * Returns whether the value and selected type use a built-in stream/reader JDBC binding. Exact type-class matching
     * preserves custom Type subclasses, whose setters may require the original value instead of a buffered cursor.
     *
     * @param value the parameter value; may be {@code null} or a value other than a stream or reader
     * @param type the selected JDBC binding type, or {@code null} if none was selected
     * @return {@code true} if the value and selected built-in type form a supported stream/reader binding
     */
    static boolean usesBuiltInStreamBinding(final Object value, final Type<?> type) {
        return type != null && (value instanceof InputStream && STREAM_TYPES.contains(type.getClass())
                || value instanceof Reader && READER_TYPES.contains(type.getClass()));
    }

    /**
     * Buffers up to {@code length} bytes or characters to replay a stream or reader across repeated JDBC parameter
     * bindings without closing the caller's resource. Drivers may read during binding or execution, so each supplier
     * invocation returns a fresh {@link ByteArrayInputStream} or {@link StringReader} with an independent cursor.
     * The caller must validate length and closed state before calling this method.
     *
     * @param <T> the stream or reader type accepted by the selected built-in binding
     * @param value the non-null stream or reader to buffer
     * @param length the non-negative maximum number of bytes or characters to read
     * @return a supplier of independent cursors over the buffered content
     * @throws SQLException if reading the caller's resource fails, with the {@link IOException} retained as its cause
     */
    @SuppressWarnings("unchecked")
    static <T> Supplier<T> bufferForRepeatedBinding(final T value, final long length) throws SQLException {
        try {
            long remaining = length;
            if (value instanceof InputStream input) {
                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                while (remaining > 0) {
                    final int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) {
                        break;
                    } else if (count == 0) {
                        final int next = input.read();
                        if (next < 0) {
                            break;
                        }
                        output.write(next);
                        remaining--;
                    } else {
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                final byte[] bytes = output.toByteArray();
                return () -> (T) new ByteArrayInputStream(bytes);
            }

            final Reader reader = (Reader) value;
            final StringBuilder output = new StringBuilder();
            final char[] buffer = new char[8192];
            while (remaining > 0) {
                final int count = reader.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    break;
                } else if (count == 0) {
                    final int next = reader.read();
                    if (next < 0) {
                        break;
                    }
                    output.append((char) next);
                    remaining--;
                } else {
                    output.append(buffer, 0, count);
                    remaining -= count;
                }
            }
            final String text = output.toString();
            return () -> (T) new StringReader(text);
        } catch (final IOException e) {
            throw new SQLException("Failed to buffer stream or reader for repeated JDBC parameter", e);
        }
    }

    /**
     * Sets the given parameters on the specified {@link PreparedStatement} according to the parsed SQL.
     * When the SQL uses named parameters, a single entity, {@link Map}, or {@link EntityId} may be passed
     * to bind them by name. An entity name may be a nested property path such as {@code address.street},
     * read through each intermediate property; a {@code null} intermediate value binds SQL {@code NULL}
     * through the leaf property's type. The value of each distinct name is read once and bound to all of
     * its occurrences. Repeated built-in stream values are buffered once after all names have
     * been validated, retaining the selected Type and without closing the caller's resource.
     *
     * @param parsedSql The parsed SQL statement containing parameter information.
     * @param stmt The {@link PreparedStatement} to set parameters on.
     * @param parameters The parameter values to set.
     * @throws IllegalArgumentException if the number of provided parameters is less than the SQL requires, or a named parameter has no matching value
     *         (for an entity, no property or nested property path of that name).
     * @throws SQLException if a JDBC parameter binding fails, including an invalid index, incompatible value, or closed statement,
     *         or if reading a repeated named stream or reader parameter for buffering fails.
     */
    static void setParameters(final ParsedSql parsedSql, final PreparedStatement stmt, final Object[] parameters)
            throws IllegalArgumentException, SQLException {
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
            parameterTypes = new Type[parameterCount];
            final BeanInfo entityInfo = Beans.isBeanClass(cls) || Beans.isRecordClass(cls) ? ParserUtil.getBeanInfo(cls) : null;
            final int[] firstIndexes = firstIndexesOfRepeatedNames(namedParameters);
            Map<String, Integer> repeatedStreams = null;

            for (int i = 0; i < parameterCount; i++) {
                final String name = namedParameters.get(i);
                final int firstIndex = firstIndexes == null ? i : firstIndexes[i];
                if (firstIndex != i) {
                    // Read each name once: a bean getter need not be idempotent and may open a resource, yet every
                    // occurrence must bind the same value with the same Type. This holds for every repeated name, including
                    // one bound through a custom Type: no occurrence reads the value again (the javadoc promises it).
                    parameterValues[i] = parameterValues[firstIndex];
                    parameterTypes[i] = parameterTypes[firstIndex];
                    if (usesBuiltInStreamBinding(parameterValues[i], parameterTypes[i])) {
                        if (repeatedStreams == null) {
                            repeatedStreams = N.newHashMap();
                        }
                        repeatedStreams.put(name, firstIndex);
                    }
                    continue;
                }

                if (entityInfo != null) {
                    PropInfo propInfo = entityInfo.getPropInfo(name);
                    final List<PropInfo> propPath = propInfo == null ? getNestedParameterPropPath(entityInfo, name) : null;
                    if (propPath != null) {
                        propInfo = propPath.get(propPath.size() - 1);
                    } else if (propInfo == null) {
                        throw new IllegalArgumentException("No property found with name: " + name + " in class: " + ClassUtil.getCanonicalClassName(cls));
                    }
                    parameterValues[i] = getBeanParameterValue(parameter_0, propInfo, propPath);
                    parameterTypes[i] = propInfo.dbType;
                } else if (parameter_0 instanceof Map) {
                    final Map<String, Object> m = (Map<String, Object>) parameter_0;
                    parameterValues[i] = m.get(name);
                    if (parameterValues[i] == null && !m.containsKey(name)) {
                        throw new IllegalArgumentException("Missing parameter for property: '" + name + "'");
                    }
                } else {
                    final EntityId entityId = (EntityId) parameter_0;
                    parameterValues[i] = entityId.get(name);
                    if (parameterValues[i] == null && !entityId.containsKey(name)) {
                        throw new IllegalArgumentException("Missing parameter for property: '" + name + "'");
                    }
                }
                if (entityInfo == null && parameterValues[i] != null) {
                    parameterTypes[i] = Type.of(parameterValues[i].getClass());
                }
            }

            if (repeatedStreams != null) {
                // Validate every name before consuming any stream, and keep the buffers local to this
                // parameter set so batch rows never share cursors. Positional parameters remain streamed.
                if (stmt.isClosed()) {
                    throw new SQLException("Cannot buffer repeated parameters for a closed statement");
                }
                final Map<String, Supplier<Object>> copies = N.newHashMap();
                for (final Map.Entry<String, Integer> entry : repeatedStreams.entrySet()) {
                    copies.put(entry.getKey(), bufferForRepeatedBinding(parameterValues[entry.getValue()], Long.MAX_VALUE));
                }
                for (int i = 0; i < parameterCount; i++) {
                    final Supplier<Object> supplier = copies.get(namedParameters.get(i));
                    if (supplier != null) {
                        parameterValues[i] = supplier.get();
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
     * @throws SQLException if a JDBC parameter binding fails, including an invalid index, incompatible value, or closed statement.
     */
    @SuppressWarnings("rawtypes")
    static void setParameters(final PreparedStatement stmt, final int parameterCount, final Object[] parameters, final Type[] parameterTypes)
            throws SQLException {
        if (N.notEmpty(parameterTypes) && parameterTypes.length >= parameterCount) {
            for (int i = 0; i < parameterCount; i++) {
                if (parameterTypes[i] == null) {
                    stmt.setObject(i + 1, parameters[i]);
                } else {
                    parameterTypes[i].set(stmt, i + 1, parameters[i]);
                }
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
     * Checks, before a batch runs anything, that every parameter set in {@code listOfParameters} can satisfy the parameters of
     * {@code parsedSql}, as {@link #setParameters(ParsedSql, PreparedStatement, Object[])} checks each one when it binds it: a
     * positional set (an {@code Object[]} or a {@code Collection}, or else a single value) supplies enough values, and an entity,
     * {@code Map} or {@link EntityId} bound by name has a property, or an entry, for every named parameter. No value is read: a
     * bean getter need not be idempotent, and a stream can be read only once (the values are read when the set is bound).
     *
     * @param parsedSql the parsed SQL of the batch
     * @param listOfParameters the parameter sets, one per batch entry
     * @throws IllegalArgumentException if a parameter set can't satisfy the SQL's parameters
     */
    static void checkBatchParameters(final ParsedSql parsedSql, final List<?> listOfParameters) throws IllegalArgumentException {
        final int parameterCount = parsedSql.parameterCount();

        if (parameterCount == 0) {
            return;
        }

        final List<String> namedParameters = parsedSql.namedParameters();
        final Object[] parameters = new Object[1];
        // The properties of a bean class are checked once: the sets of a batch are usually of one class.
        Class<?> checkedBeanClass = null;
        int index = 0;

        for (final Object parameter : listOfParameters) {
            parameters[0] = parameter;

            if (isEntityOrMapParameter(parsedSql, parameters)) {
                final Class<?> cls = parameter.getClass();

                if (Beans.isBeanClass(cls) || Beans.isRecordClass(cls)) {
                    if (cls != checkedBeanClass) {
                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

                        for (final String name : namedParameters) {
                            if (entityInfo.getPropInfo(name) == null && getNestedParameterPropPath(entityInfo, name) == null) {
                                throw new IllegalArgumentException("No property found with name: " + name + " in class: " + ClassUtil.getCanonicalClassName(cls)
                                        + " (the parameter set at index " + index + ")");
                            }
                        }

                        checkedBeanClass = cls;
                    }
                } else if (parameter instanceof final Map<?, ?> m) {
                    for (final String name : namedParameters) {
                        if (m.get(name) == null && !m.containsKey(name)) {
                            throw new IllegalArgumentException("Missing parameter for property: '" + name + "' (the parameter set at index " + index + ")");
                        }
                    }
                } else {
                    final EntityId entityId = (EntityId) parameter;

                    for (final String name : namedParameters) {
                        if (entityId.get(name) == null && !entityId.containsKey(name)) {
                            throw new IllegalArgumentException("Missing parameter for property: '" + name + "' (the parameter set at index " + index + ")");
                        }
                    }
                }
            } else if (parameterCount > 1) {
                // As getParameterValues: an array or a collection supplies its elements if it has enough of them, any other
                // value (or a shorter array or collection) is a single value.
                final int supplied = parameter instanceof final Object[] a ? a.length : parameter instanceof final Collection<?> c ? c.size() : 1;

                if (supplied < parameterCount) {
                    throw new IllegalArgumentException(
                            "SQL requires " + parameterCount + " parameter(s), but the parameter set at index " + index + " specifies only " + supplied);
                }
            }

            index++;
        }
    }

    /**
     * Extracts and returns parameter values from the provided parameters array.
     * If a single parameter is provided and it's an {@code Object[]} (not a primitive array) or a collection with
     * sufficient elements, this method unwraps it to use its contents as the actual parameter values.
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
     * @return {@code true} if the SQL has named parameters and exactly one non-null entity, {@code Map}, or {@code EntityId} parameter is provided;
     *         {@code false} for a value whose class is not a bean in the Abacus type system and has no property named after the SQL's only
     *         placeholder, which is then bound directly.
     */
    static boolean isEntityOrMapParameter(final ParsedSql parsedSql, final Object... parameters) {
        if (N.isEmpty(parsedSql.namedParameters()) || N.isEmpty(parameters) || (parameters.length != 1) || (parameters[0] == null)) {
            return false;
        }

        final Class<?> cls = parameters[0].getClass();

        if (Beans.isBeanClass(cls) || Beans.isRecordClass(cls)) {
            // Some value classes pass Beans.isBeanClass only because they expose getter/setter pairs (GregorianCalendar,
            // MutableBoolean, a class with a registered Type). With exactly one named placeholder, bind such a value directly,
            // as '?' SQL and NamedQuery do, instead of failing to find a property named after the parameter.
            return parsedSql.parameterCount() != 1 || N.typeOf(cls).isBean()
                    || ParserUtil.getBeanInfo(cls).getPropInfo(parsedSql.namedParameters().get(0)) != null;
        }

        return Map.class.isAssignableFrom(cls) || EntityId.class.isAssignableFrom(cls);
    }

    /**
     * Name-list size from which {@link #firstIndexesOfRepeatedNames(List)} indexes the names with a map instead of
     * comparing each name with the names before it.
     */
    private static final int MIN_NAME_COUNT_FOR_REPEATED_NAME_MAP = 32;

    /**
     * Maps each placeholder of a named-parameter list to the index of the first placeholder with the same name.
     *
     * @param names the parameter names in placeholder order, one entry per placeholder
     * @return {@code null} if no name occurs more than once; otherwise an array whose element {@code i} is the index of the
     *         first occurrence of {@code names.get(i)} (equal to {@code i} for a first occurrence)
     */
    static int[] firstIndexesOfRepeatedNames(final List<String> names) {
        final int size = names.size();
        int[] firstIndexes = null;

        if (size < MIN_NAME_COUNT_FOR_REPEATED_NAME_MAP) {
            // Short lists, the usual case, are answered without allocating anything when no name repeats.
            for (int i = 1; i < size; i++) {
                final String name = names.get(i);

                for (int j = 0; j < i; j++) {
                    if (names.get(j).equals(name)) {
                        if (firstIndexes == null) {
                            firstIndexes = identityIndexes(size);
                        }

                        firstIndexes[i] = j;
                        break;
                    }
                }
            }
        } else {
            final Map<String, Integer> firstIndexMap = N.newHashMap(size);

            for (int i = 0; i < size; i++) {
                final Integer firstIndex = firstIndexMap.putIfAbsent(names.get(i), i);

                if (firstIndex != null) {
                    if (firstIndexes == null) {
                        firstIndexes = identityIndexes(size);
                    }

                    firstIndexes[i] = firstIndex;
                }
            }
        }

        return firstIndexes;
    }

    private static int[] identityIndexes(final int size) {
        final int[] indexes = new int[size];

        for (int i = 0; i < size; i++) {
            indexes[i] = i;
        }

        return indexes;
    }

    /**
     * Resolves a dotted named parameter such as {@code address.street} to the bean properties read, in order, to obtain
     * its value. Callers try {@link BeanInfo#getPropInfo(String)} first, so a name that already resolves to a property of
     * the bean keeps that binding.
     *
     * @param beanInfo the bean info of the class supplying the parameter values
     * @param parameterName the named parameter
     * @return one {@link PropInfo} per path segment, the last one being the property whose value and type are bound; or
     *         {@code null} if the name contains no {@code '.'}, a segment does not resolve, or an intermediate segment is a
     *         collection (which has no single value to read the next segment from)
     */
    static List<PropInfo> getNestedParameterPropPath(final BeanInfo beanInfo, final String parameterName) {
        if (parameterName.indexOf('.') < 0) {
            return null;
        }

        final List<PropInfo> propPath = beanInfo.getPropInfoChain(parameterName);

        if (propPath.isEmpty()) {
            return null;
        }

        for (int i = 0, last = propPath.size() - 1; i < last; i++) {
            if (propPath.get(i).type.isCollection()) {
                return null;
            }
        }

        return propPath;
    }

    /**
     * Reads a named-parameter value from a bean, directly through {@code propInfo} or, for a nested name, along
     * {@code propPath}. A {@code null} intermediate value yields {@code null}, which the leaf property's type binds as
     * SQL {@code NULL}.
     *
     * @param bean the bean supplying the value
     * @param propInfo the property to read when {@code propPath} is {@code null}
     * @param propPath the nested property path from {@link #getNestedParameterPropPath(BeanInfo, String)}, or {@code null}
     * @return the parameter value
     */
    static Object getBeanParameterValue(final Object bean, final PropInfo propInfo, final List<PropInfo> propPath) {
        if (propPath == null) {
            return propInfo.getPropValue(bean);
        }

        Object value = bean;

        for (int i = 0, size = propPath.size(); i < size && value != null; i++) {
            value = propPath.get(i).getPropValue(value);
        }

        return value;
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
     * <p>A {@code Dataset} requires unique, non-empty column names, so every extraction overload renames columns
     * whose labels would clash. The first column with a given label keeps it; each later column with the same label
     * (for example, {@code SELECT * FROM a JOIN b} where both tables have an {@code ID} column) gets the first
     * suffix {@code _2}, {@code _3}, ... that does not match any other column's label ({@code ID}, {@code ID_2}).
     * A column that has neither a label nor a name (some drivers report unaliased expressions such as
     * {@code COUNT(*)} that way) is named {@code column} followed by its 1-based index (for example {@code column3}),
     * suffixed the same way if that name is already taken. Labels are compared case-sensitively. Give such columns
     * distinct aliases in the SQL to choose the names yourself.</p>
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
     *         {@link ResultSetMetaData} labels (or column names if no label is set), with duplicate or empty
     *         labels renamed as described above.
     * @throws IllegalArgumentException if {@code rs} is {@code null}.
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     * @see #extractData(ResultSet, boolean)
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs) throws IllegalArgumentException, SQLException {
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
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count) throws IllegalArgumentException, SQLException {
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
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     * @see RowFilter
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowFilter filter) throws IllegalArgumentException, SQLException {
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
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowExtractor rowExtractor) throws IllegalArgumentException, SQLException {
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
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     * @see RowFilter
     * @see RowExtractor
     * @see #extractData(ResultSet, RowFilter)
     * @see #extractData(ResultSet, RowExtractor)
     */
    public static Dataset extractData(final ResultSet rs, final RowFilter filter, final RowExtractor rowExtractor)
            throws IllegalArgumentException, SQLException {
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
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     */
    public static Dataset extractData(final ResultSet rs, final boolean closeResultSet) throws IllegalArgumentException, SQLException {
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
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final boolean closeResultSet)
            throws IllegalArgumentException, SQLException {
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}, {@code offset} or {@code count} is negative, or {@code filter} is {@code null}.
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowFilter filter, final boolean closeResultSet)
            throws IllegalArgumentException, SQLException {
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}, {@code offset} or {@code count} is negative, or {@code rowExtractor} is {@code null}.
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
     * @see #extractData(ResultSet, int, int, RowFilter, RowExtractor, boolean)
     */
    public static Dataset extractData(final ResultSet rs, final int offset, final int count, final RowExtractor rowExtractor, final boolean closeResultSet)
            throws IllegalArgumentException, SQLException {
        return extractData(rs, offset, count, INTERNAL_DUMMY_ROW_FILTER, rowExtractor, closeResultSet);
    }

    /**
     * Extracts data from the provided ResultSet with all extraction options.
     * This is the most comprehensive extraction method providing full control over the extraction process,
     * including pagination (offset/count), filtering (RowFilter), and transformation (RowExtractor).
     *
     * <p>When {@code closeResultSet} is {@code true}, a close error is suppressed on a failure from date-type
     * detection or extraction. Other exceptions from closing the result set, including SQL and runtime exceptions,
     * are logged and ignored by {@link #closeQuietly(ResultSet)}, including after successful extraction.</p>
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
     * @param closeResultSet whether to close the ResultSet after successful argument validation, including when date-type detection or extraction fails
     * @return A {@link Dataset} containing the filtered and transformed data.
     * @throws IllegalArgumentException if {@code rs} is {@code null}, {@code offset} or {@code count} is negative, or {@code filter} or
     *         {@code rowExtractor} is {@code null}.
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
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
        Throwable primaryFailure = null;

        try {
            // A ResultSetProxy already normalizes date/time types itself, so getColumnValue ignores this flag for proxies.
            // Skip the DatabaseMetaData lookup in that case (behavior-identical, avoids a potential server round-trip).
            final boolean checkDateType = !(rs instanceof ResultSetProxy) && checkDateType(rs);
            return JdbcUtil.extractResultSetToDataset(rs, offset, count, filter, rowExtractor, checkDateType);
        } catch (final SQLException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            if (closeResultSet) {
                try {
                    closeQuietly(rs);
                } catch (final RuntimeException | Error closeFailure) {
                    if (primaryFailure == null) {
                        throw closeFailure;
                    }

                    addSuppressedIfDistinct(primaryFailure, closeFailure);
                }
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
     * @throws SQLException if reading metadata, advancing or reading rows, or invoking a supplied row filter or extractor throws an SQL exception.
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

        makeColumnNamesUnique(columnNameList);

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
     * Turns result-set column labels into valid {@link Dataset} column names, which must be unique and non-empty
     * ({@code RowDataset} compares them case-sensitively). The first occurrence of a label is kept; each later
     * duplicate gets the first suffix {@code _2}, {@code _3}, ... not yet taken; an empty or {@code null} label becomes
     * {@code "column" + (1-based index)}, suffixed the same way if that name is taken. Every original label is reserved
     * up front, so a generated name never takes a label that appears later in the list.
     *
     * @param columnNames The column labels in column order; updated in place.
     */
    static void makeColumnNamesUnique(final List<String> columnNames) {
        final int columnCount = columnNames.size();

        if (columnCount == 1) {
            if (Strings.isEmpty(columnNames.get(0))) {
                columnNames.set(0, "column1");
            }

            return;
        }

        final Set<String> takenNames = N.newHashSet(columnCount);
        boolean renameNeeded = false;

        for (final String columnName : columnNames) {
            if (Strings.isEmpty(columnName) || !takenNames.add(columnName)) {
                renameNeeded = true;
            }
        }

        if (!renameNeeded) {
            return;
        }

        // takenNames now holds every non-empty original label; generated names are added as they are assigned.
        final Set<String> keptNames = N.newHashSet(columnCount);
        Map<String, Integer> nextSuffixes = null;

        for (int i = 0; i < columnCount; i++) {
            final String columnName = columnNames.get(i);
            final boolean emptyName = Strings.isEmpty(columnName);

            if (!emptyName && keptNames.add(columnName)) {
                continue; // first occurrence keeps its label
            }

            final String baseName = emptyName ? "column" + (i + 1) : columnName;
            String uniqueName = baseName;

            if (!emptyName || takenNames.contains(baseName)) {
                if (nextSuffixes == null) {
                    nextSuffixes = new HashMap<>();
                }

                int suffix = nextSuffixes.getOrDefault(baseName, 2);

                do {
                    uniqueName = baseName + "_" + suffix++;
                } while (takenNames.contains(uniqueName));

                nextSuffixes.put(baseName, suffix);
            }

            takenNames.add(uniqueName);
            columnNames.set(i, uniqueName);
        }
    }

    /**
     * Applies the given {@link ResultExtractor} to the {@link ResultSet} and then closes the result set.
     *
     * @param <R> The type of the extraction result.
     * @param rs The {@link ResultSet} to extract from. It is closed before this method returns.
     * @param resultExtractor The extractor that produces the result from the {@link ResultSet}.
     * @return The extraction result.
     * @throws SQLException if the supplied result extractor throws an SQL exception.
     * @throws UnsupportedOperationException if {@code resultExtractor} returns a {@link ResultSet}, which would be closed before it could be used.
     */
    static <R> R extractAndCloseResultSet(final ResultSet rs, final ResultExtractor<? extends R> resultExtractor)
            throws SQLException, UnsupportedOperationException {
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
     * @throws SQLException if reading column metadata or the supplied result extractor throws an SQL exception.
     * @throws UnsupportedOperationException if {@code resultExtractor} returns a {@link ResultSet}, which would be closed before it could be used.
     */
    static <R> R extractAndCloseResultSet(final ResultSet rs, final BiResultExtractor<? extends R> resultExtractor)
            throws SQLException, UnsupportedOperationException {
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
    public static Stream<Object[]> stream(final ResultSet rs) throws IllegalArgumentException {
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
     * @throws IllegalArgumentException if {@code rs} or {@code targetClass} is {@code null}.
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if advancing the result set to the next row fails.
             */
            @Override
            public boolean hasNext() throws UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if locating the next row, reading metadata, or invoking the configured row mapper fails with an SQL exception.
             * @throws NoSuchElementException if no unconsumed row remains after applying any configured filter.
             */
            @Override
            public T next() throws UncheckedSQLException, NoSuchElementException {
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

            /**
             * {@inheritDoc}
             *
             * @throws IllegalArgumentException if {@code n} is negative.
             * @throws UncheckedSQLException if advancing the result set while skipping rows fails.
             */
            @Override
            public void advance(final long n) throws IllegalArgumentException, UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if advancing the result set while counting the remaining rows fails.
             */
            @Override
            public long count() throws UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws RuntimeException if the configured close action throws an exception on the first close.
             */
            @Override
            public void closeResource() throws RuntimeException {
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
    static <T> ObjIteratorEx<T> iterate(final ResultSet resultSet, final RowFilter rowFilter, final RowMapper<? extends T> rowMapper, final Runnable onClose)
            throws IllegalArgumentException {
        N.checkArgNotNull(rowFilter, cs.rowFilter);
        N.checkArgNotNull(rowMapper, cs.rowMapper);

        return new ObjIteratorEx<>() {
            private boolean hasNext;
            private boolean exhausted;

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if advancing the result set or evaluating the configured row filter fails with an SQL exception.
             */
            @Override
            public boolean hasNext() throws UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if locating the next row, reading metadata, or invoking the configured row mapper fails with an SQL exception.
             * @throws NoSuchElementException if no unconsumed row remains after applying any configured filter.
             */
            @Override
            public T next() throws UncheckedSQLException, NoSuchElementException {
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

            /**
             * {@inheritDoc}
             *
             * @throws RuntimeException if the configured close action throws an exception on the first close.
             */
            @Override
            public void closeResource() throws RuntimeException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if advancing the result set to the next row fails.
             */
            @Override
            public boolean hasNext() throws UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if locating the next row, reading metadata, or invoking the configured row mapper fails with an SQL exception.
             * @throws NoSuchElementException if no unconsumed row remains after applying any configured filter.
             */
            @Override
            public T next() throws UncheckedSQLException, NoSuchElementException {
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

            /**
             * {@inheritDoc}
             *
             * @throws IllegalArgumentException if {@code n} is negative.
             * @throws UncheckedSQLException if advancing the result set while skipping rows fails.
             */
            @Override
            public void advance(final long n) throws IllegalArgumentException, UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if advancing the result set while counting the remaining rows fails.
             */
            @Override
            public long count() throws UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws RuntimeException if the configured close action throws an exception on the first close.
             */
            @Override
            public void closeResource() throws RuntimeException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if reading column metadata, advancing the result set, or evaluating the configured row filter fails with an
             *         SQL exception.
             */
            @Override
            public boolean hasNext() throws UncheckedSQLException {
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if locating the next row, reading metadata, or invoking the configured row mapper fails with an SQL exception.
             * @throws NoSuchElementException if no unconsumed row remains after applying any configured filter.
             */
            @Override
            public T next() throws UncheckedSQLException, NoSuchElementException {
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
     * @throws IllegalArgumentException if {@code rs} is {@code null} or {@code columnIndex} is not positive.
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
     * @throws IllegalArgumentException if {@code rs} is {@code null}, or {@code columnLabel} is {@code null} or empty.
     */
    public static <T> Stream<T> stream(final ResultSet rs, final String columnLabel) throws IllegalArgumentException {
        N.checkArgNotNull(rs, cs.rs);
        N.checkArgNotEmpty(columnLabel, cs.columnLabel);

        final RowMapper<? extends T> rowMapper = new RowMapper<>() {
            private int columnIndex = 0;
            private boolean checkDateType = true;

            /**
             * {@inheritDoc}
             *
             * @throws SQLException if reading result-set metadata or a requested column value fails.
             * @throws IllegalArgumentException if the result set has no column matching the configured column label.
             */
            @Override
            public T apply(final ResultSet resultSet) throws SQLException, IllegalArgumentException {
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
     * {@link UncheckedSQLException}. Duplicate or empty column labels in a result set are renamed
     * (see {@link #extractData(ResultSet)}).</p>
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
     * because each result set is closed immediately after its extractor returns.</p>
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
     * because each result set is closed immediately after its extractor returns.</p>
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
     * <p>When the driver reports that it supports multiple open results, a delivered result set stays open while the
     * iterator advances ({@link Statement#KEEP_CURRENT_RESULT}). Otherwise (e.g. Microsoft SQL Server, or a driver that
     * rejects {@code KEEP_CURRENT_RESULT} with {@link java.sql.SQLFeatureNotSupportedException}), the iterator instead
     * advances on the following {@code hasNext()}/{@code next()} call, which closes the previously delivered result
     * set; it must therefore be fully consumed before the iterator is used again. Any other failure while advancing is
     * propagated.</p>
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
            // null until the first next(): whether advancing with KEEP_CURRENT_RESULT is used.
            private Boolean useKeepCurrentResult = null;
            private boolean advancePending = false;

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if reading the current result set or update count, or advancing the statement to another result, fails.
             */
            @Override
            public boolean hasNext() throws UncheckedSQLException {
                if (resultSetHolder.isNull() && !noMoreResult) {
                    try {
                        if (advancePending) {
                            // Deferred advance (driver without KEEP_CURRENT_RESULT support, see next()): this closes
                            // the result set delivered by the previous next() call.
                            advancePending = false;
                            isNextResultSet = stmt.getMoreResults();
                        }

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
                                // Result-set claim was wrong (driver returned null, meaning the current
                                // result is actually an update count or no result). Fall through to the
                                // update-count check below: a real update count advances via getMoreResults();
                                // -1 means, per the JDBC contract, that there are no more results.
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

            /**
             * {@inheritDoc}
             *
             * @throws UncheckedSQLException if locating or advancing past the next result set fails.
             * @throws NoSuchElementException if the statement has no more result sets.
             */
            @Override
            public ResultSet next() throws UncheckedSQLException, NoSuchElementException {
                if (!hasNext()) {
                    throw new NoSuchElementException(InternalUtil.ERROR_MSG_FOR_NO_SUCH_EX);
                }

                final ResultSet rs = resultSetHolder.getAndSet(null);

                if (useKeepCurrentResult == null) {
                    // KEEP_CURRENT_RESULT is optional (the SQL Server driver, for example, always rejects it). Decide from
                    // the driver's declared capability up front instead of reinterpreting a failed call afterwards: a
                    // genuine failure (timeout, lost connection) must never be mistaken for "unsupported" and swallowed.
                    useKeepCurrentResult = supportsMultipleOpenResults(stmt);
                }

                if (useKeepCurrentResult) {
                    try {
                        isNextResultSet = stmt.getMoreResults(Statement.KEEP_CURRENT_RESULT);
                        return rs;
                    } catch (final java.sql.SQLFeatureNotSupportedException e) {
                        // The driver claims support but explicitly rejects the feature: use the deferred advance below.
                        useKeepCurrentResult = false;
                    } catch (final SQLException e) {
                        // The caller never received `rs`, so they cannot close it. Release it
                        // here instead of leaking it to be cleaned up when the statement closes.
                        closeQuietly(rs);
                        throw new UncheckedSQLException(e);
                    }
                }

                // Without KEEP_CURRENT_RESULT, advancing (getMoreResults()) would close `rs` before delivery, so defer the
                // advance to the next hasNext().

                // `rs` is still the statement's current result: advancing now would close it before delivery.
                isNextResultSet = false;
                advancePending = true;

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
                advancePending = false;
                noMoreResult = true;
            }
        };
    }

    /**
     * Returns whether the driver behind {@code stmt} declares support for multiple open results, which
     * {@code getMoreResults(Statement.KEEP_CURRENT_RESULT)} requires. If the capability cannot be read, support is
     * assumed: the {@code KEEP_CURRENT_RESULT} call is then attempted and any genuine failure still propagates.
     *
     * @param stmt The statement whose driver capability is checked.
     * @return {@code false} only if the driver reports that it does not support multiple open results.
     */
    private static boolean supportsMultipleOpenResults(final Statement stmt) {
        try {
            final Connection conn = stmt.getConnection();
            final DatabaseMetaData metadata = conn == null ? null : conn.getMetaData();

            return metadata == null || metadata.supportsMultipleOpenResults();
        } catch (final SQLException | RuntimeException | AbstractMethodError e) {
            logger.debug(e, "Failed to read supportsMultipleOpenResults(); assuming KEEP_CURRENT_RESULT is supported");
            return true;
        }
    }

    /**
     * Returns whether the given iterator has another result set, unwrapping any
     * {@link UncheckedSQLException} thrown by the iterator into an {@link SQLException}.
     *
     * @param iter The result-set iterator to test.
     * @return {@code true} if another result set is available.
     * @throws SQLException if advancing the result-set iterator fails with an underlying JDBC exception.
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
     * @throws SQLException if advancing the result-set iterator fails with an underlying JDBC exception.
     * @throws NoSuchElementException if the iterator has no more result sets.
     */
    static ResultSet nextResultSet(final ObjIteratorEx<ResultSet> iter) throws SQLException, NoSuchElementException {
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
     * <p>Queries run lazily during traversal. Each page acquires and releases its connection through
     * this library's transaction-aware helpers and closes its statement before the page is emitted.
     * The stream ends at the first empty page, which is not emitted.</p>
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
     * {@link UncheckedSQLException}. Duplicate or empty column labels in a page are renamed
     * (see {@link #extractData(ResultSet)}).</p>
     *
     * @param ds The {@link javax.sql.DataSource} to get the connection from.
     * @param sql The SQL query to run for each page. Must include ORDER BY and LIMIT/FETCH clauses.
     * @param pageSize The positive JDBC fetch-size hint for each page query. The SQL must enforce the page's row limit;
     *                 this value does not add a LIMIT/FETCH clause or cap the extracted row count.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the {@link Dataset} returned by the previous page (or {@code null} for the first page).
     * @return A Stream of Dataset, each representing a page of results.
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes
     *         parameter styles, or contains a malformed parameter placeholder; {@code pageSize} is not positive; or
     *         {@code parametersSetter} is {@code null}.
     */
    @SuppressWarnings("rawtypes")
    public static Stream<Dataset> queryByPage(final javax.sql.DataSource ds, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, Dataset> parametersSetter) throws IllegalArgumentException {
        return queryByPage(ds, sql, pageSize, parametersSetter, Jdbc.ResultExtractor.TO_DATASET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * The query must be ordered by at least one key/id and have a result size limitation.
     * Each page is processed by the provided ResultExtractor.
     *
     * <p>The stream ends when a page's extracted result is {@code null} or empty: a {@code Dataset}, {@code Collection},
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
     * which cannot be retained after its owning query is closed. The extractor must also fully materialize
     * any JDBC-backed streams or iterators before returning: each page's result set and statement are
     * closed before that page is emitted.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param ds The {@link javax.sql.DataSource} from which each page lazily acquires and releases a connection
     *           through this library's transaction-aware helpers.
     * @param sql The SQL query to run for each page.
     * @param pageSize The positive JDBC fetch-size hint for each page query. The SQL must enforce the page's row limit;
     *                 this value does not add a LIMIT/FETCH clause or cap the extracted row count.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The ResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes
     *         parameter styles, or contains a malformed parameter placeholder; {@code pageSize} is not positive; or
     *         {@code parametersSetter} or {@code resultExtractor} is {@code null}.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.ResultExtractor<R> resultExtractor)
            throws IllegalArgumentException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotBlank(sql, cs.sql);
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
     * <p>The stream ends when a page's extracted result is {@code null} or empty: a {@code Dataset}, {@code Collection},
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
     * which cannot be retained after its owning query is closed. The extractor must also fully materialize
     * any JDBC-backed streams or iterators before returning: each page's result set and statement are
     * closed before that page is emitted.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param ds The {@link javax.sql.DataSource} from which each page lazily acquires and releases a connection
     *           through this library's transaction-aware helpers.
     * @param sql The SQL query to run for each page.
     * @param pageSize The positive JDBC fetch-size hint for each page query. The SQL must enforce the page's row limit;
     *                 this value does not add a LIMIT/FETCH clause or cap the extracted row count.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The BiResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code ds} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes
     *         parameter styles, or contains a malformed parameter placeholder; {@code pageSize} is not positive; or
     *         {@code parametersSetter} or {@code resultExtractor} is {@code null}.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final javax.sql.DataSource ds, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.BiResultExtractor<R> resultExtractor)
            throws IllegalArgumentException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotBlank(sql, cs.sql);
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
     * {@link UncheckedSQLException}. Duplicate or empty column labels in a page are renamed
     * (see {@link #extractData(ResultSet)}).</p>
     *
     * @param conn The caller-owned database {@link Connection}; keep it open throughout lazy stream traversal.
     *             This method closes each page's statement but does not close the connection.
     * @param sql The SQL query to run for each page.
     * @param pageSize The positive JDBC fetch-size hint for each page query. The SQL must enforce the page's row limit;
     *                 this value does not add a LIMIT/FETCH clause or cap the extracted row count.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the {@link Dataset} returned by the previous page (or {@code null} for the first page).
     * @return A Stream of Dataset, each representing a page of results.
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes
     *         parameter styles, or contains a malformed parameter placeholder; {@code pageSize} is not positive; or
     *         {@code parametersSetter} is {@code null}.
     */
    @SuppressWarnings("rawtypes")
    public static Stream<Dataset> queryByPage(final Connection conn, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, Dataset> parametersSetter) throws IllegalArgumentException {
        return queryByPage(conn, sql, pageSize, parametersSetter, Jdbc.ResultExtractor.TO_DATASET);
    }

    /**
     * Runs a {@code Stream} with each element (page) loaded from the database table by running the specified SQL {@code query}.
     * Similar to the DataSource version but uses an existing Connection.
     * Each page is processed by the provided ResultExtractor.
     *
     * <p>The stream ends when a page's extracted result is {@code null} or empty: a {@code Dataset}, {@code Collection},
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
     * which cannot be retained after its owning query is closed. The extractor must also fully materialize
     * any JDBC-backed streams or iterators before returning: each page's result set and statement are
     * closed before that page is emitted.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param conn The caller-owned database {@link Connection}; keep it open throughout lazy stream traversal.
     *             This method closes each page's statement but does not close the connection.
     * @param sql The SQL query to run for each page.
     * @param pageSize The positive JDBC fetch-size hint for each page query. The SQL must enforce the page's row limit;
     *                 this value does not add a LIMIT/FETCH clause or cap the extracted row count.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The ResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes
     *         parameter styles, or contains a malformed parameter placeholder; {@code pageSize} is not positive; or
     *         {@code parametersSetter} or {@code resultExtractor} is {@code null}.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.ResultExtractor<R> resultExtractor)
            throws IllegalArgumentException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(sql, cs.sql);
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
     * <p>The stream ends when a page's extracted result is {@code null} or empty: a {@code Dataset}, {@code Collection},
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
     * which cannot be retained after its owning query is closed. The extractor must also fully materialize
     * any JDBC-backed streams or iterators before returning: each page's result set and statement are
     * closed before that page is emitted.</p>
     *
     * @param <R> The type of the result extracted from each page.
     * @param conn The caller-owned database {@link Connection}; keep it open throughout lazy stream traversal.
     *             This method closes each page's statement but does not close the connection.
     * @param sql The SQL query to run for each page.
     * @param pageSize The positive JDBC fetch-size hint for each page query. The SQL must enforce the page's row limit;
     *                 this value does not add a LIMIT/FETCH clause or cap the extracted row count.
     * @param parametersSetter The BiParametersSetter to set parameters for the query; the second argument passed to the setter is the result extracted from the previous page (or {@code null} for the first page).
     * @param resultExtractor The BiResultExtractor to extract results from the ResultSet.
     * @return A {@link Stream} of the extracted results.
     * @throws IllegalArgumentException if {@code conn} is {@code null}; {@code sql} is {@code null}, empty, or blank, mixes
     *         parameter styles, or contains a malformed parameter placeholder; {@code pageSize} is not positive; or
     *         {@code parametersSetter} or {@code resultExtractor} is {@code null}.
     */
    @SuppressWarnings("rawtypes")
    public static <R> Stream<R> queryByPage(final Connection conn, final String sql, final int pageSize,
            final Jdbc.BiParametersSetter<? super AbstractQuery, R> parametersSetter, final Jdbc.BiResultExtractor<R> resultExtractor)
            throws IllegalArgumentException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(sql, cs.sql);
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
    static <R> R checkNotResultSet(final R result) throws UnsupportedOperationException {
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
     * @throws IllegalArgumentException if {@code stmt.getConnection()} is {@code null}, or the database metadata
     *         reports a {@code null}, empty, or blank product name.
     * @throws UncheckedSQLException if reading the connection's database product metadata fails; failures from
     *         {@link Statement#getConnection()} are caught and result in {@code true}.
     */
    static boolean checkDateType(final Statement stmt) throws IllegalArgumentException, UncheckedSQLException {
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
         * @throws SQLException if reading the registered output parameter fails, including invalid addressing or a closed callable statement.
         */
        Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException;

        /**
         * Reads the out parameter with the given parameter name.
         *
         * @param stmt The {@link CallableStatement} to read from.
         * @param outParameterName The name of the out parameter.
         * @return The out parameter value.
         * @throws SQLException if reading the registered output parameter fails, including invalid addressing or a closed callable statement.
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
            // JDBC maps SQL FLOAT and DOUBLE to Java double, and SQL REAL to Java float. FLOAT and
            // DOUBLE are distinct JDBC type codes. Reading a Types.FLOAT out parameter with getFloat() would narrow the value to
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
        /**
         * Reads the out parameter at the given 1-based index with {@link CallableStatement#getObject(int)},
         * letting the driver choose the Java representation for the parameter's SQL type.
         *
         * @param stmt The {@link CallableStatement} to read from.
         * @param outParameterIndex The 1-based index of the out parameter.
         * @return The out parameter value as returned by the driver.
         * @throws SQLException if reading the registered output parameter fails, including invalid addressing or a closed callable statement.
         */
        @Override
        public Object getOutParameter(final CallableStatement stmt, final int outParameterIndex) throws SQLException {
            return stmt.getObject(outParameterIndex);
        }

        /**
         * Reads the out parameter with the given name with {@link CallableStatement#getObject(String)},
         * letting the driver choose the Java representation for the parameter's SQL type.
         *
         * @param stmt The {@link CallableStatement} to read from.
         * @param outParameterName The name of the out parameter.
         * @return The out parameter value as returned by the driver.
         * @throws SQLException if reading the registered output parameter fails, including invalid addressing or a closed callable statement.
         */
        @Override
        public Object getOutParameter(final CallableStatement stmt, final String outParameterName) throws SQLException {
            return stmt.getObject(outParameterName);
        }
    };

    /**
     * Checks whether a table exists in the database referenced by the given {@link javax.sql.DataSource}.
     *
     * <p>The lookup first consults {@link DatabaseMetaData#getTables} (using the driver's canonical storage case for undelimited parts.
     * Delimited and case-folded parts are matched case-sensitively; upper- and lower-case table variants
     * are tried only when the driver reports no canonical identifier case). An unqualified name is looked up only in the
     * connection's current schema ({@link Connection#getSchema()}) and catalog, so a table that exists only in another schema,
     * which the database would not resolve the unqualified name to (for example, one outside the PostgreSQL search path), is
     * not reported. If the current schema cannot be determined on a database that supports schemas, the metadata lookup is
     * skipped for an unqualified name and only the fallback query below, which follows the database's own name resolution,
     * can find the table. A delimited or quote-dependent unqualified name, which that query cannot check, is also looked up in
     * the other schemas of the database's search path, in search order (PostgreSQL-compatible databases and H2).
     * Schema and table parts are treated as literal names, with metadata search-pattern characters escaped.
     * If metadata lookup yields no match and all parts satisfy the unquoted identifier policy, the method falls back to
     * executing {@code SELECT 1 FROM <table> WHERE 1 > 2}. Generated identifier quoting is disabled, so delimited names,
     * non-simple names and common SQL keywords use JDBC metadata only. A SQL error from the fallback query
     * that is recognized as a "table not found" error (by SQLState, vendor error code, or message) returns
     * {@code false}; any other SQL error is propagated. On PostgreSQL and PostgreSQL-compatible databases (where a
     * failed statement aborts the enclosing transaction), when the connection has auto-commit disabled, the fallback query
     * runs under a savepoint that is rolled back if the query fails, so an expected "table not found" error does not
     * abort the caller's transaction. If no savepoint can be set in that transaction, or rolling back to it fails, the
     * transaction may be unusable, so the "table not found" error is propagated (with the rollback failure suppressed)
     * instead of returning {@code false}.</p>
     *
     * <p>The {@code tableName} may be a simple identifier or a qualified name like {@code schema.table}
     * or {@code catalog.schema.table}. On databases whose driver supports catalogs but not schemas in table definitions,
     * a two-part name is interpreted as {@code catalog.table}.</p>
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
     * @throws UncheckedSQLException if acquiring a connection or reading table metadata fails, or the fallback table query fails
     *         with an SQL error other than "table not found", or, on a database where a failed statement aborts the
     *         transaction, with a "table not found" error that may have left the connection's open transaction aborted
     *         (no savepoint could be set, or rolling back to it failed).
     * @see #tableExists(Connection, String)
     */
    public static boolean tableExists(final javax.sql.DataSource ds, final String tableName) throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotBlank(tableName, cs.tableName);
        splitQualifiedSqlIdentifier(tableName, cs.tableName);

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
     * <p>The lookup first consults {@link DatabaseMetaData#getTables} (using the driver's canonical storage case for undelimited parts.
     * Delimited and case-folded parts are matched case-sensitively; upper- and lower-case table variants
     * are tried only when the driver reports no canonical identifier case). An unqualified name is looked up only in the
     * connection's current schema ({@link Connection#getSchema()}) and catalog, so a table that exists only in another schema,
     * which the database would not resolve the unqualified name to (for example, one outside the PostgreSQL search path), is
     * not reported. If the current schema cannot be determined on a database that supports schemas, the metadata lookup is
     * skipped for an unqualified name and only the fallback query below, which follows the database's own name resolution,
     * can find the table. A delimited or quote-dependent unqualified name, which that query cannot check, is also looked up in
     * the other schemas of the database's search path, in search order (PostgreSQL-compatible databases and H2).
     * If metadata lookup yields no match and all parts satisfy the unquoted identifier policy, the method falls back to
     * executing {@code SELECT 1 FROM <table> WHERE 1 > 2}. Generated identifier quoting is disabled, so delimited names,
     * non-simple names and common SQL keywords use JDBC metadata only. A SQL error from the fallback query
     * that is recognized as a "table not found" error (by SQLState, vendor error code, or message) returns
     * {@code false}; any other SQL error is propagated. On PostgreSQL and PostgreSQL-compatible databases (where a
     * failed statement aborts the enclosing transaction), when the connection has auto-commit disabled, the fallback query
     * runs under a savepoint that is rolled back if the query fails, so an expected "table not found" error does not
     * abort the caller's transaction. If no savepoint can be set in that transaction, or rolling back to it fails, the
     * transaction may be unusable, so the "table not found" error is propagated (with the rollback failure suppressed)
     * instead of returning {@code false}.</p>
     *
     * <p>The {@code tableName} may be a simple identifier or a qualified name like {@code schema.table}
     * or {@code catalog.schema.table}. On databases whose driver supports catalogs but not schemas in table definitions,
     * a two-part name is interpreted as {@code catalog.table}.</p>
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
     * @throws UncheckedSQLException if reading connection or table metadata fails, or the fallback table query fails
     *         with an SQL error other than "table not found", or, on a database where a failed statement aborts the
     *         transaction, with a "table not found" error that may have left the connection's open transaction aborted
     *         (no savepoint could be set, or rolling back to it failed).
     */
    public static boolean tableExists(final Connection conn, final String tableName) throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(tableName, cs.tableName);

        try {
            final String[] nameParts = splitQualifiedSqlIdentifier(tableName, cs.tableName);
            final boolean[] delimitedNameParts = SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(tableName, nameParts.length);
            final DatabaseMetaData metadata = conn.getMetaData();
            final boolean caseFolded = normalizeMetadataIdentifierParts(metadata, nameParts, delimitedNameParts);
            final boolean catalogQualified = nameParts.length == 2 && !metadata.supportsSchemasInTableDefinitions()
                    && metadata.supportsCatalogsInTableDefinitions();
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
                // The first part denotes a catalog on MySQL/MariaDB in catalog mode; their metadata ignores schema filters.
                catalog = catalogQualified ? nameParts[0] : conn.getCatalog();
                schema = catalogQualified ? null : nameParts[0];
                table = nameParts[1];
                catalogDelimited = catalogQualified && delimitedNameParts[0];
                schemaDelimited = !catalogQualified && delimitedNameParts[0];
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
            boolean lookUpMetadata = true;

            if (schemaToUse == null && !catalogQualified) {
                // Unqualified name: a metadata match counts only in the current schema/catalog. When that namespace cannot be
                // pinned down, skip metadata and leave the answer to the probe below (the database's own name resolution).
                schemaToUse = getCurrentSchema(conn);
                lookUpMetadata = schemaToUse != null || isCurrentNamespaceLookup(metadata, catalog);
            }

            if (lookUpMetadata && tableExistsInSchema(metadata, catalog, schemaToUse, table, catalogDelimited, schemaDelimited, tableDelimited)) {
                return true;
            }

            // Use the current catalog only to scope metadata lookup. The fallback SQL must retain the
            // caller's original qualification: on databases such as PostgreSQL and SQL Server,
            // "catalog.table" is not equivalent to an unqualified table in the current catalog.
            // With quoting disabled, buildSimpleQualifiedTableName skips keywords instead of executing expressions
            // such as FROM USER. If quoting is enabled, canonical parts may be delimited without changing their identity.
            final String probeQuote = caseFolded && !hasDelimitedIdentifierPart(tableName) ? normalizeIdentifierQuote(metadata) : null;
            final String safeQualifiedTableName = buildSimpleQualifiedTableName(nameParts.length == 3 || catalogQualified ? catalog : null, schema, table,
                    probeQuote);
            // splitQualifiedSqlIdentifier removes delimiters. Never feed those stripped parts to
            // unquoted fallback SQL: "mixedCase" and mixedCase can identify different tables.
            final boolean probeable = !hasDelimitedIdentifierPart(tableName) && Strings.isNotEmpty(safeQualifiedTableName);

            // The fallback query below follows the database's own name resolution, including its schema search path. A name it
            // can't check (delimited or quote-dependent, as identifier quoting is disabled) is looked up in the other schemas of
            // the search path as well, in search order: e.g. OTHER."some Table" with H2's search path PUBLIC, OTHER.
            if (!probeable && schema == null && !catalogQualified) {
                for (final String searchPathSchema : getSearchPathSchemas(conn, metadata, schemaToUse)) {
                    if (tableExistsInSchema(metadata, catalog, searchPathSchema, table, catalogDelimited, true, tableDelimited)) {
                        return true;
                    }
                }
            }

            if (probeable) {
                // On PostgreSQL (and compatible databases) any failed statement aborts the enclosing transaction, so an
                // expected "table not found" from this probe would leave the caller's transaction unusable ("current
                // transaction is aborted") even though this method just returns false. There, probe under a savepoint
                // when the connection is in a transaction, and roll back to it on failure. Other databases (MySQL,
                // Oracle, SQL Server, ...) roll back only the failed statement, so no savepoint is needed.
                final boolean probeAbortsTransaction = abortsTransactionOnStatementError(metadata);
                final java.sql.Savepoint savepoint = probeAbortsTransaction ? setSavepointForTableProbe(conn) : null;

                try {
                    execute(conn, "SELECT 1 FROM " + safeQualifiedTableName + " WHERE 1 > 2");

                    if (savepoint != null) {
                        releaseSavepointQuietly(conn, savepoint);
                    }

                    return true;
                } catch (final SQLException e) {
                    // A failed probe leaves the caller's transaction aborted unless the savepoint undoes it, or there is none to
                    // abort (auto-commit mode): report the failure rather than a plain "does not exist" result the caller would
                    // take as a clean answer. With no savepoint in a transaction (none could be set), false used to be returned
                    // while the transaction was left unusable; the same rule as getSearchPathSchemas applies now.
                    if ((!probeAbortsTransaction || isTransactionUsableAfterFailure(conn, savepoint, e)) && isTableNotExistsException(e)) {
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
     * Returns the connection's current schema for an unqualified table lookup.
     *
     * @param conn The connection to read the current schema from.
     * @return The current schema, or {@code null} if the driver reports none (or an empty name), fails to report it, or does
     *         not implement {@link Connection#getSchema()} (JDBC 4.0 drivers).
     */
    private static String getCurrentSchema(final Connection conn) {
        try {
            final String schema = conn.getSchema();

            return Strings.isEmpty(schema) ? null : schema;
        } catch (final SQLException | AbstractMethodError e) {
            return null;
        }
    }

    /**
     * Returns whether a metadata lookup for an unqualified table name with a {@code null} schema argument stays within the
     * connection's current namespace. A {@code null} schema argument matches a table in any schema, and a {@code null}
     * catalog argument one in any catalog, which could report a table that the unqualified name does not resolve to
     * (for example, one in a schema outside the PostgreSQL search path). That is safe only on a database without schemas in
     * table definitions whose current catalog is known (such as MySQL in its default catalog mode) or that has no catalogs
     * either (such as SQLite).
     *
     * @param metadata The database metadata.
     * @param currentCatalog The connection's current catalog, or {@code null} if it has none.
     * @return {@code true} if the {@code null}-schema lookup is confined to the current namespace.
     * @throws SQLException if the driver cannot report its schema or catalog support.
     */
    private static boolean isCurrentNamespaceLookup(final DatabaseMetaData metadata, final String currentCatalog) throws SQLException {
        return !metadata.supportsSchemasInTableDefinitions() && (currentCatalog != null || !metadata.supportsCatalogsInTableDefinitions());
    }

    /**
     * Looks the table up in one schema through {@link DatabaseMetaData#getTables}: with the name as given and, unless it is
     * delimited, its upper- and lower-case variants.
     *
     * @param metadata The database metadata.
     * @param catalog The catalog, or {@code null}.
     * @param schema The schema, or {@code null}.
     * @param table The table name.
     * @param catalogDelimited Whether the catalog is compared case-sensitively.
     * @param schemaDelimited Whether the schema is compared case-sensitively.
     * @param tableDelimited Whether the table name is delimited (compared case-sensitively, without case variants).
     * @return {@code true} if the metadata reports the table.
     * @throws SQLException if reading the metadata fails.
     */
    private static boolean tableExistsInSchema(final DatabaseMetaData metadata, final String catalog, final String schema, final String table,
            final boolean catalogDelimited, final boolean schemaDelimited, final boolean tableDelimited) throws SQLException {
        return tableExists(metadata, catalog, schema, table, catalogDelimited, schemaDelimited, tableDelimited)
                || (!tableDelimited && (tableExists(metadata, catalog, schema, table.toUpperCase(Locale.ROOT), catalogDelimited, schemaDelimited, false)
                        || tableExists(metadata, catalog, schema, table.toLowerCase(Locale.ROOT), catalogDelimited, schemaDelimited, false)));
    }

    /**
     * Reads the table's column names from {@link DatabaseMetaData#getColumns} in one schema: with the name as given and,
     * unless it is delimited, its upper- and lower-case variants.
     *
     * @param metadata The database metadata.
     * @param catalog The catalog, or {@code null}.
     * @param schema The schema, or {@code null}.
     * @param table The table name.
     * @param catalogDelimited Whether the catalog is compared case-sensitively.
     * @param schemaDelimited Whether the schema is compared case-sensitively.
     * @param tableDelimited Whether the table name is delimited (compared case-sensitively, without case variants).
     * @return The column names, or an empty or {@code null} list if the metadata reports none.
     * @throws SQLException if reading the metadata fails.
     */
    private static List<String> getColumnNamesInSchema(final DatabaseMetaData metadata, final String catalog, final String schema, final String table,
            final boolean catalogDelimited, final boolean schemaDelimited, final boolean tableDelimited) throws SQLException {
        List<String> columnNameList = getColumnNamesFromMetadata(metadata, catalog, schema, table, catalogDelimited, schemaDelimited, tableDelimited);

        if (N.isEmpty(columnNameList) && !tableDelimited) {
            columnNameList = getColumnNamesFromMetadata(metadata, catalog, schema, table.toUpperCase(Locale.ROOT), catalogDelimited, schemaDelimited, false);
        }

        if (N.isEmpty(columnNameList) && !tableDelimited) {
            columnNameList = getColumnNamesFromMetadata(metadata, catalog, schema, table.toLowerCase(Locale.ROOT), catalogDelimited, schemaDelimited, false);
        }

        return columnNameList;
    }

    /**
     * Returns the schemas, other than {@code currentSchema}, that the database searches for an unqualified table name, in
     * search order: on PostgreSQL-compatible databases those of {@code current_schemas(true)} (including implicitly searched
     * ones such as {@code pg_catalog}), on H2 those of its {@code SCHEMA_SEARCH_PATH} (searched after the current schema).
     * The PostgreSQL query runs under a savepoint in an open transaction, so that a failure cannot abort it.
     *
     * @param conn The connection.
     * @param metadata The connection's database metadata.
     * @param currentSchema The current schema, already looked up, or {@code null}.
     * @return The schemas, or an empty list on other databases or if the search path can't be read.
     * @throws SQLException if the PostgreSQL query fails and may have left the connection's open transaction aborted: no
     *         savepoint could be set, or rolling back to it failed (attached as suppressed).
     */
    private static List<String> getSearchPathSchemas(final Connection conn, final DatabaseMetaData metadata, final String currentSchema) throws SQLException {
        final List<String> schemas = new ArrayList<>();
        java.sql.Savepoint savepoint = null;
        boolean postgreSqlQuery = false;

        try {
            final String productName = metadata.getDatabaseProductName();

            if (isPostgreSqlFamily(productName)) {
                savepoint = setSavepointForTableProbe(conn);
                postgreSqlQuery = true;

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT current_schemas(true)")) {
                    if (rs.next()) {
                        final java.sql.Array array = rs.getArray(1);

                        if (array != null) {
                            try {
                                // Read defensively: an unexpected value is no failure of the query, which leaves the fallback
                                // below (rolling back to the savepoint) to real failures.
                                if (array.getArray() instanceof final Object[] values) {
                                    for (final Object schema : values) {
                                        if (schema instanceof final String schemaName) {
                                            schemas.add(schemaName);
                                        }
                                    }
                                }
                            } finally {
                                freeQuietly(array);
                            }
                        }
                    }
                }
            } else if ("H2".equalsIgnoreCase(productName)) {
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT STATE_COMMAND FROM INFORMATION_SCHEMA.SESSION_STATE WHERE STATE_KEY = 'SCHEMA_SEARCH_PATH'")) {
                    if (rs.next()) {
                        schemas.addAll(parseH2SchemaSearchPath(rs.getString(1)));
                    }
                }
            }

            if (savepoint != null) {
                releaseSavepointQuietly(conn, savepoint);
            }
        } catch (final SQLException | RuntimeException e) {
            // On PostgreSQL a failed statement aborts an open transaction until it is rolled back. Fall back quietly only if the
            // savepoint undid that, or there was no transaction to abort: otherwise the caller would get an answer while its
            // transaction is unusable.
            if (postgreSqlQuery && !isTransactionUsableAfterFailure(conn, savepoint, e)) {
                if (e instanceof final SQLException sqlException) {
                    throw sqlException;
                }

                throw (RuntimeException) e;
            }

            logger.debug(e, "Failed to read the schema search path; looking up unqualified table names in the current schema only");
            return new ArrayList<>();
        }

        schemas.removeIf(schema -> schema == null || schema.equals(currentSchema));

        return schemas;
    }

    /**
     * Rolls back to {@code savepoint} after a statement failed on a PostgreSQL-compatible connection, and reports whether the
     * connection's transaction is usable: the rollback succeeded, or, without a savepoint, the connection is in auto-commit mode
     * (no transaction to abort). A failure to roll back or to read the auto-commit mode is attached to {@code failure}.
     *
     * @param conn The connection.
     * @param savepoint The savepoint set before the statement, or {@code null} if none was set.
     * @param failure The statement's failure.
     * @return {@code true} if the transaction is usable.
     */
    private static boolean isTransactionUsableAfterFailure(final Connection conn, final java.sql.Savepoint savepoint, final Throwable failure) {
        if (savepoint != null) {
            try {
                conn.rollback(savepoint);
                return true;
            } catch (final SQLException | RuntimeException e) {
                addSuppressedIfDistinct(failure, e);
                return false;
            } finally {
                releaseSavepointQuietly(conn, savepoint);
            }
        }

        try {
            return conn.getAutoCommit();
        } catch (final SQLException | RuntimeException e) {
            addSuppressedIfDistinct(failure, e);
            return false;
        }
    }

    /**
     * Parses the schema names of H2's {@code SET SCHEMA_SEARCH_PATH ...} session command (as reported by
     * {@code INFORMATION_SCHEMA.SESSION_STATE}), in order. Quoted names are unquoted, Unicode-escaped quoted names
     * ({@code U&"..."}, which H2 writes for a name with a character outside printable ASCII: {@code \XXXX} or
     * {@code \+XXXXXX} for a code point, {@code \\} for a backslash) are decoded; unquoted names are upper-cased, as H2
     * stores them.
     *
     * @param command The command, e.g. {@code SET SCHEMA_SEARCH_PATH "PUBLIC", "OTHER"}.
     * @return The schema names, or an empty list if {@code command} is not such a command.
     */
    static List<String> parseH2SchemaSearchPath(final String command) {
        final String prefix = "SET SCHEMA_SEARCH_PATH";
        final List<String> schemas = new ArrayList<>();

        if (command == null || !command.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return schemas;
        }

        for (int i = prefix.length(), len = command.length(); i < len;) {
            final char ch = command.charAt(i);
            // WHY: H2 quotes a name containing a code point below ' ' or above 127 as U&"..." with \XXXX escapes
            // (StringUtils.quoteIdentifier); read as a bare token, such a name came out as the bogus upper-cased U&"..." text
            // and the schema was never searched.
            final boolean unicodeQuoted = (ch == 'U' || ch == 'u') && i + 2 < len && command.charAt(i + 1) == '&' && command.charAt(i + 2) == '"';

            if (ch == '"' || unicodeQuoted) {
                final StringBuilder sb = new StringBuilder();
                i += unicodeQuoted ? 3 : 1;

                while (i < len) {
                    final char c = command.charAt(i++);

                    if (c == '"') {
                        if (i < len && command.charAt(i) == '"') {
                            sb.append('"');
                            i++;
                        } else {
                            break;
                        }
                    } else {
                        sb.append(c);
                    }
                }

                schemas.add(unicodeQuoted ? decodeH2UnicodeEscapes(sb.toString()) : sb.toString());
            } else if (ch == ',' || Character.isWhitespace(ch)) {
                i++;
            } else {
                final int start = i;

                while (i < len && command.charAt(i) != ',' && !Character.isWhitespace(command.charAt(i))) {
                    i++;
                }

                schemas.add(command.substring(start, i).toUpperCase(Locale.ROOT));
            }
        }

        return schemas;
    }

    /**
     * Decodes the body of an H2 Unicode-escaped identifier ({@code U&"..."}, with the quotes already removed and the doubled
     * quotes already undoubled): {@code \XXXX} (four hex digits) and {@code \+XXXXXX} (six hex digits) stand for a code
     * point, {@code \\} for a backslash, as H2's {@code StringUtils.quoteIdentifier} writes them (never with a
     * {@code UESCAPE} clause). A malformed escape is kept as written.
     *
     * @param body the identifier body
     * @return the decoded identifier
     */
    static String decodeH2UnicodeEscapes(final String body) {
        final int len = body.length();
        final StringBuilder sb = new StringBuilder(len);

        for (int i = 0; i < len;) {
            final char c = body.charAt(i++);

            if (c != '\\' || i >= len) {
                sb.append(c);
            } else if (body.charAt(i) == '\\') {
                sb.append('\\');
                i++;
            } else {
                final int digits = body.charAt(i) == '+' ? 6 : 4;
                final int start = digits == 6 ? i + 1 : i;
                final int codePoint = start + digits <= len ? parseHexCodePoint(body, start, start + digits) : -1;

                if (codePoint >= 0) {
                    sb.appendCodePoint(codePoint);
                    i = start + digits;
                } else {
                    sb.append(c);
                }
            }
        }

        return sb.toString();
    }

    /**
     * Parses the hex digits of {@code s} between {@code start} and {@code end} as a code point.
     *
     * @param s the text
     * @param start the index of the first digit
     * @param end the index after the last digit
     * @return the code point, or {@code -1} if a character is not a hex digit or the value is not a valid code point
     */
    private static int parseHexCodePoint(final String s, final int start, final int end) {
        int value = 0;

        for (int i = start; i < end; i++) {
            final int digit = Character.digit(s.charAt(i), 16);

            if (digit < 0) {
                return -1;
            }

            value = (value << 4) | digit;
        }

        return Character.isValidCodePoint(value) ? value : -1;
    }

    /**
     * Returns whether the database is PostgreSQL or a PostgreSQL-compatible database (identified by product name),
     * on which a failed statement aborts the enclosing transaction until it is rolled back (SQLState {@code 25P02}
     * "current transaction is aborted"). Most other databases (MySQL, Oracle, SQL Server, ...) roll back only the
     * failed statement.
     *
     * @param metadata The database metadata to read the product name from.
     * @return {@code true} if the product is PostgreSQL-compatible, or if the product name cannot be read
     *         (so the {@code tableExists} / {@code getColumnNames} probe stays protected by a savepoint); {@code false} otherwise.
     */
    private static boolean abortsTransactionOnStatementError(final DatabaseMetaData metadata) {
        try {
            return isPostgreSqlFamily(metadata.getDatabaseProductName());
        } catch (final SQLException | RuntimeException e) {
            logger.debug(e, "Failed to read the database product name for the table probe; assuming PostgreSQL-like behavior");
            return true;
        }
    }

    /**
     * Sets a savepoint before the {@code tableExists} or {@code getColumnNames} fallback probe when {@code conn} is in a transaction
     * (auto-commit disabled), so a failed probe can be undone without aborting the caller's transaction.
     * Only called for databases on which {@link #abortsTransactionOnStatementError(DatabaseMetaData)} is {@code true}.
     *
     * @param conn The connection the probe runs on.
     * @return The savepoint, or {@code null} if the connection is in auto-commit mode or savepoints are unavailable (the probe's
     *         failure is then reported to the caller when the connection is in a transaction, see
     *         {@link #isTransactionUsableAfterFailure(Connection, java.sql.Savepoint, Throwable)}).
     */
    private static java.sql.Savepoint setSavepointForTableProbe(final Connection conn) {
        try {
            return conn.getAutoCommit() ? null : conn.setSavepoint();
        } catch (final SQLException | RuntimeException | AbstractMethodError e) {
            // Savepoints are optional (JDBC 3.0) and not allowed in every state: probe without one. In a transaction, a failed
            // probe is then propagated (the transaction may be aborted) instead of being answered as if nothing had happened.
            logger.debug(e, "Failed to set a savepoint for the table probe; probing without one");
            return null;
        }
    }

    /**
     * Releases a savepoint created by {@link #setSavepointForTableProbe(Connection)}, ignoring any failure
     * (a failed release only leaves the savepoint in place until the transaction ends).
     *
     * @param conn The connection that owns the savepoint.
     * @param savepoint The savepoint to release.
     */
    private static void releaseSavepointQuietly(final Connection conn, final java.sql.Savepoint savepoint) {
        try {
            conn.releaseSavepoint(savepoint);
        } catch (final SQLException | RuntimeException e) {
            logger.debug(e, "Failed to release the table probe savepoint");
        }
    }

    /**
     * Frees {@code array}'s resources, logging a failure (e.g. of a driver that doesn't support {@code free()}) instead of
     * throwing it.
     *
     * @param array The array to free.
     */
    private static void freeQuietly(final java.sql.Array array) {
        try {
            array.free();
        } catch (final SQLException | RuntimeException e) {
            logger.debug(e, "Failed to free the SQL array");
        }
    }

    /**
     * Rolls back to (and then releases) a savepoint created by {@link #setSavepointForTableProbe(Connection)}
     * after the probe failed. A rollback failure is attached to {@code probeFailure} as suppressed.
     *
     * @param conn The connection that owns the savepoint.
     * @param savepoint The savepoint to roll back to.
     * @param probeFailure The probe failure that triggered the rollback.
     * @return {@code true} if the rollback succeeded; {@code false} if it failed.
     */
    private static boolean rollbackToProbeSavepoint(final Connection conn, final java.sql.Savepoint savepoint, final SQLException probeFailure) {
        boolean rolledBack = false;

        try {
            conn.rollback(savepoint);
            rolledBack = true;
        } catch (final SQLException | RuntimeException e) {
            addSuppressedIfDistinct(probeFailure, e);
        }

        releaseSavepointQuietly(conn, savepoint);

        return rolledBack;
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
     * @throws SQLException if querying table metadata, reading its rows, or closing the metadata result set fails.
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

                    // JDBC allows a null TABLE_CAT (e.g. older PostgreSQL drivers report none even though the
                    // connection has a current catalog); only a reported catalog that differs rules the row out.
                    if (catalogInMetadata != null && !identifierMatches(catalog, catalogInMetadata, true)) {
                        continue;
                    }
                }

                return true;
            }

            return false;
        }
    }

    /**
     * Assembles a dotted {@code catalog.schema.table} name from the non-empty parts, optionally wrapping each part in
     * {@code identifierQuote} when identifier quoting is enabled. With quoting disabled, common keywords and names
     * outside the simple identifier grammar cause this optional SQL probe to be skipped.
     *
     * @param catalog The catalog part, or {@code null}/empty to omit.
     * @param schema The schema part, or {@code null}/empty to omit.
     * @param tableName The table name part; required.
     * @param identifierQuote The quote string to wrap each part in, or {@code null} to leave the parts unquoted. The parts
     *        are unquoted-safe identifiers, so they cannot contain the quote string.
     * @return The qualified name, or {@code null} if any present part cannot be embedded in SQL safely without quoting
     *         (so callers skip the SQL-based existence probe).
     */
    private static String buildSimpleQualifiedTableName(final String catalog, final String schema, final String tableName, final String identifierQuote) {
        // A probe must not reinterpret a keyword as a value expression (for example FROM USER). Skip it when the
        // unquoted rendering policy cannot preserve identifier semantics; JDBC metadata lookup remains available.
        if (!SqlIdentifierUtil.isIdentifierQuotingEnabled()
                && (!SqlIdentifierUtil.isUnquotedIdentifier(tableName) || (Strings.isNotEmpty(catalog) && !SqlIdentifierUtil.isUnquotedIdentifier(catalog))
                        || (Strings.isNotEmpty(schema) && !SqlIdentifierUtil.isUnquotedIdentifier(schema)))) {
            return null;
        }
        final String quote = SqlIdentifierUtil.isIdentifierQuotingEnabled() ? identifierQuote : null;

        if (!isUnquotedSafeIdentifier(tableName)) {
            return null;
        }

        final StringBuilder sb = new StringBuilder(64);

        if (Strings.isNotEmpty(catalog)) {
            if (!isUnquotedSafeIdentifier(catalog)) {
                return null;
            }

            sb.append(quote == null ? catalog : SqlIdentifierUtil.quoteIdentifier(catalog, quote)).append('.');
        }

        if (Strings.isNotEmpty(schema)) {
            if (!isUnquotedSafeIdentifier(schema)) {
                return null;
            }

            sb.append(quote == null ? schema : SqlIdentifierUtil.quoteIdentifier(schema, quote)).append('.');
        }

        sb.append(quote == null ? tableName : SqlIdentifierUtil.quoteIdentifier(tableName, quote));

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
    static String[] splitQualifiedSqlIdentifier(final String qualifiedName, final String argName) throws IllegalArgumentException {
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
    private static void addQualifiedIdentifierPart(final List<String> parts, final StringBuilder sb, final String qualifiedName, final String argName)
            throws IllegalArgumentException {
        final String part = SqlIdentifierUtil.stripIdentifierDelimiters(sb.toString());

        if (Strings.isEmpty(part)) {
            throw new IllegalArgumentException("Invalid " + argName + ": " + qualifiedName);
        }

        parts.add(part);
        sb.setLength(0);
    }

    /**
     * Converts a one-, two-, or three-part SQL identifier into a qualified identifier under the fixed quoting policy.
     * Quoting is disabled in this build: caller-delimited names and names requiring quotes are rejected. Ordinary
     * parts are folded to the database's storage case and emitted without delimiters. When quoting is enabled,
     * explicit parts retain their exact case and the connection's identifier delimiter is used where supported.
     *
     * @param conn The database {@link Connection} used to determine the identifier quote string.
     * @param qualifiedName The qualified identifier to convert (e.g., {@code schema.table}).
     * @param argName The argument name used in error messages.
     * @return The rendered qualified identifier.
     * @throws IllegalArgumentException if {@code conn} is {@code null}, {@code qualifiedName} is blank or otherwise invalid,
     *         or the name requires quoting while quoting is disabled.
     * @throws SQLException if reading the database identifier quote string or identifier storage-case rules fails.
     */
    static String toQualifiedSqlIdentifier(final Connection conn, final String qualifiedName, final String argName)
            throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(qualifiedName, argName);

        final String[] rawNameParts = splitQualifiedSqlIdentifier(qualifiedName, argName);
        final boolean[] explicitlyDelimited = SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(qualifiedName, rawNameParts.length);
        if (!SqlIdentifierUtil.isIdentifierQuotingEnabled()) {
            for (int i = 0; i < rawNameParts.length; i++) {
                SqlIdentifierUtil.checkExplicitDelimiters(rawNameParts[i], explicitlyDelimited[i]);
                SqlIdentifierUtil.renderStoredIdentifier(rawNameParts[i], null);
            }
        }
        final DatabaseMetaData metadata = conn.getMetaData();
        normalizeMetadataIdentifierParts(metadata, rawNameParts, explicitlyDelimited);
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
                // The driver's opening quote need not be symmetric: '[' closes with ']' and
                // embedded closing brackets must be doubled, just as in generated DML identifiers.
                sb.append(SqlIdentifierUtil.quoteIdentifier(part, identifierQuote));
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
     * @return {@code true} if the database reports a canonical (upper- or lower-case) identifier storage case, so every
     *         undelimited part has been folded to it; {@code false} if the parts were left unchanged
     * @throws SQLException if the driver cannot report its identifier storage rules
     */
    private static boolean normalizeMetadataIdentifierParts(final DatabaseMetaData metadata, final String[] parts, final boolean[] exactMatches)
            throws SQLException {
        final boolean upperCase = metadata.storesUpperCaseIdentifiers();
        final boolean lowerCase = !upperCase && metadata.storesLowerCaseIdentifiers();

        if (!upperCase && !lowerCase) {
            return false;
        }

        for (int i = 0; i < parts.length; i++) {
            if (!exactMatches[i]) {
                parts[i] = upperCase ? parts[i].toUpperCase(Locale.ROOT) : parts[i].toLowerCase(Locale.ROOT);
                exactMatches[i] = true;
            }
        }

        return true;
    }

    /**
     * Returns the database's identifier quote string, or {@code null} when quoting is disabled or the database reports none
     * ({@code null}, blank, or a space, meaning delimited identifiers are unsupported).
     *
     * @param metadata The database metadata to query.
     * @return The trimmed identifier quote string, or {@code null}.
     * @throws SQLException if the driver cannot report its identifier quote string.
     */
    private static String normalizeIdentifierQuote(final DatabaseMetaData metadata) throws SQLException {
        if (!SqlIdentifierUtil.isIdentifierQuotingEnabled()) {
            return null;
        }
        final String quoteString = metadata.getIdentifierQuoteString();
        final String normalized = quoteString == null ? null : quoteString.trim();

        return Strings.isEmpty(normalized) ? null : normalized;
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
     * @param schema The SQL DDL statement (typically {@code CREATE TABLE ...}) used to create the table; must not be {@code null}, empty, or blank.
     * @return {@code true} if this call created the table; {@code false} if the table already existed
     *         when checked, or was created concurrently while this call was running.
     * @throws IllegalArgumentException if {@code conn} is {@code null}, {@code tableName} is blank or otherwise invalid, or {@code schema} is
     *         {@code null}, empty, or blank; also if the table is absent and {@code schema} mixes parameter styles, has malformed placeholders,
     *         or requires parameter values that this method does not supply.
     * @throws UncheckedSQLException if checking whether the table exists fails, or the {@code CREATE} fails and the table still does
     *         not exist afterwards (or that re-check fails; its failure is attached to the {@code CREATE} failure as suppressed).
     */
    public static boolean createTableIfNotExists(final Connection conn, final String tableName, final String schema)
            throws IllegalArgumentException, UncheckedSQLException {
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotBlank(tableName, cs.tableName);
        N.checkArgNotBlank(schema, cs.schema);

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
            boolean createdConcurrently = false;

            try {
                createdConcurrently = tableExists(conn, tableName);
            } catch (final RuntimeException recheckFailure) {
                // The failed CREATE may have left the connection unusable (e.g., an aborted PostgreSQL
                // transaction); the re-check failure must not mask the CREATE failure itself.
                e.addSuppressed(recheckFailure);
            }

            if (createdConcurrently) {
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
     * error is wrapped and rethrown as {@link UncheckedSQLException}. Generated identifier quoting is disabled:
     * the name is folded to the database's canonical identifier case when reported and emitted without delimiters.
     * Explicitly delimited names and names requiring quotes are rejected before any {@code DROP} is issued.</p>
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
     * @throws IllegalArgumentException if {@code conn} is {@code null}, {@code tableName} is blank or otherwise invalid,
     *         or the table name requires quoting while quoting is disabled.
     * @throws UncheckedSQLException if reading the connection's identifier metadata or checking whether the table exists fails, or the
     *         {@code DROP} fails with an error other than "table not found".
     */
    public static boolean dropTableIfExists(final Connection conn, final String tableName) throws IllegalArgumentException, UncheckedSQLException {
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
                // Quoting makes the identifier case-exact while tableExists can match case-tolerantly
                // (upper-/lower-case variants when the driver reports no canonical identifier case),
                // so the quoted name may miss the table. Retry unquoted so the database applies its
                // own case folding.
                final String simpleTableName = SqlIdentifierUtil.isIdentifierQuotingEnabled() && !hasDelimitedIdentifierPart(tableName)
                        ? buildSimpleQualifiedName(tableName)
                        : null;

                if (simpleTableName != null && !simpleTableName.equals(sqlTableName) && tableExists(conn, tableName)) {
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
     *
     * @param qualifiedName The qualified identifier to simplify.
     * @return The unquoted dotted name, or {@code null} when a part cannot be embedded without quoting.
     * @throws IllegalArgumentException if {@code qualifiedName} is {@code null}, blank, or not a one- to three-part
     *         SQL identifier.
     */
    private static String buildSimpleQualifiedName(final String qualifiedName) throws IllegalArgumentException {
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
     *        Generated identifier quoting is disabled; use simple undelimited table-name parts.
     * @return A new {@link DBLock} instance bound to {@code ds} and {@code tableName}.
     * @throws IllegalArgumentException if {@code ds} is {@code null}, or if {@code tableName} is blank, otherwise invalid,
     *         or requires quoting.
     * @throws UncheckedSQLException if opening or configuring the initialization connection, reading metadata, creating or querying the
     *         lock table, deleting stale locks, or closing the initialization connection fails.
     * @throws IllegalStateException if the lock table still does not exist after the {@code CREATE TABLE} attempt.
     * @throws RejectedExecutionException if the shared scheduler refuses the periodic lock-refresh task.
     * @see DBLock
     */
    public static DBLock createDBLock(final javax.sql.DataSource ds, final String tableName)
            throws IllegalArgumentException, UncheckedSQLException, IllegalStateException, RejectedExecutionException {
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
     * Checks if the given value is a default/unset ID property value (null, zero for numbers, an {@code EntityId} whose values are
     * all default, or a bean/record whose ID properties are all default). A non-null bean/record that declares no ID property
     * is not a default ID value.
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
                // A value built by a custom generated-key extractor (e.g. record GenKey(long seq, String code)) has no
                // ID properties to inspect: it is a real key, not an unset one.
                return false;
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static ContinuableFuture<Void> runAsync(final Throwables.Runnable<Exception> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses one of the submitted tasks; an already accepted task keeps running.
     */
    @Beta
    public static Tuple2<ContinuableFuture<Void>, ContinuableFuture<Void>> runAsync(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2) throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses one of the submitted tasks; already accepted tasks keep running.
     */
    @Beta
    public static Tuple3<ContinuableFuture<Void>, ContinuableFuture<Void>, ContinuableFuture<Void>> runAsync(final Throwables.Runnable<Exception> sqlAction1,
            final Throwables.Runnable<Exception> sqlAction2, final Throwables.Runnable<Exception> sqlAction3)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static <T> ContinuableFuture<Void> runAsync(final T parameter, final Throwables.Consumer<? super T, Exception> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static <T, U> ContinuableFuture<Void> runAsync(final T parameter1, final U parameter2,
            final Throwables.BiConsumer<? super T, ? super U, Exception> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static <A, B, C> ContinuableFuture<Void> runAsync(final A parameter1, final B parameter2, final C parameter3,
            final Throwables.TriConsumer<? super A, ? super B, ? super C, Exception> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static <R> ContinuableFuture<R> callAsync(final Callable<? extends R> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses one of the submitted tasks; an already accepted task keeps running.
     */
    @Beta
    public static <R1, R2> Tuple2<ContinuableFuture<R1>, ContinuableFuture<R2>> callAsync(final Callable<? extends R1> sqlAction1,
            final Callable<? extends R2> sqlAction2) throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses one of the submitted tasks; already accepted tasks keep running.
     */
    @Beta
    public static <R1, R2, R3> Tuple3<ContinuableFuture<R1>, ContinuableFuture<R2>, ContinuableFuture<R3>> callAsync(final Callable<? extends R1> sqlAction1,
            final Callable<? extends R2> sqlAction2, final Callable<? extends R3> sqlAction3)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static <T, R> ContinuableFuture<R> callAsync(final T parameter, final Throwables.Function<? super T, ? extends R, Exception> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static <T, U, R> ContinuableFuture<R> callAsync(final T parameter1, final U parameter2,
            final Throwables.BiFunction<? super T, ? super U, ? extends R, Exception> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down (as happens during JVM shutdown).
     * @throws RejectedExecutionException if the shared asynchronous executor refuses the submitted task.
     */
    @Beta
    public static <A, B, C, R> ContinuableFuture<R> callAsync(final A parameter1, final B parameter2, final C parameter3,
            final Throwables.TriFunction<? super A, ? super B, ? super C, ? extends R, Exception> sqlAction)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException {
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
     * Returns the {@link PropInfo} for a nested, dot-separated property path (e.g., {@code address.street}) of
     * the given entity class, or {@code null} if the path does not resolve to a property. A name without
     * {@code '.'} always yields {@code null}: callers resolve simple names through {@link BeanInfo#getPropInfo(String)}.
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
     *     // Drain all results before reading output parameters for driver portability.
     *     while (stmt.getMoreResults() || stmt.getUpdateCount() != -1) {
     *         // Discard results that are not needed by this example.
     *     }
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
     * freed/closed automatically. A SQL {@code NULL} output value is returned as {@code null}, also for
     * numeric and boolean SQL types (never as {@code 0} or {@code false}).</p>
     *
     * @param stmt The {@link CallableStatement} from which to retrieve the output parameters; must not
     *             be {@code null}.
     * @param outParams The list of {@link OutParam} objects describing the output parameters to retrieve;
     *                  if {@code null} or empty, an empty {@link OutParamResult} is returned.
     * @return An {@link OutParamResult} containing the retrieved output parameter values keyed by
     *         parameter index (for index-based out params) or parameter name (for name-based out params).
     * @throws IllegalArgumentException if {@code stmt} is {@code null}, or {@code outParams} contains a {@code null} entry or an entry
     *         that has neither a positive {@code parameterIndex} nor a non-empty {@code parameterName} (checked before any parameter is read).
     * @throws SQLException if reading a registered output parameter or its result-set rows fails, or materializing or freeing a returned LOB fails or
     *         exceeds {@link Integer#MAX_VALUE} bytes/characters.
     */
    public static OutParamResult getOutParameters(final CallableStatement stmt, final List<OutParam> outParams) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(stmt, cs.stmt);

        if (N.isEmpty(outParams)) {
            return new OutParamResult(N.emptyList(), N.emptyMap());
        }

        for (final OutParam outParam : outParams) {
            N.checkArgNotNull(outParam, "outParams must not contain null entries");
            N.checkArgument(outParam.parameterIndex() > 0 || Strings.isNotEmpty(outParam.parameterName()),
                    "Each OutParam in outParams must have a positive parameterIndex or a non-empty parameterName, but was: %s", outParam);
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
     * Named parameters are placeholders written as {@code :paramName} or in iBatis/MyBatis style as
     * {@code #{paramName}}; SQL using only positional {@code ?} placeholders yields an empty list.
     * A name is listed once per occurrence, in order of appearance. Parameters are only detected in data-operation
     * statements ({@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code WITH}, {@code MERGE},
     * {@code CALL}, ...); any other SQL, such as DDL, yields an empty list.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE name = :name AND age > :age AND city = :city";
     * List<String> params = JdbcUtil.getNamedParameters(sql);
     * // Returns: ["name", "age", "city"]
     * }</pre>
     *
     * @param sql The SQL string containing named parameters (e.g., {@code :paramName} or {@code #{paramName}}).
     * @return A list of named parameter names found in the SQL string (without the {@code :} or {@code #{...}} decoration).
     * @throws IllegalArgumentException if {@code sql} is {@code null}, empty, or blank, or if it is a data-operation statement
     *         ({@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE}, ...) that mixes parameter styles ({@code ?},
     *         {@code :name}, {@code #{name}}) or contains a malformed parameter placeholder; other SQL, such as DDL, is not scanned.
     */
    public static List<String> getNamedParameters(final String sql) throws IllegalArgumentException {
        return ParsedSql.parse(sql).namedParameters();
    }

    /**
     * Parses the given SQL string and returns a ParsedSql object.
     * This method analyzes SQL statements to extract information about parameters and structure.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "SELECT * FROM users WHERE name = :name AND age > :age";
     * ParsedSql parsedSql = JdbcUtil.parseSql(sql);
     * List<String> namedParams = parsedSql.namedParameters();   // ["name", "age"]
     * String convertedSql = parsedSql.parameterizedSql();       // "SELECT * FROM users WHERE name = ? AND age > ?"
     * }</pre>
     *
     * @param sql The SQL string to be parsed.
     * @return A ParsedSql object containing parsed information about the SQL string.
     * @throws IllegalArgumentException if {@code sql} is {@code null}, empty, or blank, or if it is a data-operation statement
     *         ({@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE}, ...) that mixes parameter styles ({@code ?},
     *         {@code :name}, {@code #{name}}) or contains a malformed parameter placeholder; other SQL, such as DDL, is not scanned.
     * @see ParsedSql#parse(String)
     */
    public static ParsedSql parseSql(final String sql) throws IllegalArgumentException {
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
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a bean (entity or record) class.
     */
    public static Collection<String> getInsertPropNames(final Class<?> entityClass) throws IllegalArgumentException {
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
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a bean (entity or record) class.
     */
    public static Collection<String> getInsertPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) throws IllegalArgumentException {
        N.checkArgNotNull(entityClass, cs.entityClass);

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
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a bean (entity or record) class.
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass) throws IllegalArgumentException {
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
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a bean (entity or record) class.
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final Set<String> excludedPropNames) throws IllegalArgumentException {
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
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a bean (entity or record) class.
     */
    public static Collection<String> getSelectPropNames(final Class<?> entityClass, final boolean includeSubEntityProperties,
            final Set<String> excludedPropNames) throws IllegalArgumentException {
        N.checkArgNotNull(entityClass, cs.entityClass);

        return QueryUtil.selectPropNames(entityClass, includeSubEntityProperties, withJoinedByPropertiesExcluded(entityClass, excludedPropNames));
    }

    /**
     * Gets the property names suitable for UPDATE operations for the given entity class.
     * This method returns all property names that should be included in an UPDATE statement,
     * excluding properties marked with {@code @ReadOnly}, {@code @ReadOnlyId}, {@code @NonUpdatable},
     * {@link JoinedBy}, etc. A plain (writable) {@code @Id} property is <i>not</i> excluded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Collection<String> propNames = JdbcUtil.getUpdatePropNames(User.class);
     * // Returns property names that can be updated
     * }</pre>
     *
     * @param entityClass The entity class to analyze; must not be {@code null}.
     * @return A collection of property names suitable for UPDATE operations.
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a bean (entity or record) class.
     */
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass) throws IllegalArgumentException {
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
     * @throws IllegalArgumentException if {@code entityClass} is {@code null} or is not a bean (entity or record) class.
     */
    public static Collection<String> getUpdatePropNames(final Class<?> entityClass, final Set<String> excludedPropNames) throws IllegalArgumentException {
        N.checkArgNotNull(entityClass, cs.entityClass);

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
     * @throws SQLException if reading or freeing the Blob fails, or its length exceeds {@link Integer#MAX_VALUE}.
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
     * @param charset The character encoding to use for the conversion. Must not be {@code null}.
     * @return The String representation of the Blob content, or {@code null} if {@code blob} is {@code null}.
     * @throws IllegalArgumentException if {@code charset} is {@code null}.
     * @throws SQLException if reading or freeing the Blob fails, or its length exceeds {@link Integer#MAX_VALUE}.
     */
    public static String blobToString(final Blob blob, final Charset charset) throws IllegalArgumentException, SQLException {
        N.checkArgNotNull(charset, cs.charset);

        if (blob == null) {
            return null;
        }

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
     * @param output The File object representing the output file. Must not be {@code null}.
     * @return The number of bytes written to the file, or {@code 0} if {@code blob} is {@code null}.
     * @throws IllegalArgumentException if {@code output} is {@code null}, or if {@code blob} is not {@code null} and {@code output}
     *         denotes a directory.
     * @throws SQLException if obtaining the Blob stream or freeing the Blob fails.
     * @throws IOException if opening or writing the output file, reading the LOB data, or closing its stream or reader fails.
     */
    public static long writeBlobToFile(final Blob blob, final File output) throws IllegalArgumentException, SQLException, IOException {
        N.checkArgNotNull(output, cs.output);

        if (blob == null) {
            return 0;
        }

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
     * @throws SQLException if reading or freeing the Clob fails, or its length exceeds {@link Integer#MAX_VALUE}.
     */
    public static String clobToString(final Clob clob) throws SQLException {
        if (clob == null) {
            return null;
        }

        return materializeClob(clob);
    }

    /**
     * Writes the content of a Clob to a file and frees the Clob resources.
     * This method streams the Clob content directly to the specified file, encoded as UTF-8.
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
     * @param output The File object representing the output file. Must not be {@code null}.
     * @return The number of characters written to the file, or {@code 0} if {@code clob} is {@code null}.
     * @throws IllegalArgumentException if {@code output} is {@code null}, or if {@code clob} is not {@code null} and {@code output}
     *         denotes a directory.
     * @throws SQLException if obtaining the Clob reader or freeing the Clob fails.
     * @throws IOException if opening or writing the output file, reading the LOB data, or closing its stream or reader fails.
     */
    public static long writeClobToFile(final Clob clob, final File output) throws IllegalArgumentException, SQLException, IOException {
        N.checkArgNotNull(output, cs.output);

        if (clob == null) {
            return 0;
        }

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
     * Reference types (such as {@link String}, {@link Character}, or any collection) are only considered default when
     * {@code null}; an empty {@code String}, an empty collection, or a {@code '\0'} character is <em>not</em> default.
     * The exception is a type whose default value is an empty instance: an empty {@link java.util.Optional},
     * {@link java.util.OptionalInt} (and its siblings), or abacus {@code Optional}/{@code Nullable} <em>is</em> default.
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
     * <p>Unlike {@link Map#merge}, a key mapped to {@code null} counts as present, so a duplicate key still reaches
     * the remapping function (keeping {@code Fn.throwingMerger()} duplicate detection intact), and either argument
     * passed to it may be {@code null}. A {@code null} result is stored as the value rather than removing the key.</p>
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
     * A SQL log handler registered via {@link #setSqlLogHandler(TriConsumer)} is still invoked.
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
     * Once called, DAO method execution times will not be logged regardless of the
     * {@link com.landawn.abacus.jdbc.annotation.PerfLog#daoMethodPerfLogThresholdMillis() @PerfLog} thresholds.
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
     * The configured maximum SQL log length is left unchanged, but it is not reused on re-enabling:
     * {@link #enableSqlLog()} resets it to {@link #DEFAULT_MAX_SQL_LOG_LENGTH} and {@link #enableSqlLog(int)}
     * applies the given length.
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
     * Checks the current thread's SQL-logging flag. This does not check the global logging switch
     * or whether the configured logger accepts DEBUG messages.
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
     * <p>Invoked from a {@code finally} block right after the statement finishes:
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
     * Extracts the SQL text of a statement for logging. Returns an empty string if the extractor
     * throws an {@link Exception} or yields {@code null}, preserving the JDBC operation's outcome
     * in those cases. Errors are not caught.
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
     * The process-wide extractor supplies the SQL text of {@code [SQL-PERF]} log entries and of the
     * {@linkplain #setSqlLogHandler(TriConsumer) SQL log handler}; the standard SQL log enabled by
     * {@link #enableSqlLog()} logs the SQL string directly and does not use it.
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
     * <p>The extractor is process-wide (it applies to all threads) and is used only for {@code [SQL-PERF]}
     * log entries and the {@linkplain #setSqlLogHandler(TriConsumer) SQL log handler}. If it throws an
     * exception or returns {@code null}, an empty string is used instead and the statement's own outcome is
     * not affected.</p>
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
    public static void setSqlExtractor(final Throwables.Function<Statement, String, SQLException> sqlExtractor) throws IllegalArgumentException {
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
     * <p>The handler is process-wide and replaces any previously set handler. It is invoked on the executing
     * thread after each statement execution performed by this library's query objects (a batch counts as one
     * execution), whether the execution succeeded or failed, independently of the per-thread SQL log and
     * performance-log settings and of the global disable switches. The SQL text comes from the
     * {@linkplain #setSqlExtractor(Throwables.Function) SQL extractor}. A {@code RuntimeException} thrown by
     * the handler is logged and ignored. The handler must be thread-safe and fast, because it runs
     * synchronously for each statement.</p>
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
    public static void setSqlLogHandler(final TriConsumer<String, Long, Long> sqlLogHandler) throws IllegalArgumentException {
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
     * // Log SQL statements that take 1 second or longer, with longer log length
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
     * @return The minimum execution time in milliseconds (default is 1000ms); a negative value means SQL
     *         performance logging is disabled on the current thread.
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
     *     // Suppress the standard SQL log for this operation; performance logs and handlers remain active.
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
    public static <E extends Exception> void runWithSqlLogDisabled(final Throwables.Runnable<E> sqlAction) throws IllegalArgumentException, E {
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
     *     // Suppress the standard SQL log for this query; performance logs and handlers remain active.
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
    public static <R, E extends Exception> R callWithSqlLogDisabled(final Throwables.Callable<? extends R, E> sqlAction) throws IllegalArgumentException, E {
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
     *   <li>Spring-managed transactions that own the connection bound to {@code ds}, when Spring is on the classpath and
     *       Spring transaction participation is neither disabled on this thread (see
     *       {@link #runIgnoringSpringTransaction(Throwables.Runnable)}) nor suspended for {@code ds} (see
     *       {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}), unless the transaction has started to
     *       complete, as far as that can be recognized (see {@link #beginTransaction(javax.sql.DataSource)}).</li>
     * </ul>
     *
     * <p>A Spring-managed transaction owns the connection bound to {@code ds} on this thread when the bound connection has
     * auto-commit off and its connection holder belongs to a transaction (also when the transaction manager's transaction
     * synchronization is disabled):
     * {@code DataSourceTransactionManager} and {@code JdbcTransactionManager} mark the holder they bind transaction-active,
     * and JPA/Hibernate transaction managers that expose their JDBC connection bind a holder with a {@code ConnectionHandle}
     * of their own. A connection Spring binds merely to synchronize it with the thread - in a non-transactional scope such as
     * {@code SUPPORTS} without a transaction, or while a transaction runs on another data source - is not a transaction. For a
     * Spring {@code TransactionAwareDataSourceProxy}, the binding of its target data source is checked. JTA transactions are
     * not detected: their data sources' connections are bound the same way as for mere synchronization.</p>
     *
     * <p>The Spring check reads Spring's thread-bound state and the bound connection's auto-commit mode; it does not acquire
     * a connection. A {@link LinkageError} from a mismatched Spring classpath is handled silently.</p>
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
     * @throws IllegalArgumentException if {@code ds} is {@code null}.
     * @throws IllegalStateException if the ownership of a Spring connection holder bound to {@code ds} has to be determined
     *         but its {@code isTransactionActive()} flag is not accessible to this library.
     * @throws UncheckedSQLException if the auto-commit mode of a connection Spring bound to {@code ds} cannot be read.
     * @see SqlTransaction
     */
    public static boolean isInTransaction(final javax.sql.DataSource ds) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException {
        N.checkArgNotNull(ds, cs.ds);

        if (TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL) != null) {
            return true;
        }

        return isSpringTransactionalUsed(ds) && isOwnedBySpringTransaction(ds);
    }

    /**
     * Begins a new database transaction with the default isolation level for the specified DataSource.
     * All subsequent JDBC operations in the same thread using this DataSource will participate in this transaction
     * until it is committed or rolled back. This provides ACID guarantees for multi-statement operations.
     *
     * <p>See the {@link SqlTransaction} class documentation for how transactions work in this library: thread and data
     * source binding, which operations enlist, nested scopes, DAO {@code @Transactional}
     * propagation, Spring-managed transactions, and rollback actions.</p>
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
     * <p>If a transaction for {@code ds} is already active on the current thread, no new transaction is started:
     * this call joins it as a nested scope and returns a new {@code SqlTransaction} handle for that scope. A nested scope's
     * {@code commit()} only exits the scope; the connection is committed when the outermost scope commits.</p>
     *
     * <p><b>Spring Integration:</b></p>
     * <p>Connection acquisition uses {@link #getConnection(javax.sql.DataSource)}, which can return a
     * Spring-bound connection. If no {@code JdbcUtil} transaction exists for {@code ds} on the current thread and a
     * Spring-managed transaction owns the connection bound to {@code ds} (the rule is described on
     * {@link #isInTransaction(javax.sql.DataSource)}; Spring participation must be neither disabled on this thread, see
     * {@link #runIgnoringSpringTransaction(Throwables.Runnable)}, nor suspended for {@code ds}, see
     * {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}), the returned transaction participates in the Spring
     * transaction: it runs on Spring's connection without changing its auto-commit mode or isolation level (a requested
     * isolation level is ignored), its outermost {@code commit()} does not commit the connection (Spring commits the work
     * when its transaction completes), and its rollback - including a failed scope - does not roll back the connection but
     * marks the Spring transaction rollback-only, so Spring rolls everything back when it completes. Inside a
     * {@code PROPAGATION_NESTED} scope the mark applies to the whole Spring transaction, not only to the nested savepoint
     * scope (as for a Spring participating scope that fails inside a nested one): the nested scope's commit then rolls
     * back to its savepoint and fails with {@code UnexpectedRollbackException}, which rolls the enclosing transaction back
     * too unless its code handles it (with {@code DataSourceTransactionManager} or {@code JdbcTransactionManager}, the rollback
     * to the savepoint clears the mark, so the enclosing transaction can still commit then; with {@code JpaTransactionManager}
     * it can't, as the mark set on the JPA transaction stays and its commit rolls back). The rollback actions of the work on {@code ds} since the newest open savepoint (the innermost nested
     * scope, which can only roll back) run when the mark is set: the IDs of its inserts are put back right away, while those
     * of the enclosing transaction's earlier inserts follow the transaction's outcome - kept if it commits their rows, put
     * back when it completes rolled back. Outside any savepoint scope, the actions of all work on {@code ds} run when the
     * mark is set. A savepoint is released without notice: a mark of a nested scope that already committed lingers until
     * its savepoint object has been garbage collected, and a participating rollback in the enclosing scope meanwhile puts
     * back the IDs registered since that mark right away and the earlier ones at transaction completion. The mark is set on the
     * connection holder ({@code DataSourceTransactionManager}, {@code JdbcTransactionManager}) and on the JPA transaction
     * identified as the owner of the connection ({@code JpaTransactionManager}: by its entity manager factory's data source,
     * or by the entity manager the connection's handle keeps; if the JPA provider fails to mark it, which can't be checked in
     * advance, the rollback fails with an {@code IllegalStateException} and the Spring transaction decides the outcome, see
     * {@link SqlTransaction#rollbackIfNotCommitted()}); a Spring transaction that neither mark
     * would reach (its transaction manager exposes the connection through a handle of its own, and no such JPA transaction can
     * be identified) is refused with an {@code IllegalStateException} before any work runs. Nested scopes and
     * queries on {@code ds} on this thread join the participating transaction as usual. A connection Spring binds merely
     * for synchronization (for example in a {@code SUPPORTS} scope without a transaction) is not a transaction: the
     * returned transaction then commits and rolls back that connection itself.</p>
     *
     * <p>Opening a scope on a transaction-owned Spring connection requires {@link SpringTransactionTracker}, registered
     * with its manager before the transaction begins. The recorded owner identifies whether this scope participates or
     * starts independently after completion begins. Missing tracking is rejected before acquiring another connection;
     * synchronization-only bindings and connections outside Spring do not require it. This requirement applies to scopes,
     * including DAO methods that open them, not ordinary SQL execution or scope-free query creation.</p>
     *
     * <p>Participating scopes and their associated queries follow their captured owner's lifetime, including disabled
     * synchronization, late first use during {@code beforeCompletion}, and another manager's inner transaction. Once the
     * owner starts completion, a new scope uses an independent connection outside Spring; an existing participating scope
     * cannot commit, roll back or execute its queries. Spring {@code PROPAGATION_REQUIRES_NEW} and
     * {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)} also provide independent callback work.</p>
     *
     * <p>While a participating transaction is open, Spring may bind another transaction to {@code ds} (a
     * {@code REQUIRES_NEW} scope) or none (it suspends its transaction for a {@code NOT_SUPPORTED} scope):
     * until Spring binds the participated transaction again, operations and {@code beginTransaction} calls on {@code ds}
     * follow Spring's binding instead of joining the participating transaction (a transaction begun then is a transaction of
     * its own, which participates in the new Spring transaction if one owns {@code ds}). See {@link SqlTransaction}.</p>
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
     *         writeOrderAudit(order);   // Database writes use the same DataSource and transaction
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
     * @return The {@link SqlTransaction} handle of the scope this call opened (the outermost scope of a new transaction, or a nested scope of
     *         the current one), which must be committed or rolled back.
     * @throws IllegalArgumentException if {@code ds} is {@code null}.
     * @throws IllegalStateException if the ownership of a
     *         Spring connection holder bound to {@code ds} cannot be determined, or if the Spring-managed transaction that owns {@code ds} exposes
     *         the connection through a handle of its own but no JPA transaction owning it can be identified (a rollback could not reach it).
     * @throws UncheckedSQLException if acquiring a connection, reading its original state, disabling auto-commit, or applying the isolation level fails.
     * @see SqlTransaction
     * @see #beginTransaction(javax.sql.DataSource, IsolationLevel)
     * @see SqlTransaction#commit()
     * @see SqlTransaction#rollback()
     * @see SqlTransaction#rollbackIfNotCommitted()
     */
    public static SqlTransaction beginTransaction(final javax.sql.DataSource ds) throws IllegalArgumentException, IllegalStateException, UncheckedSQLException {
        return beginTransaction(ds, IsolationLevel.DEFAULT);
    }

    /**
     * Begins a new transaction with the specified isolation level for the given DataSource.
     * The transaction must be explicitly committed or rolled back.
     *
     * <p>If a transaction for {@code ds} is already active on the current thread, this call joins it as a nested
     * scope and returns a new {@code SqlTransaction} handle for it; a non-{@link IsolationLevel#DEFAULT} level is applied to
     * the shared connection until the nested scope exits, when the enclosing scope's level is restored. Inside a
     * Spring-managed transaction the returned transaction participates in it, and {@code isolationLevel} is ignored (see
     * {@link #beginTransaction(javax.sql.DataSource)}).</p>
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
     * @return The {@link SqlTransaction} handle of the scope this call opened.
     * @throws IllegalArgumentException if {@code ds} or {@code isolationLevel} is {@code null}, or if
     *         {@code isolationLevel} is {@link IsolationLevel#NONE}, which is not a usable transaction isolation level.
     * @throws IllegalStateException if the ownership of a
     *         Spring connection holder bound to {@code ds} cannot be determined, or if the Spring-managed transaction that owns {@code ds} exposes
     *         the connection through a handle of its own but no JPA transaction owning it can be identified (a rollback could not reach it).
     * @throws UncheckedSQLException if acquiring a connection, reading its original state, disabling auto-commit, or applying the isolation level fails.
     * @see SqlTransaction
     */
    @SuppressWarnings("deprecation")
    public static SqlTransaction beginTransaction(final javax.sql.DataSource ds, final IsolationLevel isolationLevel)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(isolationLevel, cs.isolationLevel);
        N.checkArgument(isolationLevel != IsolationLevel.NONE,
                "'isolationLevel' must not be NONE because Connection.TRANSACTION_NONE is not a usable transaction isolation level");

        TransactionContext context = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);
        final SqlTransaction scope;

        if (context == null) {
            // Opening an outermost scope on the connection of a Spring-managed transaction would commit (or roll back) Spring's
            // connection when the scope completes: participate in the Spring transaction instead.
            final boolean springBound = isSpringTransactionalUsed(ds) && boundSpringTransactionHolder(ds) != null;
            // Unless the completion of that transaction has started (see isOwnedBySpringTransaction): a transaction of its own
            // then, on a connection of its own.
            // A JPA provider may already have restored auto-commit while its completed holder remains bound. That connection
            // must still be left to Spring: a local transaction started on it would not be independent of Spring's connection
            // and cleanup.
            final SpringTransactionOwners.Owner springOwner = springBound ? SpringTransactionOwners.require(boundSpringResource(ds)) : null;
            final boolean afterSpringCompletion = springOwner != null && springOwner.isDeciding();
            final boolean springParticipating = springBound && !afterSpringCompletion && isBoundToSpringTransaction(ds);

            if (springParticipating && !SpringRollbackActions.canMarkRollbackOnly(unwrapSpringDataSource(ds))) {
                // A rollback of this scope reaches the Spring transaction only through a rollback-only mark it consults: without
                // one, Spring would commit the work rolled back here. Refused before any work runs.
                throw new IllegalStateException("Cannot begin a transaction that participates in the Spring-managed transaction holding the connection"
                        + " of the data source: its transaction manager exposes the connection through a handle of its own, and no active JPA"
                        + " transaction owning the connection can be identified (by an entity manager factory that reports the data source, or by"
                        + " a connection handle that keeps the entity manager), so a rollback here could not make the Spring transaction roll back");
            }

            // WHY a connection of its own after the completion has started: Spring's connection belongs to the transaction Spring
            // binds to ds, which need not be the one completing (see isSpringTransactionCompleting): committing it there committed
            // that transaction's work, which it could still roll back. As PROPAGATION_REQUIRES_NEW, which Spring advises for
            // transactional work started from its completion callbacks, would.
            final boolean outsideSpring = afterSpringCompletion && suspendSpringTransactional(ds);

            Connection conn = null;
            SqlTransaction outermostScope = null;

            try { //NOSONAR
                conn = JdbcUtil.getConnection(ds);
                context = new TransactionContext(ds, conn, isolationLevel, CreatedBy.JDBC_UTIL, true, springParticipating); //NOSONAR
                outermostScope = context.enterScope(isolationLevel);
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            } finally {
                try {
                    if (outermostScope == null) {
                        if (context != null) {
                            // Constructor succeeded (so conn may have been switched to autoCommit=false / the requested
                            // isolation, unless it already matched) but opening the outermost scope failed: restore
                            // the connection's original state via the context's reset path before it goes back to the pool.
                            context.resetAndCloseConnection();
                        } else {
                            JdbcUtil.releaseConnection(conn, ds);
                        }
                    }
                } finally {
                    if (outsideSpring) {
                        resumeSpringTransactional(ds);
                    }
                }
            }

            logger.debug("Created new SqlTransaction(id={})", context.id());
            TransactionContext.putTransaction(context);
            scope = outermostScope;
        } else {
            logger.debug("Reusing existing SqlTransaction(id={})", context.id());
            scope = context.enterScope(isolationLevel);
        }

        return scope;
    }

    /**
     * Returns whether a transaction begun on {@code ds} now has to participate in a Spring-managed transaction that owns
     * {@code ds}, instead of completing the connection itself: Spring participation is enabled for {@code ds} on this thread
     * (see {@link #runIgnoringSpringTransaction(Throwables.Runnable)} and
     * {@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}), no {@code JdbcUtil} transaction is active for {@code ds},
     * and a Spring-managed transaction owns the connection bound to {@code ds} (see {@link #isInTransaction(javax.sql.DataSource)}).
     *
     * @param ds the data source the work runs on
     * @return {@code true} if a Spring-managed transaction owns the connection bound to {@code ds}
     * @throws IllegalStateException if the ownership of a Spring connection holder bound to {@code ds} cannot be determined
     * @throws UncheckedSQLException if the auto-commit mode of the bound connection cannot be read
     */
    static boolean isInSpringManagedTransaction(final javax.sql.DataSource ds) throws IllegalStateException, UncheckedSQLException {
        return isSpringTransactionalUsed(ds) && TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL) == null && isOwnedBySpringTransaction(ds);
    }

    /**
     * Returns whether a Spring-managed transaction owns the connection Spring bound to {@code ds} (or, for a
     * {@code TransactionAwareDataSourceProxy}, to its target data source) on the current thread. Callers check first that
     * Spring is on the class path and its participation is enabled on this thread.
     *
     * @param ds the data source
     * @return {@code true} if the holder bound to the data source belongs to a transaction and its connection has
     *         auto-commit off
     * @throws IllegalStateException if the ownership of the bound connection holder cannot be determined
     * @throws UncheckedSQLException if the auto-commit mode of the bound connection cannot be read
     */
    private static boolean isOwnedBySpringTransaction(final javax.sql.DataSource ds) throws IllegalStateException, UncheckedSQLException {
        // Once the transaction has started to complete (it has committed or rolled back), work joining it ran after the fact: a
        // participating scope's rollback-only mark had no effect any more, and Spring's cleanup, restoring auto-commit, committed
        // the work, also that of a failed scope. Spring advises a transaction of its own for transactional work started from
        // its completion callbacks. Checked only for a bound transaction: it reads the synchronizations (a sorted copy), on
        // every DAO insert and isInTransaction call.
        return isBoundToSpringTransaction(ds) && !hasSpringCompletionStarted(ds);
    }

    /**
     * Returns whether the completion of the Spring-managed transaction that owns the connection holder bound to {@code ds}
     * has started: as its transaction manager reports it to a {@link SpringTransactionTracker}, if one is registered with it
     * (the transaction that owns the holder, whatever synchronizations Spring runs meanwhile), or else see
     * {@link #isSpringTransactionCompleting()} (the innermost transaction on the thread). WHY the owner: the innermost
     * transaction need not be the one owning {@code ds}, and the owner's {@code afterCommit} callbacks went unrecognized if this
     * library had used it only inside another transaction manager's transaction begun in it.
     *
     * @param ds the data source
     * @return {@code true} if the transaction's completion has started
     */
    private static boolean hasSpringCompletionStarted(final javax.sql.DataSource ds) {
        final SpringTransactionOwners.Owner owner = SpringTransactionOwners.of(boundSpringResource(ds));

        return owner != null ? owner.isDeciding() : isSpringTransactionCompleting();
    }

    /**
     * Returns whether a Spring-managed transaction owns the connection Spring bound to {@code ds} (or to the target of a
     * {@code TransactionAwareDataSourceProxy}), whether or not its completion has started (see
     * {@link #isOwnedBySpringTransaction(javax.sql.DataSource)}).
     *
     * @param ds the data source
     * @return {@code true} if the holder bound to the data source belongs to a transaction and its connection has
     *         auto-commit off
     * @throws IllegalStateException if the ownership of the bound connection holder cannot be determined
     * @throws UncheckedSQLException if the auto-commit mode of the bound connection cannot be read
     */
    private static boolean isBoundToSpringTransaction(final javax.sql.DataSource ds) throws IllegalStateException, UncheckedSQLException {
        final Object holder = boundSpringTransactionHolder(ds);

        if (holder == null) {
            return false;
        }

        // WHY outside the linkage catch in boundSpringTransactionHolder: an ORM's connection handle runs provider code.
        // Its LinkageError must propagate rather than allow a scope to take Spring's connection as one of its own.
        final Connection conn = SpringConnectionHolderAccess.connectionOf(holder);

        try {
            // A transaction keeps auto-commit off. With auto-commit on, the work's own transaction keeps it atomic.
            return !conn.getAutoCommit();
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Returns the holder a Spring transaction manager bound to {@code ds}, even if its provider has already restored
     * auto-commit during completion. A synchronization-only holder does not represent a transaction.
     *
     * @param ds the data source
     * @return its transaction holder, or {@code null} when none is bound
     */
    private static Object boundSpringTransactionHolder(final javax.sql.DataSource ds) {
        try {
            // A connection holder bound to ds doesn't prove that a transaction owns ds: Spring also binds one merely to
            // synchronize the connection with the thread, which SpringConnectionHolderAccess tells apart. Spring's "actual
            // transaction active" flag is not required: a transaction manager whose transaction synchronization is disabled
            // still binds its holder but never sets the flag, and ignoring its transaction made a JdbcUtil scope commit Spring's
            // connection, including the enclosing transaction's earlier work.

            // DataSourceTransactionManager binds its holder under the target of a TransactionAwareDataSourceProxy, whose
            // connections delegate to that binding.
            final Object resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(unwrapSpringDataSource(ds));

            if (!(resource instanceof final org.springframework.jdbc.datasource.ConnectionHolder ownedHolder)
                    || !SpringConnectionHolderAccess.isOwnedByTransaction(ownedHolder)) {
                return null;
            }

            return ownedHolder;
        } catch (final LinkageError e) {
            // Spring's transaction classes failing to link (a mismatched installation): no Spring transaction can be seen.
            return null;
        }
    }

    /**
     * Returns whether the completion of the Spring-managed transaction of the current thread has started: in its
     * {@code afterCompletion} callbacks Spring has cleared the transaction synchronizations, while the transaction is still
     * marked active; its {@code afterCommit} callbacks are seen by the synchronization this library registers with the
     * synchronizations Spring runs when it takes a connection from Spring or begins to participate in a transaction (see
     * {@code SpringRollbackActions}), which runs before those of a lower precedence. A transaction this library used only while
     * another transaction manager's transaction ran inside it (whose own synchronizations Spring ran meanwhile) has no such
     * synchronization: its {@code afterCommit} callbacks are not recognized, as no Spring API reaches its suspended
     * synchronizations.
     * It is the innermost transaction on the thread, the only one whose synchronizations Spring exposes: in the completion
     * callbacks of a transaction that another transaction manager began inside the transaction owning a data source, that
     * one counts as completing too, although it still runs.
     *
     * @return {@code true} if the transaction's completion has started
     */
    private static boolean isSpringTransactionCompleting() {
        try {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            }

            final SpringRollbackActions synchronization = SpringRollbackActions.registered();

            return synchronization != null && synchronization.completing;
        } catch (final LinkageError e) {
            return false;
        }
    }

    /**
     * Unwraps Spring {@code TransactionAwareDataSourceProxy} layers: Spring's transaction managers bind their connection
     * holder under the proxy's target data source.
     *
     * @param ds the data source; must only be called when Spring JDBC is on the class path
     * @return the innermost target data source, or {@code ds} if it is not such a proxy
     */
    private static javax.sql.DataSource unwrapSpringDataSource(final javax.sql.DataSource ds) {
        javax.sql.DataSource result = ds;

        while (result instanceof final org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy proxy) {
            final javax.sql.DataSource target = proxy.getTargetDataSource();

            if (target == null || target == result) {
                break;
            }

            result = target;
        }

        return result;
    }

    /**
     * Marks the Spring-managed transaction that owns {@code ds} rollback-only, so that Spring rolls it back when it
     * completes (a commit attempt then fails with {@code UnexpectedRollbackException}). Marks the connection holder bound to
     * {@code ds}, which {@code DataSourceTransactionManager} and {@code JdbcTransactionManager} consult; the JPA transaction
     * of the {@code JpaTransactionManager} that exposes the connection, which it consults instead. A transaction for which
     * neither mark works is refused participation by {@link #beginTransaction(javax.sql.DataSource)}.
     * Returns normally only once every transaction that decides the outcome of the work is marked: the Spring-managed
     * transaction can then only end rolled back. If this method throws, Spring may still commit the work, so the caller must
     * not treat the rollback as done, and must leave the undo of the in-memory changes tied to the work to Spring's outcome.
     *
     * <p>Once marked, the rollback actions registered with Spring's transaction synchronization for work on {@code ds} run
     * right away (see {@link #registerSpringRollbackAction(javax.sql.DataSource, Runnable)}); the first {@link Error} one of
     * them throws is returned, for the caller to throw once it has completed the scope.</p>
     *
     * @param ds the data source of the participating transaction
     * @return the first {@code Error} thrown by a rollback action run right away (later ones attached to it), or {@code null}
     * @throws IllegalStateException if the mark does not reach every transaction that decides the outcome of the work: no
     *         connection holder is bound to {@code ds} any more (its transaction was suspended or has completed), or no active
     *         JPA transaction owning the connection can be identified any more, or one of them refuses the mark (its failure
     *         is the cause; the others are marked all the same)
     * @throws LinkageError if Spring's transaction classes cannot be loaded or linked (logged first): the transaction is not
     *         marked
     */
    static Error markSpringTransactionRollbackOnly(final javax.sql.DataSource ds) throws IllegalStateException, LinkageError {
        // The status of the current declarative (@Transactional) transaction is not marked: it is the innermost declarative
        // transaction, which needn't be the one that owns ds (e.g. a programmatic transaction of another transaction manager
        // started inside it), and the marks set here reach the owner.
        try {
            return SpringRollbackActions.markRollbackOnly(unwrapSpringDataSource(ds));
        } catch (final LinkageError e) {
            // Swallowed, the error made a rollback that marked nothing look complete: the generated IDs were put back although
            // Spring may commit the rows. Propagate it so that SqlTransaction leaves the undo to Spring's outcome.
            logger.warn("Cannot mark the Spring-managed transaction rollback-only: {}", e.toString());
            throw e;
        }
    }

    /**
     * Marks the Spring-managed transaction whose connection holder is {@code participatedResource} rollback-only while Spring
     * binds another resource to its data source, or none: Spring runs another transaction on the data source
     * ({@code REQUIRES_NEW}) or none ({@code NOT_SUPPORTED}), and resumes the suspended one later. The holder of
     * a {@code DataSourceTransactionManager} or {@code JdbcTransactionManager} transaction is marked, which it consults when it
     * completes. The rollback actions are left to its outcome: its synchronizations are suspended with it, and run when it
     * completes rolled back.
     *
     * @param participatedResource the connection holder of the transaction participated in
     * @throws IllegalStateException if that transaction can't be marked: it has completed, or it exposes the connection
     *         through a handle of its own (a JPA transaction, which is suspended with its entity manager), so it may still
     *         commit the work
     * @throws LinkageError if Spring's transaction classes cannot be loaded or linked: the transaction is not marked
     */
    static void markUnboundSpringTransactionRollbackOnly(final Object participatedResource) throws IllegalStateException, LinkageError {
        try {
            SpringRollbackActions.markUnboundRollbackOnly(participatedResource);
        } catch (final LinkageError e) {
            // As in markSpringTransactionRollbackOnly: propagated, so that SqlTransaction leaves the undo to Spring's outcome.
            logger.warn("Cannot mark the Spring-managed transaction rollback-only: {}", e.toString());
            throw e;
        }
    }

    /**
     * Returns this library's synchronization registered with the synchronizations Spring runs on the current thread, registering
     * it first if needed, for a {@code SqlTransaction} that begins to participate in the Spring-managed transaction: it records
     * that the completion of the transaction runs (see {@link #hasSpringSynchronizationCompletionStarted(Object)}).
     *
     * @return the synchronization, as an {@code Object} (no Spring type in the members of the caller), or {@code null} if
     *         Spring's transaction synchronization is not active
     */
    static Object participationSynchronization() {
        try {
            return SpringRollbackActions.current();
        } catch (final LinkageError e) {
            return null;
        }
    }

    /**
     * Returns whether an action synchronization has observed completion. Scope lifecycle checks use the recorded owner;
     * this observation does not impose any statement-level SQL restriction.
     *
     * @param synchronization a synchronization from {@link #participationSynchronization()}, or {@code null}
     * @return whether its completion callback has started
     */
    static boolean hasSpringSynchronizationCompletionStarted(final Object synchronization) {
        // Null checked first: the instanceof would load SpringRollbackActions, which needs Spring.
        return synchronization != null && synchronization instanceof final SpringRollbackActions actions && actions.completing;
    }

    /**
     * Returns the connection holder Spring binds to {@code ds} (to the target of a {@code TransactionAwareDataSourceProxy}) for
     * the Spring-managed transaction that owns its connection, if that transaction records its completion on it: the holder of
     * a {@code DataSourceTransactionManager} or {@code JdbcTransactionManager} transaction, which is no longer
     * transaction-active once the transaction has completed (see {@link #hasSpringTransactionCompleted(Object)}), whatever
     * transaction's synchronizations Spring runs meanwhile, and whether or not they are active.
     *
     * @param ds the data source
     * @return the holder, as an {@code Object}, or {@code null} if no transaction owns a connection holder bound to {@code ds},
     *         or its completion can't be read from it (a JPA transaction, whose holder has a handle of its own)
     */
    static Object springResourceRecordingCompletion(final javax.sql.DataSource ds) {
        if (!isInSpring) {
            return null;
        }

        try {
            return SpringRollbackActions.resourceRecordingCompletion(unwrapSpringDataSource(ds));
        } catch (final RuntimeException | LinkageError e) {
            // The holder's flag can't be read: its completion can't be followed.
            return null;
        }
    }

    /**
     * Returns whether the Spring-managed transaction whose connection holder is {@code participatedResource} has completed:
     * {@code DataSourceTransactionManager} and {@code JdbcTransactionManager} clear the transaction-active flag of their holder
     * when they clean up after the transaction, while a suspended transaction's holder keeps it. The completion of a
     * transaction that exposes its connection through a handle of its own (a JPA transaction) can't be told this way: it
     * counts as not completed.
     *
     * @param participatedResource the connection holder of the transaction a {@code SqlTransaction} participates in
     * @return {@code true} if that transaction is known to have completed
     */
    static boolean hasSpringTransactionCompleted(final Object participatedResource) {
        if (!isInSpring) {
            return false;
        }

        try {
            return SpringRollbackActions.hasCompleted(participatedResource);
        } catch (final RuntimeException | LinkageError e) {
            // The holder's flag can't be read: the outcome is unknown, so the transaction is not taken for completed.
            return false;
        }
    }

    /**
     * Registers {@code action} to run if the Spring-managed transaction of the current thread is rolled back. All actions of
     * one Spring transaction share one synchronization and run newest first (see {@link RollbackActionLog}); those of work on
     * {@code ds} run as soon as a participating transaction marks the transaction that owns {@code ds} rollback-only.
     *
     * <p>The synchronization is that of the innermost Spring transaction on the thread, the only one Spring exposes: no public
     * Spring API tells which transaction owns a bound connection, and the owner's synchronizations are suspended while
     * another transaction manager's transaction runs inside it. For work on the data source of such an enclosing transaction,
     * the action therefore follows the inner transaction's outcome (unless the work is marked rollback-only first, which runs
     * it right away): it runs if the inner transaction rolls back, even if the enclosing one then commits the work. A rollback
     * to a savepoint ({@code PROPAGATION_NESTED}) runs the actions registered since the savepoint was created, with Spring 6.2
     * or later (older versions report no savepoints to their synchronizations: the actions then follow the transaction's
     * outcome, and a participating rollback leaves them to it as well instead of running them right away). Spring reports the
     * rollback before performing it and does not report its failure, so the actions have run even if rolling back to the
     * savepoint then fails. A participating {@code SqlTransaction} rolled back inside a
     * {@code PROPAGATION_NESTED} scope marks the whole Spring transaction rollback-only (see
     * {@link #beginTransaction(javax.sql.DataSource)}), but only the actions of work on {@code ds} registered since the
     * newest open savepoint run right away - the nested scope can only roll back - while those registered before it are left
     * to the transaction's outcome: they run when the transaction completes rolled back (or on a later rollback to their
     * savepoint), and stay if the enclosing transaction handles the nested scope's {@code UnexpectedRollbackException} and
     * commits (Spring's rollback to the savepoint clears the mark; not that of a {@code JpaTransactionManager}'s JPA
     * transaction, which can't commit then). Spring reports no savepoint release, so the mark of a
     * nested scope that already committed lingers until its savepoint object has been garbage collected: a participating
     * rollback in the enclosing scope meanwhile runs the actions registered since that mark right away and the earlier
     * ones at transaction completion. Without an open savepoint, all actions of work on {@code ds} run right away.</p>
     *
     * @param ds the data source of the work the action undoes
     * @param action the action to run after a Spring rollback
     * @return {@code true} if it was registered, {@code false} if Spring's transaction synchronization is not active (or
     *         Spring is not available)
     */
    static boolean registerSpringRollbackAction(final javax.sql.DataSource ds, final Runnable action) {
        if (!isInSpring) {
            return false;
        }

        try {
            return SpringRollbackActions.register(unwrapSpringDataSource(ds), action);
        } catch (final LinkageError e) {
            return false;
        }
    }

    /**
     * A method of a given name, resolved once per class by a lookup and remembered with the class: a class for which the
     * lookup finds none is remembered too, so later calls fail without looking again. A {@link ClassValue} rather than a
     * static map keyed by class: the entry is released with the class, while a static map pinned the class (and, through
     * the cached {@link Method}, its declaring class) for the JVM's life, which in a shared-classloader container kept
     * every redeployed application or provider class alive. Spring-free, so {@code JdbcUtil} stays loadable without
     * Spring; used by {@code SpringRollbackActions}.
     */
    static final class MethodValue extends ClassValue<java.util.Optional<Method>> {
        private final String name;
        private final Throwables.Function<Class<?>, Method, NoSuchMethodException> lookup;

        /**
         * Resolves the public method {@code name} (without parameters) a class declares or inherits.
         *
         * @param name the method name
         */
        MethodValue(final String name) {
            this(name, type -> type.getMethod(name));
        }

        /**
         * Resolves a method with a custom lookup.
         *
         * @param name the method name, for the exception message
         * @param lookup resolves the method of a class, or throws {@link NoSuchMethodException} if the class has none; the
         *               value is computed from the class alone, so the lookup must derive anything else it needs from it
         */
        MethodValue(final String name, final Throwables.Function<Class<?>, Method, NoSuchMethodException> lookup) {
            this.name = name;
            this.lookup = lookup;
        }

        @Override
        protected java.util.Optional<Method> computeValue(final Class<?> type) {
            try {
                return java.util.Optional.of(lookup.apply(type));
            } catch (final NoSuchMethodException e) {
                return java.util.Optional.empty();
            }
        }

        /**
         * Returns the method for {@code type}, resolved on the first call for that class.
         *
         * @param type the class the method is resolved for
         * @return the method
         * @throws NoSuchMethodException if {@code type} has no such method (also when that was already remembered)
         */
        Method methodOf(final Class<?> type) throws NoSuchMethodException {
            final java.util.Optional<Method> method = get(type);

            if (method.isEmpty()) {
                throw new NoSuchMethodException("No public method " + name + "() on " + type.getName());
            }

            return method.get();
        }
    }

    /**
     * The rollback actions registered with one Spring-managed transaction, run newest first if Spring rolls it back. A rollback
     * to a savepoint ({@code PROPAGATION_NESTED}, or a programmatic savepoint) runs the actions registered since that savepoint
     * was created (Spring 6.2+ reports savepoints to its synchronizations; an older Spring runs none, and those actions
     * follow the transaction's outcome).
     *
     * <p>Spring is an optional ({@code provided}) dependency. Code that declares a Spring type in a member signature (a field,
     * parameter, return or {@code throws} type, or an implemented interface) lives here, not in {@code JdbcUtil}: linking such
     * a member resolves that type, so {@code JdbcUtil} would then need Spring's transaction classes to load (the constraint
     * {@code SpringAbsentRuntimeCompatibilityTest} checks). Method bodies in {@code JdbcUtil} may still bind a Spring type
     * with {@code instanceof} and pass it on, guarded by {@code isInSpring}: the verifier does not load a type to check
     * assignability to itself. This class is only loaded once Spring has been found.</p>
     */
    private static final class SpringRollbackActions implements org.springframework.transaction.support.TransactionSynchronization {
        private static final String ENTITY_MANAGER_HOLDER_CLASS_NAME = "org.springframework.orm.jpa.EntityManagerHolder";

        // The JPA accessors are resolved reflectively (spring-orm and the JPA API are optional). A Method is a per-class
        // constant, so each is looked up once per class, as SpringConnectionHolderAccess.IS_TRANSACTION_ACTIVE is: looking
        // them up on every participating beginTransaction/rollback (getMethod plus, for setRollbackOnly, an interface walk)
        // was needless work on the JPA path. A class without the method is remembered as absent. Each is a ClassValue, not a
        // static map keyed by class: the keys are application and provider classes (entityTransaction.getClass(),
        // entityManagerFactory.getClass()) and Spring's holder class, which a static map, and the cached Method through its
        // declaring class, pinned for the JVM's life (unbounded) in a shared-classloader container; a ClassValue entry is
        // released with its class.
        private static final MethodValue GET_ENTITY_MANAGER_BY_HOLDER_CLASS = new MethodValue("getEntityManager");
        private static final MethodValue GET_TRANSACTION_BY_ENTITY_MANAGER_TYPE = new MethodValue("getTransaction");
        private static final MethodValue IS_ACTIVE_BY_TRANSACTION_TYPE = new MethodValue("isActive");
        private static final MethodValue GET_DATA_SOURCE_BY_FACTORY_CLASS = new MethodValue("getDataSource");
        // Resolved through the JPA interface: the provider's implementation class need not be accessible.
        private static final MethodValue SET_ROLLBACK_ONLY_BY_TRANSACTION_CLASS = new MethodValue("setRollbackOnly",
                entityTransactionClass -> entityTransactionType(entityTransactionClass).getMethod("setRollbackOnly"));

        /**
         * Whether Spring reports savepoints to its transaction synchronizations ({@code TransactionSynchronization.savepoint},
         * Spring 6.2+): before, the log holds no savepoint marks, so the work confined to a savepoint scope can't be told apart.
         * Not final: tests simulate a Spring before 6.2, whose savepoint callbacks are then ignored.
         */
        private static boolean savepointsReported = isSavepointReported(); //NOSONAR

        private static boolean isSavepointReported() {
            try {
                org.springframework.transaction.support.TransactionSynchronization.class.getMethod("savepoint", Object.class);
                return true;
            } catch (final NoSuchMethodException | RuntimeException e) {
                return false;
            }
        }

        private final RollbackActionLog log = new RollbackActionLog();

        /**
         * Whether the transaction's completion has started: it has committed and Spring runs its {@code afterCommit} callbacks,
         * or it has completed and Spring runs its {@code afterCompletion} callbacks. Volatile: read on any thread (see
         * {@link JdbcUtil#hasSpringSynchronizationCompletionStarted(Object)}).
         */
        private volatile boolean completing;

        /**
         * The actions to run once the transaction has committed, in registration order, by key; {@code null} until the first.
         */
        private Map<Object, Runnable> commitActions;

        /**
         * The first {@link Error} thrown by a commit action in {@link #afterCommit()}, thrown from {@link #afterCompletion(int)}.
         * Not volatile, as the commit actions are not: Spring calls a synchronization's {@code afterCommit} and then its
         * {@code afterCompletion} in one call chain, on one thread ({@code AbstractPlatformTransactionManager.processCommit};
         * for a JTA transaction, whose {@code afterCompletion} may come from another thread, {@code JtaAfterCompletionSynchronization}
         * calls both from it). Only {@link #completing} is read on other threads.
         */
        private Error deferredCommitActionError;

        /**
         * Adds {@code action} to the actions to run once the Spring-managed transaction of the current thread has committed.
         *
         * @param key identifies the action: an action whose key equals that of one registered before is not registered;
         *        {@code null} to register it in any case
         * @param action the action
         * @return {@code true} if it was registered (or one with an equal key was), {@code false} if Spring's transaction
         *         synchronization is not active, or the transaction has committed already (its {@code afterCommit} callbacks run)
         */
        static boolean registerCommitAction(final Object key, final Runnable action) {
            final SpringRollbackActions synchronization = current();

            // WHY completing: an action added from an afterCommit callback after this synchronization's own afterCommit had run
            // was never run; the transaction has committed, so the caller runs it right away.
            if (synchronization == null || synchronization.completing) {
                return false;
            }

            if (synchronization.commitActions == null) {
                synchronization.commitActions = new LinkedHashMap<>(4);
            }

            synchronization.commitActions.putIfAbsent(key == null ? new Object() : key, action);

            return true;
        }

        /**
         * The synchronization {@link #watchCompletion(javax.sql.DataSource)} found or registered among the synchronizations
         * Spring runs on this thread, with the connection holders it watches ({@link #watchedHolders}); cleared when those
         * synchronizations are suspended or the transaction completes (see {@link #stopWatching()}). Weakly referenced: Spring
         * holds the synchronization while its transaction runs.
         */
        private static final ThreadLocal<java.lang.ref.WeakReference<SpringRollbackActions>> watching_TL = new ThreadLocal<>();

        /**
         * The connection holders seen while this synchronization was registered (see {@link #watchCompletion}); {@code null}
         * until the first. A holder found here needs no lookup of the synchronization: it is registered with the
         * synchronizations Spring runs now (unless they were replaced without telling it, as by a programmatic
         * {@code TransactionSynchronizationManager.clearSynchronization()}: a holder of a later transaction is not found here,
         * and registers it again). WHY not cleared when the transaction completes, as was tried: a DAO call from a later
         * synchronization's {@code afterCompletion} callback (Spring's synchronization still active, the holder still bound)
         * watches again and fills it anew, and a {@code SqlTransaction} that keeps this synchronization keeps the holder
         * itself anyway (see {@code TransactionContext._springResource}), so clearing it freed nothing.
         */
        private Set<Object> watchedHolders;

        /**
         * Registers this library's synchronization with the synchronizations Spring runs on this thread, if it isn't yet, once a
         * connection is taken from the connection holder bound to {@code ds} (to the target of a
         * {@code TransactionAwareDataSourceProxy}), so that the {@code afterCommit} callbacks of the transaction see it completing
         * (see {@code JdbcUtil.isSpringTransactionCompleting()}). Never throws: it only watches.
         *
         * <p>Registered for a holder bound for synchronization only too (a {@code SUPPORTS} scope, or a Spring transaction on
         * another data source): it is harmless there, and a later connection taken from that holder then needs neither the
         * lookup of the synchronization nor the reflective check of the holder's ownership. WHY: registered for a holder a
         * transaction owns only, every connection taken from one bound for synchronization paid for that check.</p>
         *
         * @param ds the data source the connection was taken from
         */
        static void watchCompletion(final javax.sql.DataSource ds) {
            try {
                if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                        || !(org.springframework.transaction.support.TransactionSynchronizationManager
                                .getResource(unwrapSpringDataSource(ds)) instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder)) {
                    return;
                }

                // Checked once per holder and synchronization list: looking the synchronization up sorts a copy of the transaction's
                // synchronizations. WHY the synchronization, not just the last holder seen on the thread: a holder first seen
                // in another transaction manager's transaction begun inside the one owning it registered the synchronization
                // there, and once that one completed the holder was still taken for watched, so the owner's afterCommit callbacks
                // never saw it completing (a scope begun there joined it and Spring's cleanup committed its rolled-back work).
                // Several holders: one last-seen holder alternated between data sources, looking the synchronization up each time.
                final SpringRollbackActions watching = watching();

                if (watching != null && watching.watchedHolders != null && watching.watchedHolders.contains(holder)) {
                    return;
                }

                final SpringRollbackActions synchronization = current();

                if (synchronization.watchedHolders == null) {
                    synchronization.watchedHolders = java.util.Collections.newSetFromMap(new IdentityHashMap<>(4));
                }

                synchronization.watchedHolders.add(holder);
                watching_TL.set(new java.lang.ref.WeakReference<>(synchronization));
            } catch (final RuntimeException | LinkageError e) {
                logger.debug("Cannot watch the completion of the Spring-managed transaction: {}", e.toString());
            }
        }

        /**
         * Returns the synchronization {@link #watchCompletion(javax.sql.DataSource)} recorded for the synchronizations Spring runs
         * on this thread.
         *
         * @return the synchronization, or {@code null} if none is recorded
         */
        private static SpringRollbackActions watching() {
            final java.lang.ref.WeakReference<SpringRollbackActions> ref = watching_TL.get();

            return ref == null ? null : ref.get();
        }

        /**
         * Forgets the synchronization {@link #watchCompletion(javax.sql.DataSource)} recorded: the synchronizations Spring runs on
         * this thread change (they are suspended for another transaction, resumed after it, or cleared at completion), so the
         * holders it watched must be looked at again. Also releases the thread-local on a pooled thread.
         */
        private static void stopWatching() {
            watching_TL.remove();
        }

        /**
         * Adds {@code action} to the rollback actions of the Spring-managed transaction of the current thread.
         *
         * @param dataSourceKey the (unwrapped) data source of the work the action undoes
         * @param action the action to run after a Spring rollback
         * @return {@code true} if it was registered, {@code false} if Spring's transaction synchronization is not active
         */
        static boolean register(final Object dataSourceKey, final Runnable action) {
            final SpringRollbackActions rollbackActions = current();

            if (rollbackActions == null) {
                return false;
            }

            rollbackActions.log.add(dataSourceKey, action);

            return true;
        }

        /**
         * Returns the synchronization of the Spring-managed transaction of the current thread, registering it first if needed.
         *
         * @return the synchronization, or {@code null} if Spring's transaction synchronization is not active
         */
        private static SpringRollbackActions current() {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                return null;
            }

            final SpringRollbackActions registered = registered();

            if (registered != null) {
                return registered;
            }

            final SpringRollbackActions rollbackActions = new SpringRollbackActions();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(rollbackActions);

            return rollbackActions;
        }

        /**
         * Returns the synchronization registered with the Spring-managed transaction of the current thread.
         *
         * @return the synchronization, or {@code null} if none is registered (or Spring's transaction synchronization is not active)
         */
        private static SpringRollbackActions registered() {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                return null;
            }

            // One synchronization per Spring transaction (Spring suspends and resumes it with its transaction), found among the
            // few registered ones: with one per action, Spring ran the actions oldest first, so an entity inserted twice kept
            // the ID of its first, rolled-back insert, and every insert added a synchronization.
            for (final Object synchronization : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                if (synchronization instanceof SpringRollbackActions registered) {
                    return registered;
                }
            }

            return null;
        }

        /**
         * Marks the Spring-managed transaction that holds the connection bound to {@code dataSourceKey} rollback-only: its
         * connection holder, and, for a transaction that exposes the connection through a handle of its own, every active JPA
         * transaction identified as its owner. The rollback actions registered with the Spring transaction run right away only
         * if every one of those JPA transactions was marked (or there is no such handle); otherwise they are left to the
         * transaction's actual outcome.
         *
         * @param dataSourceKey the (unwrapped) data source the connection holder is bound to
         * @return the first {@link Error} thrown by a rollback action run right away (later ones attached to it), or {@code null}:
         *         the transaction is marked either way
         * @throws IllegalStateException if no connection holder is bound to {@code dataSourceKey}, or not every identified JPA
         *         transaction (or none) could be marked: the transaction may still commit the work
         */
        static Error markRollbackOnly(final Object dataSourceKey) throws IllegalStateException {
            final Object resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(dataSourceKey);

            if (!(resource instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder)) {
                // The Spring transaction is no longer bound to the thread (suspended or completed): nothing makes it roll back.
                // This was only logged, and the participating rollback was reported as done (ROLLED_BACK).
                throw new IllegalStateException("Cannot mark the Spring-managed transaction rollback-only: no connection holder is bound to the"
                        + " data source any more (its transaction was suspended or has completed), so it may still commit the work");
            }

            // DataSourceTransactionManager and JdbcTransactionManager consult the connection holder.
            holder.setRollbackOnly();

            // An ORM transaction manager that exposes its JDBC connection binds a holder with a handle of its own, and may decide
            // from its own transaction instead: JpaTransactionManager consults the JPA EntityTransaction alone, so marking only
            // the holder let a TransactionTemplate commit the work that was rolled back here. A JPA commit of a rollback-only
            // transaction rolls back (JpaTransactionManager commits on a global rollback-only mark for that reason).
            final boolean ormHandle = !(holder.getConnectionHandle() instanceof org.springframework.jdbc.datasource.SimpleConnectionHandle);

            // Failing the commit of a transaction that consults neither mark is not an option: Spring exposes only the
            // synchronizations of the innermost transaction on the thread, which needn't own the connection (another transaction
            // manager's transaction may run inside the owner), so the commit failed could be that of an unrelated transaction.
            // beginTransaction refuses to participate in such a transaction instead (see canMarkRollbackOnly).
            // Unless every identified JPA transaction was marked (otherwise this throws), the in-memory changes are left to the
            // transaction's actual outcome (see afterCompletion): undone now, they would be lost although it may commit the work.
            if (ormHandle) {
                markJpaTransactionsRollbackOnly(dataSourceKey, holder.getConnectionHandle());
            }

            // The transaction that owns the connection can now only end rolled back (DataSourceTransactionManager and
            // JdbcTransactionManager consult the holder; a marked JPA transaction rolls back on commit): undo the in-memory changes
            // tied to its work right away, as a JdbcUtil transaction of its own does once a nested scope marks it rollback-only.
            // WHY only since the newest savepoint mark: inside a PROPAGATION_NESTED scope, Spring's rollback to the savepoint
            // (which the mark forces on the nested scope's commit) resets the holder's rollback-only flag (not the mark set on a JPA
            // transaction: JpaTransactionManager's enclosing transaction can't commit then, and the actions run at its
            // completion), so the enclosing transaction can still commit the rows of its earlier inserts; running every action of
            // the data source here left those committed entities without their IDs. The work before the savepoint follows the
            // transaction's outcome (afterCompletion, or a later rollback to its savepoint) instead; the nested scope's own work
            // can only roll back.
            // WHY not before Spring 6.2: it reports no savepoints, so the log holds no mark, and inside a PROPAGATION_NESTED scope
            // every action of the data source ran here, putting back the IDs of the enclosing transaction's earlier inserts,
            // whose rows it may still commit. The actions follow the transaction's outcome then (see afterCompletion).
            final SpringRollbackActions synchronization = savepointsReported ? registered() : null;

            if (synchronization == null) {
                return null;
            }

            // WHY the Error is returned: it was only logged, while one from an action the transaction ran itself propagates from
            // the call that completed the scope. The mark is set either way, so it must not count as a failed mark.
            final Error[] firstError = { null };

            synchronization.log.runNewestFirstSinceNewestMark(dataSourceKey, e -> {
                logger.warn(e, "Failed to run a rollback action after marking the Spring-managed transaction rollback-only");

                if (e instanceof final Error error) {
                    firstError[0] = withSuppressed(firstError[0], error);
                }
            });

            return firstError[0];
        }

        /**
         * Returns whether the transaction whose connection holder is {@code resource} has completed: see
         * {@link JdbcUtil#hasSpringTransactionCompleted(Object)}.
         *
         * @param resource the connection holder of the transaction participated in
         * @return {@code true} if it is the holder of a {@code DataSourceTransactionManager} or {@code JdbcTransactionManager}
         *         transaction that is no longer transaction-active
         * @throws IllegalStateException if the holder's transaction-active flag is not accessible
         */
        static Object resourceRecordingCompletion(final Object dataSourceKey) throws IllegalStateException {
            final Object resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(dataSourceKey);

            return resource instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder
                    && holder.getConnectionHandle() instanceof org.springframework.jdbc.datasource.SimpleConnectionHandle
                    && SpringConnectionHolderAccess.isOwnedByTransaction(holder) ? holder : null;
        }

        static boolean hasCompleted(final Object resource) throws IllegalStateException {
            return resource instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder
                    && holder.getConnectionHandle() instanceof org.springframework.jdbc.datasource.SimpleConnectionHandle
                    && !SpringConnectionHolderAccess.isOwnedByTransaction(holder);
        }

        /**
         * Marks the transaction whose connection holder is {@code resource} rollback-only while it is not bound to the thread:
         * see {@link JdbcUtil#markUnboundSpringTransactionRollbackOnly(Object)}.
         *
         * @param resource the connection holder of the transaction participated in
         * @throws IllegalStateException if the transaction has completed, or exposes the connection through a handle of its own
         */
        static void markUnboundRollbackOnly(final Object resource) throws IllegalStateException {
            // A transaction manager clears the holder when its transaction completes (no longer transaction-active then), while
            // a suspended transaction's holder is left as it is. Only a holder with Spring's own handle is consulted: a JPA
            // transaction decides from its EntityTransaction, which is suspended with its entity manager, out of reach here.
            if (resource instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder
                    && holder.getConnectionHandle() instanceof org.springframework.jdbc.datasource.SimpleConnectionHandle) {
                if (SpringConnectionHolderAccess.isOwnedByTransaction(holder)) {
                    holder.setRollbackOnly();
                    return;
                }

                throw new IllegalStateException("Cannot mark the Spring-managed transaction rollback-only: it has completed (no connection holder"
                        + " of it is bound to the data source any more), so it may have committed the work");
            }

            throw new IllegalStateException("Cannot mark the Spring-managed transaction rollback-only: Spring binds another transaction to the data"
                    + " source, or none, and the transaction participated in decides from a JPA transaction (its connection holder has a handle of"
                    + " its own), which can't be reached while it is suspended (or has completed), so it may still commit the work");
        }

        /**
         * Marks the active JPA transaction that owns the connection bound to {@code dataSourceKey} rollback-only. Its owner is
         * the transaction whose entity manager factory reports that data source ({@code EntityManagerFactoryInfo.getDataSource()},
         * as Spring's JPA factory beans do), or else the one whose entity manager the connection's handle refers to (the handles
         * of Spring's Hibernate and EclipseLink dialects keep the entity manager, or its session, whose JDBC connection they
         * expose). If neither identifies it, none is marked, and other JPA transactions are left alone ({@code beginTransaction}
         * refuses to participate in such a transaction, see {@link #canMarkRollbackOnly(Object)}). spring-orm and the JPA API are
         * optional, so they are only used reflectively.
         *
         * @param dataSourceKey the (unwrapped) data source of the participating transaction
         * @param connectionHandle the handle of the connection holder bound to {@code dataSourceKey}
         * @throws IllegalStateException if no active JPA transaction owning the connection is identified, or marking one of them
         *         fails (the others are marked all the same): the first failure is the cause, later ones are suppressed
         */
        private static void markJpaTransactionsRollbackOnly(final Object dataSourceKey, final Object connectionHandle) throws IllegalStateException {
            final List<Object> entityTransactions = jpaOwnerTransactions(dataSourceKey, connectionHandle);

            if (entityTransactions.isEmpty()) {
                // beginTransaction identified one (see canMarkRollbackOnly): it has completed, or was suspended, since.
                throw new IllegalStateException("Cannot mark the Spring-managed transaction rollback-only: no active JPA transaction owning the"
                        + " connection of the data source can be identified any more, so it may still commit the work");
            }

            IllegalStateException failure = null;

            for (final Object entityTransaction : entityTransactions) {
                try {
                    SET_ROLLBACK_ONLY_BY_TRANSACTION_CLASS.methodOf(entityTransaction.getClass()).invoke(entityTransaction);
                } catch (final ReflectiveOperationException | RuntimeException e) {
                    // Keep marking the others (each marked one can then only end rolled back), but fail the mark: the transaction
                    // that owns the work may be the unmarked one and commit it. A partial mark used to be logged only, and the
                    // participating rollback was reported as done (ROLLED_BACK, no exception) while Spring committed the work.
                    final Throwable providerFailure = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;

                    if (failure == null) {
                        failure = new IllegalStateException("Cannot mark the Spring-managed transaction rollback-only: a JPA transaction owning the"
                                + " connection of the data source refused the mark, so it may still commit the work", providerFailure);
                    } else {
                        failure.addSuppressed(providerFailure);
                    }
                }
            }

            if (failure != null) {
                throw failure;
            }
        }

        /**
         * Returns whether a rollback of a transaction participating in the Spring-managed transaction that holds the connection
         * bound to {@code dataSourceKey} can make that transaction roll back, through a rollback-only mark the transaction
         * consults (see {@link #markRollbackOnly(Object)}): its connection holder, bound with Spring's own handle by
         * DataSourceTransactionManager and JdbcTransactionManager, or the JPA transaction identified as its owner. Whether
         * marking that JPA transaction will succeed can't be checked in advance: if it fails, the rollback fails, and the
         * Spring transaction decides the outcome.
         *
         * @param dataSourceKey the (unwrapped) data source of the participating transaction
         * @return {@code true} if a rollback can be made to reach the Spring-managed transaction
         */
        static boolean canMarkRollbackOnly(final Object dataSourceKey) {
            final Object resource = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(dataSourceKey);

            return !(resource instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder)
                    || holder.getConnectionHandle() instanceof org.springframework.jdbc.datasource.SimpleConnectionHandle
                    || !jpaOwnerTransactions(dataSourceKey, holder.getConnectionHandle()).isEmpty();
        }

        /**
         * Returns the active JPA transactions that own the connection bound to {@code dataSourceKey} (see
         * {@link #markJpaTransactionsRollbackOnly(Object, Object)}); empty if none can be identified.
         *
         * @param dataSourceKey the (unwrapped) data source of the participating transaction
         * @param connectionHandle the handle of the connection holder bound to {@code dataSourceKey}
         * @return the owner's JPA {@code EntityTransaction}s
         */
        private static List<Object> jpaOwnerTransactions(final Object dataSourceKey, final Object connectionHandle) {
            final List<Object> owned = new ArrayList<>(1);
            final List<Object> referenced = new ArrayList<>(1);

            for (final Map.Entry<Object, Object> entry : org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap()
                    .entrySet()) {
                final Object resource = entry.getValue();

                if (resource == null || !isEntityManagerHolder(resource.getClass())) {
                    continue;
                }

                try {
                    final Method getEntityManager = GET_ENTITY_MANAGER_BY_HOLDER_CLASS.methodOf(resource.getClass());
                    final Object entityManager = getEntityManager.invoke(resource);
                    final Method getTransaction = GET_TRANSACTION_BY_ENTITY_MANAGER_TYPE.methodOf(getEntityManager.getReturnType());
                    final Object entityTransaction = getTransaction.invoke(entityManager);

                    // An inactive transaction (e.g. an entity manager bound for open-in-view, without a transaction) has nothing to mark.
                    final Method isActive = IS_ACTIVE_BY_TRANSACTION_TYPE.methodOf(getTransaction.getReturnType());

                    if (!Boolean.TRUE.equals(isActive.invoke(entityTransaction))) {
                        continue;
                    }

                    final javax.sql.DataSource dataSource = dataSourceOf(entry.getKey());

                    if (dataSource == null) {
                        if (refersTo(connectionHandle, entityManager)) {
                            referenced.add(entityTransaction);
                        }
                    } else if (unwrapSpringDataSource(dataSource) == dataSourceKey) {
                        owned.add(entityTransaction);
                    }
                } catch (final ReflectiveOperationException | RuntimeException e) {
                    // Identification only (also run by canMarkRollbackOnly at every participating beginTransaction, which then
                    // succeeds): a lingering closed holder, for example, must not be reported as a failed mark.
                    logger.warn(e, "Cannot inspect the JPA transaction bound to the thread");
                }
            }

            return !owned.isEmpty() ? owned : referenced;
        }

        /**
         * Returns whether one of the instance fields of {@code handle} (declared by its class or a superclass) holds
         * {@code target}.
         *
         * @param handle the connection handle, or {@code null}
         * @param target the object to look for
         * @return {@code true} if a readable field of {@code handle} holds {@code target}
         */
        private static boolean refersTo(final Object handle, final Object target) {
            if (handle == null) {
                return false;
            }

            for (Class<?> c = handle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (final java.lang.reflect.Field field : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);

                        if (field.get(handle) == target) {
                            return true;
                        }
                    } catch (final ReflectiveOperationException | RuntimeException e) {
                        // Not readable (e.g. a module that does not open its package): this field can't tell.
                    }
                }
            }

            return false;
        }

        /**
         * Returns the JPA {@code EntityTransaction} interface (jakarta or javax) that {@code entityTransactionClass} implements.
         *
         * @param entityTransactionClass the class of a JPA entity transaction
         * @return the interface
         * @throws NoSuchMethodException if it implements none
         */
        private static Class<?> entityTransactionType(final Class<?> entityTransactionClass) throws NoSuchMethodException {
            for (Class<?> c = entityTransactionClass; c != null; c = c.getSuperclass()) {
                for (final Class<?> type : ClassUtil.getAllInterfaces(c)) {
                    if (type.getName().endsWith(".persistence.EntityTransaction")) {
                        return type;
                    }
                }
            }

            throw new NoSuchMethodException("No JPA EntityTransaction interface on " + entityTransactionClass.getName());
        }

        /**
         * Returns the data source an entity manager factory reports through a {@code getDataSource()} method (Spring's
         * {@code EntityManagerFactoryInfo}).
         *
         * @param entityManagerFactory the factory, the key its {@code EntityManagerHolder} is bound to
         * @return the data source, or {@code null} if the factory reports none
         */
        private static javax.sql.DataSource dataSourceOf(final Object entityManagerFactory) {
            try {
                final Method getDataSource = GET_DATA_SOURCE_BY_FACTORY_CLASS.methodOf(entityManagerFactory.getClass());

                return getDataSource.invoke(entityManagerFactory) instanceof final javax.sql.DataSource dataSource ? dataSource : null;
            } catch (final ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }

        private static boolean isEntityManagerHolder(final Class<?> cls) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                if (ENTITY_MANAGER_HOLDER_CLASS_NAME.equals(c.getName())) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public void savepoint(final Object savepoint) {
            // Invoked right after the savepoint is created (also on a synchronization registered by the enclosing transaction):
            // the actions added from here on belong to the nested transaction's work.
            // WHY the null check: a driver whose Connection.setSavepoint returns null (a JDBC violation) makes Spring report a
            // null savepoint here, from inside its nested begin, which has no safety net; Spring itself then runs the nested
            // scope without a savepoint (hasSavepoint() is false), and so must we - rejecting it failed that begin with our
            // IllegalArgumentException. Nothing can roll back to such a savepoint, so there is no position to mark.
            if (savepointsReported && savepoint != null) {
                log.mark(savepoint);
            }
        }

        @Override
        public void savepointRollback(final Object savepoint) {
            if (!savepointsReported) {
                return; // a Spring before 6.2 does not call it (only simulated by tests)
            }

            // WHY: the completion callbacks report the enclosing transaction's outcome only, so the IDs of an insert in a nested
            // transaction rolled back to its savepoint were kept although its rows were gone (committed with the enclosing
            // transaction). Invoked right before Spring rolls back to the savepoint (a failure of that rollback is not reported
            // to synchronizations: the actions have run by then, see CrudInsertOps); a synchronization registered inside the
            // nested transaction has no mark for it and runs all of its actions, which all belong to the nested work.
            log.runNewestFirstSince(savepoint,
                    e -> logger.warn(e, "Failed to run a rollback action after the Spring-managed transaction was rolled back to a savepoint"));
        }

        /**
         * Runs before the synchronizations of a lower precedence (those registered with the same, before it, run first), so
         * that their {@code afterCommit} callbacks see the transaction as completing (see
         * {@code JdbcUtil.isSpringTransactionCompleting()}).
         */
        @Override
        public int getOrder() {
            return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void afterCommit() {
            completing = true;

            // WHY deferred: this synchronization runs before lower-precedence callbacks (see getOrder), and Spring calls
            // afterCommit callbacks without
            // catching, so an Error thrown here skipped the afterCommit callbacks of every other synchronization, the
            // application's own (such as @TransactionalEventListener(AFTER_COMMIT)) included, for a failure of a cache
            // invalidation. Thrown from afterCompletion instead, where Spring logs it.
            deferredCommitActionError = runCommitActions();
        }

        @Override
        public void suspend() {
            // Another transaction's synchronizations take over (or none): the holders watched must be looked at again there.
            stopWatching();
        }

        @Override
        public void resume() {
            stopWatching();
        }

        /**
         * Runs (and discards) the commit actions, in registration order. A failing action does not stop the others, as in
         * {@code TransactionContext.runCommitActions()} (an Error used to stop the remaining actions, which were lost, as they
         * had been discarded first). A {@code RuntimeException} is logged; an {@link Error} is returned for the caller to throw,
         * and not logged here: Spring logs it when {@code afterCompletion} throws it (it was logged twice).
         *
         * @return the first {@link Error} thrown by an action (later ones attached to it), for the caller to throw once it has
         *         done its own work, or {@code null}
         */
        private Error runCommitActions() {
            final Map<Object, Runnable> actions = commitActions;
            commitActions = null;

            if (actions == null) {
                return null;
            }

            Error firstError = null;

            for (final Runnable action : actions.values()) {
                try {
                    action.run();
                } catch (final Error e) {
                    firstError = withSuppressed(firstError, e);
                } catch (final RuntimeException e) {
                    logger.warn(e, "Failed to run an action after the Spring-managed transaction committed");
                }
            }

            return firstError;
        }

        /**
         * Returns {@code first}, with {@code next} attached to it, or {@code next} if {@code first} is {@code null}.
         */
        private static Error withSuppressed(final Error first, final Error next) {
            if (first == null) {
                return next;
            }

            if (next != null && next != first) {
                first.addSuppressed(next);
            }

            return first;
        }

        /**
         * Runs the commit actions left for the transaction's completion and the rollback actions of its outcome. The first
         * {@link Error} thrown by an action (an {@code afterCommit} one included, see {@link #afterCommit()}) is thrown once all
         * have run; Spring logs it.
         */
        @Override
        public void afterCompletion(final int status) {
            // Also after a rollback, which calls no afterCommit (see JdbcUtil.hasSpringSynchronizationCompletionStarted).
            completing = true;

            // The transaction's synchronizations are cleared (or suspended ones resumed in their place).
            stopWatching();

            Error error = deferredCommitActionError;
            deferredCommitActionError = null;

            if (status == STATUS_COMMITTED) {
                // WHY: registered from another synchronization's afterCommit callback, this synchronization gets no afterCommit
                // call (Spring calls the synchronizations registered before the commit), while it gets this one; its commit
                // actions were never run.
                error = withSuppressed(error, runCommitActions());
            } else if (status == STATUS_UNKNOWN) {
                // The commit failed in a way Spring couldn't resolve (e.g. the connection was lost during the commit): the work may
                // have committed. The commit actions (cache invalidations) run: they were dropped, and caches kept entries the
                // commit had made stale. Their Error no longer keeps the rollback actions below from running.
                error = withSuppressed(error, runCommitActions());

                // A JPA commit of a rollback-only transaction rolls it back and throws a RollbackException, which Spring reports as
                // an unknown outcome: the work on a connection a participating transaction marked rollback-only (its holder is
                // still bound until the transaction is cleaned up) can't have committed.
                log.runNewestFirstWhere(SpringRollbackActions::isMarkedRollbackOnly,
                        e -> logger.warn(e, "Failed to run a rollback action after the Spring-managed transaction was rolled back"));
            } else {
                commitActions = null;

                if (status == STATUS_ROLLED_BACK) {
                    log.runNewestFirst(e -> logger.warn(e, "Failed to run a rollback action after the Spring-managed transaction was rolled back"));
                }
            }

            if (error != null) {
                throw error;
            }
        }

        /**
         * Returns whether the connection holder bound to {@code dataSourceKey} is marked rollback-only.
         *
         * @param dataSourceKey the (unwrapped) data source the actions' work ran on, or {@code null}
         * @return {@code true} if a connection holder marked rollback-only is bound to it
         */
        private static boolean isMarkedRollbackOnly(final Object dataSourceKey) {
            return dataSourceKey != null && org.springframework.transaction.support.TransactionSynchronizationManager
                    .getResource(dataSourceKey) instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder && holder.isRollbackOnly();
        }
    }

    /**
     * Registers the action supplied by {@code actionSupplier} to run if the transaction that encloses work on {@code ds} (work
     * about to run, or running: an insert registers it right before it first writes an ID) is rolled back: the
     * {@code JdbcUtil} transaction active for {@code ds} on this thread (its outermost scope
     * ending without a successful commit), or else the Spring-managed transaction that owns {@code ds}. Outside any
     * transaction, {@code actionSupplier} is not called. Used to undo in-memory changes, such as generated IDs written back
     * to entities, that refer to work a rollback discards.
     *
     * @param <A> the action type
     * @param ds the data source the work runs on
     * @param actionSupplier supplies the action (or {@code null} for none); called at most once, before this method returns
     * @return the action registered, or {@code null} if none was (outside any transaction, or without Spring's transaction
     *         synchronization)
     */
    static <A extends Runnable> A registerEnclosingTransactionRollbackAction(final javax.sql.DataSource ds, final Supplier<? extends A> actionSupplier) {
        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null) {
            final A action = actionSupplier.get();
            tran.addRollbackAction(action);
            return action;
        } else if (isInSpringManagedTransaction(ds)) {
            final A action = actionSupplier.get();

            return action != null && registerSpringRollbackAction(ds, action) ? action : null;
        }

        return null;
    }

    /**
     * Tells the connection holders that Spring's transaction managers bind apart from those bound for mere synchronization. A
     * nested class: a method whose signature names a Spring type would make reflection over {@code JdbcUtil}
     * ({@code getDeclaredMethods()}) fail without Spring on the class path. It reads the protected
     * {@code ConnectionHolder.isTransactionActive()} flag; Spring's jars are used on the class path or as automatic modules,
     * whose packages are open to reflection.
     */
    private static final class SpringConnectionHolderAccess {
        private static final Method IS_TRANSACTION_ACTIVE = findIsTransactionActive();

        // Resolved on first use, by a failed rollback only: a missing one is logged then, not at every class initialization.
        private static volatile Method setConnection; //NOSONAR

        private static volatile boolean setConnectionResolved; //NOSONAR

        private SpringConnectionHolderAccess() {
            // utility class
        }

        private static Method findIsTransactionActive() {
            try {
                final Method method = org.springframework.jdbc.datasource.ConnectionHolder.class.getDeclaredMethod("isTransactionActive");
                method.setAccessible(true);
                return method;
            } catch (final ReflectiveOperationException | RuntimeException e) {
                logger.warn("Cannot access ConnectionHolder.isTransactionActive(): {}", e.toString());
                return null;
            }
        }

        /**
         * Returns whether a Spring transaction manager bound {@code holder} for a transaction it runs, rather than
         * {@code DataSourceUtils} for mere synchronization.
         *
         * @param holder the connection holder bound to the data source
         * @return {@code true} if the holder belongs to a transaction
         * @throws IllegalStateException if the holder's {@code isTransactionActive()} flag has to be read but is not accessible
         */
        static boolean isOwnedByTransaction(final org.springframework.jdbc.datasource.ConnectionHolder holder) throws IllegalStateException {
            final org.springframework.jdbc.datasource.ConnectionHandle handle = holder.getConnectionHandle();

            if (handle == null) {
                return false;
            }

            // JPA and Hibernate transaction managers can't mark the holder transaction-active (the setter is protected); they
            // expose their JDBC connection through a ConnectionHandle of their own, whereas DataSourceUtils always uses
            // SimpleConnectionHandle.
            if (!(handle instanceof org.springframework.jdbc.datasource.SimpleConnectionHandle)) {
                return true;
            }

            // DataSourceTransactionManager and JdbcTransactionManager mark the holder they bind transaction-active.
            return isTransactionActive(holder);
        }

        /**
         * Returns the connection of {@code holder}, a {@code ConnectionHolder} (through its handle: for an ORM transaction
         * manager, the provider's code). Typed {@code Object}, so that callers in {@code JdbcUtil} name no Spring type.
         *
         * @param holder the connection holder
         * @return its connection
         */
        static Connection connectionOf(final Object holder) {
            return ((org.springframework.jdbc.datasource.ConnectionHolder) holder).getConnection();
        }

        /**
         * Takes {@code conn} from the connection holder Spring bound to {@code dataSource} for synchronization (not for a
         * transaction), if that holder holds it: the holder then fetches a new connection when the connection is next requested.
         *
         * @param dataSource the data source key the holder is bound to
         * @param conn the connection to take
         * @return {@code true} if it was taken: the caller closes it, as Spring no longer releases it
         * @throws IllegalStateException if the ownership of the holder bound to {@code dataSource} cannot be determined
         */
        static boolean detachSynchronizedConnection(final javax.sql.DataSource dataSource, final Connection conn) throws IllegalStateException {
            if (!(org.springframework.transaction.support.TransactionSynchronizationManager
                    .getResource(dataSource) instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder)
                    || holder.getConnectionHandle() == null || holder.getConnection() != conn || isOwnedByTransaction(holder)) {
                return false;
            }

            final Method method = setConnectionMethod();

            if (method == null) {
                return false;
            }

            try {
                method.invoke(holder, (Object) null);
                return true;
            } catch (final ReflectiveOperationException e) {
                logger.warn(e, "Cannot take a connection from Spring's connection holder");
                return false;
            }
        }

        private static Method setConnectionMethod() {
            if (!setConnectionResolved) {
                try {
                    final Method method = org.springframework.jdbc.datasource.ConnectionHolder.class.getDeclaredMethod("setConnection", Connection.class);
                    method.setAccessible(true);
                    setConnection = method;
                } catch (final ReflectiveOperationException | RuntimeException e) {
                    logger.warn("Cannot access ConnectionHolder.setConnection(Connection): {}", e.toString());
                }

                setConnectionResolved = true;
            }

            return setConnection;
        }

        private static boolean isTransactionActive(final org.springframework.jdbc.datasource.ConnectionHolder holder) throws IllegalStateException {
            if (IS_TRANSACTION_ACTIVE == null) {
                // Neither answer is safe: joining could leave the work uncommitted, and a transaction of its own could commit
                // Spring's work.
                throw new IllegalStateException("Cannot determine whether the Spring connection holder bound to the data source belongs to a"
                        + " transaction: ConnectionHolder.isTransactionActive() is not accessible. Open package org.springframework.jdbc.datasource"
                        + " to this library");
            }

            try {
                return Boolean.TRUE.equals(IS_TRANSACTION_ACTIVE.invoke(holder));
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read ConnectionHolder.isTransactionActive()", e);
            }
        }
    }

    /**
     * Executes the given callable within a transaction and returns its result.
     * The transaction is committed if the callable completes normally; it is rolled back
     * if the callable throws any exception.
     *
     * <p>This is a convenience alternative to the {@code beginTransaction} / {@code commit} /
     * {@code rollbackIfNotCommitted} pattern. It uses the {@link IsolationLevel#DEFAULT} isolation
     * level, which keeps the connection's current isolation level (or, for a nested call, the enclosing
     * scope's level).</p>
     *
     * <p><b>Nested calls:</b> If a transaction for {@code ds} is already active on the current
     * thread when this method is invoked, the callable participates in that existing transaction
     * (as a nested scope). The underlying {@code Connection} is not actually
     * committed until the outermost transaction scope closes. If {@code cmd} fails in a nested call,
     * the shared transaction is marked rollback-only, so the outermost scope rolls back even if it
     * later calls {@code commit()}, which then throws {@link RollbackOnlyException}.</p>
     *
     * <p><b>Spring integration:</b> Inside a Spring-managed transaction that owns the connection bound to {@code ds},
     * this method participates in it (see {@link #beginTransaction(javax.sql.DataSource)}): {@code cmd}'s work is committed
     * by Spring when its transaction completes, not by this method, and if {@code cmd} fails the Spring transaction is
     * marked rollback-only, so Spring rolls it back.</p>
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
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it
     *         (for example, it completes an enclosing scope, which exits this method's scope); {@link RollbackOnlyException} if this method's scope is the
     *         outermost one and the transaction was marked rollback-only, typically by a nested transactional failure that {@code cmd} caught: the
     *         transaction has been rolled back.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @see SqlTransaction
     * @see #runInTransaction(javax.sql.DataSource, Throwables.Runnable)
     * @see #beginTransaction(javax.sql.DataSource)
     */
    @Beta
    public static <R, E extends Throwable> R callInTransaction(final javax.sql.DataSource ds, final Throwables.Callable<? extends R, E> cmd)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, E {
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
     * {@code cmd} is the existing transaction's connection. If {@code cmd} fails in a nested call, the
     * shared transaction is marked rollback-only, so the outermost scope rolls back even if it later
     * calls {@code commit()}, which then throws {@link RollbackOnlyException}.</p>
     *
     * <p><b>Spring integration:</b> Inside a Spring-managed transaction that owns the connection bound to {@code ds},
     * this method participates in it (see {@link #beginTransaction(javax.sql.DataSource)}): {@code cmd}'s work is committed
     * by Spring when its transaction completes, not by this method, and if {@code cmd} fails the Spring transaction is
     * marked rollback-only, so Spring rolls it back.</p>
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
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it
     *         (for example, it completes an enclosing scope, which exits this method's scope); {@link RollbackOnlyException} if this method's scope is the
     *         outermost one and the transaction was marked rollback-only, typically by a nested transactional failure that {@code cmd} caught: the
     *         transaction has been rolled back.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @see SqlTransaction
     * @see #runInTransaction(javax.sql.DataSource, Throwables.Consumer)
     * @see #callInTransaction(javax.sql.DataSource, Throwables.Callable)
     */
    @Beta
    public static <T, E extends Throwable> T callInTransaction(final javax.sql.DataSource ds, final Throwables.Function<Connection, T, E> cmd)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, E {
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
     * {@link IsolationLevel#DEFAULT} isolation level, which keeps the connection's current isolation
     * level (or, for a nested call, the enclosing scope's level).</p>
     *
     * <p><b>Nested calls:</b> If a transaction for {@code ds} is already active on the current
     * thread, the runnable participates in that existing transaction (as a nested
     * scope). The underlying {@code Connection} is not actually committed until the
     * outermost transaction scope closes. If {@code cmd} fails in a nested call, the shared
     * transaction is marked rollback-only, so the outermost scope rolls back even if it later
     * calls {@code commit()}, which then throws {@link RollbackOnlyException}.</p>
     *
     * <p><b>Spring integration:</b> Inside a Spring-managed transaction that owns the connection bound to {@code ds},
     * this method participates in it (see {@link #beginTransaction(javax.sql.DataSource)}): {@code cmd}'s work is committed
     * by Spring when its transaction completes, not by this method, and if {@code cmd} fails the Spring transaction is
     * marked rollback-only, so Spring rolls it back.</p>
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
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it
     *         (for example, it completes an enclosing scope, which exits this method's scope); {@link RollbackOnlyException} if this method's scope is the
     *         outermost one and the transaction was marked rollback-only, typically by a nested transactional failure that {@code cmd} caught: the
     *         transaction has been rolled back.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @see SqlTransaction
     * @see #callInTransaction(javax.sql.DataSource, Throwables.Callable)
     * @see #beginTransaction(javax.sql.DataSource)
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, E {
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
     * {@code cmd} is the existing transaction's connection. If {@code cmd} fails in a nested call, the
     * shared transaction is marked rollback-only, so the outermost scope rolls back even if it later
     * calls {@code commit()}, which then throws {@link RollbackOnlyException}.</p>
     *
     * <p><b>Spring integration:</b> Inside a Spring-managed transaction that owns the connection bound to {@code ds},
     * this method participates in it (see {@link #beginTransaction(javax.sql.DataSource)}): {@code cmd}'s work is committed
     * by Spring when its transaction completes, not by this method, and if {@code cmd} fails the Spring transaction is
     * marked rollback-only, so Spring rolls it back.</p>
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
     * @throws IllegalStateException if {@code cmd} completes or invalidates the shared transaction before this method commits it
     *         (for example, it completes an enclosing scope, which exits this method's scope); {@link RollbackOnlyException} if this method's scope is the
     *         outermost one and the transaction was marked rollback-only, typically by a nested transactional failure that {@code cmd} caught: the
     *         transaction has been rolled back.
     * @throws UncheckedSQLException if beginning, committing, or rolling back the transaction fails.
     *         A rollback failure is suppressed when {@code cmd} has already failed.
     * @throws E if {@code cmd} throws an exception (the transaction is rolled back before propagating).
     * @see SqlTransaction
     * @see #callInTransaction(javax.sql.DataSource, Throwables.Function)
     * @see #runInTransaction(javax.sql.DataSource, Throwables.Runnable)
     */
    @Beta
    public static <E extends Throwable> void runInTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<Connection, E> cmd)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, E {
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
     * @throws RuntimeException if rollback fails and {@code commandFailure} is {@code null} (an {@link Error} is rethrown likewise);
     *         otherwise the rollback failure is suppressed on that failure.
     */
    static void rollbackAfterTransactionCommand(final SqlTransaction tran, final Throwable commandFailure) throws RuntimeException {
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
     * this library's participation in Spring transactions is also suspended for {@code ds} for the duration of
     * {@code cmd}, so the callable does not join a Spring {@code @Transactional} transaction on {@code ds}
     * through this library's connection-acquisition helpers, while work on other data sources still joins
     * theirs. This does not suspend Spring's own transaction synchronization, affect other database
     * libraries, or detach an already acquired connection. The callback must obtain its connections
     * through the appropriate helpers.</p>
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
     * // No active transaction: the callback acquires connections as needed
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
     * @throws IllegalStateException if a transaction for {@code ds} was active and, after {@code cmd} completes normally, another
     *         transaction for {@code ds} opened inside {@code cmd} is still open; if {@code cmd} throws, that condition is attached to
     *         its exception as a suppressed exception instead.
     * @see SqlTransaction
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)
     * @see SqlTransaction#callOutsideTransaction(Throwables.Callable)
     */
    @Beta
    public static <R, E extends Throwable> R callOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Callable<? extends R, E> cmd)
            throws IllegalArgumentException, E, IllegalStateException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        // Suspended for ds only: inside a REQUIRES_NEW method on one data source, work on another one used to ignore that data
        // source's Spring transaction too, so it ran on a connection of its own and committed on its own.
        final boolean suspended = suspendSpringTransactional(ds);

        try {
            // Looked up inside the try: a failure there left the suspension on the (pooled) thread.
            final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.call();
            } else {
                return tran.callOutsideTransaction(cmd);
            }
        } finally {
            if (suspended) {
                resumeSpringTransactional(ds);
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
     * <p><b>Spring integration:</b> This library's participation in Spring transactions is suspended for
     * {@code ds} for the duration of {@code cmd} (work on other data sources still joins theirs). Spring's
     * own transaction synchronization and other database libraries are unaffected.</p>
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
     * @throws IllegalStateException if a transaction for {@code ds} was active and, after {@code cmd} completes normally, another
     *         transaction for {@code ds} opened inside {@code cmd} is still open; if {@code cmd} throws, that condition is attached to
     *         its exception as a suppressed exception instead.
     * @see SqlTransaction
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Consumer)
     */
    @Beta
    public static <T, E extends Throwable> T callOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Function<javax.sql.DataSource, T, E> cmd)
            throws IllegalArgumentException, E, IllegalStateException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        // Suspended for ds only: inside a REQUIRES_NEW method on one data source, work on another one used to ignore that data
        // source's Spring transaction too, so it ran on a connection of its own and committed on its own.
        final boolean suspended = suspendSpringTransactional(ds);

        try {
            // Looked up inside the try: a failure there left the suspension on the (pooled) thread.
            final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                return cmd.apply(ds);
            } else {
                return tran.callOutsideTransaction(() -> cmd.apply(ds));
            }
        } finally {
            if (suspended) {
                resumeSpringTransactional(ds);
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
     * <p><b>Spring integration:</b> This library's participation in Spring transactions is suspended for
     * {@code ds} for the duration of {@code cmd} (work on other data sources still joins theirs). Spring's
     * own transaction synchronization and other database libraries are unaffected.</p>
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
     * @throws IllegalStateException if a transaction for {@code ds} was active and, after {@code cmd} completes normally, another
     *         transaction for {@code ds} opened inside {@code cmd} is still open; if {@code cmd} throws, that condition is attached to
     *         its exception as a suppressed exception instead.
     * @see SqlTransaction
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)
     * @see SqlTransaction#runOutsideTransaction(Throwables.Runnable)
     */
    @Beta
    public static <E extends Throwable> void runOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Runnable<E> cmd)
            throws IllegalArgumentException, E, IllegalStateException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        final boolean suspended = suspendSpringTransactional(ds);

        try {
            // Looked up inside the try: a failure there left the suspension on the (pooled) thread.
            final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.run();
            } else {
                tran.runOutsideTransaction(cmd);
            }
        } finally {
            if (suspended) {
                resumeSpringTransactional(ds);
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
     * <p><b>Spring integration:</b> This library's participation in Spring transactions is suspended for
     * {@code ds} for the duration of {@code cmd} (work on other data sources still joins theirs). Spring's
     * own transaction synchronization and other database libraries are unaffected.</p>
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
     * @throws IllegalStateException if a transaction for {@code ds} was active and, after {@code cmd} completes normally, another
     *         transaction for {@code ds} opened inside {@code cmd} is still open; if {@code cmd} throws, that condition is attached to
     *         its exception as a suppressed exception instead.
     * @see SqlTransaction
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Function)
     */
    @Beta
    public static <E extends Throwable> void runOutsideTransaction(final javax.sql.DataSource ds, final Throwables.Consumer<javax.sql.DataSource, E> cmd)
            throws IllegalArgumentException, E, IllegalStateException {
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(cmd, cs.cmd);

        final boolean suspended = suspendSpringTransactional(ds);

        try {
            // Looked up inside the try: a failure there left the suspension on the (pooled) thread.
            final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

            if (tran == null) {
                cmd.accept(ds);
            } else {
                tran.runOutsideTransaction(() -> cmd.accept(ds));
            }
        } finally {
            if (suspended) {
                resumeSpringTransactional(ds);
            }
        }
    }

    /**
     * Executes the given runnable with this library's Spring transaction participation temporarily disabled for
     * the current thread.
     *
     * <p>When this library is used inside a Spring application, JDBC connections are normally
     * obtained via {@code DataSourceUtils.getConnection()}, which participates in any
     * Spring-managed ({@code @Transactional}) transaction that is active on the calling thread.
     * This method sets a thread-local flag that prevents that participation for the duration of
     * {@code sqlAction}, so connections obtained through this library's acquisition helpers during
     * execution come from the pool directly instead of from Spring's transaction synchronization.</p>
     *
     * <p>This flag does not suspend Spring's own transaction synchronization or affect connection
     * acquisition in other libraries. It also does not detach an already acquired connection.</p>
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
     * @see SqlTransaction
     * @see #callIgnoringSpringTransaction(Throwables.Callable)
     * @see #runOutsideTransaction(javax.sql.DataSource, Throwables.Runnable)
     */
    public static <E extends Exception> void runIgnoringSpringTransaction(final Throwables.Runnable<E> sqlAction) throws IllegalArgumentException, E {
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
     * Executes the given callable with this library's Spring transaction participation temporarily disabled for
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
     * @see SqlTransaction
     * @see #runIgnoringSpringTransaction(Throwables.Runnable)
     * @see #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)
     */
    public static <R, E extends Exception> R callIgnoringSpringTransaction(final Throwables.Callable<? extends R, E> sqlAction)
            throws IllegalArgumentException, E {
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
     * Returns whether this library uses Spring-managed transactions for {@code ds} on the current thread: Spring is on the
     * class path, and their use is neither disabled on the thread ({@link #runIgnoringSpringTransaction(Throwables.Runnable)})
     * nor suspended for {@code ds} ({@link #callOutsideTransaction(javax.sql.DataSource, Throwables.Callable)}; a Spring
     * transaction begun inside the suspension is used, see {@link #suspendSpringTransactional(javax.sql.DataSource)}).
     *
     * @param ds the data source, or {@code null} (only the thread's setting applies)
     * @return {@code true} if connections for {@code ds} are acquired and released through Spring
     */
    static boolean isSpringTransactionalUsed(final javax.sql.DataSource ds) {
        if (!isInSpring || isSpringTransactionalDisabled_TL.get()) { //NOSONAR
            return false;
        }

        final Map<javax.sql.DataSource, SpringSuspension> suspended = springSuspendedDataSources_TL.get();

        if (suspended == null || ds == null) {
            return true;
        }

        final javax.sql.DataSource key = dataSourceOutsideSpringTransaction(ds);
        final SpringSuspension suspension = suspended.get(key);

        if (suspension == null) {
            return true;
        }

        // WHY the binding: a Spring transaction begun inside the suspension (a REQUIRES_NEW scope in work run outside the
        // transaction) binds a resource of its own, and is used. The data source's Spring transactions were all ignored, so the
        // work in it ran on a connection of its own and committed, whatever that transaction's outcome. WHY every enclosing
        // suspension: Spring may bind again, inside a nested suspension, the resource an enclosing one ignores (a programmatic
        // commit of the transaction begun between them), whose work then joined the transaction left by the enclosing one.
        final Object bound = boundSpringResource(key);

        for (SpringSuspension s = suspension; s != null; s = s.enclosing()) {
            if (bound == s.resource()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Suspends this library's use of Spring-managed transactions for {@code ds} on the current thread, unless it is not used
     * for {@code ds} anyway; {@link #resumeSpringTransactional(javax.sql.DataSource)} resumes it. The suspension applies to
     * what Spring binds to {@code ds} now (the connection holder of its transaction, or none): a Spring transaction begun
     * while it lasts binds another resource, and is used again until it completes, unless an enclosing suspension of
     * {@code ds} applies to that resource.
     *
     * @param ds the data source
     * @return {@code true} if it was suspended by this call, so the caller must resume it
     */
    static boolean suspendSpringTransactional(final javax.sql.DataSource ds) {
        if (!isSpringTransactionalUsed(ds)) {
            return false;
        }

        Map<javax.sql.DataSource, SpringSuspension> suspended = springSuspendedDataSources_TL.get();

        if (suspended == null) {
            suspended = new IdentityHashMap<>();
            springSuspendedDataSources_TL.set(suspended);
        }

        final javax.sql.DataSource key = dataSourceOutsideSpringTransaction(ds);

        // Within a Spring transaction begun inside an enclosing suspension of ds, which resumes once this one ends.
        suspended.put(key, new SpringSuspension(boundSpringResource(key), suspended.get(key)));

        logger.debug("Suspended Spring Transactional integration for a data source");

        return true;
    }

    /**
     * Resumes this library's use of Spring-managed transactions for {@code ds} on the current thread, after
     * {@link #suspendSpringTransactional(javax.sql.DataSource)} suspended it: the enclosing suspension of {@code ds}, if any,
     * applies again.
     *
     * @param ds the data source
     */
    static void resumeSpringTransactional(final javax.sql.DataSource ds) {
        final Map<javax.sql.DataSource, SpringSuspension> suspended = springSuspendedDataSources_TL.get();

        if (suspended != null) {
            final javax.sql.DataSource key = dataSourceOutsideSpringTransaction(ds);
            final SpringSuspension suspension = suspended.remove(key);

            if (suspension != null && suspension.enclosing() != null) {
                suspended.put(key, suspension.enclosing());
            }

            if (suspended.isEmpty()) {
                springSuspendedDataSources_TL.remove();
            }
        }
    }

    /**
     * Returns whether work on {@code ds} on the current thread runs in a transaction, as
     * {@link #isInTransaction(javax.sql.DataSource)} tells, without reading the state of a connection and without throwing: a
     * Spring connection holder whose ownership can't be determined counts as a transaction. Keeps the DAO result caches out of
     * transactions.
     *
     * @param ds the data source
     * @return {@code true} if a {@code JdbcUtil} transaction for {@code ds}, or a Spring-managed transaction owning its
     *         connection, is active on the current thread
     */
    static boolean mayRunInTransaction(final javax.sql.DataSource ds) {
        if (TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL) != null) {
            return true;
        }

        if (!isSpringTransactionalUsed(ds)) {
            return false;
        }

        try {
            return boundSpringResource(ds) instanceof final org.springframework.jdbc.datasource.ConnectionHolder holder
                    && SpringConnectionHolderAccess.isOwnedByTransaction(holder);
        } catch (final IllegalStateException e) {
            return true;
        } catch (final LinkageError e) {
            return false;
        }
    }

    /**
     * Returns whether work on {@code ds} on the current thread runs in a {@code JdbcUtil} transaction of its own (one that does
     * not participate in a Spring-managed transaction): its commit is observed exactly, while Spring reports the completion of
     * the innermost Spring transaction on the thread only, which needn't be the one the work runs in (see
     * {@link #runAfterCommit(javax.sql.DataSource, Object, Runnable)}).
     *
     * @param ds the data source
     * @return {@code true} if such a transaction is active for {@code ds} on the current thread
     */
    static boolean isInTransactionOfItsOwn(final javax.sql.DataSource ds) {
        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        return tran != null && !tran.isSpringParticipating();
    }

    /**
     * Runs {@code action} once the transaction that work on {@code ds} on the current thread runs in may have committed: the
     * {@code JdbcUtil} transaction for {@code ds}, or else the Spring-managed transaction of the thread, through its
     * transaction synchronization (also for a {@code JdbcUtil} transaction participating in it). It runs after a commit, and
     * also when a commit's outcome is unknown: a {@code JdbcUtil} commit that failed (the database may have committed, the
     * acknowledgement lost) or an unknown outcome Spring reports. It is discarded if the transaction rolls back without
     * attempting a commit. Only for actions that are safe to run once too often, such as cache invalidations: not for work
     * that needs a confirmed commit. Without such a transaction, or when its commit can't be observed (Spring's transaction
     * synchronization is not active, or the transaction has committed already), {@code action} runs right away. As for
     * rollback actions (see {@link #registerSpringRollbackAction(javax.sql.DataSource, Runnable)}), the Spring transaction is
     * the innermost one on the thread: for work on the data source of an enclosing transaction while another transaction
     * manager's transaction runs inside it, the action follows the inner transaction (it runs when that one commits).
     *
     * @param ds the data source of the work
     * @param key identifies the action: one whose key equals that of an action registered before with the same transaction is
     *        not registered (it does the same), and is not run; {@code null} to register it in any case
     * @param action the action to run after the commit
     */
    static void runAfterCommit(final javax.sql.DataSource ds, final Object key, final Runnable action) {
        final TransactionContext tran = TransactionContext.getTransaction(ds, CreatedBy.JDBC_UTIL);

        if (tran != null && !tran.isSpringParticipating()) {
            tran.addCommitAction(key, action);
            return;
        }

        // A participating transaction commits with the Spring transaction, also inside runIgnoringSpringTransaction (which still
        // finds it): the action ran right away there, before the Spring commit.
        if (tran != null || isSpringTransactionalUsed(ds)) {
            try {
                if (SpringRollbackActions.registerCommitAction(key, action)) {
                    return;
                }
            } catch (final LinkageError e) {
                // Spring's transaction classes can't be used: run it now.
            }
        }

        action.run();
    }

    /**
     * Binds {@code query}, prepared on the connection of {@code tran}, to that transaction: it then runs only on the
     * transaction's thread and while the transaction is open, or, for a transaction participating in a Spring-managed one
     * (whose connection Spring keeps), while that Spring transaction runs (see {@code AbstractQuery.assertNotClosed()}); the
     * deferred execution of a lazy stream it returned is checked for the latter only (see
     * {@code TransactionContext.assertDeferredQueryCanRun()}).
     *
     * @param <Q> the query type
     * @param tran the transaction the query was prepared in
     * @param query the query
     * @return {@code query}
     */
    private static <Q extends AbstractQuery<?, ?>> Q inTransaction(final TransactionContext tran, final Q query) {
        query.transaction = tran;
        return query;
    }

    /**
     * Returns the resource Spring has bound to {@code ds} (to the target of Spring {@code TransactionAwareDataSourceProxy}
     * layers) on the current thread, such as the connection holder of the Spring-managed transaction that owns it. Spring
     * binds another one while another transaction runs on {@code ds} ({@code REQUIRES_NEW}), and none while it suspends its
     * transaction ({@code NOT_SUPPORTED}).
     *
     * @param ds the data source
     * @return the bound resource, or {@code null} if none is bound (or Spring is not on the class path)
     */
    static Object boundSpringResource(final javax.sql.DataSource ds) {
        if (!isInSpring) {
            return null;
        }

        try {
            return org.springframework.transaction.support.TransactionSynchronizationManager.getResource(unwrapSpringDataSource(ds));
        } catch (final LinkageError e) {
            return null;
        }
    }

    /**
     * Returns the tuple of (generated-key extractor, ID getter, ID setter) used to populate entity IDs
     * from JDBC generated keys for the given DAO interface and entity class.
     *
     * <p>When invoked, an ID getter for an entity with ID properties rejects a {@code null} entity with
     * {@link IllegalArgumentException}. An ID setter does the same when the supplied ID would assign
     * an entity property. Callbacks for entities without ID properties remain no-ops, and composite-ID
     * setters still ignore {@code null} or unsupported IDs. A composite {@link EntityId} with no entries matching
     * entity properties also leaves the entity untouched, except that a single entry (a generated key returned under a
     * driver-specific label such as {@code GENERATED_KEY}) is assigned to the ID property declared as database-generated
     * ({@code @ReadOnlyId}, {@code @Id} with {@code @ReadOnly}, or JPA {@code @GeneratedValue}), if exactly one is declared;
     * otherwise it is left unassigned and a warning is logged.</p>
     *
     * <p>For a composite ID, a single generated-key column is extracted as an {@link EntityId} (keyed by the ID property
     * the column maps to, or by the column label), whatever the ID type: the ID setter of a bean/record ID class accepts it
     * and assigns only that key. Callers return the entity's full ID after assigning it.</p>
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
            final PropInfo generatedIdPropInfo = isNoId ? null : getDeclaredGeneratedIdProp(idPropInfoList);
            final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
            final boolean isOneId = !isNoId && idPropNameList.size() == 1;
            final boolean isEntityId = idType != null && EntityId.class.isAssignableFrom(idType);
            final BeanInfo idBeanInfo = Beans.isBeanClass(idType) ? ParserUtil.getBeanInfo(idType) : null;

            final com.landawn.abacus.util.function.Function<Object, ID> idGetter = isNoId ? noIdGeneratorGetterSetter._2 //
                    : (isOneId ? entity -> {
                        N.checkArgNotNull(entity, cs.entity);

                        return idPropInfo.getPropValue(entity);
                    } //
                            : (isEntityId ? entity -> {
                                N.checkArgNotNull(entity, cs.entity);

                                final Seid ret = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                for (final PropInfo propInfo : idPropInfoList) {
                                    ret.set(propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) ret;
                            } : entity -> {
                                N.checkArgNotNull(entity, cs.entity);

                                final Object ret = idBeanInfo.createBeanResult();

                                for (final PropInfo propInfo : idPropInfoList) {
                                    // Set through idBeanInfo: for an immutable ID class (e.g. a record) 'ret' is the constructor-argument
                                    // array or builder, not an ID instance, so Beans.setPropValue(ret, ...) cannot resolve its properties.
                                    idBeanInfo.setPropValue(ret, propInfo.name, propInfo.getPropValue(entity));
                                }

                                return (ID) idBeanInfo.finishBeanResult(ret);
                            }));

            final com.landawn.abacus.util.function.BiConsumer<ID, Object> idSetter = isNoId ? noIdGeneratorGetterSetter._3 //
                    : (isOneId ? (id, entity) -> {
                        N.checkArgNotNull(entity, cs.entity);
                        idPropInfo.setPropValue(entity, id);
                    } //
                            : (isEntityId ? (id, entity) -> {
                                if (id instanceof final EntityId entityId) {
                                    PropInfo propInfo = null;
                                    boolean anyPropSet = false;

                                    for (final String propName : entityId.keySet()) {
                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null) {
                                            N.checkArgNotNull(entity, cs.entity);
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                            anyPropSet = true;
                                        }
                                    }

                                    if (!anyPropSet && entityId.size() == 1) {
                                        setSingleUnlabeledGeneratedKey(entityClass, generatedIdPropInfo, entityId, entity);
                                    }
                                } else {
                                    logger.warn("Cannot set generated keys for unsupported id type(idType={})",
                                            id == null ? "null" : ClassUtil.getCanonicalClassName(id.getClass()));
                                }
                            } : (id, entity) -> {
                                if (id instanceof final EntityId entityId) {
                                    // A single generated key of a composite bean/record id (see the key extractor below): assign
                                    // only that key, leaving the other id properties of the entity untouched.
                                    PropInfo propInfo = null;
                                    boolean anyPropSet = false;

                                    for (final String propName : entityId.keySet()) {
                                        if ((propInfo = entityInfo.getPropInfo(propName)) != null && idPropNameList.contains(propInfo.name)) {
                                            N.checkArgNotNull(entity, cs.entity);
                                            propInfo.setPropValue(entity, entityId.get(propName));
                                            anyPropSet = true;
                                        }
                                    }

                                    if (!anyPropSet && entityId.size() == 1) {
                                        setSingleUnlabeledGeneratedKey(entityClass, generatedIdPropInfo, entityId, entity);
                                    }
                                } else if (id != null && Beans.isBeanClass(id.getClass())) {
                                    N.checkArgNotNull(entity, cs.entity);

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
                                                // Some drivers (e.g. MySQL, SQL Server) return only the auto-generated column of a composite key.
                                                // Wrap it in an EntityId, for an EntityId as for a bean/record id class: a bare value is rejected
                                                // by the paired id setters and by DaoImpl's composite default-id tests (ClassCastException), and
                                                // was returned from insert as if it were the composite id. A label that names no id property
                                                // (e.g. MySQL's GENERATED_KEY) is kept as-is instead of being guessed onto an id property here:
                                                // the id setter resolves it against the entity (setSingleUnlabeledGeneratedKey).
                                                final String columnLabel = columnLabels.get(0);
                                                final String propName = columnPropNameMap.get(columnLabel);
                                                final PropInfo propInfo = propName == null ? null : entityInfo.getPropInfo(propName);
                                                final Seid id = Seid.of(ClassUtil.getSimpleClassName(entityClass));

                                                return propInfo != null && idPropNameList.contains(propInfo.name)
                                                        ? id.set(propInfo.name, propInfo.dbType.get(rs, 1))
                                                        : id.set(columnLabel, JdbcUtil.getColumnValue(rs, 1));
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
                                                // Resolve each label through the entity's column-to-property mapping first, as the EntityId
                                                // branches above do: matching the label only against the ID class's property names misses an
                                                // ID column whose name differs from its property (e.g. @Column("SEQ_NO") long entityId), whose
                                                // generated value was then silently dropped (left at the ID class's default).
                                                final Object id = idBeanInfo.createBeanResult();

                                                for (final String columnLabel : columnLabels) {
                                                    final String propName = columnPropNameMap.get(columnLabel);
                                                    final PropInfo mappedPropInfo = propName == null ? null : idBeanInfo.getPropInfo(propName);
                                                    final PropInfo propInfo = mappedPropInfo == null ? idBeanInfo.getPropInfo(columnLabel) : mappedPropInfo;

                                                    if (propInfo != null) {
                                                        propInfo.setPropValue(id, propInfo.dbType.get(rs, columnLabel));
                                                    }
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
     * Returns the only ID property declared as database-generated: marked {@code @ReadOnlyId} (or {@code @Id} plus
     * {@code @ReadOnly}), or annotated with JPA's {@code @GeneratedValue} ({@code jakarta.persistence} or
     * {@code javax.persistence}).
     *
     * @param idPropInfoList The entity's ID properties.
     * @return The single declared generated ID property, or {@code null} if none or more than one is declared.
     */
    private static PropInfo getDeclaredGeneratedIdProp(final List<PropInfo> idPropInfoList) {
        PropInfo result = null;

        for (final PropInfo propInfo : idPropInfoList) {
            if (propInfo.isMarkedAsReadOnlyId || hasGeneratedValueAnnotation(propInfo)) {
                if (result != null) {
                    return null;
                }

                result = propInfo;
            }
        }

        return result;
    }

    /**
     * Returns whether {@code propInfo} carries a JPA {@code @GeneratedValue} annotation, matched by name so that
     * neither {@code jakarta.persistence} nor {@code javax.persistence} is required on the class path.
     *
     * @param propInfo The property to inspect.
     * @return {@code true} if the property is annotated with {@code jakarta.persistence.GeneratedValue} or
     *         {@code javax.persistence.GeneratedValue}.
     */
    private static boolean hasGeneratedValueAnnotation(final PropInfo propInfo) {
        for (final Class<?> annotationType : propInfo.annotations.keySet()) {
            final String name = annotationType.getName();

            if ("jakarta.persistence.GeneratedValue".equals(name) || "javax.persistence.GeneratedValue".equals(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Assigns a single generated key whose label names no entity property (e.g. MySQL's {@code GENERATED_KEY} or SQL
     * Server's {@code GENERATED_KEYS}, returned for a composite ID) to the ID property declared as database-generated
     * (see {@link #getDeclaredGeneratedIdProp(List)}). Without exactly one such declaration the target cannot be
     * identified reliably, so the key is left unassigned and a warning is logged.
     *
     * @param entityClass The entity class, for logging.
     * @param generatedIdPropInfo The declared database-generated ID property, or {@code null} if there is none.
     * @param generatedKey The single-entry generated key.
     * @param entity The entity to update.
     * @throws IllegalArgumentException if {@code entity} is {@code null}.
     */
    private static void setSingleUnlabeledGeneratedKey(final Class<?> entityClass, final PropInfo generatedIdPropInfo, final EntityId generatedKey,
            final Object entity) throws IllegalArgumentException {
        N.checkArgNotNull(entity, cs.entity);

        final String keyName = generatedKey.keySet().iterator().next();

        // Only declared metadata identifies the generated column reliably. Guessing from the entity's current values
        // (e.g. "the ID property still holding a default value") can overwrite a valid component: for
        // (tenantId = 0, entityId = 9 supplied explicitly) the driver still reports GENERATED_KEY = 9, and tenantId
        // would become 9, so later updates/deletes would target the wrong row.
        if (generatedIdPropInfo == null) {
            logger.warn(
                    "Cannot tell which ID property of {} the generated key {} belongs to. Mark the database-generated ID property with"
                            + " @ReadOnlyId (or @GeneratedValue), or register an ID extractor with JdbcUtil.setIdExtractorForDao",
                    ClassUtil.getSimpleClassName(entityClass), keyName);
        } else {
            generatedIdPropInfo.setPropValue(entity, generatedKey.get(keyName));
        }
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
     *     return new UserId(rs.getLong("tenant_id"), rs.getLong("user_id"));
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
     * if (user.isPresent()) {
     *     user.get().setStatus("INACTIVE");
     *     userDao.update(user.get());
     * }
     *
     * // Delete operations
     * userDao.deleteById(userId);
     * }</pre>
     *
     * <p><b>Advanced DAO Features:</b></p>
     * <pre>{@code
     * // Define a DAO with complex queries; @SqlSource loads the SQL mapper file used by @Query(id = ...)
     * @SqlSource("sql/OrderDao.xml")
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
     * <p>Each distinct combination of interface, target table name and the identities of the {@code DataSource} and
     * other configuration objects ({@code Dsl}, {@code SqlMapper}, {@code DaoCache}, {@code Executor}) adds an entry
     * to a process-wide pool, which keeps the proxy and everything it references (including its {@code DataSource},
     * even after that is closed, and its DAO cache). The pool holds at most 4,096 proxies: when it is full, registering
     * another one evicts the oldest entries first, in second-chance fashion rather than strict least-recently-used
     * order: an entry a repeated call has returned since the previous eviction is requeued as the newest once instead
     * of evicted. So DAOs created for short-lived {@code DataSource}s or configuration objects cannot
     * accumulate without bound. An evicted proxy keeps working for whoever holds it, and a cache the factory created
     * for it (from {@code @Cache} or an enabled {@code @CacheResult}) is closed once the evicted proxy is no longer
     * reachable; a later call with the same configuration creates a new proxy, at the full creation cost. So still do
     * not create DAOs per request or per tenant on demand, and pass the same configuration instances to every call
     * instead of new ones.</p>
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
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null} or not an interface, {@code ds} is {@code null}, the DAO
     *         type or entity/ID mapping is invalid, or the SQL dialect, query metadata, or other DAO configuration is invalid.
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down, or the generated join SQL
     *         of a {@code @JoinedBy} property lacks a clause required to build its query plans.
     * @throws UncheckedSQLException if obtaining database product metadata fails while initializing the DAO.
     * @throws UncheckedIOException if an XML SQL mapper file referenced by {@code @SqlSource} cannot be read.
     * @throws ParsingException if an XML SQL mapper file referenced by {@code @SqlSource} is not well-formed,
     *         or its root element is not {@code sqlMapper}.
     * @throws UnsupportedOperationException if DAO annotations, method signatures, SQL operations, return types,
     *         or cache settings are unsupported.
     * @see Dao
     * @see CrudDao
     * @see #createDao(Class, javax.sql.DataSource, SqlDialect)
     * @see #createDao(Class, javax.sql.DataSource, DaoCreationOptions)
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds) throws IllegalArgumentException,
            IllegalStateException, UncheckedSQLException, UncheckedIOException, ParsingException, UnsupportedOperationException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgument(daoInterface.isInterface(), "'daoInterface' must be an interface. It can't be {}", daoInterface);
        N.checkArgNotNull(ds, cs.ds);

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
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null} or not an interface, {@code ds} or {@code sqlDialect} is
     *         {@code null}, the DAO type or entity/ID mapping is invalid, or the SQL dialect, query metadata, or other DAO configuration
     *         is invalid.
     * @throws IllegalStateException if the shared asynchronous executor has already been shut down, or the generated join SQL
     *         of a {@code @JoinedBy} property lacks a clause required to build its query plans.
     * @throws UncheckedSQLException if obtaining database product metadata fails while initializing the DAO.
     * @throws UncheckedIOException if an XML SQL mapper file referenced by {@code @SqlSource} cannot be read.
     * @throws ParsingException if an XML SQL mapper file referenced by {@code @SqlSource} is not well-formed,
     *         or its root element is not {@code sqlMapper}.
     * @throws UnsupportedOperationException if DAO annotations, method signatures, SQL operations, return types,
     *         or cache settings are unsupported.
     * @see #createDao(Class, javax.sql.DataSource)
     * @see #createDao(Class, javax.sql.DataSource, DaoCreationOptions)
     * @see Dsl#forDialect(SqlDialect)
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final SqlDialect sqlDialect)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, UncheckedIOException, ParsingException,
            UnsupportedOperationException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgument(daoInterface.isInterface(), "'daoInterface' must be an interface. It can't be {}", daoInterface);
        N.checkArgNotNull(ds, cs.ds);
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
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null} or not an interface, {@code ds} is {@code null}, the DAO
     *         type or entity/ID mapping is invalid, or the SQL dialect, query metadata, or other DAO configuration is invalid.
     * @throws IllegalStateException if no executor is configured and the shared asynchronous executor has already
     *         been shut down, or the generated join SQL of a {@code @JoinedBy} property lacks a clause required to build
     *         its query plans.
     * @throws UncheckedSQLException if obtaining database product metadata fails while initializing the DAO.
     * @throws UncheckedIOException if an XML SQL mapper file referenced by {@code @SqlSource} cannot be read.
     * @throws ParsingException if an XML SQL mapper file referenced by {@code @SqlSource} is not well-formed,
     *         or its root element is not {@code sqlMapper}.
     * @throws UnsupportedOperationException if DAO annotations, method signatures, SQL operations, return types,
     *         or cache settings are unsupported.
     * @see #createDao(Class, javax.sql.DataSource)
     * @see #createDao(Class, javax.sql.DataSource, SqlDialect)
     * @see DaoCreationOptions
     */
    @SuppressWarnings("rawtypes")
    public static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds, final DaoCreationOptions daoCreationOptions)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, UncheckedIOException, ParsingException,
            UnsupportedOperationException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgument(daoInterface.isInterface(), "'daoInterface' must be an interface. It can't be {}", daoInterface);
        N.checkArgNotNull(ds, cs.ds);

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
         * The cache stays owned by the caller and is never closed by the DAO. The cache instance is part of the
         * identity of the created DAO proxy, which is registered in the DAO proxy pool (see
         * {@link JdbcUtil#createDao(Class, javax.sql.DataSource)}): reuse one cache rather than creating one per
         * {@code createDao} call, and close it only after the DAO's last use (see {@link Jdbc.DaoCache#create(int, long)}).
         * One cache can be shared by several DAOs: its keys identify the DAO and its {@code DataSource}.
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
     * <p>Inside a transaction on a DAO's data source (a {@code JdbcUtil} transaction, or a Spring-managed transaction that
     * owns its connection), the scope's cache is neither read nor filled by that DAO: its results could miss the
     * transaction's own writes, or hold work the transaction may still roll back. A write there invalidates the scope's cache
     * once the transaction has committed, and, unless it joins a {@code JdbcUtil} transaction of its own, right away as well
     * (see {@link com.landawn.abacus.jdbc.annotation.RefreshCache}). Outside a transaction, the scope's cache serves the lookup
     * of an {@code upsert} as it serves any read, so the upsert decides between insert and update on the rows as they were
     * read earlier in the scope: the writes of DAOs within the scope invalidate it, other work does not (another thread
     * outside the scope, or a statement not run through a DAO).</p>
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
     * cache. Transactions: see {@link #openDaoCacheScope()}.
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
        public void close() throws IllegalStateException {
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
     *     userDao.get(1L);   // First call hits database
     *     userDao.get(1L);   // Second call uses cache
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
    public static Jdbc.DaoCache openDaoCacheOnCurrentThread() throws IllegalStateException {
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
     * @throws IllegalStateException if a {@link DaoCacheScope} is active on the current thread.
     * @throws IllegalArgumentException if {@code localThreadCache} is {@code null}.
     * @deprecated Use {@link #openDaoCacheScope(Jdbc.DaoCache)} with try-with-resources.
     * @see Jdbc.DaoCache#createByMap()
     * @see Jdbc.DaoCache#createByMap(Map)
     * @see #openDaoCacheScope(Jdbc.DaoCache)
     * @see #closeDaoCacheOnCurrentThread()
     */
    @Deprecated
    public static Jdbc.DaoCache openDaoCacheOnCurrentThread(final Jdbc.DaoCache localThreadCache) throws IllegalStateException, IllegalArgumentException {
        checkNoActiveDaoCacheScopeForLegacyOperation();
        N.checkArgNotNull(localThreadCache, cs.localThreadCache);

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
    public static void closeDaoCacheOnCurrentThread() throws IllegalStateException {
        checkNoActiveDaoCacheScopeForLegacyOperation();
        localThreadCache_TL.remove();
    }

    /**
     * Throws {@link IllegalStateException} if a {@link DaoCacheScope} is active on the current thread, because the
     * legacy open/close cache methods would break the scope's restore-on-close bookkeeping.
     * @throws IllegalStateException if a {@link DaoCacheScope} is active on the current thread.
     */
    private static void checkNoActiveDaoCacheScopeForLegacyOperation() throws IllegalStateException {
        if (daoCacheScope_TL.get() != null) {
            throw new IllegalStateException("Legacy DAO-cache lifecycle methods cannot be used while a DaoCacheScope is active");
        }
    }

    /**
     * Builds the cache key used to store/query cached DAO results for the given method invocation:
     * {@code fullClassMethodName#tableName#daoId#serializedArgs}. Returns {@code null} when a key cannot be generated
     * (in which case the result is not cached).
     *
     * <p>{@code daoId} identifies the calling DAO proxy, and with it the DAO interface and the {@code DataSource} the
     * proxy was created with: a cache shared by several DAOs (a thread-local DAO cache scope, or one cache passed to
     * several {@code createDao} calls) must never return a result produced through another DAO or {@code DataSource}.
     * The table name stays between the first and the second separator, where the built-in caches read it to invalidate
     * the entries of an updated table.</p>
     *
     * @param tableName The name of the table the DAO operates on.
     * @param fullClassMethodName The fully qualified name of the DAO method being invoked.
     * @param daoId The identity token of the DAO proxy making the call.
     * @param args The method arguments. Can be {@code null}.
     * @param daoLogger The logger of the DAO, used to report key-generation failures.
     * @return The cache key, or {@code null} if it cannot be generated.
     */
    static String createCacheKey(final String tableName, final String fullClassMethodName, final String daoId, final Object[] args, final Logger daoLogger) {
        String paramKey = null;

        // Reuse the shared empty array: this runs on every cached DAO call, and no-arg DAO methods are common.
        final Object[] cacheKeyArgs = args == null ? N.EMPTY_OBJECT_ARRAY : args;

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

        return Strings.concat(fullClassMethodName, CACHE_KEY_SEPARATOR, tableName, CACHE_KEY_SEPARATOR, daoId, CACHE_KEY_SEPARATOR, paramKey);
    }

    // ==============================================Jdbc Context=======================================================>>
}
