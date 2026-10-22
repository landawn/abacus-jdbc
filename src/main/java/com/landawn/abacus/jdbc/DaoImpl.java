/*
 * Copyright (c) 2019, Haiyang Li.
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

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.ParsingException;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiParametersSetter;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.jdbc.Jdbc.HandlerFactory;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.BindList;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.CacheSerialization;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.annotation.FetchColumnByEntityClass;
import com.landawn.abacus.jdbc.annotation.Handler;
import com.landawn.abacus.jdbc.annotation.Handlers;
import com.landawn.abacus.jdbc.annotation.MappedByKey;
import com.landawn.abacus.jdbc.annotation.MergedById;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.annotation.OutParameter;
import com.landawn.abacus.jdbc.annotation.OutParameters;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.PrefixFieldMapping;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.annotation.SqlFragment;
import com.landawn.abacus.jdbc.annotation.SqlFragmentList;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.SqlScript;
import com.landawn.abacus.jdbc.annotation.SqlSource;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.jdbc.dao.DaoBase;
import com.landawn.abacus.jdbc.dao.DaoUtil;
import com.landawn.abacus.jdbc.dao.NonUpdateDao;
import com.landawn.abacus.jdbc.dao.ReadOnlyDao;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.JsonParser;
import com.landawn.abacus.parser.KryoParser;
import com.landawn.abacus.parser.ParserFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.AbstractQueryBuilder;
import com.landawn.abacus.query.AbstractQueryBuilder.SP;
import com.landawn.abacus.query.Dsl;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SqlBuilder;
import com.landawn.abacus.query.SqlDialect;
import com.landawn.abacus.query.SqlDialect.SqlPolicy;
import com.landawn.abacus.query.SqlMapper;
import com.landawn.abacus.query.SqlParser;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.Criteria;
import com.landawn.abacus.query.condition.Limit;
import com.landawn.abacus.query.condition.OrderBy;
import com.landawn.abacus.query.condition.SqlExpression;
import com.landawn.abacus.query.condition.Where;
import com.landawn.abacus.util.Array;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Dates;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.Immutable;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.ImmutableSet;
import com.landawn.abacus.util.IntFunctions;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Numbers;
import com.landawn.abacus.util.Pair;
import com.landawn.abacus.util.RegExUtil;
import com.landawn.abacus.util.SK;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Seq;
import com.landawn.abacus.util.Splitter;
import com.landawn.abacus.util.Splitter.MapSplitter;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Suppliers;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.IntFunction;
import com.landawn.abacus.util.function.LongFunction;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.function.TriFunction;
import com.landawn.abacus.util.stream.BaseStream;
import com.landawn.abacus.util.stream.EntryStream;
import com.landawn.abacus.util.stream.IntStream;
import com.landawn.abacus.util.stream.Stream;

/**
 * Internal implementation class providing the core runtime logic for dynamic proxy-based DAO (Data Access Object) interfaces.
 *
 * <p>This is a non-instantiable utility class. Its primary responsibility is the
 * {@link #createDao(Class, String, javax.sql.DataSource, Dsl, SqlMapper, Jdbc.DaoCache, Executor) createDao} factory
 * method, which builds an {@link InvocationHandler} for a JDK dynamic proxy that implements a user-defined DAO
 * interface. The handler intercepts each method call and dispatches it to a pre-built invoker derived from the
 * method's annotation metadata. Supported annotations include {@code @Query} (covering SELECT/INSERT/UPDATE/DELETE
 * and stored-procedure calls) together with helpers such as {@code @Bind}, {@code @BindList}, {@code @SqlScript},
 * {@code @SqlFragment}, {@code @SqlFragmentList}, and {@code @SqlSource}. The SQL operation type is determined from
 * the {@link QueryOperation} value supplied in the {@code @Query} annotation, from the SQL statement kind (e.g., {@code SELECT},
 * {@code INSERT}) detected by parsing the statement text (which also recognizes CTE and parenthesized forms), or from
 * built-in CRUD methods inherited from {@link Dao} and {@link CrudDao}.</p>
 *
 * <p>The DaoImpl class is responsible for:</p>
 * <ul>
 *   <li>Parsing and caching method-level SQL annotations and their configurations</li>
 *   <li>Building and executing SQL statements based on annotation metadata</li>
 *   <li>Managing parameter binding for named parameters, collections, and entities</li>
 *   <li>Handling result-set mapping to entities, primitives, collections, and custom types</li>
 *   <li>Supporting transactional behavior through {@code @Transactional} annotations</li>
 *   <li>Implementing cache management for {@code @CacheResult} annotated methods</li>
 *   <li>Managing join-entity relationships through {@code @JoinedBy} annotations</li>
 *   <li>Providing SQL logging and performance-monitoring capabilities</li>
 * </ul>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li><b>SQL Annotation Processing:</b> Supports {@code @Query} for inline or referenced SQL (covering SELECT/INSERT/UPDATE/DELETE
 *       and stored-procedure calls) with flexible parameter binding via {@code @Bind} and {@code @BindList},
 *       reusable SQL snippets through {@code @SqlFragment}, and field-level inline SQL definitions through {@code @SqlScript}</li>
 *   <li><b>Named Parameter Support:</b> Automatically maps method parameters to SQL named parameters (e.g., {@code :paramName})</li>
 *   <li><b>Collection Parameter Expansion:</b> Handles IN clauses by expanding collection parameters</li>
 *   <li><b>Entity Mapping:</b> Automatic mapping between database result sets and entity classes</li>
 *   <li><b>Join Support:</b> Loads related entities through {@code @JoinedBy} relationships</li>
 *   <li><b>Transaction Management:</b> Declarative transactions via {@code @Transactional}</li>
 *   <li><b>Result Caching:</b> Method-level caching with {@code @CacheResult} and {@code @RefreshCache}</li>
 *   <li><b>Batch Operations:</b> Support for batch inserts, updates, and deletes</li>
 *   <li><b>Stored Procedures:</b> Execution of database stored procedures with OUT parameters</li>
 *   <li><b>SQL Fragments:</b> Reusable SQL snippets through {@code @SqlFragment} and {@code @SqlFragmentList}</li>
 * </ul>
 *
 * <p><b>Design Pattern:</b></p>
 * <p>The DAO proxies produced by this class apply the Proxy pattern: each method invocation is intercepted by the
 * generated {@link InvocationHandler} and dispatched to a pre-built handler determined from the method's annotation
 * metadata at proxy-creation time. Method-handle caching, prepared SQL parsing, and type-safe result mapping are
 * used to keep per-call overhead low.</p>
 *
 * <p><b>Thread Safety:</b></p>
 * <p>This class has no instances, but it does maintain process-wide proxy/metadata caches and per-thread invocation
 * state. The shared caches use concurrent maps, immutable metadata is safely published before a proxy is cached, and
 * handler recursion state is isolated with {@link ThreadLocal}; the factory is therefore safe for concurrent use.
 * Database connections and transactions are managed per thread through {@link SqlTransaction} and connection pooling.</p>
 *
 * <p><b>Important Notes:</b></p>
 * <ul>
 *   <li>This is an internal framework class marked with {@code @Internal}</li>
 *   <li>Application code should never directly invoke methods on this class</li>
 *   <li>Always use public DAO interfaces ({@link Dao}, {@link CrudDao}, etc.) instead</li>
 *   <li>DAOs are created via {@link JdbcUtil#createDao(Class, javax.sql.DataSource)}</li>
 * </ul>
 *
 * @see Dao
 * @see CrudDao
 * @see JdbcUtil#createDao(Class, javax.sql.DataSource)
 * @see Query
 * @see Transactional
 * @see CacheResult
 */
@Internal
@SuppressWarnings({ "deprecation", "java:S1192", "resource", "unused" })
final class DaoImpl {

    /**
     * Prevents instantiation; this is a utility class with only static members.
     */
    private DaoImpl() {
        // utility class - prevent instantiation.
    }

    /** Literal {@code "1"} used as the selected column in generated exists queries. */
    private static final String _1 = "1";

    /** Shared JSON parser used to serialize/deserialize cached query results when JSON cache serialization applies. */
    private static final JsonParser jsonParser = ParserFactory.createJsonParser();

    /**
     * Shared Kryo parser used to deep-copy cached query results; {@code null} when Kryo is absent from the classpath
     * or cannot initialize under the current runtime settings.
     */
    private static final KryoParser kryoParser = ParserFactory.isKryoParserAvailable() ? ParserFactory.createKryoParser() : null;

    /**
     * Lazily explains why {@link #kryoParser} is {@code null}. The probe runs on the first KRYO failure rather than
     * during class initialization, so a classloader or runtime that rejects the probe can only affect a KRYO caller,
     * never DAO creation or the JSON/NONE cache paths.
     */
    private static final class KryoUnavailability {
        static final String REASON = describeKryoUnavailability();
    }

    /**
     * Marks whether the current thread is already executing inside a DAO method invocation, so nested DAO calls
     * can be distinguished from outermost ones (e.g. for {@code @Handler} invocation semantics).
     */
    static final ThreadLocal<Boolean> isInDaoMethod_TL = ThreadLocal.withInitial(() -> false);

    // Active only while an opted-in cached invocation executes; explicit method policies still apply.
    private static final ThreadLocal<Boolean> suppressInheritedResultCaching_TL = new ThreadLocal<>();

    /**
     * Default maximum number of DAO proxies kept in {@link #daoPool}.
     */
    static final int DEFAULT_DAO_POOL_CAPACITY = 4096;

    /**
     * Maximum number of DAO proxies kept in {@link #daoPool}; only tests change it. Read under the
     * {@link #daoPoolQueue} monitor.
     */
    private static int daoPoolCapacity = DEFAULT_DAO_POOL_CAPACITY;

    /**
     * Process-wide cache of created DAO proxies keyed by {@link DaoCacheKey}; repeated creation requests with the
     * same configuration return the same proxy instance while it stays cached. The cache holds at most
     * {@link #daoPoolCapacity} proxies, so that DAOs created for short-lived data sources (or other per-call
     * configuration objects) cannot accumulate: hits are lock-free and mark the entry as recently used, and a full
     * cache gives each recently used entry one second chance before evicting in insertion order (the policy of
     * JoinInfo's bounded metadata cache). An evicted proxy keeps working for whoever holds it; see
     * {@link #releaseEvictedDao(PooledDao)} for what its eviction releases. Every change is made under the
     * {@link #daoPoolQueue} monitor.
     */
    private static final Map<DaoCacheKey, PooledDao> daoPool = new ConcurrentHashMap<>();

    /**
     * Insertion order of the entries of {@link #daoPool}. Its monitor guards this queue and every change to
     * {@code daoPool}, so both always hold the same entries.
     */
    private static final ArrayDeque<PooledDao> daoPoolQueue = new ArrayDeque<>();

    /**
     * Whether the capacity warning has been logged; guarded by the {@link #daoPoolQueue} monitor.
     */
    private static boolean daoPoolCapacityWarned;

    /**
     * Source of the per-proxy identity tokens embedded in DAO result-cache keys.
     */
    private static final java.util.concurrent.atomic.AtomicLong daoIdSequence = new java.util.concurrent.atomic.AtomicLong();

    /**
     * An entry of {@link #daoPool}: a registered DAO proxy and what its creation allocated or registered for it, so
     * that its eviction can release them without calling the proxy.
     */
    @SuppressWarnings("rawtypes")
    private static final class PooledDao {
        final DaoCacheKey key;
        final DaoBase dao;
        final Class<?> daoInterface;
        final Class<?> entityClass;
        final String tableName;
        /** The join metadata retained for the proxy by {@link JoinInfo#retainDaoJoinInfo}, or {@code null}. */
        final Map<String, JoinInfo> joinBeanInfo;
        /** The cache {@code createDao} created for the proxy (it is released with the proxy), or {@code null}. */
        final Jdbc.DaoCache createdCache;
        final Logger daoLogger;
        // Set by pool hits and cleared by eviction scans; a lost update only changes which entry is evicted.
        volatile boolean recentlyUsed;

        PooledDao(final DaoCacheKey key, final DaoBase dao, final Class<?> daoInterface, final Class<?> entityClass, final String tableName,
                final Map<String, JoinInfo> joinBeanInfo, final Jdbc.DaoCache createdCache, final Logger daoLogger) {
            this.key = key;
            this.dao = dao;
            this.daoInterface = daoInterface;
            this.entityClass = entityClass;
            this.tableName = tableName;
            this.joinBeanInfo = joinBeanInfo;
            this.createdCache = createdCache;
            this.daoLogger = daoLogger;
        }
    }

    /**
     * Closes the caches that {@code createDao} created for evicted proxies once those proxies are unreachable. Created
     * on the first such eviction only.
     */
    private static final class EvictedDaoCacheCleaner {
        static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();
    }

    /**
     * Collision-safe key for the process-wide DAO proxy pool. Dependencies deliberately use
     * reference identity: two distinct data sources, mappers, caches, or executors must never
     * share a proxy merely because their identity hash codes collide.
     */
    private static final class DaoCacheKey {
        /** The DAO interface the proxy implements; compared by reference identity. */
        private final Class<?> daoInterface;
        /** The target table name override, or {@code null} when the table name is derived from the entity class. */
        private final String targetTableName;
        /** The data source backing the DAO; compared by reference identity. */
        private final Object dataSource;
        /** The SQL dialect DSL; compared by reference identity. */
        private final Object dsl;
        /** The SQL mapper holding pre-defined SQL statements, or {@code null}; compared by reference identity. */
        private final Object sqlMapper;
        /** The DAO cache, or {@code null}; compared by reference identity. */
        private final Object daoCache;
        /** The executor for asynchronous operations, or {@code null}; compared by reference identity. */
        private final Object executor;

        DaoCacheKey(final Class<?> daoInterface, final String targetTableName, final Object dataSource, final Object dsl, final Object sqlMapper,
                final Object daoCache, final Object executor) {
            this.daoInterface = daoInterface;
            this.targetTableName = targetTableName;
            this.dataSource = dataSource;
            this.dsl = dsl;
            this.sqlMapper = sqlMapper;
            this.daoCache = daoCache;
            this.executor = executor;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(daoInterface);
            result = 31 * result + Objects.hashCode(targetTableName);
            result = 31 * result + System.identityHashCode(dataSource);
            result = 31 * result + System.identityHashCode(dsl);
            result = 31 * result + System.identityHashCode(sqlMapper);
            result = 31 * result + System.identityHashCode(daoCache);
            return 31 * result + System.identityHashCode(executor);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof DaoCacheKey other)) {
                return false;
            }

            return daoInterface == other.daoInterface && Objects.equals(targetTableName, other.targetTableName) && dataSource == other.dataSource
                    && dsl == other.dsl && sqlMapper == other.sqlMapper && daoCache == other.daoCache && executor == other.executor;
        }
    }

    /** Identity-based key for the smaller cache used while resolving DAOs for joined entities. */
    private static final class JoinEntityDaoCacheKey {
        /** The referenced (joined) entity class; compared by reference identity. */
        private final Class<?> entityClass;
        /** The data source the join DAO is bound to; compared by reference identity. */
        private final javax.sql.DataSource dataSource;
        /** Whether the selected DAO must support deleting joined rows. */
        private final boolean forDelete;

        JoinEntityDaoCacheKey(final Class<?> entityClass, final javax.sql.DataSource dataSource, final boolean forDelete) {
            this.entityClass = entityClass;
            this.dataSource = dataSource;
            this.forDelete = forDelete;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * System.identityHashCode(entityClass) + System.identityHashCode(dataSource)) + Boolean.hashCode(forDelete);
        }

        @Override
        public boolean equals(final Object obj) {
            return this == obj || obj instanceof JoinEntityDaoCacheKey other && entityClass == other.entityClass && dataSource == other.dataSource
                    && forDelete == other.forDelete;
        }
    }

    /**
     * Maps each supported SQL annotation type (e.g. {@link Query}) to a factory that builds the {@link QueryInfo}
     * for an annotated DAO method from the annotation and an optional {@link SqlMapper}. Populated once in a static
     * initializer and effectively immutable afterwards.
     */
    private static final Map<Class<? extends Annotation>, BiFunction<Annotation, SqlMapper, QueryInfo>> sqlAnnoMap = new HashMap<>();

    static {
        sqlAnnoMap.put(Query.class, (final Annotation anno, final SqlMapper sqlMapper) -> {
            final Query tmp = (Query) anno;
            int queryTimeout = tmp.queryTimeoutSeconds();
            int fetchSize = tmp.fetchSize();
            int batchSize = tmp.batchSize();
            final boolean isBatch = tmp.batch();
            final QueryOperation queryOperation = tmp.op() == null ? QueryOperation.DEFAULT : tmp.op();
            final boolean isSingleParameter = tmp.collectionAsSingleParameter();
            final boolean isProcedure = tmp.procedure();

            ParsedSql parsedSql = null;
            String sql = N.notEmpty(tmp.value()) ? Strings.trim(tmp.value()[0]) : null;

            if (N.notEmpty(tmp.value()) == N.notEmpty(tmp.id())) {
                throw new IllegalArgumentException("Sql script and id both are empty or both are not empty: " + Strings.concat(sql, ", ", tmp.id()));
            }

            if (N.notEmpty(tmp.id()) && Stream.of(tmp.id()).anyMatch(it -> !RegExUtil.JAVA_IDENTIFIER_MATCHER.matcher(it).matches())) {
                throw new IllegalArgumentException("Invalid query identifiers. Query IDs don't match Java identifier specification: "
                        + Stream.of(tmp.id()).filter(it -> !RegExUtil.JAVA_IDENTIFIER_MATCHER.matcher(it).matches()).toList());
            }

            final String valueSqlId = !Strings.containsWhitespace(sql) && sqlMapper != null && sqlMapper.get(sql) != null ? sql : null;

            if (valueSqlId != null) {
                sql = sqlMapper.get(valueSqlId).parameterizedSql();
            }

            final String id = N.notEmpty(tmp.id()) ? tmp.id()[0] : valueSqlId;

            if (Strings.isNotEmpty(id)) {
                if (sqlMapper == null || sqlMapper.get(id) == null || Strings.isEmpty(sqlMapper.get(id).parameterizedSql())) {
                    throw new IllegalArgumentException("No predefined SQL found by id: " + id);
                }

                parsedSql = sqlMapper.get(id);
                sql = parsedSql.parameterizedSql();

                final Map<String, String> attrs = sqlMapper.attributes(id);

                if (N.notEmpty(attrs)) {
                    if (attrs.containsKey(SqlMapper.TIMEOUT)) {
                        queryTimeout = Numbers.toInt(attrs.get(SqlMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SqlMapper.FETCH_SIZE)) {
                        fetchSize = Numbers.toInt(attrs.get(SqlMapper.FETCH_SIZE));
                    }

                    if (attrs.containsKey(SqlMapper.BATCH_SIZE)) {
                        batchSize = Numbers.toInt(attrs.get(SqlMapper.BATCH_SIZE));
                    }
                }
            }

            // SqlParser handles parenthesized queries and CTEs (WITH ... SELECT / WITH ... INSERT),
            // which naive prefix checks misclassified (a CTE SELECT would fail DAO init, or worse,
            // silently execute via update() when the return type looked like an update count).
            final String trimmedSql = Strings.trim(sql);
            final boolean isSelect = SqlParser.isSelectQuery(trimmedSql);
            final boolean isInsert = SqlParser.isInsertQuery(trimmedSql);

            return new QueryInfo(sql, parsedSql, queryTimeout, fetchSize, isBatch, batchSize, queryOperation, isSingleParameter,
                    tmp.injectCurrentTimeParameters(), isSelect, isInsert, isProcedure, tmp.fragmentsContainNamedParameters());
        });
    }

    /**
     * Maps the supported value-holder types (the {@code u.Optional}/{@code u.Nullable} family and the
     * {@code java.util.Optional} family) to a predicate testing whether an instance holds a value.
     */
    @SuppressWarnings("rawtypes")
    private static final Map<Class<?>, Predicate> isValuePresentMap = N.newHashMap(20);

    static {
        final Map<Class<?>, Predicate<?>> tmp = N.newHashMap(20);

        tmp.put(u.Nullable.class, (final u.Nullable<?> t) -> t.isPresent()); // NOSONAR
        tmp.put(u.Optional.class, (final u.Optional<?> t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalBoolean.class, (final u.OptionalBoolean t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalChar.class, (final u.OptionalChar t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalByte.class, (final u.OptionalByte t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalShort.class, (final u.OptionalShort t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalInt.class, (final u.OptionalInt t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalLong.class, (final u.OptionalLong t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalFloat.class, (final u.OptionalFloat t) -> t.isPresent()); // NOSONAR
        tmp.put(u.OptionalDouble.class, (final u.OptionalDouble t) -> t.isPresent()); // NOSONAR

        tmp.put(java.util.Optional.class, (final java.util.Optional<?> t) -> t.isPresent()); // NOSONAR
        tmp.put(java.util.OptionalInt.class, (final java.util.OptionalInt t) -> t.isPresent()); // NOSONAR
        tmp.put(java.util.OptionalLong.class, (final java.util.OptionalLong t) -> t.isPresent()); // NOSONAR
        tmp.put(java.util.OptionalDouble.class, (final java.util.OptionalDouble t) -> t.isPresent()); // NOSONAR

        isValuePresentMap.putAll(tmp);
    }

    /** Tests whether a type is immutable (assignable to {@link Immutable}), e.g. to decide if cached results need copying. */
    private static final Predicate<? super Class<?>> isImmutableTester = Immutable.class::isAssignableFrom;

    /**
     * Return types that must never be cached: live or single-use values ({@link Iterator}, streams) and
     * {@code void}/{@code Void}.
     */
    private static final Set<Class<?>> notCacheableTypes = N.asSet(void.class, Void.class, Iterator.class, java.util.stream.BaseStream.class, BaseStream.class,
            EntryStream.class, Stream.class, Seq.class);

    /** Binds a collection of arguments to a parameterized query via {@link AbstractQuery#setParameters(Collection)}. */
    @SuppressWarnings("rawtypes")
    private static final Jdbc.BiParametersSetter<AbstractQuery, Collection> collParamsSetter = AbstractQuery::setParameters;

    /**
     * Binds the parameters of a built statement like {@link #collParamsSetter}, except that a {@link TypedParameter} is
     * bound with its declared type.
     */
    @SuppressWarnings("rawtypes")
    private static final Jdbc.BiParametersSetter<AbstractQuery, Collection> typedCollParamsSetter = (pq, params) -> {
        int parameterIndex = 1;

        for (final Object param : params) {
            if (param instanceof final TypedParameter typedParameter) {
                typedParameter.type.set(pq.stmt, parameterIndex++, typedParameter.value);
            } else {
                pq.setObject(parameterIndex++, param);
            }
        }
    };

    /**
     * A value to be bound with a given type (that of the entity property it is written to or compared with), passed to the
     * query builder as an ordinary value so that it keeps its position among the built statement's parameters; bound by
     * {@link #typedCollParamsSetter}. Deliberately not a bean or record: the builder must not read it as one.
     */
    private static final class TypedParameter {
        private final Object value;
        private final com.landawn.abacus.type.Type<Object> type;

        private TypedParameter(final Object value, final com.landawn.abacus.type.Type<Object> type) {
            this.value = value;
            this.type = type;
        }

        /**
         * Returns the value wrapped to be bound with the property's type, or the value itself (bound by its runtime class,
         * as before) when it is not an instance of the property's class: the property type would reject it, e.g. a
         * {@code Long} given for an {@code int} property.
         */
        static Object of(final Object value, final PropInfo propInfo) {
            return value == null || ClassUtil.wrap(propInfo.clazz).isInstance(value) ? new TypedParameter(value, propInfo.dbType) : value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /** Binds a bean or map argument to a named query via {@link NamedQuery#setParameters(Object)}. */
    private static final Jdbc.BiParametersSetter<NamedQuery, Object> objParamsSetter = NamedQuery::setParameters; // NOSONAR

    /**
     * Method-name prefixes identifying single-result (non-list) queries when the declared {@link QueryOperation}
     * is {@code DEFAULT}.
     */
    private static final Set<String> singleQueryPrefix = N.toSet("get", "findFirst", "findOne", "findOnlyOne", "selectFirst", "selectOne", "selectOnlyOne",
            "exist", "notExist", "has", "is");

    /**
     * Creates a {@link MethodHandle} for invoking a default interface method via {@code unreflectSpecial}.
     *
     * <p>This method tries four strategies in order to remain compatible across JDK versions and module
     * access constraints:</p>
     * <ol>
     *   <li>{@link MethodHandles#lookup()} narrowed to the declaring class with {@code unreflectSpecial} (works on
     *       most modern JDKs).</li>
     *   <li>{@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)} with {@code unreflectSpecial} (the
     *       supported JDK 9+ path for private/default interface access).</li>
     *   <li>The legacy {@code MethodHandles.Lookup(Class)} private constructor accessed reflectively, used for
     *       older JDKs where the public lookup cannot reach the default method.</li>
     *   <li>{@link MethodHandles.Lookup#findSpecial(Class, String, MethodType, Class)} as a final fallback.</li>
     * </ol>
     *
     * <p>It is used by the proxy invocation handler to delegate to {@code default} methods declared on the DAO
     * interface (e.g., user-defined helper methods that the proxy must inherit verbatim).</p>
     *
     * @param method the {@link Method} object representing the default interface method
     * @return a {@link MethodHandle} that can be used to invoke the default method on a proxy instance
     * @throws UnsupportedOperationException if all strategies fail to produce a usable handle
     */
    private static MethodHandle createMethodHandle(final Method method) throws UnsupportedOperationException {
        final Class<?> declaringClass = method.getDeclaringClass();

        try {
            return MethodHandles.lookup().in(declaringClass).unreflectSpecial(method, declaringClass);
        } catch (final Exception e) {
            try {
                return MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup()).unreflectSpecial(method, declaringClass);
            } catch (final Exception ex) {
                try {
                    final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
                    ClassUtil.setAccessibleQuietly(constructor, true);

                    return constructor.newInstance(declaringClass).in(declaringClass).unreflectSpecial(method, declaringClass);
                } catch (final Exception exx) {
                    try {
                        return MethodHandles.lookup()
                                .findSpecial(declaringClass, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                                        declaringClass);
                    } catch (final Exception exxx) {
                        throw new UnsupportedOperationException(exxx);
                    }
                }
            }
        }
    }

    /**
     * Returns a DSL that renders parameterized SQL: {@code dsl} itself if its policy is already
     * {@link SqlPolicy#PARAMETERIZED_SQL}, otherwise a copy rebuilt with that policy.
     *
     * @param dsl the source DSL; must not be {@code null}
     * @return a DSL whose SQL policy is {@link SqlPolicy#PARAMETERIZED_SQL}
     */
    private static Dsl parameterizedDsl(final Dsl dsl) {
        if (dsl.sqlDialect().sqlPolicy() == SqlPolicy.PARAMETERIZED_SQL) {
            return dsl;
        }

        return dslWithPolicy(dsl, SqlPolicy.PARAMETERIZED_SQL);
    }

    /**
     * Returns a DSL that renders named-parameter SQL: {@code dsl} itself if its policy is already
     * {@link SqlPolicy#NAMED_SQL}, otherwise a copy rebuilt with that policy.
     *
     * @param dsl the source DSL; must not be {@code null}
     * @return a DSL whose SQL policy is {@link SqlPolicy#NAMED_SQL}
     */
    private static Dsl namedDsl(final Dsl dsl) {
        if (dsl.sqlDialect().sqlPolicy() == SqlPolicy.NAMED_SQL) {
            return dsl;
        }

        return dslWithPolicy(dsl, SqlPolicy.NAMED_SQL);
    }

    /**
     * Returns a DSL rendering with the given SQL policy while preserving all other dialect customizations of
     * {@code dsl}.
     *
     * @param dsl the source DSL; must not be {@code null}
     * @param sqlPolicy the SQL rendering policy to apply
     * @return a DSL with the requested policy
     */
    private static Dsl dslWithPolicy(final Dsl dsl, final SqlPolicy sqlPolicy) {
        // Preserve all dialect customizations (including the named-parameter handler and tokenizer configuration)
        // when only the rendering policy needs to change.
        return Dsl.forDialect(dsl.sqlDialect().toBuilder().sqlPolicy(sqlPolicy).build());
    }

    /**
     * Returns whether {@code returnType} is one an update-count execution can produce ({@code void}, {@code int},
     * {@code long}, {@code boolean} or their wrappers).
     *
     * @param returnType the return type of the DAO method
     * @return {@code true} if a {@link QueryOperation#DEFAULT} statement with this return type is executed as an update
     */
    private static boolean isUpdateReturnType(final Class<?> returnType) {
        return returnType.equals(int.class) || returnType.equals(long.class) || returnType.equals(boolean.class) || returnType.equals(void.class)
                || returnType.equals(Integer.class) || returnType.equals(Long.class) || returnType.equals(Boolean.class);
    }

    /**
     * Returns whether a custom {@code @Query} method is executed as a query (reading result sets) rather than as an
     * update: a SELECT, or a procedure whose operation is not {@code update}/{@code largeUpdate} and is not
     * {@link QueryOperation#DEFAULT} with an update-count return type. Shared by the method dispatch and the
     * thread-local DAO cache classification so the two cannot disagree.
     *
     * @param queryInfo the parsed {@code @Query} information
     * @param returnType the return type of the DAO method
     * @return {@code true} if the method is executed as a query
     */
    private static boolean isExecutedAsQuery(final QueryInfo queryInfo, final Class<?> returnType) {
        final QueryOperation queryOperation = queryInfo.queryOperation;

        return queryInfo.isSelect || (queryInfo.isProcedure && !(queryOperation == QueryOperation.update || queryOperation == QueryOperation.largeUpdate)
                && (queryOperation != QueryOperation.DEFAULT || !isUpdateReturnType(returnType)));
    }

    /**
     * Determines whether the specified method should be dispatched as a list-style (multi-row) query, based on its
     * return type, its declared {@link QueryOperation}, the presence of {@code @MappedByKey}/{@code @MergedById} annotations,
     * and (for {@link QueryOperation#DEFAULT}) the method's name prefix.
     *
     * <p>The decision rules are:</p>
     * <ul>
     *   <li>Methods annotated with {@link MappedByKey @MappedByKey}: always treated as list queries.</li>
     *   <li>{@link QueryOperation#list} or {@link QueryOperation#listAll}: treated as a list query, but only if the return type is a proper
     *       {@link Collection} subtype (not raw {@code Collection.class} itself). A {@code Tuple2} return type (a stored procedure with
     *       out parameters) is treated as non-list; any other non-{@code Collection} return type raises an
     *       {@link UnsupportedOperationException}.</li>
     *   <li>Any explicit {@code QueryOperation} other than {@link QueryOperation#DEFAULT}: not a list query.</li>
     *   <li>{@link QueryOperation#DEFAULT}: only a return type that is a {@code Collection} subtype can be a list query; for any
     *       other return type the result is non-list. When the return type is a {@code Collection} subtype, the
     *       following rules are applied in order:
     *       <ol>
     *         <li>if the method is annotated with {@link MergedById @MergedById}, the result is a list;</li>
     *         <li>otherwise, if the last parameter is a parameterized {@code RowMapper}/{@code BiRowMapper}, the
     *             decision is based on comparing its element type with the return element type: it is non-list when
     *             the mapper produces the whole return type, otherwise it is a list when the return element type is
     *             assignable from the mapper element type (defaulting to a list when either element type cannot be
     *             resolved to a concrete class);</li>
     *         <li>otherwise, if the last parameter is a {@code ResultExtractor}/{@code BiResultExtractor}, the result
     *             is non-list;</li>
     *         <li>otherwise, the result is a list unless the method name starts with one of the single-result prefixes
     *             (e.g., {@code get}, {@code findFirst}, {@code findOne}, {@code findOnlyOne}, {@code selectFirst},
     *             {@code selectOne}, {@code selectOnlyOne}, {@code exist}, {@code notExist}, {@code has}, {@code is}).</li>
     *       </ol>
     *   </li>
     * </ul>
     *
     * @param method the DAO method to inspect
     * @param returnType the return type of the method
     * @param queryOperation the operation type declared for the method ({@link QueryOperation#DEFAULT} when not explicitly specified)
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @return {@code true} if the method should be dispatched as a list query, {@code false} otherwise
     * @throws UnsupportedOperationException if {@code QueryOperation} is {@link QueryOperation#list} or {@link QueryOperation#listAll} but the return
     *         type is neither a proper {@link Collection} subtype nor one of the supported exemptions
     *         ({@code Map} via {@link MappedByKey}, {@code Tuple2} for procedures with out parameters)
     */
    private static boolean isListQuery(final Method method, final Class<?> returnType, final QueryOperation queryOperation, final String fullClassMethodName)
            throws UnsupportedOperationException {
        final String methodName = method.getName();
        final Class<?>[] paramTypes = method.getParameterTypes();
        final int paramLen = paramTypes.length;

        // Checked before the list-QueryOperation Collection requirement: @MappedByKey methods return a Map built
        // from the listed rows and explicitly support QueryOperation.list.
        if (method.getAnnotation(MappedByKey.class) != null) {
            return true;
        }

        if (queryOperation == QueryOperation.list || queryOperation == QueryOperation.listAll) {
            // Tuple2<..., OutParamResult> returns are legal for procedures with list OPs; they are handled
            // by the dedicated procedure dispatch, not the plain list dispatch.
            if (Tuple2.class.isAssignableFrom(returnType)) {
                return false;
            }

            if (Collection.class.equals(returnType) || !(Collection.class.isAssignableFrom(returnType))) {
                throw new UnsupportedOperationException("The result type of list QueryOperation must be sub type of Collection, can't be: " + returnType
                        + " in method: " + fullClassMethodName);
            }

            return true;
        } else if (queryOperation != QueryOperation.DEFAULT) {
            return false;
        }

        if (Collection.class.isAssignableFrom(returnType)) {
            if (method.getAnnotation(MergedById.class) != null) {
                return true;
            }

            // Check if return type is generic List type.
            if (method.getGenericReturnType() instanceof final ParameterizedType parameterizedReturnType) {
                final java.lang.reflect.Type returnTypeArg = parameterizedReturnType.getActualTypeArguments()[0];
                final Class<?> paramClassInReturnType = returnTypeArg instanceof Class ? (Class<?>) returnTypeArg
                        : (returnTypeArg instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) returnTypeArg).getRawType() : null);

                if (paramLen > 0
                        && (Jdbc.RowMapper.class.isAssignableFrom(paramTypes[paramLen - 1])
                                || Jdbc.BiRowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]))
                        && method.getGenericParameterTypes()[paramLen - 1] instanceof final ParameterizedType rowMapperType) {

                    final java.lang.reflect.Type mapperTypeArg = rowMapperType.getActualTypeArguments()[0];
                    final Class<?> paramClassInRowMapper = mapperTypeArg instanceof Class ? (Class<?>) mapperTypeArg
                            : (mapperTypeArg instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) mapperTypeArg).getRawType() : null);

                    if (parameterizedReturnType.equals(mapperTypeArg)) {
                        // The mapper returns the method's whole return type, not one element of it.
                        return false;
                    } else if (paramClassInReturnType != null && paramClassInRowMapper != null) {
                        return paramClassInReturnType.isAssignableFrom(paramClassInRowMapper);
                    } else {
                        // Element types can't be resolved to concrete classes; default to treating it as a list query.
                        return true;
                    }
                }
            }

            if (paramLen > 0 && (Jdbc.ResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1])
                    || Jdbc.BiResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1]))) {
                return false;
            }

            return singleQueryPrefix.stream()
                    .noneMatch(
                            it -> methodName.startsWith(it) && (methodName.length() == it.length() || Character.isUpperCase(methodName.charAt(it.length()))));
        } else {
            return false;
        }
    }

    /**
     * Determines whether the method should be dispatched as an exists query (a boolean row-existence check). It is
     * an exists query when {@link QueryOperation#exists} is declared (which requires a {@code boolean}/{@code Boolean}
     * return type), or, for {@link QueryOperation#DEFAULT}, when the method returns {@code boolean}/{@code Boolean},
     * its name starts with {@code exists}/{@code exist}/{@code notExists}/{@code notExist} followed by an upper-case
     * letter or nothing, and it has no trailing row-mapper or result-extractor parameter.
     *
     * @param method the DAO method to inspect
     * @param queryOperation the operation type declared for the method
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @return {@code true} if the method should be dispatched as an exists query
     * @throws UnsupportedOperationException if {@link QueryOperation#exists} is declared but the return type is not
     *         {@code boolean} or {@code Boolean}
     */
    private static boolean isExistsQuery(final Method method, final QueryOperation queryOperation, final String fullClassMethodName)
            throws UnsupportedOperationException {
        final String methodName = method.getName();
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final int paramLen = paramTypes.length;

        final boolean isBooleanReturnType = boolean.class.equals(returnType) || Boolean.class.equals(returnType);

        if (queryOperation == QueryOperation.exists) {
            if (!isBooleanReturnType) {
                throw new UnsupportedOperationException(
                        "The result type of exists QueryOperation must be boolean or Boolean, can't be: " + returnType + " in method: " + fullClassMethodName);
            }

            return true;
        } else if (queryOperation != QueryOperation.DEFAULT) {
            return false;
        }

        if (paramLen > 0 //
                && (Jdbc.ResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1]) //
                        || Jdbc.BiResultExtractor.class.isAssignableFrom(paramTypes[paramLen - 1]) //
                        || Jdbc.RowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]) //
                        || Jdbc.BiRowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]))) {
            return false;
        }

        return isBooleanReturnType && ((methodName.startsWith("exists") && (methodName.length() == 6 || Character.isUpperCase(methodName.charAt(6))))
                || (methodName.startsWith("exist") && (methodName.length() == 5 || Character.isUpperCase(methodName.charAt(5))))
                || isNotExistsMethodName(methodName));
    }

    /**
     * Returns {@code true} if the method name starts with {@code notExists} or {@code notExist}, followed by an
     * upper-case letter or nothing.
     *
     * @param methodName the DAO method name to test
     * @return {@code true} if the name denotes a not-exists query
     */
    private static boolean isNotExistsMethodName(final String methodName) {
        return (methodName.startsWith("notExists") && (methodName.length() == 9 || Character.isUpperCase(methodName.charAt(9))))
                || (methodName.startsWith("notExist") && (methodName.length() == 8 || Character.isUpperCase(methodName.charAt(8))));
    }

    /**
     * Returns {@code true} if the method should be dispatched with find-first semantics (at most the first row is
     * read): either explicitly via {@link QueryOperation#findFirst}, or, for {@link QueryOperation#DEFAULT}, whenever
     * the method name does not start with {@code findOnlyOne}/{@code selectOnlyOne} (at-most-one-row semantics) or
     * {@code queryForSingle}/{@code queryForUnique} (single-value semantics).
     *
     * @param method the DAO method to inspect
     * @param queryOperation the operation type declared for the method
     * @return {@code true} if the method is a find-first query
     */
    private static boolean isFindFirst(final Method method, final QueryOperation queryOperation) {
        if (queryOperation == QueryOperation.findFirst) {
            return true;
        }

        return queryOperation == QueryOperation.DEFAULT && !(method.getName().startsWith("findOnlyOne") || method.getName().startsWith("selectOnlyOne")
                || method.getName().startsWith("queryForSingle") || method.getName().startsWith("queryForUnique"));
    }

    /**
     * Returns {@code true} if the method must enforce at-most-one-row semantics: either explicitly via
     * {@link QueryOperation#findOnlyOne}, or, for {@link QueryOperation#DEFAULT}, when the method name starts with
     * {@code findOnlyOne} or {@code selectOnlyOne}.
     *
     * @param method the DAO method to inspect
     * @param queryOperation the operation type declared for the method
     * @return {@code true} if the method is a find-only-one query
     */
    private static boolean isFindOnlyOne(final Method method, final QueryOperation queryOperation) {
        if (queryOperation == QueryOperation.findOnlyOne) {
            return true;
        }

        // Both "OnlyOne" prefixes from singleQueryPrefix promise at-most-one-row semantics.
        return queryOperation == QueryOperation.DEFAULT && (method.getName().startsWith("findOnlyOne") || method.getName().startsWith("selectOnlyOne"));
    }

    /**
     * Returns {@code true} if the method must enforce unique-result semantics (at most one row, failing on
     * duplicates): either explicitly via {@link QueryOperation#queryForUnique}, or, for
     * {@link QueryOperation#DEFAULT}, when the method name starts with {@code queryForUnique}.
     *
     * @param method the DAO method to inspect
     * @param queryOperation the operation type declared for the method
     * @return {@code true} if the method is a query-for-unique query
     */
    private static boolean isQueryForUnique(final Method method, final QueryOperation queryOperation) {
        if (queryOperation == QueryOperation.queryForUnique) {
            return true;
        }

        return queryOperation == QueryOperation.DEFAULT && method.getName().startsWith("queryForUnique");
    }

    /** Return types holding at most one value: the {@code u.Nullable}/{@code u.Optional} and {@code java.util.Optional} families. */
    private static final ImmutableSet<Class<?>> singleReturnTypeSet = ImmutableSet.wrap((N.toSet(u.Nullable.class, u.Optional.class, u.OptionalBoolean.class,
            u.OptionalChar.class, u.OptionalByte.class, u.OptionalShort.class, u.OptionalInt.class, u.OptionalLong.class, u.OptionalFloat.class,
            u.OptionalDouble.class, java.util.Optional.class, java.util.OptionalInt.class, java.util.OptionalLong.class, java.util.OptionalDouble.class)));

    /**
     * Returns {@code true} if the return type holds a single value: one of the optional/nullable types in
     * {@link #singleReturnTypeSet}, or a primitive (wrapper) type.
     *
     * @param returnType the return type to test
     * @return {@code true} if the return type is a single-value type
     */
    private static boolean isSingleReturnType(final Class<?> returnType) {
        return singleReturnTypeSet.contains(returnType) || ClassUtil.isPrimitiveType(ClassUtil.unwrap(returnType));
    }

    /**
     * Builds the execution function for a single-value (non-list) return type. The returned function executes the
     * prepared query and converts the result to {@code returnType}: the matching {@code queryForXxx} variant or a
     * uniqueness-checking row mapper for primitive optionals, and {@code queryForSingleValue}/{@code queryForUniqueValue} (falling back to the
     * type's default value when no row is found) for plain scalar types, honoring the uniqueness contract of
     * find-only-one/query-for-unique methods.
     *
     * <p>This method itself performs no query and throws nothing; for a find-only-one/query-for-unique method the
     * returned function throws {@link DuplicateResultException} when the query matches more than one row, and
     * {@link SQLException} when the query fails.</p>
     *
     * @param <R> the result type
     * @param returnType the single-value return type of the DAO method; for a find-only-one/query-for-unique method,
     *                  primitive optionals enforce uniqueness while retaining their existing SQL
     *                  {@code NULL}-to-primitive-default semantics
     * @param method the DAO method, used to determine uniqueness semantics
     * @param queryOperation the operation type declared for the method
     * @return a function executing a prepared query and returning the single result value
     */
    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractQuery, Object[], R, SQLException> createSingleQueryFunction(final Class<?> returnType, final Method method,
            final QueryOperation queryOperation) {
        final boolean unique = isQueryForUnique(method, queryOperation) || isFindOnlyOne(method, queryOperation);

        if (u.OptionalBoolean.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper<u.OptionalBoolean>) rs -> u.OptionalBoolean.of(rs.getBoolean(1)))
                                    .orElse(u.OptionalBoolean.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForBoolean();
        } else if (u.OptionalChar.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery
                                    .findOnlyOne((Jdbc.RowMapper<u.OptionalChar>) rs -> u.OptionalChar.of(N.<Character> typeOf(char.class).get(rs, 1)))
                                    .orElse(u.OptionalChar.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForChar();
        } else if (u.OptionalByte.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper<u.OptionalByte>) rs -> u.OptionalByte.of(rs.getByte(1)))
                                    .orElse(u.OptionalByte.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForByte();
        } else if (u.OptionalShort.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper<u.OptionalShort>) rs -> u.OptionalShort.of(rs.getShort(1)))
                                    .orElse(u.OptionalShort.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForShort();
        } else if (u.OptionalInt.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper<u.OptionalInt>) rs -> u.OptionalInt.of(rs.getInt(1)))
                                    .orElse(u.OptionalInt.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForInt();
        } else if (u.OptionalLong.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper<u.OptionalLong>) rs -> u.OptionalLong.of(rs.getLong(1)))
                                    .orElse(u.OptionalLong.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForLong();
        } else if (u.OptionalFloat.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper<u.OptionalFloat>) rs -> u.OptionalFloat.of(rs.getFloat(1)))
                                    .orElse(u.OptionalFloat.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForFloat();
        } else if (u.OptionalDouble.class.isAssignableFrom(returnType)) {
            return unique
                    ? (preparedQuery,
                            args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper<u.OptionalDouble>) rs -> u.OptionalDouble.of(rs.getDouble(1)))
                                    .orElse(u.OptionalDouble.empty())
                    : (preparedQuery, args) -> (R) preparedQuery.queryForDouble();
        } else {
            // u.Optional/u.Nullable returns never reach here: the sole caller handles them (with the
            // element type, not the wrapper class) before falling back to this function.

            // Bare scalar returns must still honor the uniqueness contract of findOnlyOne/queryForUnique
            // (DuplicateResultException on more than one row) instead of silently reading the first row.
            if (isQueryForUnique(method, queryOperation) || isFindOnlyOne(method, queryOperation)) {
                return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueValue(returnType).orElse(N.defaultValueOf(returnType));
            }

            return (preparedQuery, args) -> (R) preparedQuery.queryForSingleValue(returnType).orElse(N.defaultValueOf(returnType));
        }
    }

    /**
     * Validates the DAO method's return type against its declared {@link QueryOperation} and builds the function
     * that executes the prepared query and maps the result accordingly (list, single value, map keyed by id,
     * procedure result sets with out parameters, stream, etc.). Incompatible return-type/operation combinations are
     * rejected eagerly here, at proxy-creation time, rather than at first invocation.
     * For an optional result annotated with {@code @MergedById}, find-only-one and query-for-unique
     * semantics are checked after rows have been merged: several rows with the same entity ID
     * represent one result, while multiple distinct merged entities cause {@link DuplicateResultException}.
     *
     * @param <R> the result type
     * @param entityClass the entity class of the DAO
     * @param method the DAO method to build the query function for
     * @param mappedByKey the property name from {@code @MappedByKey}, or {@code null}
     * @param mergedByIds the id property names from {@code @MergedById}, or {@code null}
     * @param prefixFieldMap the column-prefix-to-field mapping used for result mapping, or {@code null}
     * @param fetchColumnByEntityClass {@code true} to read {@code Dataset} columns with the entity class's property types
     * @param hasRowMapperOrExtractor {@code true} if the method declares a row-mapper or result-extractor parameter
     *        (always {@code false} at present: {@code createDao} rejects such parameters on {@code @Query} methods)
     * @param hasRowFilter {@code true} if the method declares a row-filter parameter (always {@code false} at present,
     *        since a row filter requires a row-mapper parameter)
     * @param queryOperation the operation type declared for the method
     * @param isProcedure {@code true} if the SQL is a stored procedure call
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @return a function executing a prepared query and returning the mapped result
     * @throws UnsupportedOperationException if the return type is not supported by the declared
     *         {@link QueryOperation}, if a {@code Tuple2} return type is declared on a non-procedure query, or a
     *         required generic element type cannot be resolved
     * @throws IllegalArgumentException if {@code mappedByKey} does not identify a property of the result entity class
     */
    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractQuery, Object[], R, SQLException> createQueryFunctionByMethod(final Class<?> entityClass,
            final Method method, final String mappedByKey, final List<String> mergedByIds, final Map<String, String> prefixFieldMap,
            final boolean fetchColumnByEntityClass, final boolean hasRowMapperOrExtractor, final boolean hasRowFilter, final QueryOperation queryOperation,
            final boolean isProcedure, final String fullClassMethodName) throws UnsupportedOperationException, IllegalArgumentException {
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = resolveQueryReturnType(method.getReturnType());
        final Class<?> firstReturnEleType = getFirstReturnEleType(method);
        final Class<?> secondReturnEleType = getSecondReturnEleType(method);
        final Class<?> firstReturnEleEleType = getFirstReturnEleEleType(method);
        final Class<?> firstReturnEleEleEleType = getFirstReturnEleEleEleType(method);

        final int paramLen = paramTypes.length;
        final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];
        final boolean isListQuery = isListQuery(method, returnType, queryOperation, fullClassMethodName);
        final boolean isExists = isExistsQuery(method, queryOperation, fullClassMethodName);

        // A Tuple2 return type for QueryOperation.query is only handled on the procedure path (result
        // sets + OUT parameters); accepting it for a plain query would silently fall through to the
        // single-value dispatch and fail obscurely at execution instead of here at proxy creation.
        if ((queryOperation == QueryOperation.stream && !Stream.class.isAssignableFrom(returnType)) || (queryOperation == QueryOperation.query
                && !(Dataset.class.isAssignableFrom(returnType) || (Tuple2.class.isAssignableFrom(returnType) && isProcedure) || (lastParamType != null
                        && (Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType) || Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)))))) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is not supported by the specified queryOperation: " + queryOperation);
        }

        // The same applies to every other operation (list, DEFAULT, ...): Tuple2 results are only produced on the
        // procedure path, so on a plain query the method would fall through to the single-value dispatch.
        if (Tuple2.class.isAssignableFrom(returnType) && !isProcedure) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is only supported by @Query methods with procedure=true, not supported by the specified queryOperation: " + queryOperation);
        }

        if ((queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.findOnlyOne) && Nullable.class.isAssignableFrom(returnType)) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is not supported by the specified queryOperation: " + queryOperation);
        }

        if ((queryOperation == QueryOperation.executeAndGetOutParameters && !returnType.isAssignableFrom(Jdbc.OutParamResult.class)) || (Stream.class
                .isAssignableFrom(returnType)
                && !(queryOperation == QueryOperation.stream || queryOperation == QueryOperation.streamAll || queryOperation == QueryOperation.DEFAULT))) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is not supported by the specified queryOperation: " + queryOperation);
        }

        if (Dataset.class.isAssignableFrom(returnType) && !(queryOperation == QueryOperation.query || queryOperation == QueryOperation.DEFAULT)) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is not supported by the specified queryOperation: " + queryOperation);
        }

        // java.util.Optional/OptionalInt/OptionalLong/OptionalDouble return types are rejected unconditionally
        // at proxy creation before this factory is invoked, so of the Optional family only the u.* optionals/Nullable can appear here.
        if (u.Optional.class.isAssignableFrom(returnType) && !(queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.findOnlyOne
                || queryOperation == QueryOperation.queryForSingle || queryOperation == QueryOperation.queryForUnique
                || queryOperation == QueryOperation.DEFAULT)) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is not supported by the specified queryOperation: " + queryOperation);
        }

        if (Nullable.class.isAssignableFrom(returnType) && !(queryOperation == QueryOperation.queryForSingle || queryOperation == QueryOperation.queryForUnique
                || queryOperation == QueryOperation.DEFAULT)) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is not supported by the specified queryOperation: " + queryOperation);
        }

        if (isProcedure) {
            if (Tuple2.class.isAssignableFrom(returnType)
                    && (secondReturnEleType == null || !secondReturnEleType.isAssignableFrom(Jdbc.OutParamResult.class))) {
                throw new UnsupportedOperationException("The second type argument of the procedure return type in method: " + fullClassMethodName
                        + " must be Jdbc.OutParamResult (or a supertype). It can't be: " + method.getGenericReturnType());
            }

            if (queryOperation == QueryOperation.executeAndGetOutParameters) {
                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).executeAndGetOutParameters();
            } else if (queryOperation == QueryOperation.listAll) {
                if (Tuple2.class.isAssignableFrom(returnType)) {
                    if (firstReturnEleType == null || !firstReturnEleType.isAssignableFrom(List.class)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    if (firstReturnEleEleType == null || !firstReturnEleEleType.isAssignableFrom(List.class)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.RowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                        .listAllResultSetsAndGetOutParameters((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery,
                                        args) -> (R) ((CallableQuery) preparedQuery).listAllResultSetsAndGetOutParameters((Jdbc.RowMapper) args[paramLen - 1]);
                            }
                        } else if (Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                        .listAllResultSetsAndGetOutParameters((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                        .listAllResultSetsAndGetOutParameters((Jdbc.BiRowMapper) args[paramLen - 1]);
                            }
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }
                    } else {
                        if (firstReturnEleEleEleType == null) {
                            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultSetsAndGetOutParameters(firstReturnEleEleEleType);
                    }
                } else {
                    if (!List.class.isAssignableFrom(returnType) || !returnType.isAssignableFrom(ArrayList.class)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    if (firstReturnEleType == null || !firstReturnEleType.isAssignableFrom(List.class)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.RowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery,
                                        args) -> (R) preparedQuery.listAllResultSets((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery, args) -> (R) preparedQuery.listAllResultSets((Jdbc.RowMapper) args[paramLen - 1]);
                            }
                        } else if (Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery, args) -> (R) preparedQuery.listAllResultSets((Jdbc.BiRowFilter) args[paramLen - 2],
                                        (Jdbc.BiRowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery, args) -> (R) preparedQuery.listAllResultSets((Jdbc.BiRowMapper) args[paramLen - 1]);
                            }
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }
                    } else {
                        if (firstReturnEleEleType == null) {
                            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultSets(firstReturnEleEleType);
                    }
                }
            } else if (queryOperation == QueryOperation.queryAll) {
                if (Tuple2.class.isAssignableFrom(returnType)) {
                    if (firstReturnEleType == null || !firstReturnEleType.isAssignableFrom(List.class)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                    .queryAllResultSetsAndGetOutParameters((Jdbc.ResultExtractor) args[paramLen - 1]);
                        } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                    .queryAllResultSetsAndGetOutParameters((Jdbc.BiResultExtractor) args[paramLen - 1]);
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }
                    } else {
                        if (firstReturnEleEleType == null || !Dataset.class.isAssignableFrom(firstReturnEleEleType)) {
                            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAllResultSetsAndGetOutParameters();
                    }

                } else {
                    if (!List.class.isAssignableFrom(returnType) || !returnType.isAssignableFrom(ArrayList.class)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) preparedQuery.queryAllResultSets((Jdbc.ResultExtractor) args[paramLen - 1]);
                        } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) preparedQuery.queryAllResultSets((Jdbc.BiResultExtractor) args[paramLen - 1]);
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }
                    } else {
                        if (firstReturnEleType == null || !Dataset.class.isAssignableFrom(firstReturnEleType)) {
                            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAllResultSets();
                    }
                }
            } else if (queryOperation == QueryOperation.streamAll) {
                if (!Stream.class.isAssignableFrom(returnType)) {
                    throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                            + " is not supported by the specified queryOperation: " + queryOperation);
                }

                if (hasRowMapperOrExtractor) {
                    if (lastParamType != null && Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                        return (preparedQuery, args) -> (R) preparedQuery.streamAllResultSets((Jdbc.ResultExtractor) args[paramLen - 1]);
                    } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)) {
                        return (preparedQuery, args) -> (R) preparedQuery.streamAllResultSets((Jdbc.BiResultExtractor) args[paramLen - 1]);
                    } else {
                        throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }
                } else {
                    if (firstReturnEleType == null || !Dataset.class.isAssignableFrom(firstReturnEleType)) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).streamAllResultSets();
                }
            }

            if (Tuple2.class.isAssignableFrom(returnType)
                    && (queryOperation == QueryOperation.list || queryOperation == QueryOperation.query || queryOperation == QueryOperation.DEFAULT)) {
                //    if (firstReturnEleType == null || !List.class.isAssignableFrom(firstReturnEleType)) {
                //        throw new UnsupportedOperationException(
                //                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported by the specified queryOperation: " + queryOperation);
                //    }

                if (hasRowMapperOrExtractor) {
                    if (lastParamType != null && Jdbc.RowMapper.class.isAssignableFrom(lastParamType)) {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAndGetOutParameters((Jdbc.RowFilter) args[paramLen - 2],
                                    (Jdbc.RowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAndGetOutParameters((Jdbc.RowMapper) args[paramLen - 1]);
                        }
                    } else if (Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)) {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAndGetOutParameters((Jdbc.BiRowFilter) args[paramLen - 2],
                                    (Jdbc.BiRowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAndGetOutParameters((Jdbc.BiRowMapper) args[paramLen - 1]);
                        }
                    } else if (Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAndGetOutParameters((Jdbc.ResultExtractor) args[paramLen - 1]);
                    } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)) {
                        return (preparedQuery,
                                args) -> (R) ((CallableQuery) preparedQuery).queryAndGetOutParameters((Jdbc.BiResultExtractor) args[paramLen - 1]);
                    } else {
                        throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }
                } else {
                    if (firstReturnEleType == null) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    if (Dataset.class.isAssignableFrom(firstReturnEleType)) {
                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAndGetOutParameters();
                    }

                    if (firstReturnEleEleType == null || !firstReturnEleType.isAssignableFrom(List.class)) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported by the specified queryOperation: "
                                        + queryOperation + ". The first Tuple2 type argument must accept a List result");
                    }

                    return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAndGetOutParameters(firstReturnEleEleType);
                }
            }

            // Only the branches above produce a Tuple2 (results + OUT parameters). With findFirst/findOnlyOne/queryForSingle/
            // queryForUnique the method fell through to the single-value dispatch, which converts the first column of the
            // first row to a Tuple2 and fails obscurely at execution instead of here at proxy creation.
            if (Tuple2.class.isAssignableFrom(returnType)) {
                throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                        + " is not supported by the specified queryOperation: " + queryOperation);
            }
        }

        final MappedByKey mappedByKeyAnno = method.getAnnotation(MappedByKey.class);
        final Class<? extends Map> targetMapClass = Strings.isEmpty(mappedByKey) ? null : mappedByKeyAnno.mapClass();

        // Not reachable at present: createDao rejects RowMapper/BiRowMapper/ResultExtractor parameters on custom query methods
        // ("not enabled at present"). Kept consistent with the automatic-mapping branches (e.g. Immutable* collection returns).
        if (hasRowMapperOrExtractor) {
            if (Strings.isNotEmpty(mappedByKey) || N.notEmpty(mergedByIds) || N.notEmpty(prefixFieldMap)) {
                throw new UnsupportedOperationException(
                        "RowMapper/ResultExtractor is not supported by method annotated with @MappedByKey/@MergedById/@PrefixFieldMapping: "
                                + fullClassMethodName);
            }

            if (!(queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.findOnlyOne || queryOperation == QueryOperation.list
                    || queryOperation == QueryOperation.listAll || queryOperation == QueryOperation.query || queryOperation == QueryOperation.queryAll
                    || queryOperation == QueryOperation.stream || queryOperation == QueryOperation.streamAll || queryOperation == QueryOperation.DEFAULT)) {
                throw new UnsupportedOperationException(
                        "RowMapper/ResultExtractor is not supported by queryOperation: " + queryOperation + " in method: " + fullClassMethodName);
            }

            if (hasRowFilter && !(queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.findOnlyOne
                    || queryOperation == QueryOperation.list || queryOperation == QueryOperation.listAll || queryOperation == QueryOperation.stream
                    || queryOperation == QueryOperation.DEFAULT)) {
                throw new UnsupportedOperationException(
                        "RowFilter is not supported by queryOperation: " + queryOperation + " in method: " + fullClassMethodName);
            }

            if (lastParamType != null && Jdbc.RowMapper.class.isAssignableFrom(lastParamType)) {
                if (isListQuery) {
                    if (returnType.equals(List.class)) {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) preparedQuery.list((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.list((Jdbc.RowMapper) args[paramLen - 1]);
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery,
                                    args) -> (R) toDeclaredCollectionType(
                                            preparedQuery.stream((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1])
                                                    .toCollection(Suppliers.ofCollection((Class<Collection>) returnType)),
                                            returnType);
                        } else {
                            return (preparedQuery, args) -> (R) toDeclaredCollectionType(preparedQuery.stream((Jdbc.RowMapper) args[paramLen - 1])
                                    .toCollection(Suppliers.ofCollection((Class<Collection>) returnType)), returnType);
                        }
                    }
                } else if (u.Optional.class.isAssignableFrom(returnType)) {
                    if (isFindOnlyOne(method, queryOperation)) {
                        if (hasRowFilter) {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.stream((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1]).onlyOne();
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper) args[paramLen - 1]);
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.stream((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1]).first();
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((Jdbc.RowMapper) args[paramLen - 1]);
                        }
                    }
                } else if (Stream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((Jdbc.RowMapper) args[paramLen - 1]);
                    }
                } else {
                    if (Nullable.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + method.getName() + " can't be: " + returnType + " when RowMapper/BiRowMapper is specified");
                    }

                    if (isFindOnlyOne(method, queryOperation)) {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) ((Stream<Object>) preparedQuery.stream((Jdbc.RowFilter) args[paramLen - 2],
                                    (Jdbc.RowMapper) args[paramLen - 1])).onlyOne().orElse(N.defaultValueOf(returnType));
                        } else {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.findOnlyOne((Jdbc.RowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) ((Stream<Object>) preparedQuery.stream((Jdbc.RowFilter) args[paramLen - 2],
                                    (Jdbc.RowMapper) args[paramLen - 1])).first().orElse(N.defaultValueOf(returnType));
                        } else {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.findFirst((Jdbc.RowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                        }
                    }
                }
            } else if (Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)) {
                if (isListQuery) {
                    if (returnType.equals(List.class)) {
                        if (hasRowFilter) {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.list((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1]);
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.list((Jdbc.BiRowMapper) args[paramLen - 1]);
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery,
                                    args) -> (R) toDeclaredCollectionType(
                                            preparedQuery.stream((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1])
                                                    .toCollection(Suppliers.ofCollection((Class<Collection>) returnType)),
                                            returnType);
                        } else {
                            return (preparedQuery, args) -> (R) toDeclaredCollectionType(preparedQuery.stream((Jdbc.BiRowMapper) args[paramLen - 1])
                                    .toCollection(Suppliers.ofCollection((Class<Collection>) returnType)), returnType);
                        }
                    }
                } else if (u.Optional.class.isAssignableFrom(returnType)) {
                    if (isFindOnlyOne(method, queryOperation)) {
                        if (hasRowFilter) {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.stream((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1]).onlyOne();
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne((Jdbc.BiRowMapper) args[paramLen - 1]);
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.stream((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1]).first();
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.findFirst((Jdbc.BiRowMapper) args[paramLen - 1]);
                        }
                    }
                } else if (Stream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((Jdbc.BiRowMapper) args[paramLen - 1]);
                    }
                } else {
                    if (Nullable.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + method.getName() + " can't be: " + returnType + " when RowMapper/BiRowMapper is specified");
                    }

                    if (isFindOnlyOne(method, queryOperation)) {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) ((Stream<Object>) preparedQuery.stream((Jdbc.BiRowFilter) args[paramLen - 2],
                                    (Jdbc.BiRowMapper) args[paramLen - 1])).onlyOne().orElse(N.defaultValueOf(returnType));
                        } else {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.findOnlyOne((Jdbc.BiRowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                        }
                    } else {
                        if (hasRowFilter) {
                            return (preparedQuery, args) -> (R) ((Stream<Object>) preparedQuery.stream((Jdbc.BiRowFilter) args[paramLen - 2],
                                    (Jdbc.BiRowMapper) args[paramLen - 1])).first().orElse(N.defaultValueOf(returnType));
                        } else {
                            return (preparedQuery,
                                    args) -> (R) preparedQuery.findFirst((Jdbc.BiRowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                        }
                    }
                }
            } else {
                if (!(queryOperation == QueryOperation.query || queryOperation == QueryOperation.DEFAULT)) {
                    throw new UnsupportedOperationException(
                            "ResultExtractor/BiResultExtractor is not supported by queryOperation: " + queryOperation + " in method: " + fullClassMethodName);
                }

                if (method.getGenericParameterTypes()[paramLen - 1] instanceof ParameterizedType) {
                    final java.lang.reflect.Type resultExtractorReturnType = ((ParameterizedType) method.getGenericParameterTypes()[paramLen - 1])
                            .getActualTypeArguments()[0];
                    final Class<?> resultExtractorReturnClass = resultExtractorReturnType instanceof Class ? (Class<?>) resultExtractorReturnType
                            : (resultExtractorReturnType instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) resultExtractorReturnType).getRawType()
                                    : null);

                    if (resultExtractorReturnClass != null && !ClassUtil.wrap(returnType).isAssignableFrom(ClassUtil.wrap(resultExtractorReturnClass))) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName()
                                + " is not assignable from the return type of ResultExtractor: " + resultExtractorReturnClass);
                    }
                }

                if (Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                    return (preparedQuery, args) -> (R) preparedQuery.query((Jdbc.ResultExtractor) args[paramLen - 1]);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.query((Jdbc.BiResultExtractor) args[paramLen - 1]);
                }
            }
        } else if (Strings.isNotEmpty(mappedByKey)) {
            final Class<?> targetEntityClass = !Beans.isBeanClass(secondReturnEleType) ? entityClass : secondReturnEleType;
            final BeanInfo entityInfo = ParserUtil.getBeanInfo(targetEntityClass);
            final PropInfo propInfo = entityInfo.getPropInfo(mappedByKey);
            if (propInfo == null) {
                throw new IllegalArgumentException("No property found with name: " + mappedByKey + " in class: " + targetEntityClass);
            }
            final Function<Object, Object> keyExtractor = propInfo::getPropValue;
            final List<String> mergedByKey = N.isEmpty(mergedByIds) ? N.asList(mappedByKey) : mergedByIds;

            return (preparedQuery, args) -> {
                final Dataset dataset = (Dataset) preparedQuery.query(Jdbc.ResultExtractor.toDataset(targetEntityClass, prefixFieldMap));
                final List<Object> entities = dataset.toMergedEntities(mergedByKey, dataset.columnNames(), prefixFieldMap, targetEntityClass);

                return (R) Stream.of(entities).toMap(keyExtractor, Fn.identity(), Suppliers.ofMap(targetMapClass));
            };
        } else if (N.notEmpty(mergedByIds)) {
            if (Collection.class.isAssignableFrom(returnType) || u.Optional.class.isAssignableFrom(returnType)) {
                final Class<?> targetEntityClass = !Beans.isBeanClass(firstReturnEleType) ? entityClass : firstReturnEleType;
                final boolean isCollection = Collection.class.isAssignableFrom(returnType);

                return (preparedQuery, args) -> {
                    final Dataset dataset = (Dataset) preparedQuery.query(Jdbc.ResultExtractor.toDataset(targetEntityClass, prefixFieldMap));
                    final List<Object> mergedEntities = dataset.toMergedEntities(mergedByIds, dataset.columnNames(), prefixFieldMap, targetEntityClass);

                    if (isCollection) {
                        if (returnType.isAssignableFrom(mergedEntities.getClass())) {
                            return (R) mergedEntities;
                        } else {
                            final Collection<Object> c = N.newCollection((Class<Collection>) returnType);
                            c.addAll(mergedEntities);

                            return (R) toDeclaredCollectionType(c, returnType);
                        }
                    } else {
                        if ((isFindOnlyOne(method, queryOperation) || isQueryForUnique(method, queryOperation)) && N.size(mergedEntities) > 1) {
                            throw new DuplicateResultException("More than one record found by the query defined or generated in method: " + method.getName());
                        }

                        return (R) (N.isEmpty(mergedEntities) ? Optional.empty() : Optional.of(N.firstOrNullIfEmpty(mergedEntities)));
                    }
                };
            } else {
                throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName()
                        + " is not supported by method annotated with @MergedById. Only Optional/List/Collection are supported at present");
            }
        } else if (isExists) {
            if (queryOperation == QueryOperation.DEFAULT && isNotExistsMethodName(method.getName())) {
                return (preparedQuery, args) -> (R) (Boolean) preparedQuery.notExists();
            } else {
                return (preparedQuery, args) -> (R) (Boolean) preparedQuery.exists();
            }
        } else if (isListQuery) {
            checkReturnEleTypeResolved(firstReturnEleType, returnType, fullClassMethodName);

            if (returnType.equals(List.class)) {
                return (preparedQuery, args) -> (R) preparedQuery.list(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
            } else {
                return (preparedQuery, args) -> (R) toDeclaredCollectionType(preparedQuery.stream(BiRowMapper.to(firstReturnEleType, prefixFieldMap))
                        .toCollection(Suppliers.ofCollection((Class<Collection>) returnType)), returnType);
            }
        } else if (Dataset.class.isAssignableFrom(returnType)) {
            if (fetchColumnByEntityClass) {
                return (preparedQuery, args) -> (R) preparedQuery.query(Jdbc.ResultExtractor.toDataset(entityClass, prefixFieldMap));
            } else {
                return (preparedQuery, args) -> (R) preparedQuery.query();
            }
        } else if (Stream.class.isAssignableFrom(returnType)) {
            checkReturnEleTypeResolved(firstReturnEleType, returnType, fullClassMethodName);

            return (preparedQuery, args) -> (R) preparedQuery.stream(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
        } else if (u.Optional.class.isAssignableFrom(returnType) || Nullable.class.isAssignableFrom(returnType)) {
            checkReturnEleTypeResolved(firstReturnEleType, returnType, fullClassMethodName);

            if (Nullable.class.isAssignableFrom(returnType)) {
                // Honor the uniqueness contract of findOnlyOne/selectOnlyOne method names (and queryForUnique)
                // for Nullable returns too, like every other single-result branch does, instead of silently
                // reading the first row.
                if (isQueryForUnique(method, queryOperation) || isFindOnlyOne(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueValue(firstReturnEleType);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleValue(firstReturnEleType);
                }
            } else if (u.Optional.class.isAssignableFrom(returnType)) {
                if (isFindFirst(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
                } else if (isFindOnlyOne(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
                } else if (isQueryForUnique(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueNonNull(firstReturnEleType);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(firstReturnEleType);
                }
            } else {
                throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName() + " is not supported at present");
            }
        } else {
            if (isFindOrListTargetClass(returnType)) {
                // Honor the uniqueness contract of queryForUnique (explicit op or method-name prefix) for
                // bean/Map/List/array returns too, not just findOnlyOne: both promise DuplicateResultException
                // when more than one row is found, rather than silently reading the first row.
                if (isFindOnlyOne(method, queryOperation) || isQueryForUnique(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne(Jdbc.BiRowMapper.to(returnType, prefixFieldMap)).orElseNull();
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(Jdbc.BiRowMapper.to(returnType, prefixFieldMap)).orElseNull();
                }
            } else if (void.class.equals(returnType) || Void.class.equals(returnType)) {
                // A void SELECT runs only for its side effect (e.g. SELECT ... FOR UPDATE, SELECT pg_advisory_xact_lock(?)).
                // Reading it as a single value converted the first column to void and failed every call. Its rows are read to
                // the end rather than only executed: a driver fetching through a cursor (e.g. PostgreSQL with a fetch size in a
                // transaction) runs the query, and locks its rows, only as far as they are fetched. The uniqueness contract of
                // findOnlyOne/queryForUnique is honored as by every other single-result dispatch.
                final boolean unique = isFindOnlyOne(method, queryOperation) || isQueryForUnique(method, queryOperation);

                return (preparedQuery, args) -> {
                    final long rowCount = executeAndReadAllRows(preparedQuery);

                    if (unique && rowCount > 1) {
                        throw new DuplicateResultException("More than one record found by the query defined or generated in method: " + method.getName());
                    }

                    return null;
                };
            } else {
                return createSingleQueryFunction(returnType, method, queryOperation);
            }
        }
    }

    /**
     * Executes the prepared query and, if its first result is a result set, reads that result set to the end,
     * discarding the rows. A statement that returns no result set (e.g. {@code SELECT ... INTO} a table) is only
     * executed. The query is closed afterwards unless {@code closeAfterExecution(false)} was set.
     *
     * @param preparedQuery the prepared query to execute
     * @return the number of rows read, or {@code 0} if the first result is not a result set
     * @throws SQLException if executing the statement or reading its result set fails
     */
    private static long executeAndReadAllRows(final AbstractQuery<?, ?> preparedQuery) throws SQLException {
        return preparedQuery.executeThenApply((stmt, isResultSet) -> {
            long rowCount = 0;

            if (isResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    while (rs.next()) {
                        rowCount++;
                    }
                }
            }

            return rowCount;
        });
    }

    // Fails DAO creation instead of every invocation when a raw or wildcard generic return type (e.g.
    // List, Stream, Nullable<?>) leaves the element type unresolved.
    /**
     * Ensures the element type of a generic return type was resolved.
     *
     * @param firstReturnEleType the resolved first element type, or {@code null} if unresolvable
     * @param returnType the method's return type, used for the error message
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @throws UnsupportedOperationException if {@code firstReturnEleType} is {@code null}
     */
    private static void checkReturnEleTypeResolved(final Class<?> firstReturnEleType, final Class<?> returnType, final String fullClassMethodName)
            throws UnsupportedOperationException {
        if (firstReturnEleType == null) {
            throw new UnsupportedOperationException("The element type of the return type: " + returnType + " in method: " + fullClassMethodName
                    + " can't be resolved. Raw or wildcard generic return types are not supported");
        }
    }

    /**
     * Returns {@code true} if query result rows can be materialized into the given class directly: a bean or record
     * class, or a {@code Map}, {@code List}, or {@code Object[]} type.
     *
     * @param cls the candidate target class
     * @return {@code true} if the class is a supported find/list result target
     */
    private static boolean isFindOrListTargetClass(final Class<?> cls) {
        return Beans.isBeanClass(cls) || Map.class.isAssignableFrom(cls) || List.class.isAssignableFrom(cls) || Object[].class.isAssignableFrom(cls)
                || Beans.isRecordClass(cls);
    }

    /**
     * Adapts a collection created by {@link Suppliers#ofCollection(Class)} (or {@code N.newCollection(Class)}) for
     * the declared return type to an instance of that type.
     *
     * <p>For an {@code Immutable*} type those factories create the plain mutable JDK counterpart (e.g. an
     * {@code ArrayList} for {@code ImmutableList}), meant to be filled and then wrapped; returned unwrapped, the
     * proxy would fail every call with {@link ClassCastException}.</p>
     *
     * @param c the filled collection
     * @param returnType the declared collection return type of the DAO method
     * @return {@code c} itself if it is already an instance of {@code returnType}, otherwise its matching
     *         {@code Immutable*} wrapper (or {@code c} unchanged if there is none)
     */
    @SuppressWarnings({ "unchecked" })
    private static Collection<?> toDeclaredCollectionType(final Collection<?> c, final Class<?> returnType) {
        if (returnType.isInstance(c)) {
            return c;
        } else if (ImmutableList.class.isAssignableFrom(returnType) && c instanceof final List list) {
            return ImmutableList.wrap(list);
        } else if (com.landawn.abacus.util.ImmutableNavigableSet.class.isAssignableFrom(returnType) && c instanceof final java.util.NavigableSet set) {
            return com.landawn.abacus.util.ImmutableNavigableSet.wrap(set);
        } else if (com.landawn.abacus.util.ImmutableSortedSet.class.isAssignableFrom(returnType) && c instanceof final java.util.SortedSet set) {
            return com.landawn.abacus.util.ImmutableSortedSet.wrap(set);
        } else if (ImmutableSet.class.isAssignableFrom(returnType) && c instanceof final Set set) {
            return ImmutableSet.wrap(set);
        } else {
            return c;
        }
    }

    /**
     * Maps the declared return type of a custom query method to the type whose instance is returned for it: a
     * {@code List} for {@code Iterable} or another interface that an {@code ArrayList} implements but no collection
     * factory instantiates (e.g. {@code SequencedCollection}), and an {@code ImmutableList} for the abstract
     * {@code ImmutableCollection}. Without it such a method fell through to the single-value dispatch, or failed every
     * call creating the result collection. Any other type is returned unchanged.
     *
     * @param declaredReturnType the declared return type of the method
     * @return the type of the returned instance
     */
    private static Class<?> resolveQueryReturnType(final Class<?> declaredReturnType) {
        if (com.landawn.abacus.util.ImmutableCollection.class.equals(declaredReturnType)) {
            return ImmutableList.class;
        } else if (declaredReturnType.isInterface() && Iterable.class.isAssignableFrom(declaredReturnType)
                && declaredReturnType.isAssignableFrom(ArrayList.class)
                && !(Collection.class.equals(declaredReturnType) || List.class.equals(declaredReturnType))) {
            return List.class;
        }

        return declaredReturnType;
    }

    /**
     * Resolves the raw class of the first type argument of the method's generic return type (e.g. {@code User} in
     * {@code List<User>} or the key type in {@code Map<K, V>}).
     *
     * @param method the method whose generic return type is inspected
     * @return the raw class of the first type argument, or {@code null} if the return type is not parameterized or
     *         the argument cannot be resolved to a class
     */
    private static Class<?> getFirstReturnEleType(final Method method) {
        final java.lang.reflect.Type genericReturnType = method.getGenericReturnType();

        final ParameterizedType parameterizedReturnType = genericReturnType instanceof ParameterizedType ? (ParameterizedType) genericReturnType : null;

        final java.lang.reflect.Type firstActualTypeArgument = parameterizedReturnType == null || N.isEmpty(parameterizedReturnType.getActualTypeArguments())
                ? null
                : parameterizedReturnType.getActualTypeArguments()[0];

        return firstActualTypeArgument == null ? null
                : (firstActualTypeArgument instanceof Class ? (Class<?>) firstActualTypeArgument
                        : (firstActualTypeArgument instanceof ParameterizedType && ((ParameterizedType) firstActualTypeArgument).getRawType() instanceof Class
                                ? (Class<?>) ((ParameterizedType) firstActualTypeArgument).getRawType()
                                : null));
    }

    /**
     * Resolves the raw class of the second type argument of the method's generic return type (e.g. the value type in
     * {@code Map<K, V>} or the second element of a {@code Tuple2}).
     *
     * @param method the method whose generic return type is inspected
     * @return the raw class of the second type argument, or {@code null} if there is no second type argument or it
     *         cannot be resolved to a class
     */
    private static Class<?> getSecondReturnEleType(final Method method) {
        final java.lang.reflect.Type genericReturnType = method.getGenericReturnType();

        final ParameterizedType parameterizedReturnType = genericReturnType instanceof ParameterizedType ? (ParameterizedType) genericReturnType : null;

        final java.lang.reflect.Type secondActualTypeArgument = parameterizedReturnType == null || N.len(parameterizedReturnType.getActualTypeArguments()) < 2
                ? null
                : parameterizedReturnType.getActualTypeArguments()[1];

        return secondActualTypeArgument == null ? null
                : (secondActualTypeArgument instanceof Class ? (Class<?>) secondActualTypeArgument
                        : (secondActualTypeArgument instanceof ParameterizedType && ((ParameterizedType) secondActualTypeArgument).getRawType() instanceof Class
                                ? (Class<?>) ((ParameterizedType) secondActualTypeArgument).getRawType()
                                : null));
    }

    /**
     * Resolves the raw class two generic levels into the method's return type: the first type argument of the first
     * type argument (e.g. {@code User} in {@code List<List<User>>}).
     *
     * @param method the method whose generic return type is inspected
     * @return the raw class at the second nesting level, or {@code null} if the return type is not parameterized two
     *         levels deep or the type cannot be resolved to a class
     */
    private static Class<?> getFirstReturnEleEleType(final Method method) {
        final java.lang.reflect.Type genericReturnType = method.getGenericReturnType();

        final ParameterizedType parameterizedReturnType = genericReturnType instanceof ParameterizedType ? (ParameterizedType) genericReturnType : null;

        final java.lang.reflect.Type firstActualTypeArgument = parameterizedReturnType == null || N.isEmpty(parameterizedReturnType.getActualTypeArguments())
                ? null
                : parameterizedReturnType.getActualTypeArguments()[0];

        if (!(firstActualTypeArgument instanceof ParameterizedType) || N.isEmpty(((ParameterizedType) firstActualTypeArgument).getActualTypeArguments())) {
            return null;
        }

        final java.lang.reflect.Type firstReturnEleEleType = ((ParameterizedType) firstActualTypeArgument).getActualTypeArguments()[0];

        return firstReturnEleEleType == null ? null
                : (firstReturnEleEleType instanceof Class ? (Class<?>) firstReturnEleEleType
                        : (firstReturnEleEleType instanceof ParameterizedType && ((ParameterizedType) firstReturnEleEleType).getRawType() instanceof Class
                                ? (Class<?>) ((ParameterizedType) firstReturnEleEleType).getRawType()
                                : null));
    }

    /**
     * Resolves the raw class three generic levels into the method's return type: the first type argument of the
     * first type argument of the first type argument (e.g. {@code User} in {@code List<List<List<User>>>}).
     *
     * @param method the method whose generic return type is inspected
     * @return the raw class at the third nesting level, or {@code null} if the return type is not parameterized three
     *         levels deep or the type cannot be resolved to a class
     */
    private static Class<?> getFirstReturnEleEleEleType(final Method method) {
        final java.lang.reflect.Type genericReturnType = method.getGenericReturnType();
        final ParameterizedType parameterizedReturnType = genericReturnType instanceof ParameterizedType ? (ParameterizedType) genericReturnType : null;
        final java.lang.reflect.Type firstActualTypeArgument = parameterizedReturnType == null || N.isEmpty(parameterizedReturnType.getActualTypeArguments())
                ? null
                : parameterizedReturnType.getActualTypeArguments()[0];

        if (!(firstActualTypeArgument instanceof ParameterizedType firstParameterizedType) || N.isEmpty(firstParameterizedType.getActualTypeArguments())) {
            return null;
        }

        final java.lang.reflect.Type secondLevelType = firstParameterizedType.getActualTypeArguments()[0];

        if (!(secondLevelType instanceof ParameterizedType secondParameterizedType) || N.isEmpty(secondParameterizedType.getActualTypeArguments())) {
            return null;
        }

        final java.lang.reflect.Type thirdLevelType = secondParameterizedType.getActualTypeArguments()[0];

        return thirdLevelType instanceof Class ? (Class<?>) thirdLevelType
                : (thirdLevelType instanceof ParameterizedType && ((ParameterizedType) thirdLevelType).getRawType() instanceof Class
                        ? (Class<?>) ((ParameterizedType) thirdLevelType).getRawType()
                        : null);
    }

    /**
     * Builds the setter that binds a DAO method's argument array to its prepared query, based on the query's
     * parameter positions, named-parameter bindings, and batch/fragment configuration. When the SQL contains the
     * reserved system-time named parameters and their auto-binding is enabled, the current time (one clock reading
     * shared by all of them) is bound to them: by the returned setter when fragments cannot add named parameters,
     * otherwise by {@code prepareQuery} after fragment expansion, and for batch rows through the add-batch action. With
     * auto-binding disabled nothing binds them automatically; they are bound like any other named parameter. The
     * {@code @Bind} names of a non-procedure named query are validated eagerly here, at
     * proxy-creation time; names introduced by SQL fragments are resolved when the expanded query is prepared.
     *
     * @param queryInfo the parsed metadata of the method's SQL query
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @param method the DAO method
     * @param paramTypes the method's parameter types
     * @param paramLen the number of method parameters
     * @param fragmentParamLen the number of SQL-fragment parameters
     * @param stmtParamIndexes indexes of the method parameters that bind to statement parameters
     * @param bindListParamFlags flags, parallel to {@code stmtParamIndexes} (indexed by statement-parameter position,
     *        not by method-parameter index), marking the statement parameters annotated with {@code @BindList}
     * @param stmtParamLen the number of statement-bound parameters
     * @return the parameters setter, or {@link Jdbc.BiParametersSetter#DO_NOTHING} when there is nothing to bind
     * @throws UnsupportedOperationException if a {@code ParametersSetter}/{@code BiParametersSetter}/
     *         {@code TriParametersSetter} method parameter is used (not enabled at present), if a procedure parameter
     *         cannot be bound (an empty {@code @Bind} name, {@code @Bind} on only some parameters, or a single
     *         entity/{@code EntityId} parameter), if a single unnamed named-query parameter is not a bean, record,
     *         {@code Map} or {@code EntityId}, if a named-query parameter lacks {@code @Bind}, or if named-parameter
     *         bindings are invalid
     */
    @SuppressWarnings("rawtypes")
    private static Jdbc.BiParametersSetter<AbstractQuery, Object[]> createParametersSetter(final QueryInfo queryInfo, final String fullClassMethodName,
            final Method method, final Class<?>[] paramTypes, final int paramLen, final int fragmentParamLen, final int[] stmtParamIndexes,
            final boolean[] bindListParamFlags, final int stmtParamLen) throws UnsupportedOperationException {

        Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter = null;

        // ParametersSetter/BiParametersSetter/TriParametersSetter method parameters are detected but the
        // feature is not enabled at present: reject at DAO creation instead of building setters that are never used.
        if (Stream.of(paramTypes)
                .anyMatch(paramType -> Jdbc.ParametersSetter.class.isAssignableFrom(paramType) || Jdbc.BiParametersSetter.class.isAssignableFrom(paramType)
                        || Jdbc.TriParametersSetter.class.isAssignableFrom(paramType))) {
            throw new UnsupportedOperationException(
                    "Setting parameters by 'ParametersSetter/BiParametersSetter/TriParametersSetter' is not enabled at present. Can't use it in method: "
                            + fullClassMethodName);
        }

        if (stmtParamLen == 0) {
            // A procedure's named markers may all be OUT parameters registered by name through @OutParameter rather
            // than bound from method arguments, so, as with one or more statement parameters below, procedures are not
            // checked against @Bind names: a zero-argument "{call p(:out)}" used to fail DAO creation as "Missing bindings".
            if (queryInfo.isNamedQuery && !queryInfo.isProcedure) {
                validateNamedParameterBindings(queryInfo, java.util.Collections.emptyList(), fullClassMethodName);
            }
        } else if (queryInfo.isBatch) { //NOSONAR
            // ignore
        } else if (stmtParamLen == 1) {
            final Class<?> paramTypeOne = paramTypes[stmtParamIndexes[0]];

            if (queryInfo.isProcedure) {
                final Bind bind = Stream.of(method.getParameterAnnotations()[stmtParamIndexes[0]]).select(Bind.class).first().orElseNull();

                if (bind != null && Strings.isEmpty(bind.value())) {
                    throw new UnsupportedOperationException(
                            "In method: " + fullClassMethodName + ", @Bind on a procedure parameter must specify a non-empty name when named binding is used");
                }

                final String paramName = bind == null ? null : bind.value();

                if (Strings.isNotEmpty(paramName)) {
                    parametersSetter = (preparedQuery, args) -> ((CallableQuery) preparedQuery).setObject(paramName, args[stmtParamIndexes[0]]);
                } else if (queryInfo.isSingleParameter) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                } else if (Map.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((CallableQuery) preparedQuery).setParameters((Map<String, ?>) args[stmtParamIndexes[0]]);
                } else if (Beans.isBeanClass(paramTypeOne) || EntityId.class.isAssignableFrom(paramTypeOne) || Beans.isRecordClass(paramTypeOne)) {
                    throw new UnsupportedOperationException("In method: " + fullClassMethodName
                            + ", parameters for call(procedure) have to be bound with names through annotation @Bind, or Map. Entity/EntityId type parameter are not supported");
                } else if (Collection.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Collection) args[stmtParamIndexes[0]]);
                } else if (Object[].class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Object[]) args[stmtParamIndexes[0]]);
                } else {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                }
            } else if (queryInfo.isNamedQuery) {
                final Bind bind = Stream.of(method.getParameterAnnotations()[stmtParamIndexes[0]]).select(Bind.class).first().orElseNull();

                if (bind != null && Strings.isEmpty(bind.value())) {
                    throw new UnsupportedOperationException(
                            "In method: " + fullClassMethodName + ", @Bind on a named-query parameter must specify a non-empty name");
                }

                final String paramName = bind == null ? null : bind.value();

                if (Strings.isNotEmpty(paramName)) {
                    validateNamedParameterBindings(queryInfo, N.asList(paramName), fullClassMethodName);
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setObject(paramName, args[stmtParamIndexes[0]]);
                } else if (queryInfo.isSingleParameter) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                } else if (Beans.isBeanClass(paramTypeOne) || Beans.isRecordClass(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters(args[stmtParamIndexes[0]]);
                } else if (Map.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters((Map<String, ?>) args[stmtParamIndexes[0]]);
                } else if (EntityId.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters((EntityId) args[stmtParamIndexes[0]]);
                } else {
                    throw new UnsupportedOperationException("In method: " + fullClassMethodName
                            + ", parameters for named query have to be bound with names through annotation @Bind, or Map/Entity with getter/setter methods. Can not be: "
                            + ClassUtil.getSimpleClassName(paramTypeOne));
                }
            } else {
                if (bindListParamFlags[0]) {
                    parametersSetter = (preparedQuery, args) -> {
                        final Object paramOne = args[stmtParamIndexes[0]];
                        int idx = 1;

                        if (paramOne instanceof Collection) {
                            for (final Object e : (Collection) paramOne) {
                                preparedQuery.setObject(idx++, e);
                            }
                        } else if (paramOne instanceof int[]) {
                            for (final int e : (int[]) paramOne) {
                                preparedQuery.setInt(idx++, e);
                            }
                        } else if (paramOne instanceof long[]) {
                            for (final long e : (long[]) paramOne) {
                                preparedQuery.setLong(idx++, e);
                            }
                        } else if (paramOne instanceof double[]) {
                            for (final double e : (double[]) paramOne) {
                                preparedQuery.setDouble(idx++, e);
                            }
                        } else if (paramOne instanceof String[]) {
                            for (final String e : (String[]) paramOne) {
                                preparedQuery.setString(idx++, e);
                            }
                        } else if (paramOne instanceof Object[]) {
                            for (final Object e : (Object[]) paramOne) {
                                preparedQuery.setObject(idx++, e);
                            }
                        } else if (paramOne != null) {
                            for (int j = 0, len = Array.getLength(paramOne); j < len; j++) {
                                preparedQuery.setObject(idx++, Array.get(paramOne, j));
                            }
                        }
                    };
                } else if (queryInfo.isSingleParameter) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                } else if (Collection.class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Collection) args[stmtParamIndexes[0]]);
                } else if (Object[].class.isAssignableFrom(paramTypeOne)) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Object[]) args[stmtParamIndexes[0]]);
                } else {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                }
            }
        } else {
            if (queryInfo.isProcedure) {
                final Bind[] paramBinds = IntStream.of(stmtParamIndexes)
                        .mapToObj(i -> Stream.of(method.getParameterAnnotations()[i]).select(Bind.class).first().orElse(null))
                        .toArray(Bind[]::new);

                final boolean hasBind = Stream.of(paramBinds).anyMatch(Objects::nonNull);
                final boolean hasNoBind = Stream.of(paramBinds).anyMatch(Objects::isNull);

                if (hasBind) {
                    if (hasNoBind) {
                        throw new UnsupportedOperationException(
                                "In method: " + fullClassMethodName + ", either all procedure parameters must be bound with @Bind or none");
                    }

                    @SuppressWarnings("resource")
                    final String[] paramNames = Stream.of(paramBinds).map(Bind::value).toArray(IntFunctions.ofStringArray());

                    for (int i = 0; i < paramNames.length; i++) {
                        if (Strings.isEmpty(paramNames[i])) {
                            throw new UnsupportedOperationException("In method: " + fullClassMethodName + ", parameters[" + stmtParamIndexes[i]
                                    + "]: @Bind on a procedure parameter must specify a non-empty name when named binding is used"
                                    + " (bind every parameter with a non-empty @Bind, or none of them for positional binding)");
                        }
                    }

                    parametersSetter = (preparedQuery, args) -> {
                        final CallableQuery namedQuery = ((CallableQuery) preparedQuery);

                        for (int i = 0; i < stmtParamLen; i++) {
                            namedQuery.setObject(paramNames[i], args[stmtParamIndexes[i]]);
                        }
                    };
                } else {
                    if (stmtParamLen == paramLen) {
                        parametersSetter = AbstractQuery::setParameters;
                    } else {
                        parametersSetter = (preparedQuery, args) -> {
                            for (int i = 0; i < stmtParamLen; i++) {
                                preparedQuery.setObject(i + 1, args[stmtParamIndexes[i]]);
                            }
                        };
                    }
                }
            } else if (queryInfo.isNamedQuery) {
                @SuppressWarnings("resource")
                final String[] paramNames = IntStream.of(stmtParamIndexes)
                        .mapToObj(i -> Stream.of(method.getParameterAnnotations()[i])
                                .select(Bind.class)
                                .first()
                                .orElseThrow(() -> new UnsupportedOperationException("In method: " + fullClassMethodName + ", parameters[" + i + "]: "
                                        + ClassUtil.getSimpleClassName(method.getParameterTypes()[i])
                                        + " is not bound with parameter named through annotation @Bind")))
                        .map(Bind::value)
                        .toArray(IntFunctions.ofStringArray());

                validateNamedParameterBindings(queryInfo, N.asList(paramNames), fullClassMethodName);

                parametersSetter = (preparedQuery, args) -> {
                    final NamedQuery namedQuery = ((NamedQuery) preparedQuery);

                    for (int i = 0; i < stmtParamLen; i++) {
                        namedQuery.setObject(paramNames[i], args[stmtParamIndexes[i]]);
                    }
                };
            } else {
                if (stmtParamLen == paramLen && Stream.of(bindListParamFlags).allMatch(it -> !it)) {
                    parametersSetter = AbstractQuery::setParameters;
                } else {
                    parametersSetter = (preparedQuery, args) -> {
                        for (int idx = 1, i = 0; i < stmtParamLen; i++) {
                            if (bindListParamFlags[i]) {
                                final Object paramOne = args[stmtParamIndexes[i]];

                                if (paramOne instanceof Collection) {
                                    for (final Object e : (Collection) paramOne) {
                                        preparedQuery.setObject(idx++, e);
                                    }
                                } else if (paramOne instanceof int[]) {
                                    for (final int e : (int[]) paramOne) {
                                        preparedQuery.setInt(idx++, e);
                                    }
                                } else if (paramOne instanceof long[]) {
                                    for (final long e : (long[]) paramOne) {
                                        preparedQuery.setLong(idx++, e);
                                    }
                                } else if (paramOne instanceof double[]) {
                                    for (final double e : (double[]) paramOne) {
                                        preparedQuery.setDouble(idx++, e);
                                    }
                                } else if (paramOne instanceof String[]) {
                                    for (final String e : (String[]) paramOne) {
                                        preparedQuery.setString(idx++, e);
                                    }
                                } else if (paramOne instanceof Object[]) {
                                    for (final Object e : (Object[]) paramOne) {
                                        preparedQuery.setObject(idx++, e);
                                    }
                                } else if (paramOne != null) {
                                    for (int j = 0, len = Array.getLength(paramOne); j < len; j++) {
                                        preparedQuery.setObject(idx++, Array.get(paramOne, j));
                                    }
                                }
                            } else {
                                preparedQuery.setObject(idx++, args[stmtParamIndexes[i]]);
                            }
                        }
                    };
                }
            }
        }

        if (queryInfo.isNamedQuery && queryInfo.autoSetSysTimeParam && !queryInfo.fragmentsContainNamedParameters) {
            final boolean hasNow = queryInfo.parsedSql.namedParameters().contains(JdbcUtil.PN_NOW);
            final boolean hasSysTime = queryInfo.parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_TIME);
            final boolean hasSysDate = queryInfo.parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_DATE);

            if (hasNow || hasSysTime || hasSysDate) {
                final BiParametersSetter<AbstractQuery, Object[]> tmp = parametersSetter == null ? Jdbc.BiParametersSetter.DO_NOTHING : parametersSetter;

                parametersSetter = (preparedQuery, args) -> {
                    tmp.accept(preparedQuery, args);

                    // Capture one clock reading for the entire execution. Besides honoring the documented
                    // alias semantics (:now and :sysTime are the same instant), deriving :sysDate from that
                    // same value prevents a midnight boundary from producing internally inconsistent values.
                    final java.sql.Timestamp currentTimestamp = Dates.currentTimestamp();

                    if (hasNow) {
                        setSysTimestampParam(preparedQuery, JdbcUtil.PN_NOW, currentTimestamp);
                    }
                    if (hasSysTime) {
                        setSysTimestampParam(preparedQuery, JdbcUtil.PN_SYS_TIME, currentTimestamp);
                    }
                    if (hasSysDate) {
                        setSysDateParam(preparedQuery, JdbcUtil.PN_SYS_DATE, new java.sql.Date(currentTimestamp.getTime()));
                    }
                };
            }
        }

        return parametersSetter == null ? Jdbc.BiParametersSetter.DO_NOTHING : parametersSetter;
    }

    /**
     * Validates the {@code @Bind} names of a named-query method: every name must be non-empty, no two method
     * parameters may bind to the same name, and the bound names must exactly cover the named parameters in the SQL
     * (excluding the reserved system-time parameters when they are auto-set). When fragments may add named
     * parameters, additional binding names are checked by the named query after fragment expansion.
     *
     * @param queryInfo the parsed metadata of the method's SQL query
     * @param boundParamNames the {@code @Bind} names declared on the method's parameters
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @throws UnsupportedOperationException if a binding name is empty or duplicated, if a named parameter of the SQL
     *         (other than an auto-set system-time parameter) has no binding, or, unless fragments may add named
     *         parameters, if a binding name has no matching named parameter in the SQL
     */
    private static void validateNamedParameterBindings(final QueryInfo queryInfo, final Collection<String> boundParamNames, final String fullClassMethodName)
            throws UnsupportedOperationException {
        final Set<String> boundParamNameSet = new HashSet<>();
        final List<String> duplicateParamNames = new ArrayList<>();

        for (final String paramName : boundParamNames) {
            if (Strings.isEmpty(paramName)) {
                throw new UnsupportedOperationException(
                        "In method: " + fullClassMethodName + ", every @Bind on a named-query parameter must specify a non-empty name");
            }

            if (!boundParamNameSet.add(paramName) && !duplicateParamNames.contains(paramName)) {
                duplicateParamNames.add(paramName);
            }
        }

        if (N.notEmpty(duplicateParamNames)) {
            throw new UnsupportedOperationException(
                    "In method: " + fullClassMethodName + ", multiple method parameters are bound to the same named parameter: " + duplicateParamNames);
        }

        final Set<String> sqlParamNameSet = new HashSet<>(queryInfo.parsedSql.namedParameters());
        final Set<String> requiredSqlParamNameSet = new HashSet<>(sqlParamNameSet);

        if (queryInfo.autoSetSysTimeParam) {
            requiredSqlParamNameSet.remove(JdbcUtil.PN_NOW);
            requiredSqlParamNameSet.remove(JdbcUtil.PN_SYS_TIME);
            requiredSqlParamNameSet.remove(JdbcUtil.PN_SYS_DATE);
        }

        final List<String> missingParamNames = N.difference(requiredSqlParamNameSet, boundParamNameSet);
        final List<String> extraParamNames = queryInfo.fragmentsContainNamedParameters ? java.util.Collections.emptyList()
                : N.difference(boundParamNameSet, sqlParamNameSet);

        if (N.notEmpty(missingParamNames) || N.notEmpty(extraParamNames)) {
            throw new UnsupportedOperationException("In method: " + fullClassMethodName
                    + ", named SQL parameters and @Bind names do not match. Missing bindings: " + missingParamNames + "; extra bindings: " + extraParamNames);
        }
    }

    // A procedure whose SQL uses named parameters is prepared as a CallableQuery, not a NamedQuery;
    // both declare named setters but share no common interface for them.
    /**
     * Sets a named timestamp parameter on the prepared query, dispatching to {@link CallableQuery} or
     * {@link NamedQuery} as appropriate.
     *
     * @param preparedQuery the query to bind; must be a {@link CallableQuery} or {@link NamedQuery}
     * @param parameterName the named parameter to set
     * @param value the timestamp value to bind
     * @throws SQLException if setting the parameter fails
     * @throws IllegalArgumentException if {@code preparedQuery} is a {@link NamedQuery} and {@code parameterName}
     *         is not a named parameter of that query
     */
    private static void setSysTimestampParam(@SuppressWarnings("rawtypes") final AbstractQuery preparedQuery, final String parameterName,
            final java.sql.Timestamp value) throws SQLException, IllegalArgumentException {
        if (preparedQuery instanceof CallableQuery) {
            ((CallableQuery) preparedQuery).setTimestamp(parameterName, value);
        } else {
            ((NamedQuery) preparedQuery).setTimestamp(parameterName, value);
        }
    }

    /**
     * Sets a named date parameter on the prepared query, dispatching to {@link CallableQuery} or
     * {@link NamedQuery} as appropriate.
     *
     * @param preparedQuery the query to bind; must be a {@link CallableQuery} or {@link NamedQuery}
     * @param parameterName the named parameter to set
     * @param value the date value to bind
     * @throws SQLException if setting the parameter fails
     * @throws IllegalArgumentException if {@code preparedQuery} is a {@link NamedQuery} and {@code parameterName}
     *         is not a named parameter of that query
     */
    private static void setSysDateParam(@SuppressWarnings("rawtypes") final AbstractQuery preparedQuery, final String parameterName, final java.sql.Date value)
            throws SQLException, IllegalArgumentException {
        if (preparedQuery instanceof CallableQuery) {
            ((CallableQuery) preparedQuery).setDate(parameterName, value);
        } else {
            ((NamedQuery) preparedQuery).setDate(parameterName, value);
        }
    }

    /**
     * Creates and configures the {@link AbstractQuery} for one DAO method invocation: substitutes SQL-fragment
     * parameters into the SQL text, opens a parameterized, named, or callable query against the DAO's data source,
     * registers procedure out parameters, applies fetch direction/size and query timeout derived from the query
     * metadata and operation type, and binds the method arguments (or configures the batch action for batch
     * queries). On any failure, a partially prepared query is closed before the error is rethrown.
     *
     * @param proxy the DAO proxy providing the data source
     * @param queryInfo the parsed metadata of the method's SQL query
     * @param mergedByIdAnno the {@code @MergedById} annotation on the method, or {@code null}
     * @param returnType the method's return type
     * @param args the invocation arguments
     * @param fragmentParamIndexes indexes of the SQL-fragment parameters, or {@code null}
     * @param fragmentAnnos the fragment annotations and their placeholders, or {@code null}
     * @param fragmentMappers mappers converting fragment argument values to SQL text, or {@code null}
     * @param returnGeneratedKeys {@code true} to prepare the query for generated-key retrieval
     * @param generatedKeyColumnNames the generated key column names, or {@code null}
     * @param outParameterList the procedure out parameters to register, or {@code null}
     * @param parametersSetter the setter binding {@code args} to the query
     * @param isExistsQueryMethod {@code true} if the method is an exists query
     * @param isSingleReturnTypeMethod {@code true} if the method returns a single value
     * @param isListQueryMethod {@code true} if the method is a list query
     * @return the prepared and configured query; the caller is responsible for closing it
     * @throws ParsingException if a fragment argument value, or an element of a {@code @SqlFragmentList} argument, whose
     *         type renders as JSON (e.g. a bean or map) cannot be converted to SQL text; elements are otherwise joined
     *         verbatim, not serialized as JSON
     * @throws UncheckedIOException if rendering such a fragment value as JSON fails while reading a value
     * @throws IllegalArgumentException if the SQL left after fragment substitution cannot be parsed, if a bound name
     *         has no matching named parameter in the expanded SQL, or if an argument value has a type that cannot be
     *         bound to a named parameter
     * @throws UncheckedSQLException if acquiring a required database connection fails
     * @throws SQLException if preparing or configuring the query, or binding its parameters, fails
     */
    @SuppressWarnings({ "rawtypes", "unused" })
    private static AbstractQuery prepareQuery(final DaoBase proxy, final QueryInfo queryInfo, final MergedById mergedByIdAnno, final Class<?> returnType,
            final Object[] args, final int[] fragmentParamIndexes, final Tuple2<Annotation, String>[] fragmentAnnos,
            final BiFunction<Annotation, Object, String>[] fragmentMappers, final boolean returnGeneratedKeys, final String[] generatedKeyColumnNames,
            final List<OutParameter> outParameterList, final Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter,
            final boolean isExistsQueryMethod, final boolean isSingleReturnTypeMethod, final boolean isListQueryMethod)
            throws ParsingException, UncheckedIOException, IllegalArgumentException, UncheckedSQLException, SQLException {
        final QueryOperation queryOperation = queryInfo.queryOperation;
        String query = queryInfo.sql;
        ParsedSql parsedSql = queryInfo.parsedSql;

        if (N.notEmpty(fragmentAnnos)) {
            // SQL referenced by id (SqlMapper/@SqlScript) is held in QueryInfo in its parameterized form ("?"
            // markers). A named query must expand its fragments on the original text, or the re-parse below
            // would lose its named parameters.
            if (queryInfo.isNamedQuery && !queryInfo.isProcedure) {
                query = parsedSql.originalSql();
            }

            for (int i = 0, len = fragmentAnnos.length; i < len; i++) {
                query = Strings.replaceAll(query, fragmentAnnos[i]._2, fragmentMappers[i].apply(fragmentAnnos[i]._1, args[fragmentParamIndexes[i]]));
            }

            if (N.notEmpty(parsedSql.namedParameters()) || parsedSql.parameterCount() == 0) {
                parsedSql = ParsedSql.parse(query);
                query = parsedSql.originalSql();
            }
        }

        AbstractQuery preparedQuery = null;

        try {
            preparedQuery = queryInfo.isProcedure ? JdbcUtil.prepareCallableQuery(proxy.dataSource(), query)
                    : (queryInfo.isNamedQuery
                            ? (returnGeneratedKeys ? JdbcUtil.prepareNamedQuery(proxy.dataSource(), parsedSql, generatedKeyColumnNames)
                                    : JdbcUtil.prepareNamedQuery(proxy.dataSource(), parsedSql))
                            : (returnGeneratedKeys ? JdbcUtil.prepareQuery(proxy.dataSource(), query, generatedKeyColumnNames)
                                    : JdbcUtil.prepareQuery(proxy.dataSource(), query)));

            if (queryInfo.isProcedure && N.notEmpty(outParameterList)) {
                final CallableQuery callableQuery = ((CallableQuery) preparedQuery);

                for (final OutParameter outParameter : outParameterList) {
                    if (Strings.isEmpty(outParameter.name())) {
                        callableQuery.registerOutParameter(outParameter.position(), outParameter.sqlType());
                    } else {
                        callableQuery.registerOutParameter(outParameter.name(), outParameter.sqlType());
                    }
                }
            }

            if (queryInfo.isSelect) {
                preparedQuery.setFetchDirection(FetchDirection.FORWARD);
            }

            if (queryInfo.fetchSize > 0) {
                preparedQuery.setFetchSize(queryInfo.fetchSize);
            } else if (queryInfo.isSelect) {
                if (mergedByIdAnno != null) {
                    preparedQuery.configureStatement(JdbcUtil.stmtSetterForBigQueryResult); // @MergedById may fetch a large multi-row result set to merge
                } else if (queryOperation == QueryOperation.findOnlyOne || queryOperation == QueryOperation.queryForUnique) {
                    preparedQuery.setFetchSize(2);
                } else if (queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.queryForSingle
                        || queryOperation == QueryOperation.exists || isExistsQueryMethod || isSingleReturnTypeMethod) {
                    preparedQuery.setFetchSize(1);
                } else if (queryOperation == QueryOperation.stream || queryOperation == QueryOperation.streamAll || Stream.class.isAssignableFrom(returnType)) {
                    preparedQuery.configureStatement(JdbcUtil.stmtSetterForStream);
                } else if (queryOperation == QueryOperation.list || queryOperation == QueryOperation.listAll || queryOperation == QueryOperation.query
                        || queryOperation == QueryOperation.queryAll || isListQueryMethod) {
                    preparedQuery.configureStatement(JdbcUtil.stmtSetterForBigQueryResult);
                } else if (queryOperation == QueryOperation.DEFAULT && (Dataset.class.isAssignableFrom(returnType))) {
                    preparedQuery.configureStatement(JdbcUtil.stmtSetterForBigQueryResult);
                }
            }

            if (queryInfo.queryTimeout >= 0) {
                preparedQuery.setQueryTimeout(queryInfo.queryTimeout);
            }

            if (queryInfo.isBatch) {
                if (queryInfo.isNamedQuery && queryInfo.autoSetSysTimeParam) {
                    final boolean hasNow = parsedSql.namedParameters().contains(JdbcUtil.PN_NOW);
                    final boolean hasSysTime = parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_TIME);
                    final boolean hasSysDate = parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_DATE);

                    if (hasNow || hasSysTime || hasSysDate) {
                        preparedQuery.configAddBatchAction((q, s) -> {
                            // Each batch row is one execution unit: all injected aliases for that row
                            // must come from the same clock reading, while successive rows may advance.
                            final java.sql.Timestamp currentTimestamp = Dates.currentTimestamp();

                            if (hasNow) {
                                setSysTimestampParam((AbstractQuery) q, JdbcUtil.PN_NOW, currentTimestamp);
                            }
                            if (hasSysTime) {
                                setSysTimestampParam((AbstractQuery) q, JdbcUtil.PN_SYS_TIME, currentTimestamp);
                            }
                            if (hasSysDate) {
                                setSysDateParam((AbstractQuery) q, JdbcUtil.PN_SYS_DATE, new java.sql.Date(currentTimestamp.getTime()));
                            }
                            ((PreparedStatement) s).addBatch();
                        });
                    }
                }
            } else {
                preparedQuery.settParameters(args, parametersSetter);

                if (queryInfo.isNamedQuery && queryInfo.autoSetSysTimeParam && queryInfo.fragmentsContainNamedParameters) {
                    // Fragments may introduce any of the aliases. Bind them together after expansion so
                    // aliases from both the static SQL and the fragments share the same clock reading.
                    final java.sql.Timestamp currentTimestamp = Dates.currentTimestamp();

                    if (parsedSql.namedParameters().contains(JdbcUtil.PN_NOW)) {
                        setSysTimestampParam(preparedQuery, JdbcUtil.PN_NOW, currentTimestamp);
                    }
                    if (parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_TIME)) {
                        setSysTimestampParam(preparedQuery, JdbcUtil.PN_SYS_TIME, currentTimestamp);
                    }
                    if (parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_DATE)) {
                        setSysDateParam(preparedQuery, JdbcUtil.PN_SYS_DATE, new java.sql.Date(currentTimestamp.getTime()));
                    }
                }
            }

            return preparedQuery;
        } catch (final SQLException | RuntimeException | Error e) {
            if (preparedQuery != null) {
                try {
                    preparedQuery.close();
                } catch (final RuntimeException | Error closeFailure) {
                    if (closeFailure != e) {
                        e.addSuppressed(closeFailure);
                    }
                }
            }

            throw e;
        }
    }

    /**
     * Applies a row-count limit to a condition, returning the condition to use. A non-positive {@code count} leaves
     * the condition unchanged, and an existing limit (standalone, inside a {@code Criteria}, or spelled out as a
     * top-level {@code LIMIT}/{@code OFFSET}/{@code FETCH} clause in a {@code SqlExpression} literal, i.e. not inside a
     * subquery, quoted literal or comment) is always preserved.
     * Otherwise a limit of {@code count} is added; when {@code skipLimitWithoutOrderBy} is {@code true} the limit is
     * dropped instead if the resulting condition has no {@code ORDER BY} (for dialects that cannot render a limit
     * without one).
     *
     * @param cond the condition to limit, or {@code null}
     * @param count the maximum row count; non-positive means no framework-added limit
     * @param skipLimitWithoutOrderBy {@code true} to skip adding a limit when there is no {@code ORDER BY}
     * @return the condition with the limit applied as described; {@code null} only when {@code cond} is {@code null}
     *         and no limit is added
     * @throws IllegalArgumentException if {@code count} is positive and {@code cond} is rejected by
     *         {@link Criteria.Builder#add(Condition)}
     */
    private static Condition handleLimit(final Condition cond, final int count, final boolean skipLimitWithoutOrderBy) throws IllegalArgumentException {
        // A non-positive count means "no framework-added limit": return the condition unchanged. Any existing Limit
        // (standalone or inside a Criteria) is preserved as-is and rendered per dialect later by the SQL builder.
        // skipLimitWithoutOrderBy marks a best-effort limit on a dialect (SQL Server) that renders LIMIT as
        // OFFSET/FETCH, whose grammar requires ORDER BY: adding it to an ORDER-BY-less condition would make the
        // query unbuildable, so the limit is dropped instead. Mandatory limits (paging) pass false and surface
        // the builder's validation error to the caller.

        if (count <= 0) {
            return cond;
        } else if (cond == null) {
            return skipLimitWithoutOrderBy ? null : Filters.limit(count);
        } else if (cond instanceof final Limit limit) {
            return limit;
        } else if (cond instanceof final Criteria criteria) {
            final Limit limit = criteria.limit();

            if (limit != null) {
                return cond;
            }

            if (skipLimitWithoutOrderBy && criteria.orderBy() == null) {
                return cond;
            }

            return criteria.toBuilder().limit(count).build();
        } else {
            // Only a top-level limit clause belongs to the condition itself: LIMIT inside a subquery or inside a quoted
            // literal must not suppress the framework limit (a paginate page would otherwise silently become unbounded).
            if (cond instanceof final SqlExpression expr && hasTopLevelLimitClause(expr.literal())) {
                try {
                    return Filters.limit(expr.literal());
                } catch (Exception e) {
                    // The literal carries a limit clause but isn't a parseable standalone Limit
                    // (e.g. "id > 0 FETCH FIRST 10 ROWS ONLY"); return it as-is rather than appending
                    // a second limit, which would produce invalid SQL.
                    return cond;
                }
            }

            final Criteria criteria = Criteria.builder().limit(count).add(cond).build();

            // Criteria.Builder.add routes clause conditions (e.g. a bare OrderBy) into their slots, so the
            // built criteria may legitimately carry an ORDER BY that satisfies the dialect's requirement.
            return skipLimitWithoutOrderBy && criteria.orderBy() == null ? cond : criteria;
        }
    }

    /**
     * Finds an outer row-limiting clause ({@code LIMIT}, {@code OFFSET}, {@code FETCH FIRST} or {@code FETCH NEXT}) in a
     * literal condition, ignoring quoted tokens, comments and nested SQL such as subqueries.
     *
     * <p>As in {@link #hasTopLevelOrderBy(String)}, when the parentheses seen by the tokenizer do not balance the nesting
     * depth cannot be trusted, and any of those keywords anywhere in the text is accepted instead.</p>
     *
     * @param sql the literal condition
     * @return whether a top-level limit clause is present
     */
    private static boolean hasTopLevelLimitClause(final String sql) {
        int depth = 0;
        boolean balanced = true;
        boolean previousWasFetch = false;
        boolean found = false;

        for (final String token : SqlParser.tokenize(sql)) {
            if (token.isBlank()) {
                continue;
            }

            if ("(".equals(token)) {
                depth++;
            } else if (")".equals(token)) {
                balanced &= --depth >= 0;
            } else if (depth == 0 && ("LIMIT".equalsIgnoreCase(token) || "OFFSET".equalsIgnoreCase(token)
                    || (previousWasFetch && ("FIRST".equalsIgnoreCase(token) || "NEXT".equalsIgnoreCase(token))))) {
                found = true;
            }

            previousWasFetch = depth == 0 && "FETCH".equalsIgnoreCase(token);
        }

        return balanced && depth == 0 ? found : Strings.containsAnyIgnoreCase(sql, " LIMIT ", " OFFSET ", " FETCH NEXT ", " FETCH FIRST ");
    }

    /**
     * Removes the outer {@code ORDER BY}, {@code LIMIT}, {@code OFFSET} or {@code FETCH FIRST|NEXT} clause (and everything
     * after it) that ends a literal condition, for {@code count(Condition)}: applied to the single aggregate row, an
     * {@code OFFSET} skips it and an {@code ORDER BY} is rejected by strict databases.
     *
     * <p>The literal is left unchanged when it has no such clause, has an outer {@code GROUP BY} or set operation (the
     * clauses are then meaningful), or when its structure is uncertain: unbalanced parentheses, an unterminated quote or
     * comment, or a quote whose end depends on whether backslash escapes or {@code #} comments apply.</p>
     *
     * @param expr the literal condition
     * @return {@code expr} itself if nothing is removed, {@code null} if nothing is left, otherwise the remaining condition
     */
    private static SqlExpression stripTrailingRowClauses(final SqlExpression expr) {
        final String literal = expr.literal();
        // Scanned under both lexical modes (MySQL: backslash escapes and # comments; standard: neither); a cut point both
        // agree on is outside any quote or comment whichever mode the database uses.
        final int end = indexOfTrailingRowClauses(literal, false);

        if (end < 0 || end != indexOfTrailingRowClauses(literal, true)) {
            return expr;
        }

        final String remaining = literal.substring(0, end);

        return Strings.isBlank(remaining) ? null : Filters.expr(remaining);
    }

    /**
     * Returns the end of the code preceding the outer row-limiting/ordering clause that ends {@code sql} (so trailing
     * comments before the clause are dropped with it), or {@code -1} if it should be kept as is
     * (see {@link #stripTrailingRowClauses(SqlExpression)}).
     *
     * @param sql the literal condition
     * @param mySqlLexicalMode {@code true} to read backslash escapes in quotes and {@code #} line comments (other than a
     *        {@code #{name}} parameter), {@code false} for neither
     * @return the end index of the code to keep, or {@code -1}
     */
    private static int indexOfTrailingRowClauses(final String sql, final boolean mySqlLexicalMode) {
        final int len = sql.length();
        int depth = 0;
        int lastTokenEnd = 0; // end of the last top-level token (word or symbol), comments and whitespace excluded
        String previousWord = null; // the previous top-level token, if it was a word; null after any other token
        int previousWordPrecedingEnd = -1;
        boolean previousWasDot = false;
        int pendingEnd = -1; // LIMIT/OFFSET/FETCH seen: a clause only if a row count (or FIRST/NEXT) follows
        boolean pendingIsFetch = false;
        boolean pendingIsLimit = false;
        int keepEnd = -1;

        for (int i = 0; i < len;) {
            final char ch = sql.charAt(i);

            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            } else if (ch == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }

                continue;
            } else if (ch == '#' && mySqlLexicalMode && !(i + 1 < len && sql.charAt(i + 1) == '{')) {
                while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }

                continue;
            } else if (ch == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int commentDepth = 1;
                i += 2;

                while (i < len && commentDepth > 0) {
                    if (sql.startsWith("/*", i)) {
                        commentDepth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        commentDepth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }

                if (commentDepth > 0) {
                    return -1;
                }

                continue;
            }

            final int tokenStart = i;
            String word = null;
            boolean isParameter = false;

            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                final char close = ch == '[' ? ']' : ch;
                boolean terminated = false;
                i++;

                while (i < len) {
                    final char c = sql.charAt(i++);

                    if (mySqlLexicalMode && c == '\\' && ch != '[' && i < len) {
                        i++;
                    } else if (c == close) {
                        if (i < len && sql.charAt(i) == close) {
                            i++;
                        } else {
                            terminated = true;
                            break;
                        }
                    }
                }

                if (!terminated) {
                    return -1;
                }
            } else if (ch == '$' && isDollarQuoteTag(sql, i)) {
                // PostgreSQL dollar-quoted string: $$...$$ or $tag$...$tag$
                final String delimiter = sql.substring(i, sql.indexOf('$', i + 1) + 1);
                final int closing = sql.indexOf(delimiter, i + delimiter.length());

                if (closing < 0) {
                    return -1;
                }

                i = closing + delimiter.length();
            } else if (ch == '#' && i + 1 < len && sql.charAt(i + 1) == '{') {
                final int closing = sql.indexOf('}', i + 2);

                if (closing < 0) {
                    return -1;
                }

                i = closing + 1;
                isParameter = true;
            } else if (ch == '(') {
                depth++;
                i++;
            } else if (ch == ')') {
                if (--depth < 0) {
                    return -1;
                }

                i++;
            } else if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                while (i < len && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }

                word = sql.substring(tokenStart, i);
            } else if (ch == ':' && i + 1 < len && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                // a named parameter (:name); "::" casts are read as symbols below
                i++;

                while (i < len && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }

                isParameter = true;
            } else {
                isParameter = ch == '?';
                i++;
            }

            // The token sql[tokenStart, i) is outside any parentheses ('(' was counted above, a closing ')' already uncounted).
            final boolean topLevel = ch == '(' ? depth == 1 : depth == 0;

            if (pendingEnd >= 0) {
                final boolean confirmed = topLevel && (pendingIsFetch ? "FIRST".equalsIgnoreCase(word) || "NEXT".equalsIgnoreCase(word)
                        : (word != null && Character.isDigit(word.charAt(0))) || isParameter || (pendingIsLimit && "ALL".equalsIgnoreCase(word)));

                if (confirmed && keepEnd < 0) {
                    keepEnd = pendingEnd;
                }

                pendingEnd = -1;
            }

            if (topLevel && word != null && !previousWasDot) {
                if ("BY".equalsIgnoreCase(word) && "GROUP".equalsIgnoreCase(previousWord)) {
                    return -1;
                } else if ("UNION".equalsIgnoreCase(word) || "INTERSECT".equalsIgnoreCase(word) || "EXCEPT".equalsIgnoreCase(word)
                        || "MINUS".equalsIgnoreCase(word)) {
                    return -1;
                } else if ("BY".equalsIgnoreCase(word) && "ORDER".equalsIgnoreCase(previousWord)) {
                    if (keepEnd < 0) {
                        keepEnd = previousWordPrecedingEnd;
                    }
                } else if ("LIMIT".equalsIgnoreCase(word) || "OFFSET".equalsIgnoreCase(word) || "FETCH".equalsIgnoreCase(word)) {
                    pendingEnd = lastTokenEnd;
                    pendingIsFetch = "FETCH".equalsIgnoreCase(word);
                    pendingIsLimit = "LIMIT".equalsIgnoreCase(word);
                }
            }

            if (topLevel) {
                previousWasDot = ch == '.';
                previousWordPrecedingEnd = word == null ? -1 : lastTokenEnd;
                previousWord = word;
                lastTokenEnd = i;
            }
        }

        return depth == 0 ? keepEnd : -1;
    }

    /**
     * Returns whether a PostgreSQL dollar-quote delimiter ({@code $$} or {@code $tag$}, the tag not starting with a digit,
     * unlike a {@code $1} parameter) starts at {@code index}.
     */
    private static boolean isDollarQuoteTag(final String sql, final int index) {
        final int tagEnd = sql.indexOf('$', index + 1);

        if (tagEnd < 0 || (tagEnd > index + 1 && Character.isDigit(sql.charAt(index + 1)))) {
            return false;
        }

        for (int k = index + 1; k < tagEnd; k++) {
            if (!(Character.isLetterOrDigit(sql.charAt(k)) || sql.charAt(k) == '_')) {
                return false;
            }
        }

        return true;
    }

    /**
     * Sums the positive entries of a JDBC batch update-count array, ignoring zero and negative entries
     * (e.g. {@link java.sql.Statement#SUCCESS_NO_INFO}).
     *
     * @param updateCounts the per-statement update counts returned by a batch execution
     * @return the total number of affected rows
     */
    private static long sumUpdateCounts(final int[] updateCounts) {
        long total = 0;

        for (final int updateCount : updateCounts) {
            if (updateCount > 0) {
                total += updateCount;
            }
        }

        return total;
    }

    /**
     * Sums the positive entries of a JDBC large-batch update-count array, ignoring zero and negative entries
     * (e.g. {@link java.sql.Statement#SUCCESS_NO_INFO}).
     *
     * @param updateCounts the per-statement update counts returned by a batch execution
     * @return the total number of affected rows
     * @throws ArithmeticException if the total overflows a {@code long}
     */
    private static long sumUpdateCounts(final long[] updateCounts) throws ArithmeticException {
        long total = 0;

        for (final long updateCount : updateCounts) {
            if (updateCount > 0) {
                total = Math.addExact(total, updateCount);
            }
        }

        return total;
    }

    /**
     * Returns the id extractor for the given DAO: the extractor declared on the DAO interface if present, otherwise
     * {@code defaultIdExtractor}. The resolved extractor is stored in {@code idExtractorHolder} so subsequent calls
     * reuse it.
     *
     * @param idExtractorHolder holder caching the resolved extractor between calls
     * @param defaultIdExtractor the fallback extractor when the DAO declares none
     * @param dao the DAO whose declared id extractor is looked up
     * @return the id extractor to use
     */
    @SuppressWarnings("rawtypes")
    private static Jdbc.BiRowMapper<Object> getIdExtractor(final Holder<Jdbc.BiRowMapper<Object>> idExtractorHolder,
            final Jdbc.BiRowMapper<Object> defaultIdExtractor, final DaoBase dao) {
        Jdbc.BiRowMapper<Object> keyExtractor = idExtractorHolder.value();

        if (keyExtractor == null) {
            // idExtractor() is declared on CrudInsertOps, so this covers every insert-capable CRUD
            // variant (for example NonUpdateCrudDao), but not read-only CRUD DAOs.
            keyExtractor = N.defaultIfNull((Jdbc.BiRowMapper<Object>) DaoUtil.getDeclaredIdExtractor(dao), defaultIdExtractor);

            idExtractorHolder.setValue(keyExtractor);
        }

        return keyExtractor;
    }

    /**
     * Distinguishes a missing Kryo dependency from a Kryo parser that is present but failed to initialize.
     * {@code ParserFactory} only exposes a combined availability flag and discards the failure, so the Kryo entry
     * class is probed directly and the parser is created once more to quote the actual failure instead of guessing
     * at a cause. Nothing thrown by the probes escapes: a diagnostic must never fail harder than the condition it
     * describes.
     *
     * @return a sentence naming the cause and the remedy for the Kryo parser being unavailable
     */
    private static String describeKryoUnavailability() {
        try {
            try {
                Class.forName("com.esotericsoftware.kryo.Kryo", false, DaoImpl.class.getClassLoader());
            } catch (final ClassNotFoundException e) {
                return "Kryo is absent from the classpath. Add the Kryo dependency.";
            } catch (final Throwable e) {
                // A restrictive classloader can reject the package outright; the parser attempt below reports it.
            }

            final Throwable failure;

            try {
                ParserFactory.createKryoParser();

                return "Kryo was unavailable when this DAO class initialized but its parser can be created now; the DAO keeps its "
                        + "start-up decision. Check for classpath or JVM access changes during start-up.";
            } catch (final Throwable e) {
                failure = e;
            }

            Throwable root = failure;

            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }

            // The JVM reports a class whose initializer already failed as "Could not initialize class X" with a
            // synthetic ExceptionInInitializerError cause whose message names the original exception.
            final String cause = root instanceof ExceptionInInitializerError && Strings.isNotEmpty(root.getMessage()) ? root.getMessage() : root.toString();
            final boolean missingClass = (cause.contains("NoClassDefFoundError") || cause.contains("ClassNotFoundException"))
                    && !cause.contains("Could not initialize class");

            if (missingClass && (cause.contains("com/esotericsoftware/kryo/") || cause.contains("com.esotericsoftware.kryo."))) {
                return "Kryo is absent from the classpath (" + cause + "). Add the Kryo dependency.";
            } else if (missingClass) {
                return "Kryo is on the classpath but one of its dependencies is missing (" + cause + "). Add Kryo's transitive dependencies "
                        + "(minlog, objenesis, reflectasm).";
            } else if (root instanceof SecurityException) {
                return "Kryo is on the classpath but loading it was denied (" + cause + "). Check the classloader or security policy.";
            }

            return "Kryo is on the classpath but its parser failed to initialize (" + cause + "). Check the JVM's Unsafe/module access options "
                    + "(for example --sun-misc-unsafe-memory-access) and the Kryo dependency versions.";
        } catch (final Throwable e) {
            return "Kryo is unavailable and the cause could not be determined (" + e + ").";
        }
    }

    /**
     * Returns {@code declaredType} when it is resolved to something more specific than {@code Object}; otherwise the
     * type of the runtime class shared by all non-{@code null} elements of {@code elements}, falling back to
     * {@code declaredType} (possibly {@code null}) when the elements have no single common class or share a
     * container class the copy cannot build.
     *
     * <p>A JDK view such as {@code Collections.unmodifiableSortedMap}, {@code TreeMap.descendingMap} or
     * {@code List.of} has no default constructor, so naming it as the element type would fail the copy with
     * "No default constructor found". Left untyped, such an element is rebuilt from its runtime shape instead
     * (a sorted one keeps its comparator, an ordinary one becomes the JSON default container).</p>
     *
     * @param declaredType the declared element type, or {@code null} if unknown
     * @param elements the runtime elements to inspect
     * @return the type to deserialize the elements as, or {@code null} if none could be determined
     */
    private static com.landawn.abacus.type.Type<?> resolvedOrRuntimeType(final com.landawn.abacus.type.Type<?> declaredType, final Iterable<?> elements) {
        if (declaredType != null && !Object.class.equals(declaredType.javaType())) {
            return declaredType;
        }

        final Class<?> elementClass = uniformElementClass(elements);

        if (elementClass == null) {
            return declaredType;
        }

        final com.landawn.abacus.type.Type<?> runtimeType = N.typeOf(elementClass);

        return isUsableJsonElementType(runtimeType) ? runtimeType : declaredType;
    }

    /**
     * Returns whether {@code type} can be named as a JSON element type: any type the parser does not treat as a
     * container (scalars, beans, and application-registered types with their own string form), a library immutable
     * wrapper (rebuilt by name), or a container class with a no-argument constructor for the parser.
     *
     * @param type the type resolved for the runtime class shared by the elements
     * @return {@code true} if the copy can deserialize elements as {@code type}
     */
    private static boolean isUsableJsonElementType(final com.landawn.abacus.type.Type<?> type) {
        if (!(type.isCollection() || type.isMap()) || com.landawn.abacus.util.Immutable.class.isAssignableFrom(type.javaType())) {
            return true;
        }

        for (final Constructor<?> constructor : type.javaType().getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the runtime class shared by all non-{@code null} elements of {@code elements}, or {@code null} if there
     * is no non-{@code null} element or the non-{@code null} elements are of different classes.
     *
     * @param elements the elements to inspect
     * @return the common runtime class of the non-{@code null} elements, or {@code null}
     */
    private static Class<?> uniformElementClass(final Iterable<?> elements) {
        Class<?> elementClass = null;

        for (final Object element : elements) {
            if (element == null) {
                continue;
            }

            if (elementClass == null) {
                elementClass = element.getClass();
            } else if (elementClass != element.getClass()) {
                return null;
            }
        }

        return elementClass;
    }

    /**
     * Returns {@code call} unchanged when {@code method} declares {@link SQLException} (or a supertype of it);
     * otherwise returns a wrapper that rethrows any {@link SQLException} raised by {@code call} as
     * {@link UncheckedSQLException}, so a checked failure never escapes the proxy as
     * {@link java.lang.reflect.UndeclaredThrowableException}.
     *
     * @param method the DAO method whose {@code throws} clause decides whether wrapping is needed
     * @param call the invoker to guard
     * @return {@code call} itself, or a wrapper converting {@link SQLException} to {@link UncheckedSQLException}
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Throwables.BiFunction<DaoBase, Object[], ?, Throwable> wrapUndeclaredSQLException(final Method method,
            final Throwables.BiFunction<DaoBase, Object[], ?, SQLException> call) {
        if (Stream.of(method.getExceptionTypes()).anyMatch(e -> e.isAssignableFrom(SQLException.class))) {
            return (Throwables.BiFunction) call;
        }

        return (proxy, args) -> {
            try {
                return call.apply(proxy, args);
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        };
    }

    /**
     * Logs the elapsed time of a DAO method invocation at INFO level when DAO method performance logging is globally
     * allowed, the {@code @PerfLog} threshold is non-negative, and the elapsed time meets that threshold.
     *
     * @param daoLogger the logger of the DAO class
     * @param simpleClassMethodName the simple class and method name included in the log message
     * @param perfLogAnno the effective {@code @PerfLog} annotation supplying the threshold
     * @param startTimeNanos the invocation start time in {@link System#nanoTime()} units
     */
    private static void logDaoMethodPerf(final Logger daoLogger, final String simpleClassMethodName, final PerfLog perfLogAnno, final long startTimeNanos) {
        if (JdbcUtil.isDaoMethodPerfLogAllowed && perfLogAnno.daoMethodPerfLogThresholdMillis() >= 0 && daoLogger.isInfoEnabled()) {
            final long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);

            if (elapsedTime >= perfLogAnno.daoMethodPerfLogThresholdMillis()) {
                daoLogger.info(Strings.concat("[DAO-QUERY-OPERATION-PERF]-[", simpleClassMethodName, "]: ", String.valueOf(elapsedTime), " ms"));
            }
        }
    }

    /**
     * Resolves the table name used for generated CRUD SQL: the explicit {@code targetTableName} when non-empty,
     * otherwise the entity's declared table name, otherwise the entity class's simple name converted with
     * {@code namingPolicy}.
     *
     * @param entityClass the entity class whose simple name is converted when no explicit or declared table name exists
     * @param entityInfo the entity's bean metadata
     * @param namingPolicy the naming policy used to derive the table name from the class name
     * @param targetTableName the explicit table name override, or {@code null}/empty
     * @return the table name to use
     */
    private static String getTableName(final Class<?> entityClass, final BeanInfo entityInfo, final NamingPolicy namingPolicy, final String targetTableName) {
        if (Strings.isNotEmpty(targetTableName)) {
            return targetTableName;
        }

        return entityInfo.tableName.orElseGet(() -> namingPolicy.convert(ClassUtil.getSimpleClassName(entityClass)));
    }

    /**
     * Validates a condition for a {@code paginate} operation: it must be non-{@code null} and carry an
     * outer {@code ORDER BY} (as a standalone order-by clause, on the {@code Criteria}, or in its literal form).
     * Returns the condition unchanged.
     *
     * @param <T> the condition type
     * @param cond the condition to validate
     * @return the same condition
     * @throws IllegalArgumentException if {@code cond} is {@code null} or has no {@code ORDER BY}
     */
    private static <T extends Condition> T checkCondForPaginate(final T cond) throws IllegalArgumentException {
        N.checkArgNotNull(cond, "Condition for \"paginate\" cannot be null");

        if (cond instanceof OrderBy || (cond instanceof Criteria && ((Criteria) cond).orderBy() != null) || hasTopLevelOrderBy(cond.toString())) {
            // okay, has order by
        } else {
            throw new IllegalArgumentException("Condition for \"paginate\" must have \"orderBy\"");
        }

        return cond;
    }

    /**
     * Checks that each element of {@code propNamesToUpdate} names a property of the entity, or is a raw
     * {@code "column = expression"} SET expression (see {@link #isRawSetExpression(String)}). An unknown name would otherwise
     * reach the database as an invalid column and fail there with a SQL error.
     *
     * @param propNamesToUpdate the property names to update
     * @param entityInfo the entity's bean info
     * @throws IllegalArgumentException if an element is {@code null} or names no property of the entity
     */
    private static void checkPropNamesToUpdate(final Collection<String> propNamesToUpdate, final BeanInfo entityInfo) throws IllegalArgumentException {
        for (final String propName : propNamesToUpdate) {
            if (propName == null || (!isRawSetExpression(propName) && entityInfo.getPropInfo(propName) == null)) {
                throw new IllegalArgumentException(
                        "No property found with name: " + propName + " in class: " + ClassUtil.getCanonicalClassName(entityInfo.clazz));
            }
        }
    }

    /**
     * Returns whether an element of the property names to update is a raw SET assignment such as
     * {@code "score = score + 5"}, which the query builder's {@code set(Collection)} renders as is, without a placeholder:
     * it has an {@code '='} outside quoted text, parentheses and brackets. An {@code '='} inside a delimited name or an
     * array index (e.g. {@code notes['a=b']}) belongs to the assignment target, which gets a placeholder. The builder reads
     * quoted text by its dialect's rules; this check reads it both with and without backslash escapes and requires the
     * {@code '='} in both readings, so an element it reports as raw is raw for the builder too. Any other element must name a
     * property, so a disagreement surfaces as an "unknown property" error rather than a misbound statement.
     *
     * @param propName an element of the property names to update; not {@code null}
     * @return {@code true} if the element is a raw SET assignment
     */
    static boolean isRawSetExpression(final String propName) {
        return propName.indexOf('=') >= 0 && hasTopLevelEquals(propName, false) && hasTopLevelEquals(propName, true);
    }

    /**
     * Returns whether {@code expr} has an {@code '='} outside quoted text, parentheses and brackets.
     *
     * @param expr the expression to scan
     * @param backslashEscapes whether a backslash escapes the next character in a string literal
     * @return {@code true} if such an {@code '='} occurs; {@code false} also if a quote is not closed
     */
    private static boolean hasTopLevelEquals(final String expr, final boolean backslashEscapes) {
        int depth = 0;

        for (int i = 0, len = expr.length(); i < len; i++) {
            final char ch = expr.charAt(i);

            if (ch == '\'' || ch == '"' || ch == '`') {
                // Backslashes escape only in string literals (MySQL), not in backtick-quoted names.
                final boolean escapes = backslashEscapes && ch != '`';
                int j = i + 1;
                boolean closed = false;

                while (j < len) {
                    final char c = expr.charAt(j++);

                    if (escapes && c == '\\' && j < len) {
                        j++;
                    } else if (c == ch) {
                        if (j < len && expr.charAt(j) == ch) {
                            j++; // a doubled delimiter stands for the character itself
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }

                if (!closed) {
                    return false;
                }

                i = j - 1;
            } else if (ch == '(' || ch == '[') {
                depth++;
            } else if ((ch == ')' || ch == ']') && depth > 0) {
                depth--;
            } else if (ch == '=' && depth == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Puts back the IDs that an insert generated in the application ({@link DaoConfig#callGenerateIdForInsertIfIdNotSet()},
     * {@link DaoConfig#callGenerateIdForInsertWithSqlIfIdNotSet()}) before it failed, when the failure leaves none of its rows:
     * before any statement executed, or when its own transaction was rolled back (a single statement that has executed is
     * covered by {@link #restoreGeneratedIdsUnlessExecuted(Runnable, ExecutionMark, Throwable)}). Its entities must not keep the
     * IDs of rows that don't exist. IDs read back from the database are only assigned once the insert succeeded, and a later
     * rollback of an enclosing transaction is handled by the action registered with it.
     *
     * @param generatedIdRestorer restores the ID values captured before the IDs were generated; {@code null} if none were generated
     * @param failure the failure of the insert, to which a failure of the restore is attached
     */
    private static void restoreGeneratedIds(final Runnable generatedIdRestorer, final Throwable failure) {
        if (generatedIdRestorer == null) {
            return;
        }

        try {
            generatedIdRestorer.run();
        } catch (final RuntimeException | Error e) {
            if (e != failure) {
                failure.addSuppressed(e);
            }
        }
    }

    /**
     * Begins (or joins) the transaction of a multi-row insert call, which may already have generated IDs in the application. If
     * that fails, no row was inserted, so the generated IDs are put back (see {@link #restoreGeneratedIds(Runnable, Throwable)})
     * before the failure propagates.
     *
     * @param ds the data source to begin the transaction on
     * @param generatedIdRestorer restores the ID values captured before the IDs were generated; {@code null} if none were generated
     * @return the transaction
     */
    private static SqlTransaction beginInsertTransaction(final javax.sql.DataSource ds, final Runnable generatedIdRestorer) {
        try {
            return JdbcUtil.beginTransaction(ds);
        } catch (final RuntimeException | Error e) {
            restoreGeneratedIds(generatedIdRestorer, e);
            throw e;
        }
    }

    /**
     * Returns the generated key of a custom INSERT into a table other than the DAO entity's as the method's declared type.
     * The key is read with the DAO entity's id type (e.g. as a {@code Long} for a {@code long} id), so a key declared as
     * another type (e.g. {@code int}) was returned unconverted and the call failed with a {@code ClassCastException}.
     *
     * @param key the generated key as read; may be {@code null}
     * @param declaredType the method's declared key type; {@code null} if it cannot be resolved (the key is returned as is)
     * @return the key as the declared type
     */
    private static Object convertGeneratedKey(final Object key, final Class<?> declaredType) {
        return declaredType == null || key == null || declaredType.isInstance(key) ? key : N.convert(key, declaredType);
    }

    /**
     * Returns the first non-null element of a custom batch INSERT's rows, which decides whether the batch holds DAO entities
     * when the declared element type does not (a wildcard, a type variable or a raw collection).
     *
     * @param batchParameters the rows; may be empty
     * @return the first non-null row; {@code null} if there is none
     */
    /**
     * Returns the class a declared type stands for: the class itself, the raw class of a parameterized type, or the (single)
     * upper bound of a wildcard or type variable, resolved recursively; {@code null} if it has none (an unbounded or
     * multiply-bounded wildcard/type variable resolves to {@code Object} or {@code null}).
     *
     * @param type the declared type
     * @return the class, or {@code null} if it cannot be resolved
     */
    private static Class<?> classOf(final Type type) {
        if (type instanceof final Class<?> cls) {
            return cls;
        } else if (type instanceof final ParameterizedType pt) {
            return pt.getRawType() instanceof final Class<?> rawCls ? rawCls : null;
        } else if (type instanceof final WildcardType wt) {
            final Type[] upperBounds = wt.getUpperBounds();

            return upperBounds.length == 1 ? classOf(upperBounds[0]) : null;
        } else if (type instanceof final TypeVariable<?> tv) {
            final Type[] bounds = tv.getBounds();

            return bounds.length == 1 ? classOf(bounds[0]) : null;
        }

        return null;
    }

    private static Object firstNonNullElement(final Collection<?> batchParameters) {
        for (final Object element : batchParameters) {
            if (element != null) {
                return element;
            }
        }

        return null;
    }

    /**
     * Puts back the IDs generated in the application for a single-statement insert that failed, unless the statement's
     * execution returned (reading the generated keys may still fail afterwards): with auto-commit the row, holding the
     * generated ID, may then already be committed, and inside an enclosing transaction its rollback puts the IDs back. A
     * failure thrown by the execution itself counts as not executed. A statement that runs in the call's own transaction
     * needs no such check: that transaction is rolled back.
     *
     * @param generatedIdRestorer restores the ID values captured before the IDs were generated; {@code null} if none were generated
     * @param executionMark marked once the statement's execution returned; {@code null} if no IDs were generated
     * @param failure the failure of the insert, to which a failure of the restore is attached
     */
    private static void restoreGeneratedIdsUnlessExecuted(final Runnable generatedIdRestorer, final ExecutionMark executionMark, final Throwable failure) {
        if (executionMark != null && !executionMark.executed) {
            restoreGeneratedIds(generatedIdRestorer, failure);
        }
    }

    /**
     * Marks that the execution of an insert statement returned (reading its generated keys may still fail afterwards); see
     * {@link #restoreGeneratedIdsUnlessExecuted(Runnable, ExecutionMark, Throwable)}.
     */
    private static final class ExecutionMark implements Runnable {
        private boolean executed;

        @Override
        public void run() {
            executed = true;
        }
    }

    /**
     * Matches the words {@code ORDER BY} anywhere in a literal condition; used only when the tokenizer's view of the
     * condition is unreliable (see {@link #hasTopLevelOrderBy(String)}).
     */
    private static final Pattern ORDER_BY_WORDS = Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Finds an outer ordering clause in a literal condition, ignoring quoted tokens, comments and nested SQL.
     *
     * <p>{@link SqlParser#tokenize(String)} does not recognize PostgreSQL dollar-quoted strings or nested block
     * comments, so a parenthesis inside them is counted as nesting. When the parentheses seen by the tokenizer do not
     * balance, the nesting depth cannot be trusted and any {@code ORDER BY} in the text is accepted instead.</p>
     *
     * @param sql the literal condition
     * @return whether consecutive top-level ORDER and BY keywords are present
     */
    private static boolean hasTopLevelOrderBy(final String sql) {
        int depth = 0;
        boolean balanced = true;
        boolean previousWasOrder = false;
        boolean found = false;

        for (final String token : SqlParser.tokenize(sql)) {
            if (token.isBlank()) {
                continue;
            }

            if ("(".equals(token)) {
                depth++;
            } else if (")".equals(token)) {
                balanced &= --depth >= 0;
            } else if (depth == 0 && previousWasOrder && "BY".equalsIgnoreCase(token)) {
                found = true;
            }

            previousWasOrder = depth == 0 && "ORDER".equalsIgnoreCase(token);
        }

        return balanced && depth == 0 ? found : ORDER_BY_WORDS.matcher(sql).find();
    }

    /**
     * Finds the SQL-fragment annotation ({@code @SqlFragment}, {@code @SqlFragmentList}, or {@code @BindList})
     * declared on the given method parameter and returns it together with its normalized placeholder name.
     *
     * @param method the DAO method
     * @param paramIndex the index of the parameter to inspect
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @return the fragment annotation and its placeholder name
     * @throws UnsupportedOperationException if the fragment annotation value is empty and the parameter name cannot
     *         be resolved
     * @throws IllegalArgumentException if the parameter carries none of the supported fragment annotations
     */
    private static Tuple2<Annotation, String> resolveFragmentAnnoAndPlaceholder(final Method method, final int paramIndex, final String fullClassMethodName)
            throws UnsupportedOperationException, IllegalArgumentException {
        final Annotation[] annotations = method.getParameterAnnotations()[paramIndex];

        for (final Annotation annotation : annotations) {
            if (annotation.annotationType().equals(SqlFragment.class)) {
                return Tuple.of(annotation, normalizeSqlFragmentPlaceholder(((SqlFragment) annotation).value(), method, paramIndex, fullClassMethodName,
                        annotation.annotationType()));
            } else if (annotation.annotationType().equals(SqlFragmentList.class)) {
                return Tuple.of(annotation, normalizeSqlFragmentPlaceholder(((SqlFragmentList) annotation).value(), method, paramIndex, fullClassMethodName,
                        annotation.annotationType()));
            } else if (annotation.annotationType().equals(BindList.class)) {
                return Tuple.of(annotation,
                        normalizeSqlFragmentPlaceholder(((BindList) annotation).value(), method, paramIndex, fullClassMethodName, annotation.annotationType()));
            }
        }

        throw new IllegalArgumentException("Parameter[" + paramIndex + "] in method: " + fullClassMethodName
                + " is expected to be annotated with @SqlFragment/@SqlFragmentList/@BindList.");
    }

    /**
     * Derives the placeholder name for a SQL-fragment parameter: the configured annotation value when non-empty,
     * otherwise the Java parameter name (which requires compilation with {@code -parameters}). The result is
     * wrapped in braces ({@code {name}}) unless it already is.
     *
     * @param configuredName the annotation value, or empty to use the Java parameter name
     * @param method the DAO method
     * @param paramIndex the index of the annotated parameter
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @param annotationType the fragment annotation type, used for error messages
     * @return the placeholder name enclosed in braces
     * @throws UnsupportedOperationException if no name can be derived (empty value and parameter names not
     *         available, or a still-empty placeholder)
     */
    private static String normalizeSqlFragmentPlaceholder(final String configuredName, final Method method, final int paramIndex,
            final String fullClassMethodName, final Class<? extends Annotation> annotationType) throws UnsupportedOperationException {
        String placeholderName = configuredName;

        if (Strings.isEmpty(placeholderName)) {
            final java.lang.reflect.Parameter parameter = method.getParameters()[paramIndex];

            if (!parameter.isNamePresent()) {
                throw new UnsupportedOperationException("Empty value in @" + annotationType.getSimpleName() + " on parameter[" + paramIndex + "] in method: "
                        + fullClassMethodName + ". Specify annotation value explicitly or compile with '-parameters'.");
            }

            placeholderName = parameter.getName();
        }

        if (Strings.isEmpty(placeholderName)) {
            throw new UnsupportedOperationException(
                    "Empty placeholder name in @" + annotationType.getSimpleName() + " on parameter[" + paramIndex + "] in method: " + fullClassMethodName);
        }

        return placeholderName.charAt(0) == '{' && placeholderName.charAt(placeholderName.length() - 1) == '}' ? placeholderName : "{" + placeholderName + "}";
    }

    /**
     * Resolves the entity (and, for CRUD DAOs, ID) arguments through intermediate generic DAO interfaces.
     * A specialized interface may expose only its self type, for example
     * {@code UserDaoBase<TD> extends CrudDao<User, Long, TD>}; treating the first argument of that
     * intermediate interface as the entity would incorrectly resolve {@code TD} as the entity class.
     *
     * @param daoInterface the DAO interface whose entity (and ID) type arguments are resolved
     * @param crudDao {@code true} if resolving a CRUD DAO (entity and ID type arguments expected)
     * @return the resolved type arguments of the terminal DAO interface, or {@code null} if it is not reachable
     *         from {@code daoInterface}
     */
    private static Type[] resolveDaoTypeArguments(final Class<?> daoInterface, final boolean crudDao) {
        return resolveDaoTypeArguments(daoInterface, new HashMap<>(), crudDao);
    }

    /**
     * Recursive worker for {@link #resolveDaoTypeArguments(Class, boolean)}: walks the interface hierarchy of
     * {@code type}, accumulating type-variable bindings from parameterized supertypes, until the terminal DAO
     * interface ({@link DaoBase} itself, or a CRUD ops interface carrying the entity/ID types) is found.
     *
     * @param type the interface type to resolve, possibly parameterized
     * @param inheritedBindings type-variable bindings accumulated from already-visited supertypes
     * @param crudDao {@code true} if resolving a CRUD DAO (entity and ID type arguments expected)
     * @return the resolved type arguments of the terminal DAO interface, or {@code null} if it is not reachable
     *         from {@code type}
     */
    private static Type[] resolveDaoTypeArguments(final Type type, final Map<TypeVariable<?>, Type> inheritedBindings, final boolean crudDao) {
        final Class<?> rawType;
        final Map<TypeVariable<?>, Type> bindings = new HashMap<>(inheritedBindings);

        if (type instanceof final ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof final Class<?> cls) {
            rawType = cls;
            final TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
            final Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();

            for (int i = 0; i < Math.min(typeParameters.length, actualTypeArguments.length); i++) {
                bindings.put(typeParameters[i], resolveBoundType(actualTypeArguments[i], inheritedBindings));
            }
        } else if (type instanceof final Class<?> cls) {
            rawType = cls;
        } else {
            return null;
        }

        final TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
        final boolean isCrudTypeCarrier = crudDao && rawType.getPackageName().equals(DaoBase.class.getPackageName()) && DaoUtil.isCrudReadOps(rawType)
                && typeParameters.length >= 3 && "ID".equals(typeParameters[1].getName());

        if ((!crudDao && rawType == DaoBase.class) || isCrudTypeCarrier) {
            final Type[] resolvedTypes = new Type[typeParameters.length];

            for (int i = 0; i < typeParameters.length; i++) {
                resolvedTypes[i] = resolveBoundType(typeParameters[i], bindings);
            }

            return resolvedTypes;
        }

        for (final Type genericInterface : rawType.getGenericInterfaces()) {
            final Type[] resolvedTypes = resolveDaoTypeArguments(genericInterface, bindings, crudDao);

            if (resolvedTypes != null) {
                return resolvedTypes;
            }
        }

        return null;
    }

    /**
     * Follows a chain of type-variable bindings to the concrete bound type, guarding against cyclic bindings.
     * A type that is not a type variable, or whose variable is unbound, is returned unchanged.
     *
     * @param type the type to resolve
     * @param bindings the accumulated type-variable bindings
     * @return the resolved type
     */
    private static Type resolveBoundType(final Type type, final Map<TypeVariable<?>, Type> bindings) {
        Type resolvedType = type;
        final Set<Type> visitedTypes = new HashSet<>();

        while (resolvedType instanceof final TypeVariable<?> typeVariable && visitedTypes.add(resolvedType)) {
            final Type boundType = bindings.get(typeVariable);

            if (boundType == null || boundType == resolvedType) {
                break;
            }

            resolvedType = boundType;
        }

        return resolvedType;
    }

    /**
     * Cache of DAOs resolved for join operations, keyed by {@link JoinEntityDaoCacheKey} (referenced entity class
     * plus data source identity and whether deletion support is required).
     */
    @SuppressWarnings("rawtypes")
    private static final Map<JoinEntityDaoCacheKey, DaoBase> joinEntityDaoPool = new ConcurrentHashMap<>();

    private static final int MAX_JOIN_DAO_MISSES = 256;

    // Guarded by this map's monitor. A registration invalidates misses, never a caller-specific fallback.
    private static final Map<JoinEntityDaoCacheKey, Boolean> joinEntityDaoMisses = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<JoinEntityDaoCacheKey, Boolean> eldest) {
            return size() > MAX_JOIN_DAO_MISSES;
        }
    };

    private static long daoRegistrationVersion;

    /**
     * Creates a dynamic proxy implementation of the specified DAO interface backed by the given {@link javax.sql.DataSource}.
     *
     * <p>This is the core factory method for DAO proxy creation. It processes the supported annotations
     * (e.g., {@code @Query}, {@code @Transactional}, {@code @CacheResult}, {@code @RefreshCache}, {@code @Handler},
     * {@code @PerfLog}, {@code @SqlLogEnabled}) declared on the DAO interface and its methods, and builds an
     * {@link InvocationHandler} that intercepts each method call to execute the corresponding SQL operation.</p>
     *
     * <p>Created DAO instances are cached by a collision-safe composite key containing the interface class,
     * target table name, and the reference identity of the data source, DSL dialect, SQL mapper, DAO cache,
     * and executor. Subsequent calls with the same object references return the cached instance while it stays in
     * the bounded pool ({@link #daoPool}); an evicted proxy keeps working, and a later call creates a new one.</p>
     *
     * <p>In addition to method-level annotations, the method also processes type-level configuration:</p>
     * <ul>
     *   <li>{@code @SqlSource} annotations to load external SQL mapper files, retaining each statement's
     *       timeout, fetch-size, and batch-size attributes</li>
     *   <li>{@code @SqlScript} annotated fields for embedded SQL definitions</li>
     *   <li>{@code @DaoConfig} for configuration options like {@code addLimitForSingleQuery}</li>
     *   <li>{@code @Handler} and {@code @Handlers} for custom method handlers</li>
     *   <li>{@code @PerfLog} for performance logging of DAO method invocations</li>
     *   <li>{@code @SqlLogEnabled} for SQL statement logging control</li>
     * </ul>
     * <p>If a DAO invocation or a handler fails, the first failure remains primary and distinct
     * handler cleanup failures are suppressed. A handler that rethrows the primary failure does
     * not add it to the cleanup failures, avoiding circular suppression chains.</p>
     *
     * @param <TD> the DAO interface type, must extend {@link DaoBase}
     * @param daoInterface the DAO interface class to create a proxy for; must be an interface
     * @param targetTableName the database table name to use, or {@code null}/{@code ""} to derive from the entity class
     * @param ds the {@link javax.sql.DataSource} providing database connections
     * @param dsl the {@link Dsl} dialect used to generate CRUD SQL; must not be {@code null}
     * @param sqlMapper an optional {@link SqlMapper} containing pre-defined SQL statements keyed by ID; may be {@code null}.
     *        The DAO works on a copy merged with the {@code @SqlSource} and {@code @SqlScript} definitions, so later
     *        changes to {@code sqlMapper} are not seen by the DAO
     * @param inputDaoCache an optional {@link Jdbc.DaoCache} for caching query results of methods annotated with
     *        {@code @CacheResult} (and invalidated by {@code @RefreshCache}). When {@code null}, the cache is
     *        derived from the class-level {@code @Cache} annotation (using its {@code impl()}, {@code capacity()}
     *        and {@code evictDelayMillis()}) if present, otherwise a {@link Jdbc.DefaultDaoCache} with
     *        {@link JdbcUtil#DEFAULT_CACHE_CAPACITY default capacity} and
     *        {@link JdbcUtil#DEFAULT_CACHE_EVICT_DELAY default evict delay} is used. Such a cache is created only when
     *        the DAO declares {@code @Cache} or an <em>enabled</em> {@code @CacheResult} (on the type, a super-interface
     *        or a method), since only methods with an enabled {@code @CacheResult} store results, and only after every
     *        other validation has passed, so a rejected DAO creates none. Note: result caching is
     *        currently only supported for cacheable DAOs — {@code NonUpdateDao}/{@code ReadOnlyDao} and their
     *        {@code Unchecked} variants — supplying a non-{@code null} {@code inputDaoCache} (or declaring
     *        {@code @Cache}) on a DAO that supports update/delete operations will fail with
     *        {@link UnsupportedOperationException}
     * @param executor an optional {@link Executor} for asynchronous operations; if {@code null}, the default async executor is used
     * @return a proxy instance implementing the specified DAO interface
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null} or is not an interface, if {@code ds}
     *         is {@code null}, if {@code dsl} is {@code null}, if {@code dsl}'s SQL policy is neither {@code null}
     *         nor {@link SqlPolicy#PARAMETERIZED_SQL}, if no {@code inputDaoCache} is supplied and the {@code @Cache}
     *         implementation does not declare an {@code (int, long)} constructor, if the same SQL id is defined more
     *         than once across {@code sqlMapper}, {@code @SqlSource} files and {@code @SqlScript} fields, if an SQL id
     *         is not a valid Java identifier, if an XML SQL mapper file referenced by {@code @SqlSource} cannot be found
     *         or holds an invalid SQL definition, if the DAO interface has invalid annotation configurations or
     *         generic type arguments (e.g. a {@code Map} 'ID' type, or a composite 'ID' bean/record class lacking one of the
     *         entity's id properties), or if it extends a read-only (or non-update) DAO interface together with a
     *         write-capable one ({@code Dao}/{@code NonUpdateDao} or a CRUD/{@code Unchecked} variant) whose operations it forbids
     * @throws UncheckedSQLException if obtaining database product info from {@code ds} fails
     * @throws UncheckedIOException if an XML SQL mapper file referenced by {@code @SqlSource} cannot be read
     * @throws ParsingException if an XML SQL mapper file referenced by {@code @SqlSource} is not well-formed XML or
     *         does not have {@code <sqlMapper>} as its root element
     * @throws UnsupportedOperationException if a DAO method uses an unsupported annotation configuration, an
     *         incompatible return type for the declared {@link QueryOperation}, or a feature not yet enabled
     *         (e.g., cache on a non-cacheable interface that supports update/delete operations, or a
     *         {@code RowMapper}/{@code ResultExtractor} parameter on a custom {@code @Query} method), if {@code @Cache}
     *         declares a negative {@code capacity} or {@code evictDelayMillis}, or if an enabled {@code @RefreshCache} has
     *         no enabled {@code @CacheResult} to refresh
     * @throws IllegalStateException if generated join SQL for a {@code @JoinedBy} property lacks a clause required to
     *         build the join query plans
     */
    @SuppressWarnings({ "rawtypes", "null", "resource" })
    static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds, final Dsl dsl,
            final SqlMapper sqlMapper, final Jdbc.DaoCache inputDaoCache, final Executor executor) throws IllegalArgumentException, UncheckedSQLException,
            UncheckedIOException, ParsingException, UnsupportedOperationException, IllegalStateException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgument(daoInterface.isInterface(), "'daoInterface' must be an interface. It can't be {}", daoInterface);
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(dsl, cs.dsl);
        N.checkArgument(dsl.sqlDialect().sqlPolicy() == null || dsl.sqlDialect().sqlPolicy() == SqlPolicy.PARAMETERIZED_SQL,
                "'dsl.sqlDialect.sqlPolicy' must be null or SqlPolicy.PARAMETERIZED_SQL. It can't be {}", dsl.sqlDialect().sqlPolicy());

        final Logger daoLogger = LoggerFactory.getLogger(daoInterface);
        final String daoClassName = ClassUtil.getCanonicalClassName(daoInterface);
        final DaoCacheKey daoCacheKey = new DaoCacheKey(daoInterface, targetTableName, ds, dsl, sqlMapper, inputDaoCache, executor);

        final PooledDao pooledDaoInstance = daoPool.get(daoCacheKey);

        if (pooledDaoInstance != null) {
            markRecentlyUsed(pooledDaoInstance);

            if (daoLogger.isDebugEnabled()) {
                daoLogger.debug("Reusing cached Dao proxy(interface={}, targetTableName={}, dataSourceId={})", daoClassName, targetTableName,
                        System.identityHashCode(ds));
            }

            return (TD) pooledDaoInstance.dao;
        }

        // Identity of this proxy in DAO result-cache keys (see JdbcUtil.createCacheKey). Unique per proxy, so it also tells
        // apart proxies of the same interface created for different DataSources or SQL mappers that share one cache.
        final String daoId = daoInterface.getSimpleName() + "@" + daoIdSequence.incrementAndGet();

        if (daoLogger.isDebugEnabled()) {
            daoLogger.debug("Creating Dao proxy(interface={}, targetTableName={}, dataSourceId={}, dsl={}, sqlMapperId={}, daoCacheId={}, executorId={})",
                    daoClassName, targetTableName, System.identityHashCode(ds), System.identityHashCode(dsl),
                    sqlMapper == null ? null : System.identityHashCode(sqlMapper), inputDaoCache == null ? null : System.identityHashCode(inputDaoCache),
                    executor == null ? null : System.identityHashCode(executor));
        }

        @SuppressWarnings("UnnecessaryLocalVariable")
        final javax.sql.DataSource primaryDataSource = ds;
        final SqlDialect.ProductInfo dbProductInfo = JdbcUtil.getDBProductInfo(ds);

        if (daoLogger.isDebugEnabled()) {
            daoLogger.debug("Resolved database product for Dao proxy(interface={}): name={}, version={}", daoClassName, dbProductInfo.name(),
                    dbProductInfo.version());
        }

        final AsyncExecutor asyncExecutor = executor == null ? JdbcUtil.asyncExecutor : new AsyncExecutor(executor);
        final boolean isUncheckedDao = DaoUtil.isUncheckedReadOps(daoInterface);
        final boolean isCrudDao = DaoUtil.isCrudReadOps(daoInterface);
        // Restriction level for centralizing the prepareQuery/prepareNamedQuery SQL-kind gate (formerly per-method overrides in ReadOnlyDao/NonUpdateDao).
        final boolean isReadOnlyDao = ReadOnlyDao.class.isAssignableFrom(daoInterface);
        final boolean isNonUpdateDao = !isReadOnlyDao && NonUpdateDao.class.isAssignableFrom(daoInterface);

        // A restriction level combined with a capability it forbids would otherwise let the restriction win silently: raw SQL
        // gated to SELECT (or SELECT/INSERT) and the built-in INSERT SQL not generated, while the write methods stay on the type.
        if ((isReadOnlyDao && (Dao.class.isAssignableFrom(daoInterface) || NonUpdateDao.class.isAssignableFrom(daoInterface)))
                || (isNonUpdateDao && Dao.class.isAssignableFrom(daoInterface))) {
            throw new IllegalArgumentException(
                    "Dao interface: " + daoClassName + " extends both " + (isReadOnlyDao ? "ReadOnlyDao (read-only)" : "NonUpdateDao (no update/delete)")
                            + " and " + (Dao.class.isAssignableFrom(daoInterface) ? "Dao" : "NonUpdateDao")
                            + ", whose write operations it forbids. Extend only the interfaces of one restriction level");
        }

        final List<Class<?>> allInterfaces = Stream.of(ClassUtil.getAllInterfaces(daoInterface)).prepend(daoInterface).toList();

        final SqlMapper newSqlMapper = sqlMapper == null ? new SqlMapper() : sqlMapper.copy();

        Stream.of(allInterfaces) //
                .flatMapArray(Class::getAnnotations)
                .select(SqlSource.class)
                .map(SqlSource::value)
                .map(String::trim)
                .filter(Strings::isNotEmpty)
                .map(SqlMapper::loadFrom)
                .forEach(it -> {
                    for (final String key : it.ids()) {
                        if (newSqlMapper.get(key) != null) {
                            throw new IllegalArgumentException("Duplicated sql keys: " + key + " defined in SqlMapper for Dao class: " + daoClassName);
                        }

                        newSqlMapper.add(key, it.get(key), it.attributes(key));
                    }
                });

        DaoConfig daoConfigAnno = null;

        for (final Class<?> itf : allInterfaces) {
            for (final Annotation anno : itf.getAnnotations()) {
                if (anno instanceof DaoConfig dc) {
                    daoConfigAnno = dc;
                    break;
                }
            }
            if (daoConfigAnno != null) {
                break;
            }
        }

        final boolean addLimitForSingleQuery = daoConfigAnno != null && daoConfigAnno.addLimitForSingleQuery();
        final boolean callGenerateIdForInsert = daoConfigAnno != null && daoConfigAnno.callGenerateIdForInsertIfIdNotSet();
        final boolean callGenerateIdForInsertWithSql = daoConfigAnno != null && daoConfigAnno.callGenerateIdForInsertWithSqlIfIdNotSet();
        final boolean fetchColumnByEntityClassForDatasetQuery = daoConfigAnno == null || daoConfigAnno.fetchColumnByEntityClassForDatasetQuery();

        final Map<String, String> sqlScriptMap = Stream.of(allInterfaces)
                .flatMapArray(Class::getDeclaredFields)
                .append(Stream.of(allInterfaces).flatMapArray(Class::getDeclaredClasses).flatMapArray(Class::getDeclaredFields))
                .filter(it -> it.isAnnotationPresent(SqlScript.class))
                .onEach(it -> N.checkArgument(Modifier.isStatic(it.getModifiers()) && Modifier.isFinal(it.getModifiers()) && String.class.equals(it.getType()),
                        "Field annotated with @SqlScript must be static&final String. but {} is not in Dao class {}.", it, daoInterface))
                .onEach(it -> ClassUtil.setAccessibleQuietly(it, true))
                .map(it -> Tuple.of(it.getAnnotation(SqlScript.class), it))
                .map(it -> Tuple.of(Strings.isEmpty(it._1.id()) ? it._2.getName() : it._1.id(), it._2))
                .onEach(it -> N.checkArgument(Strings.isNotEmpty(it._1) && !Strings.containsWhitespace(it._1),
                        "Sql id {} is empty or contains whitespace characters defined by field in Dao class {}.", it._1, daoInterface))
                .distinctBy(it -> it._1, (a, b) -> {
                    throw new IllegalArgumentException(
                            "Two fields annotated with @SqlScript have the same id (or name): " + a + "," + b + " in Dao class: " + daoClassName);
                })
                .toMap(it -> it._1, Fn.ff(it -> (String) (it._2.get(null))));

        if (N.notEmpty(sqlScriptMap)) {
            if (daoLogger.isInfoEnabled()) {
                daoLogger.info("Embedded SQL scripts defined for Dao interface(interface={}, count={})", daoClassName, sqlScriptMap.size());
            }

            if (daoLogger.isDebugEnabled()) {
                sqlScriptMap.forEach((key, value) -> daoLogger.debug("Embedded SQL script(interface={}, id={}, sql={})", daoClassName, key, value));
            }
        }

        if (!N.disjoint(newSqlMapper.ids(), sqlScriptMap.keySet())) {
            throw new IllegalArgumentException("Duplicated sql keys: " + N.commonSet(newSqlMapper.ids(), sqlScriptMap.keySet())
                    + " defined by both SqlMapper and SqlScript for Dao interface: " + daoClassName);
        }

        for (final Map.Entry<String, String> entry : sqlScriptMap.entrySet()) {
            newSqlMapper.add(entry.getKey(), ParsedSql.parse(entry.getValue()));
        }

        if (Stream.of(newSqlMapper.ids()).anyMatch(key -> !RegExUtil.JAVA_IDENTIFIER_MATCHER.matcher(key).matches())) {
            throw new IllegalArgumentException(
                    "Invalid query identifiers for DAO interface: " + daoClassName + ". Query IDs don't match Java identifier specification: "
                            + Stream.of(newSqlMapper.ids()).filter(key -> !RegExUtil.JAVA_IDENTIFIER_MATCHER.matcher(key).matches()).toList());
        }

        final Type[] typeArguments = resolveDaoTypeArguments(daoInterface, isCrudDao);

        if (isCrudDao && N.notEmpty(typeArguments) && typeArguments.length >= 2
                && Map.class.isAssignableFrom(
                        typeArguments[1] instanceof final ParameterizedType pt && pt.getRawType() instanceof final Class<?> rawIdClass ? rawIdClass
                                : (typeArguments[1] instanceof final Class<?> idCls ? idCls : Object.class))) {
            throw new IllegalArgumentException("The 'ID' type of Dao interface: " + daoClassName + " can't be a Map: " + typeArguments[1]
                    + ". Use EntityId or an id bean/record class for a composite id");
        }

        if (isCrudDao && (N.isEmpty(typeArguments) || (typeArguments.length < 2) || !(typeArguments[1] instanceof Class))) {
            throw new IllegalArgumentException("Failed to resolve entity/id generic type parameters for DAO interface: " + daoClassName);
        }

        // An unbound entity type variable (e.g. createDao called on the raw Dao interface, or on a generic
        // interface like BaseDao<T> extends Dao<T, BaseDao<T>> without a concrete binding) would otherwise
        // fail with a ClassCastException at the '(Class) typeArguments[0]' cast below.
        if (N.notEmpty(typeArguments) && !(typeArguments[0] instanceof Class)) {
            throw new IllegalArgumentException("Failed to resolve entity generic type parameter for DAO interface: " + daoClassName);
        }

        if (N.notEmpty(typeArguments)) {
            if ((typeArguments.length >= 1 && typeArguments[0] instanceof Class) && !Beans.isBeanClass((Class) typeArguments[0])) {
                throw new IllegalArgumentException(
                        "Entity Type parameter of Dao interface must be an entity class with getter/setter methods. Can't be: " + typeArguments[0]);
            }

            if (DaoUtil.isJoinEntityReadOps(daoInterface) && (typeArguments.length >= 1 && typeArguments[0] instanceof Class)
                    && ParserUtil.getBeanInfo((Class) typeArguments[0]).propInfoList.stream().noneMatch(it -> it.isSubEntity)) {
                throw new IllegalArgumentException("Dao interface: " + ClassUtil.getCanonicalClassName(daoInterface)
                        + " extends JoinEntityHelper, but the entity class: " + typeArguments[0] + " has no sub-entity properties.");
            }

            if (isCrudDao) {
                final List<String> idFieldNames = QueryUtil.idPropNames((Class) typeArguments[0]);
                final Class<?> declaredIdClass = (Class) typeArguments[1];

                if (idFieldNames.size() == 0) {
                    throw new IllegalArgumentException("To support CRUD operations by extending CrudDao interface, the entity class: " + typeArguments[0]
                            + " must have at least one field annotated with @Id");
                } else if (idFieldNames.size() == 1) {
                    if (!(ClassUtil.wrap(declaredIdClass))
                            .isAssignableFrom(ClassUtil.wrap(Beans.getPropGetter((Class) typeArguments[0], idFieldNames.get(0)).getReturnType()))) {
                        throw new IllegalArgumentException("The 'ID' type declared in Dao: " + ClassUtil.getCanonicalClassName(daoInterface)
                                + " is not assignable from the id property type: "
                                + Beans.getPropGetter((Class) typeArguments[0], idFieldNames.get(0)).getReturnType());
                    }
                } else if (idFieldNames.size() > 1
                        && !(EntityId.class.equals(declaredIdClass) || Beans.isBeanClass(declaredIdClass) || Beans.isRecordClass(declaredIdClass))) {
                    throw new IllegalArgumentException(
                            "To support composite ids, the 'ID' type must be EntityId/Entity/Record. It can't be: " + declaredIdClass);
                } else if (idFieldNames.size() > 1 && !EntityId.class.equals(declaredIdClass)) {
                    // Every id property is read from (and written to) the id object by name, so a missing one failed only at
                    // runtime, with a NullPointerException in getOrNull/exists/deleteById/update(Map, id).
                    final BeanInfo declaredIdInfo = ParserUtil.getBeanInfo(declaredIdClass);

                    for (final String idFieldName : idFieldNames) {
                        if (declaredIdInfo.getPropInfo(idFieldName) == null) {
                            throw new IllegalArgumentException("The composite 'ID' class: " + ClassUtil.getCanonicalClassName(declaredIdClass)
                                    + " declared in Dao: " + daoClassName + " has no property: " + idFieldName + " (an id property of entity class: "
                                    + ClassUtil.getCanonicalClassName((Class) typeArguments[0]) + ")");
                        }
                    }
                }
            }
        }

        final Map<Method, Throwables.BiFunction<DaoBase, Object[], ?, Throwable>> methodInvokerMap = new ConcurrentHashMap<>();

        final List<Method> sqlMethods = Stream.of(allInterfaces)
                .reversed()
                .distinct()
                .flatMapArray(Class::getDeclaredMethods)
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .toList();

        final SqlDialect sqlDialect = dsl.sqlDialect();
        // SQL Server renders LIMIT as OFFSET/FETCH, whose grammar requires ORDER BY: best-effort
        // framework limits (addLimitForSingleQuery) must be skipped when the condition has no ORDER BY.
        final boolean limitRequiresOrderBy = sqlDialect.productInfo() != null && sqlDialect.productInfo().isSQLServer();
        final NamingPolicy namingPolicy = sqlDialect.namingPolicy() == null ? NamingPolicy.SNAKE_CASE : sqlDialect.namingPolicy();
        final Dsl parameterizedDsl = parameterizedDsl(dsl);
        final Dsl namedDsl = namedDsl(dsl);

        final Class<Object> entityClass = N.isEmpty(typeArguments) ? null : (Class) typeArguments[0];
        final BeanInfo entityInfo = entityClass == null ? null : ParserUtil.getBeanInfo(entityClass);
        final String tableName = entityInfo == null ? null : getTableName(entityClass, entityInfo, namingPolicy, targetTableName);
        final boolean hasJoinedByProperties = entityInfo != null
                && entityInfo.propInfoList.stream().anyMatch(propInfo -> propInfo.isAnnotationPresent(JoinedBy.class));
        final Collection<String> defaultSelectPropNames = hasJoinedByProperties ? JdbcUtil.getSelectPropNames(entityClass) : null;
        final Collection<String> defaultInsertPropNames = hasJoinedByProperties ? JdbcUtil.getInsertPropNames(entityClass) : null;

        final Class<?> idClass = isCrudDao ? (Class) typeArguments[1] : null;
        final boolean isEntityId = idClass != null && EntityId.class.isAssignableFrom(idClass);
        final BeanInfo idBeanInfo = Beans.isBeanClass(idClass) || Beans.isRecordClass(idClass) ? ParserUtil.getBeanInfo(idClass) : null;

        final Function<Condition, SqlBuilder.SP> selectFromSqlBuilderFunc = cond -> hasJoinedByProperties
                ? parameterizedDsl.select(defaultSelectPropNames).from(tableName, entityClass).append(cond).build()
                : parameterizedDsl.select(entityClass).from(tableName).append(cond).build();

        // count(Condition) counts the records the condition matches. Its LIMIT/OFFSET and ORDER BY would otherwise apply to
        // the single aggregate row: an OFFSET skips that row (so the count became 0), and ORDER BY next to an aggregate is
        // rejected by strict databases. Both are dropped unless GROUP BY is present (one row per group, so they are meaningful).
        // The same applies to such clauses trailing a literal expression used as (or as the WHERE of) the condition.
        final Function<Condition, Condition> countCondFunc = cond -> {
            if (cond instanceof Limit || cond instanceof OrderBy) {
                return Criteria.builder().build();
            } else if (cond instanceof final SqlExpression expr) {
                final SqlExpression stripped = stripTrailingRowClauses(expr);

                return stripped == expr ? cond : (stripped == null ? Criteria.builder().build() : stripped);
            } else if (cond instanceof final Where where && where.condition() instanceof final SqlExpression expr) {
                final SqlExpression stripped = stripTrailingRowClauses(expr);

                return stripped == expr ? cond : (stripped == null ? Criteria.builder().build() : Filters.where(stripped));
            } else if (cond instanceof final Criteria criteria && criteria.groupBy() == null) {
                final SqlExpression whereExpr = criteria.where() != null && criteria.where().condition() instanceof final SqlExpression expr ? expr : null;
                final SqlExpression strippedWhereExpr = whereExpr == null ? null : stripTrailingRowClauses(whereExpr);

                if (criteria.limit() == null && criteria.orderBy() == null && strippedWhereExpr == whereExpr) {
                    return cond;
                }

                final Criteria.Builder builder = Criteria.builder().selectModifier(criteria.selectModifier());

                for (final Condition clause : criteria.conditions()) {
                    if (clause instanceof Limit || clause instanceof OrderBy) {
                        continue;
                    } else if (whereExpr != null && clause instanceof Where) {
                        if (strippedWhereExpr != null) {
                            builder.add(strippedWhereExpr == whereExpr ? clause : Filters.where(strippedWhereExpr));
                        }
                    } else {
                        builder.add(clause);
                    }
                }

                return builder.build();
            }

            return cond;
        };

        final BiFunction<String, Condition, SqlBuilder.SP> singleQuerySqlBuilderFunc = (selectPropName,
                cond) -> parameterizedDsl.select(selectPropName).from(tableName, entityClass).append(cond).build();

        final BiFunction<String, Condition, SqlBuilder.SP> singleQueryByIdSqlBuilderFunc = (selectPropName,
                cond) -> namedDsl.select(selectPropName).from(tableName, entityClass).append(cond).build();

        final BiFunction<Collection<String>, Condition, SqlBuilder> selectSqlBuilderFunc = (selectPropNames, cond) -> N.isEmpty(selectPropNames)
                ? (hasJoinedByProperties ? parameterizedDsl.select(defaultSelectPropNames).from(tableName, entityClass).append(cond)
                        : parameterizedDsl.select(entityClass).from(tableName).append(cond))
                : parameterizedDsl.select(selectPropNames).from(tableName, entityClass).append(cond);

        final BiFunction<Collection<String>, Condition, SqlBuilder> namedSelectSqlBuilderFunc = (selectPropNames, cond) -> N.isEmpty(selectPropNames)
                ? (hasJoinedByProperties ? namedDsl.select(defaultSelectPropNames).from(tableName, entityClass).append(cond)
                        : namedDsl.select(entityClass).from(tableName).append(cond))
                : namedDsl.select(selectPropNames).from(tableName, entityClass).append(cond);

        final Function<Collection<String>, SqlBuilder> namedInsertSqlBuilderFunc = propNamesToInsert -> N.isEmpty(propNamesToInsert)
                ? (hasJoinedByProperties ? namedDsl.insert(defaultInsertPropNames).into(tableName, entityClass) : namedDsl.insert(entityClass).into(tableName))
                : namedDsl.insert(propNamesToInsert).into(tableName, entityClass);

        final BiFunction<String, Class<?>, SqlBuilder> parameterizedUpdateFunc = parameterizedDsl::update;

        final BiFunction<String, Class<?>, SqlBuilder> parameterizedDeleteFromFunc = parameterizedDsl::deleteFrom;

        final BiFunction<String, Class<?>, SqlBuilder> namedUpdateFunc = namedDsl::update;

        final List<String> idPropNameList = entityClass == null ? N.emptyList() : QueryUtil.idPropNames(entityClass);
        final boolean isNoId = entityClass == null || N.isEmpty(idPropNameList);
        final Set<String> idPropNameSet = N.newHashSet(idPropNameList);
        final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
        final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
        final boolean isOneId = !isNoId && idPropNameList.size() == 1;
        final Condition idCond = isNoId ? null : isOneId ? Filters.eq(oneIdPropName) : Filters.and(Stream.of(idPropNameList).map(Filters::eq).toList());
        // A bean/record id is matched on the id properties only, as idParamSetter (getOrNull/exists/deleteById) and batchGet do:
        // Filters.allEqual(id) used every property of the id object, so e.g. with the entity class as the ID type its non-id
        // properties joined the WHERE clause of update(Map, id) and the row was missed. Like idParamSetter, the id values are
        // bound with the id properties' types (the entity's for a single id or an EntityId, the ID class's own for a bean/record
        // id, exactly as idParamSetter does), so an id with a custom @Type (e.g. an enum stored as its ordinal) matches.
        final Function<Object, Condition> id2CondFunc = isNoId || idClass == null ? null
                : (isOneId ? id -> Filters.eq(oneIdPropName, TypedParameter.of(id, idPropInfo))
                        : (isEntityId ? id -> Filters.and(Stream.of(idPropNameList)
                                .map(idName -> Filters.eq(idName, TypedParameter.of(((EntityId) id).get(idName), entityInfo.getPropInfo(idName))))
                                .toList()) : id -> Filters.and(Stream.of(idPropNameList).map(idName -> {
                                    final PropInfo propInfo = idBeanInfo.getPropInfo(idName);

                                    return Filters.eq(idName, TypedParameter.of(propInfo.getPropValue(id), propInfo));
                                }).toList())));

        // update(Map, id/cond) binds each value whose key names an entity property with that property's type, as update(entity)
        // does, so e.g. an enum property stored as its ordinal (custom @Type) is written as such. Other keys (e.g. raw column
        // names) and Condition values (e.g. SqlExpression, rendered inline) are left to the query builder as they are.
        final Function<Map<String, Object>, Map<String, Object>> typedUpdatePropsFunc = updateProps -> {
            final Map<String, Object> typedUpdateProps = N.newLinkedHashMap(updateProps.size());
            PropInfo propInfo = null;

            for (final Map.Entry<String, Object> entry : updateProps.entrySet()) {
                final Object value = entry.getValue();

                propInfo = entityInfo == null || entry.getKey() == null || value instanceof Condition ? null : entityInfo.getPropInfo(entry.getKey());
                typedUpdateProps.put(entry.getKey(), propInfo == null ? value : TypedParameter.of(value, propInfo));
            }

            return typedUpdateProps;
        };

        N.checkArgument(idPropNameList.size() > 1 || !(isEntityId || (idClass != null && (Beans.isBeanClass(idClass) || Map.class.isAssignableFrom(idClass)))),
                "Id type/class cannot be EntityId/Map or Entity for single id");

        String sql_getById = null;
        String sql_existsById = null;
        String sql_insertWithId = null;
        String sql_insertWithoutId = null;
        String sql_updateById = null;
        String sql_deleteById = null;

        final boolean noOtherInsertPropNameExceptIdPropNames = entityClass != null
                && (idPropNameSet.containsAll(Beans.getPropNameList(entityClass)) || N.isEmpty(JdbcUtil.getInsertPropNames(entityClass, idPropNameSet)));
        final Collection<String> propNamesToUpdateById = entityClass == null ? N.emptyList() : JdbcUtil.getUpdatePropNames(entityClass, idPropNameSet);
        final boolean noPropNameToUpdateById = N.isEmpty(propNamesToUpdateById);

        sql_getById = isNoId ? null
                : hasJoinedByProperties ? namedDsl.select(defaultSelectPropNames).from(tableName, entityClass).where(idCond).build().query()
                        : namedDsl.select(entityClass).from(tableName).where(idCond).build().query();
        sql_existsById = isNoId ? null : namedDsl.select(_1).from(tableName, entityClass).where(idCond).build().query();
        // A read-only DAO never inserts, and its entity may have no insertable property at all (e.g. a database view whose
        // properties are all @ReadOnly). Building the INSERT eagerly would then make createDao fail for a DAO that never uses it.
        sql_insertWithId = entityClass == null || isReadOnlyDao ? null
                : hasJoinedByProperties ? namedDsl.insert(defaultInsertPropNames).into(tableName, entityClass).build().query()
                        : namedDsl.insert(entityClass).into(tableName).build().query();
        sql_insertWithoutId = entityClass == null || isReadOnlyDao ? null
                : noOtherInsertPropNameExceptIdPropNames ? sql_insertWithId
                        : hasJoinedByProperties
                                ? namedDsl.insert(JdbcUtil.getInsertPropNames(entityClass, idPropNameSet)).into(tableName, entityClass).build().query()
                                : namedDsl.insert(entityClass, idPropNameSet).into(tableName).build().query();
        sql_updateById = isNoId || noPropNameToUpdateById ? null
                : namedDsl.update(tableName, entityClass).set(propNamesToUpdateById).where(idCond).build().query();
        sql_deleteById = isNoId ? null : namedDsl.deleteFrom(tableName, entityClass).where(idCond).build().query();

        final ParsedSql namedGetByIdSQL = Strings.isEmpty(sql_getById) ? null : ParsedSql.parse(sql_getById);
        final ParsedSql namedExistsByIdSQL = Strings.isEmpty(sql_existsById) ? null : ParsedSql.parse(sql_existsById);
        final ParsedSql namedInsertWithIdSQL = Strings.isEmpty(sql_insertWithId) ? null : ParsedSql.parse(sql_insertWithId);
        final ParsedSql namedInsertWithoutIdSQL = Strings.isEmpty(sql_insertWithoutId) ? null : ParsedSql.parse(sql_insertWithoutId);
        final ParsedSql namedUpdateByIdSQL = Strings.isEmpty(sql_updateById) ? null : ParsedSql.parse(sql_updateById);
        final ParsedSql namedDeleteByIdSQL = Strings.isEmpty(sql_deleteById) ? null : ParsedSql.parse(sql_deleteById);

        final ImmutableMap<String, String> propColumnNameMap = QueryUtil.propToColumnNameMap(entityClass, namingPolicy);

        final String[] generatedKeyColumnNames = isNoId ? N.EMPTY_STRING_ARRAY
                : (isOneId ? Array.of(propColumnNameMap.get(oneIdPropName))
                        : Stream.of(idPropNameList).map(propColumnNameMap::get).toArray(IntFunctions.ofStringArray()));

        // A non-CRUD DAO declares no ID type, so a composite ID is represented as an EntityId: building it as an
        // instance of the (missing) ID class would fail in save/batchSave with a NullPointerException.
        final boolean isCompositeIdAsEntityId = isEntityId || (idClass == null && !isNoId && !isOneId);

        final Tuple3<Jdbc.BiRowMapper<Object>, Function<Object, Object>, BiConsumer<Object, Object>> tp3 = JdbcUtil.getIdGeneratorGetterSetter(daoInterface,
                entityClass, namingPolicy, isCompositeIdAsEntityId && idClass == null ? EntityId.class : idClass);

        final Holder<Jdbc.BiRowMapper<Object>> idExtractorHolder = new Holder<>();
        final Jdbc.BiRowMapper<Object> idExtractor = tp3._1;
        final Function<Object, Object> idGetter = tp3._2;
        final BiConsumer<Object, Object> idSetter = tp3._3;

        final Predicate<Object> isDefaultIdTester = isNoId ? id -> true
                : (isOneId ? JdbcUtil::isDefaultIdPropValue
                        : (isCompositeIdAsEntityId ? id -> Stream.of(((EntityId) id).entrySet()).allMatch(it -> JdbcUtil.isDefaultIdPropValue(it.getValue()))
                                : id -> {
                                    if (id instanceof final EntityId entityId) {
                                        // A single generated key of the composite id, extracted as an EntityId (see JdbcUtil.getIdGeneratorGetterSetter).
                                        return Stream.of(entityId.entrySet()).allMatch(it -> JdbcUtil.isDefaultIdPropValue(it.getValue()));
                                    }

                                    if (idBeanInfo == null) {
                                        throw new IllegalStateException("ID class " + idClass + " is not a bean class and cannot be used for composite ID");
                                    }
                                    return Stream.of(idPropNameList).allMatch(idName -> JdbcUtil.isDefaultIdPropValue(idBeanInfo.getPropValue(id, idName)));
                                }));

        final Jdbc.BiParametersSetter<NamedQuery, Object> idParamSetter = isOneId ? (pq, id) -> pq.setObject(oneIdPropName, id, idPropInfo.dbType)
                : (isEntityId ? (pq, id) -> {
                    N.checkArgNotNull(id, cs.id);

                    final EntityId entityId = (EntityId) id;
                    PropInfo propInfo = null;

                    for (final String idName : idPropNameList) {
                        propInfo = entityInfo.getPropInfo(idName);
                        pq.setObject(idName, entityId.get(idName), propInfo.dbType);
                    }
                } : (pq, id) -> {
                    if (idBeanInfo == null) {
                        throw new IllegalStateException("ID class " + idClass + " is not a bean class and cannot be used for composite ID");
                    }

                    N.checkArgNotNull(id, cs.id);

                    PropInfo propInfo = null;

                    for (final String idName : idPropNameList) {
                        propInfo = idBeanInfo.getPropInfo(idName);
                        pq.setObject(idName, propInfo.getPropValue(id), propInfo.dbType);
                    }
                });

        final Jdbc.BiParametersSetter<NamedQuery, Object> idParamSetterByEntity = isOneId ? (pq, entity) -> {
            N.checkArgNotNull(entity, cs.entity);
            pq.setObject(oneIdPropName, idPropInfo.getPropValue(entity), idPropInfo.dbType);
        } : (pq, entity) -> pq.settParameters(entity, objParamsSetter);

        // Every type-level @CacheResult/@RefreshCache, in interface order (the DAO interface first): like @PerfLog and
        // @SqlLogEnabled, a method uses the first one whose filter matches its name (resolved per method below).
        final List<CacheResult> daoClassCacheResultAnnos = new ArrayList<>(1);
        final List<RefreshCache> daoClassRefreshCacheAnnos = new ArrayList<>(1);
        com.landawn.abacus.jdbc.annotation.Cache tmpDaoClassCacheAnno = null;

        for (final Class<?> itf : allInterfaces) {
            for (final Annotation anno : itf.getAnnotations()) {
                if (anno instanceof CacheResult cr) {
                    daoClassCacheResultAnnos.add(cr);
                } else if (anno instanceof RefreshCache rc) {
                    daoClassRefreshCacheAnnos.add(rc);
                } else if (tmpDaoClassCacheAnno == null && anno instanceof com.landawn.abacus.jdbc.annotation.Cache ca) {
                    tmpDaoClassCacheAnno = ca;
                }
            }
        }

        final AtomicBoolean hasCacheResult = new AtomicBoolean(false);
        final AtomicBoolean hasRefreshCache = new AtomicBoolean(false);

        final List<Handler> daoClassHandlerList = Stream.of(allInterfaces)
                .reversed()
                .flatMapArray(Class::getAnnotations)
                .filter(anno -> anno.annotationType().equals(Handler.class) || anno.annotationType().equals(Handlers.class))
                .flatmap(anno -> anno.annotationType().equals(Handler.class) ? N.asList((Handler) anno) : N.toList(((Handlers) anno).value()))
                .toList();

        final Map<String, Jdbc.Handler<?>> daoClassHandlerMap = Stream.of(allInterfaces)
                .flatMapArray(Class::getDeclaredFields)
                .append(Stream.of(allInterfaces).flatMapArray(Class::getDeclaredClasses).flatMapArray(Class::getDeclaredFields))
                .filter(it -> Jdbc.Handler.class.isAssignableFrom(it.getType()))
                .onEach(it -> N.checkArgument(Modifier.isStatic(it.getModifiers()) && Modifier.isFinal(it.getModifiers()),
                        "Handler Fields defined in Dao declared classes must be static&final Handler. but {} is not in Dao class {}.", it, daoInterface))
                .onEach(it -> ClassUtil.setAccessibleQuietly(it, true))
                .distinctBy(Field::getName, (a, b) -> {
                    throw new IllegalArgumentException("Two Handler fields have the same id (or name): " + a + "," + b + " in Dao class: " + daoClassName);
                })
                .toMap(Field::getName, Fn.ff(it -> (Jdbc.Handler<?>) it.get(null)));

        final com.landawn.abacus.jdbc.annotation.Cache daoClassCacheAnno = tmpDaoClassCacheAnno;

        // TODO maybe it's not a good idea to support Cache in general Dao which supports update/delete operations.
        if (!DaoUtil.isCacheable(daoInterface)
                && (!daoClassCacheResultAnnos.isEmpty() || !daoClassRefreshCacheAnnos.isEmpty() || inputDaoCache != null || daoClassCacheAnno != null)) {
            throw new UnsupportedOperationException(
                    "Cache is only supported for Cacheable DAOs (NonUpdate/ReadOnly and their Unchecked variants), not supported for Dao interface: "
                            + daoClassName);
        }

        final int capacity = daoClassCacheAnno == null ? JdbcUtil.DEFAULT_CACHE_CAPACITY : daoClassCacheAnno.capacity();
        final long evictDelay = daoClassCacheAnno == null ? JdbcUtil.DEFAULT_CACHE_EVICT_DELAY : daoClassCacheAnno.evictDelayMillis();

        if (daoClassCacheAnno != null && (capacity < 0 || evictDelay < 0)) {
            throw new UnsupportedOperationException("Invalid ('capacity', 'evictDelayMillis'): (" + capacity + ", " + evictDelay
                    + ") (both must be >= 0) in annotation 'Cache' on Dao interface: " + daoClassName);
        }

        // Only methods whose effective @CacheResult is enabled store results (enabled=false opts out), so a cache is
        // created only when the DAO declares @Cache or an enabled @CacheResult. @RefreshCache alone could only invalidate an
        // always-empty cache, and that configuration is rejected below anyway. A DefaultDaoCache is backed by a pool that
        // registers a JVM shutdown hook and a periodic eviction task, so it must not be created for every DAO proxy (e.g.
        // every CrudDao).
        final boolean isDaoCacheUsed = daoClassCacheAnno != null || Stream.of(daoClassCacheResultAnnos).anyMatch(CacheResult::enabled)
                || Stream.of(sqlMethods).anyMatch(m -> {
                    final CacheResult cacheResult = m.getAnnotation(CacheResult.class);

                    return cacheResult != null && cacheResult.enabled();
                });

        final boolean isDaoCacheCreatedHere = inputDaoCache == null && isDaoCacheUsed;

        // Resolve a custom implementation's (int, long) constructor up front so a missing one still fails DAO initialization
        // here; the cache itself is created only after every other validation has passed (see daoCacheRef).
        final Constructor<? extends Jdbc.DaoCache> daoCacheConstructor = isDaoCacheCreatedHere && daoClassCacheAnno != null
                ? ClassUtil.getDeclaredConstructor(daoClassCacheAnno.impl(), int.class, long.class)
                : null;

        if (isDaoCacheCreatedHere && daoClassCacheAnno != null && daoCacheConstructor == null) {
            throw new IllegalArgumentException("Cache implementation: " + ClassUtil.getCanonicalClassName(daoClassCacheAnno.impl())
                    + " must declare a constructor (int capacity, long evictDelay) for annotation 'Cache' on Dao interface: " + daoClassName);
        }

        // Method invokers read the cache from here on use. A cache created by this method is set only once the DAO can no
        // longer be rejected (right before the proxy is published): creating it up front leaked its pool's shutdown hook and
        // eviction task whenever a later validation rejected the DAO, since nothing could ever close that cache.
        final AtomicReference<Jdbc.DaoCache> daoCacheRef = new AtomicReference<>(inputDaoCache);

        final Set<Method> nonDBOperationSet = N.newConcurrentHashSet();

        final Map<String, JoinInfo> joinBeanInfo = DaoUtil.isJoinEntityReadOps(daoInterface) ? JoinInfo.getEntityJoinInfo(daoInterface, entityClass, tableName)
                : null;

        if (DaoUtil.isJoinEntityReadOps(daoInterface) && N.isEmpty(joinBeanInfo)) {
            throw new IllegalArgumentException(
                    "Entity class: " + ClassUtil.getCanonicalClassName(entityClass) + " must have at least one join entity property for its Dao interface: "
                            + ClassUtil.getCanonicalClassName(daoInterface) + " which extends JoinEntityHelper interface");
        }

        if ((DaoUtil.isJoinEntityReadOps(daoInterface) && !DaoBase.class.isAssignableFrom(daoInterface))
                || (DaoUtil.isCrudJoinEntityReadOps(daoInterface) && !DaoUtil.isCrudReadOps(daoInterface))) {
            throw new IllegalArgumentException("Dao interface: " + ClassUtil.getCanonicalClassName(daoInterface)
                    + " extending JoinEntityHelper/CrudJoinEntityHelper must also extend a corresponding read Dao interface"
                    + " (e.g. Dao/CrudDao or ReadOnlyDao/ReadOnlyCrudDao)");
        }

        final Predicate<Object> isNotEmptyResult = ret -> {
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
        };

        Stream.of(sqlMethods).parallel().filter(method -> Modifier.isPublic(method.getModifiers())).forEach(method -> {
            final boolean isNonDBOperation = Stream.of(method.getAnnotations()).anyMatch(anno -> anno.annotationType().equals(NonDBOperation.class));

            // Single, unified method-name filter rule shared by every type-level filter() element
            // (@Handler, @PerfLog, @SqlLogEnabled, @CacheResult, @RefreshCache): an entry matches when the
            // method name starts with it (case-insensitive prefix) or when it matches the full name as a regex.
            final Predicate<String> filterByMethodName = it -> Strings.isNotEmpty(it)
                    && (Strings.startsWithIgnoreCase(method.getName(), it) || Pattern.matches(it, method.getName()));

            final Class<?> declaringClass = method.getDeclaringClass();
            final String methodName = method.getName();
            final String simpleClassMethodName = declaringClass.getSimpleName() + "." + methodName;
            final String fullClassMethodName = ClassUtil.getCanonicalClassName(declaringClass) + "." + methodName;
            final Class<?>[] paramTypes = method.getParameterTypes();
            final Class<?> returnType = method.getReturnType();
            // the collection type returned for an Iterable/ImmutableCollection return type of a custom query method
            final Class<?> queryReturnType = resolveQueryReturnType(returnType);
            final int paramLen = paramTypes.length;

            final boolean fetchColumnByEntityClass = Stream.of(method.getAnnotations())
                    .select(FetchColumnByEntityClass.class)
                    .map(FetchColumnByEntityClass::value)
                    .onEach(it -> N.checkArgument(Dataset.class.isAssignableFrom(returnType),
                            "@FetchColumnByEntityClass is not supported for method: {} because its return type is not Dataset", fullClassMethodName))
                    .first()
                    .orElse(fetchColumnByEntityClassForDatasetQuery);

            final Query queryAnno = Stream.of(method.getAnnotations()).select(Query.class).onlyOne().orElseNull();
            List<String> sqlList = null;

            if (queryAnno != null && ((N.len(queryAnno.value()) > 1 || N.len(queryAnno.id()) > 1)
                    || (!Modifier.isAbstract(method.getModifiers()) && (paramLen > 0 && paramTypes[paramLen - 1].equals(String[].class))))) {
                sqlList = new ArrayList<>(Stream.of(queryAnno.value())
                        .map(Fn.strip())
                        .filter(Fn.notEmpty())
                        .map(it -> newSqlMapper.get(it) == null ? it : newSqlMapper.get(it).parameterizedSql())
                        .toList());

                for (final String id : queryAnno.id()) {
                    if (!RegExUtil.JAVA_IDENTIFIER_MATCHER.matcher(id).matches()) {
                        throw new IllegalArgumentException("Invalid query identifier. Query ID doesn't match Java identifier specification: " + id);
                    }

                    if (newSqlMapper.get(id) == null || Strings.isEmpty(newSqlMapper.get(id).parameterizedSql())) {
                        throw new IllegalArgumentException("No predefined SQL found by id: " + id);
                    }

                    sqlList.add(newSqlMapper.get(id).parameterizedSql());
                }

                sqlList.replaceAll(sql -> sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql);
            }

            final String[] sqls = N.isEmpty(sqlList) ? N.EMPTY_STRING_ARRAY : sqlList.toArray(new String[0]);

            if (N.notEmpty(sqls)) {
                if (Modifier.isAbstract(method.getModifiers())) {
                    throw new UnsupportedOperationException(
                            "Annotation @Query with multiple values or ids is only supported by interface methods with default implementation, not supported by abstract method: "
                                    + fullClassMethodName);
                }

                if (paramLen == 0 || !paramTypes[paramLen - 1].equals(String[].class)) {
                    throw new UnsupportedOperationException(
                            "To support multiple values or ids binding by @Query, the type of last parameter must be: String... sqls. It can't be: "
                                    + (paramLen == 0 ? "<no parameter>" : paramTypes[paramLen - 1]) + " in method: " + fullClassMethodName);
                }
            }

            Throwables.BiFunction<DaoBase, Object[], ?, Throwable> call = null;

            // Centralized SQL-kind gate for read-only / non-update DAOs: the prepareQuery/prepareNamedQuery (and
            // *ForLargeResult) overloads whose first argument is a raw SQL String or ParsedSql must be restricted to
            // SELECT (read-only) or SELECT/INSERT (non-update). This replaces the per-method overrides that used to live
            // in ReadOnlyDao/NonUpdateDao. The Condition/Collection-based prepare builders always produce SELECTs and are
            // intentionally excluded. 1 = read-only gate, 2 = non-update gate, 0 = no gate.
            final int prepareSqlGate = (isReadOnlyDao || isNonUpdateDao) && paramLen >= 1
                    && (methodName.equals("prepareQuery") || methodName.equals("prepareNamedQuery") || methodName.equals("prepareQueryForLargeResult")
                            || methodName.equals("prepareNamedQueryForLargeResult"))
                    && (paramTypes[0].equals(String.class) || paramTypes[0].equals(ParsedSql.class)) ? (isReadOnlyDao ? 1 : 2) : 0;
            final boolean prepareSqlIsParsed = prepareSqlGate != 0 && paramTypes[0].equals(ParsedSql.class);
            // The gated overloads declare their SQL parameter as 'namedSql' on prepareNamedQuery/prepareNamedQueryForLargeResult
            // and as 'sql' on prepareQuery/prepareQueryForLargeResult, so name it the same way JdbcUtil does.
            final String prepareSqlArgName = prepareSqlGate == 0 ? null
                    : (methodName.equals("prepareNamedQuery") || methodName.equals("prepareNamedQueryForLargeResult") ? cs.namedSql : cs.sql);

            if (!Modifier.isAbstract(method.getModifiers())) {
                final MethodHandle methodHandle = createMethodHandle(method).asFixedArity()
                        .asSpreader(Object[].class, paramLen)
                        .asType(MethodType.methodType(Object.class, Object.class, Object[].class));

                call = (proxy, args) -> {
                    if (prepareSqlGate != 0) {
                        final String sqlToCheck;

                        if (prepareSqlIsParsed) {
                            N.checkArgNotNull(args[0], prepareSqlArgName);
                            sqlToCheck = ((ParsedSql) args[0]).originalSql();
                        } else {
                            // Validated before the SQL-kind check so null/empty SQL fails with the same IAE
                            // a full DAO would throw, not a misleading "Only SELECT ..." UOE.
                            sqlToCheck = N.checkArgNotEmpty((String) args[0], prepareSqlArgName);
                        }

                        if (prepareSqlGate == 1) {
                            if (!SqlParser.isReadOnlyQuery(sqlToCheck)) {
                                throw new UnsupportedOperationException("Only SELECT queries are supported in a read-only DAO");
                            }
                        } else if (!SqlParser.isReadOrInsertQuery(sqlToCheck)) {
                            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in a non-update DAO");
                        }
                    }

                    if (N.notEmpty(sqls)) {
                        if (N.notEmpty((String[]) args[paramLen - 1])) {
                            throw new IllegalArgumentException(
                                    "The last parameter(String[]) of method annotated by @Query must be empty, don't specify it. It will be auto-filled by sqls from annotation @Query on the method: "
                                            + fullClassMethodName);
                        }

                        // A default method can mutate its varargs array. Sharing the annotation-derived
                        // array across calls would let one invocation corrupt every later invocation.
                        args[paramLen - 1] = sqls.clone();
                    }

                    final Object[] invocationArgs = args == null ? N.EMPTY_OBJECT_ARRAY : args;
                    return methodHandle.invokeExact((Object) proxy, invocationArgs);
                };
            } else if (methodName.equals("executor") && Executor.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> asyncExecutor.getExecutor();
            } else if (method.getName().equals("targetEntityClass") && Class.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> entityClass;
            } else if (method.getName().equals("targetTableName") && String.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> tableName;
            } else if (method.getName().equals("targetDaoInterface") && Class.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> daoInterface;
            } else if (methodName.equals("dataSource") && javax.sql.DataSource.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> primaryDataSource;
            } else if (methodName.equals("dsl") && Dsl.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> dsl;
            } else if (methodName.equals("sqlMapper") && SqlMapper.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> newSqlMapper;
            } else if (methodName.equals("prepareQuery") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                    && Condition.class.isAssignableFrom(paramTypes[1])) {
                // Binding the condition parameters can fail with a checked SQLException; on an Unchecked DAO this
                // method declares none, so it must be wrapped or it escapes the proxy as UndeclaredThrowableException.
                call = wrapUndeclaredSQLException(method, (proxy, args) -> {
                    final Collection<String> selectPropNames = (Collection<String>) args[0];
                    final Condition cond = N.checkArgNotNull((Condition) args[1], cs.cond);
                    final Condition limitedCond = handleLimit(cond, -1, false);
                    final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                    return proxy.prepareQuery(sp.query()).settParameters(sp.parameters(), collParamsSetter);
                });
            } else if (methodName.equals("prepareNamedQuery") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                    && Condition.class.isAssignableFrom(paramTypes[1])) {
                call = wrapUndeclaredSQLException(method, (proxy, args) -> {
                    final Collection<String> selectPropNames = (Collection<String>) args[0];
                    final Condition cond = N.checkArgNotNull((Condition) args[1], cs.cond);
                    final Condition limitedCond = handleLimit(cond, -1, false);
                    final SP sp = namedSelectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                    return proxy.prepareNamedQuery(sp.query()).settParameters(sp.parameters(), collParamsSetter);
                });
            } else {
                final boolean isStreamReturn = Stream.class.isAssignableFrom(returnType);
                final boolean throwsSQLException = Stream.of(method.getExceptionTypes()).anyMatch(e -> e.isAssignableFrom(SQLException.class));
                // A declared common supertype of both (Exception/Throwable) already counts as 'throws SQLException'; counting it
                // as 'throws UncheckedSQLException' too made 'throws Exception' fail as declaring both.
                final boolean throwsUncheckedSQLException = Stream.of(method.getExceptionTypes())
                        .anyMatch(e -> e.isAssignableFrom(UncheckedSQLException.class) && !e.isAssignableFrom(SQLException.class));
                final Annotation sqlAnno = Stream.of(method.getAnnotations())
                        .filter(anno -> sqlAnnoMap.containsKey(anno.annotationType()))
                        .first()
                        .orElseNull();

                if (DaoUtil.isDaoOperationDeclaringClass(declaringClass)) {
                    if (methodName.equals("save") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, cs.entity);

                            final ParsedSql namedInsertSql = isNoId || isDefaultIdTester.test(idGetter.apply(entity)) ? namedInsertWithoutIdSQL
                                    : namedInsertWithIdSQL;

                            proxy.prepareNamedQuery(namedInsertSql).settParameters(entity, objParamsSetter).update();

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToSave = (Collection<String>) args[1];

                            N.checkArgNotNull(entity, cs.entity);
                            N.checkArgNotEmpty(propNamesToSave, cs.propNamesToSave);

                            final String namedInsertSql = namedInsertSqlBuilderFunc.apply(propNamesToSave).build().query();

                            proxy.prepareNamedQuery(namedInsertSql).settParameters(entity, objParamsSetter).update();

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final String namedInsertSql = (String) args[0];
                            final Object entity = args[1];
                            N.checkArgNotEmpty(namedInsertSql, cs.namedInsertSql);
                            N.checkArgNotNull(entity, cs.entity);

                            proxy.prepareNamedQuery(namedInsertSql).settParameters(entity, objParamsSetter).update();

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return null;
                            }

                            if (!isNoId) {
                                final boolean hasDefaultIdEntity = N.anyMatch(entities, entity -> isDefaultIdTester.test(idGetter.apply(entity)));
                                final boolean hasExplicitIdEntity = N.anyMatch(entities, entity -> !isDefaultIdTester.test(idGetter.apply(entity)));

                                if (hasDefaultIdEntity && hasExplicitIdEntity) {
                                    // A single JDBC batch cannot alternate between the INSERT shape that omits the ID and the one that includes it.
                                    // Execute contiguous same-shape runs so explicit IDs do not force null/default IDs into the ID column, while preserving write order.
                                    final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                    Throwable failure = null;

                                    try {
                                        try (NamedQuery withoutIdQuery = proxy.prepareNamedQuery(namedInsertWithoutIdSQL).closeAfterExecution(false);
                                             NamedQuery withIdQuery = proxy.prepareNamedQuery(namedInsertWithIdSQL).closeAfterExecution(false)) {
                                            final List<Object> run = new ArrayList<>(Math.min(batchSize, entities.size()));
                                            boolean runUsesDefaultIdSql = isDefaultIdTester.test(idGetter.apply(N.firstOrNullIfEmpty(entities)));

                                            for (final Object entity : entities) {
                                                final boolean entityUsesDefaultIdSql = isDefaultIdTester.test(idGetter.apply(entity));

                                                if (entityUsesDefaultIdSql != runUsesDefaultIdSql && !run.isEmpty()) {
                                                    (runUsesDefaultIdSql ? withoutIdQuery : withIdQuery).addBatchParameters(run).batchUpdate();
                                                    run.clear();
                                                }

                                                runUsesDefaultIdSql = entityUsesDefaultIdSql;
                                                run.add(entity);

                                                if (run.size() == batchSize) {
                                                    (runUsesDefaultIdSql ? withoutIdQuery : withIdQuery).addBatchParameters(run).batchUpdate();
                                                    run.clear();
                                                }
                                            }

                                            if (!run.isEmpty()) {
                                                (runUsesDefaultIdSql ? withoutIdQuery : withIdQuery).addBatchParameters(run).batchUpdate();
                                            }
                                        }

                                        tran.commit();
                                    } catch (final Throwable e) { //NOSONAR
                                        failure = e;
                                        throw e;
                                    } finally {
                                        JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                    }

                                    return null;
                                }
                            }

                            final ParsedSql namedInsertSql = isNoId || N.allMatch(entities, entity -> isDefaultIdTester.test(idGetter.apply(entity)))
                                    ? namedInsertWithoutIdSQL
                                    : namedInsertWithIdSQL;

                            if (entities.size() == 1) {
                                proxy.prepareNamedQuery(namedInsertSql).addBatchParameters(entities).batchUpdate();
                            } else {
                                // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSql).closeAfterExecution(false)) {
                                        Stream.of(entities)
                                                .split(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];

                            final Collection<String> propNamesToSave = (Collection<String>) args[1];
                            N.checkArgNotEmpty(propNamesToSave, cs.propNamesToSave);

                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return null;
                            }

                            final String namedInsertSql = namedInsertSqlBuilderFunc.apply(propNamesToSave).build().query();

                            if (entities.size() == 1) {
                                proxy.prepareNamedQuery(namedInsertSql).addBatchParameters(entities).batchUpdate();
                            } else {
                                // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSql).closeAfterExecution(false)) {
                                        Stream.of(entities)
                                                .split(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 3 && String.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final String namedInsertSql = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgNotEmpty(namedInsertSql, cs.namedInsertSql);
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return null;
                            }

                            if (entities.size() == 1) {
                                proxy.prepareNamedQuery(namedInsertSql).addBatchParameters(entities).batchUpdate();
                            } else {
                                // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSql).closeAfterExecution(false)) {
                                        Stream.of(entities)
                                                .split(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("exists") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(_1, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).exists();
                        };
                    } else if (methodName.equals("count") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final SP sp = singleQuerySqlBuilderFunc.apply(SK.COUNT_ALL, countCondFunc.apply(cond));
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForInt().orElseZero();
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(entityClass);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(entityClass);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("queryForBoolean") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForBytes") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForBytes();
                        };
                    } else if (methodName.equals("queryForSingleValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForSingleValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForSingleNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .query((Jdbc.ResultExtractor<Optional<?>>) rs -> rs.next() ? Optional.of(rowMapper.apply(rs)) : Optional.empty());
                        };
                    } else if (methodName.equals("queryForUniqueValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(2)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForUniqueValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(2)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForUniqueNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = singleQuerySqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(2)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .query((Jdbc.ResultExtractor<Optional<?>>) rs -> {
                                        if (rs.next()) {
                                            final Object val = rowMapper.apply(rs);

                                            if (rs.next()) {
                                                throw new DuplicateResultException("More than one record found for query: " + sp.query());
                                            }

                                            return Optional.of(val);
                                        }

                                        return Optional.empty();
                                    });
                        };
                    } else if (methodName.equals("query") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            if (fetchColumnByEntityClass) {
                                return proxy.prepareQuery(sp.query())
                                        .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters(), collParamsSetter)
                                        .query(Jdbc.ResultExtractor.toDataset(entityClass));
                            } else {
                                return proxy.prepareQuery(sp.query())
                                        .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters(), collParamsSetter)
                                        .query();
                            }
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply((Collection<String>) args[0], limitedCond).build();

                            if (fetchColumnByEntityClass) {
                                return proxy.prepareQuery(sp.query())
                                        .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters(), collParamsSetter)
                                        .query(Jdbc.ResultExtractor.toDataset(entityClass));
                            } else {
                                return proxy.prepareQuery(sp.query())
                                        .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters(), collParamsSetter)
                                        .query();
                            }
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.ResultExtractor resultExtractor = (Jdbc.ResultExtractor) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(resultExtractor, cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("query") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.ResultExtractor resultExtractor = (Jdbc.ResultExtractor) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(resultExtractor, cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiResultExtractor resultExtractor = (Jdbc.BiResultExtractor) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(resultExtractor, cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("query") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiResultExtractor resultExtractor = (Jdbc.BiResultExtractor) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(resultExtractor, cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("paginate") && paramLen == 3 //
                            && paramTypes[0].equals(Condition.class) //
                            && paramTypes[1].equals(int.class) //
                            && paramTypes[2].equals(Jdbc.BiParametersSetter.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = checkCondForPaginate((Condition) args[0]);
                            final int pageSize = N.checkArgPositive((Integer) args[1], cs.pageSize);
                            final Jdbc.BiParametersSetter<PreparedQuery, Dataset> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[2],
                                    cs.paramSetter);
                            final Jdbc.ResultExtractor<Dataset> resultExtractor = fetchColumnByEntityClass ? Jdbc.ResultExtractor.toDataset(entityClass)
                                    : Jdbc.ResultExtractor.TO_DATASET;

                            final Condition limitedCond = handleLimit(cond, pageSize, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            return Stream.just(Holder.of((Dataset) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Dataset ret = proxy.prepareQuery(sp.query())
                                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters(), collParamsSetter)
                                                    .settParameters(it.value(), paramSetter)
                                                    .query(resultExtractor);

                                            it.setValue(ret);

                                            return ret;
                                        } catch (final SQLException e) {
                                            throw new UncheckedSQLException(e);
                                        }
                                    })
                                    .takeWhile(N::notEmpty);
                        };
                    } else if (methodName.equals("paginate") && paramLen == 4 //
                            && paramTypes[0].equals(Condition.class) //
                            && paramTypes[1].equals(int.class) //
                            && paramTypes[2].equals(Jdbc.BiParametersSetter.class) //
                            && paramTypes[3].equals(Jdbc.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = checkCondForPaginate((Condition) args[0]);
                            final int pageSize = N.checkArgPositive((Integer) args[1], cs.pageSize);
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[2],
                                    cs.paramSetter);
                            final Jdbc.ResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.ResultExtractor) args[3], cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, pageSize, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            return Stream.just(Holder.of(null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query())
                                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters(), collParamsSetter)
                                                    .settParameters(it.value(), paramSetter)
                                                    .query(resultExtractor);

                                            it.setValue(ret);

                                            return ret;
                                        } catch (final SQLException e) {
                                            throw new UncheckedSQLException(e);
                                        }
                                    })
                                    .takeWhile(isNotEmptyResult);
                        };
                    } else if (methodName.equals("paginate") && paramLen == 4 //
                            && paramTypes[0].equals(Condition.class) //
                            && paramTypes[1].equals(int.class) //
                            && paramTypes[2].equals(Jdbc.BiParametersSetter.class) //
                            && paramTypes[3].equals(Jdbc.BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = checkCondForPaginate((Condition) args[0]);
                            final int pageSize = N.checkArgPositive((Integer) args[1], cs.pageSize);
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[2],
                                    cs.paramSetter);
                            final Jdbc.BiResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.BiResultExtractor) args[3], cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, pageSize, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            return Stream.just(Holder.of(null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query())
                                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters(), collParamsSetter)
                                                    .settParameters(it.value(), paramSetter)
                                                    .query(resultExtractor);

                                            it.setValue(ret);

                                            return ret;
                                        } catch (final SQLException e) {
                                            throw new UncheckedSQLException(e);
                                        }
                                    })
                                    .takeWhile(isNotEmptyResult);
                        };
                    } else if (methodName.equals("paginate") && paramLen == 4 //
                            && paramTypes[0].equals(Collection.class) //
                            && paramTypes[1].equals(Condition.class) //
                            && paramTypes[2].equals(int.class) //
                            && paramTypes[3].equals(Jdbc.BiParametersSetter.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = checkCondForPaginate((Condition) args[1]);
                            final int pageSize = N.checkArgPositive((Integer) args[2], cs.pageSize);
                            final Jdbc.BiParametersSetter<PreparedQuery, Dataset> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[3],
                                    cs.paramSetter);
                            final Jdbc.ResultExtractor<Dataset> resultExtractor = fetchColumnByEntityClass ? Jdbc.ResultExtractor.toDataset(entityClass)
                                    : Jdbc.ResultExtractor.TO_DATASET;

                            final Condition limitedCond = handleLimit(cond, pageSize, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            return Stream.just(Holder.of((Dataset) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Dataset ret = proxy.prepareQuery(sp.query())
                                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters(), collParamsSetter)
                                                    .settParameters(it.value(), paramSetter)
                                                    .query(resultExtractor);

                                            it.setValue(ret);

                                            return ret;
                                        } catch (final SQLException e) {
                                            throw new UncheckedSQLException(e);
                                        }
                                    })
                                    .takeWhile(N::notEmpty);
                        };
                    } else if (methodName.equals("paginate") && paramLen == 5 //
                            && paramTypes[0].equals(Collection.class) //
                            && paramTypes[1].equals(Condition.class) //
                            && paramTypes[2].equals(int.class) //
                            && paramTypes[3].equals(Jdbc.BiParametersSetter.class) //
                            && paramTypes[4].equals(Jdbc.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = checkCondForPaginate((Condition) args[1]);
                            final int pageSize = N.checkArgPositive((Integer) args[2], cs.pageSize);
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[3],
                                    cs.paramSetter);
                            final Jdbc.ResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.ResultExtractor) args[4], cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, pageSize, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            return Stream.just(Holder.of(null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query())
                                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters(), collParamsSetter)
                                                    .settParameters(it.value(), paramSetter)
                                                    .query(resultExtractor);

                                            it.setValue(ret);

                                            return ret;
                                        } catch (final SQLException e) {
                                            throw new UncheckedSQLException(e);
                                        }
                                    })
                                    .takeWhile(isNotEmptyResult);
                        };
                    } else if (methodName.equals("paginate") && paramLen == 5 //
                            && paramTypes[0].equals(Collection.class) //
                            && paramTypes[1].equals(Condition.class) //
                            && paramTypes[2].equals(int.class) //
                            && paramTypes[3].equals(Jdbc.BiParametersSetter.class) //
                            && paramTypes[4].equals(Jdbc.BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = checkCondForPaginate((Condition) args[1]);
                            final int pageSize = N.checkArgPositive((Integer) args[2], cs.pageSize);
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[3],
                                    cs.paramSetter);
                            final Jdbc.BiResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.BiResultExtractor) args[4], cs.resultExtractor);

                            final Condition limitedCond = handleLimit(cond, pageSize, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            return Stream.just(Holder.of(null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query())
                                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters(), collParamsSetter)
                                                    .settParameters(it.value(), paramSetter)
                                                    .query(resultExtractor);

                                            it.setValue(ret);

                                            return ret;
                                        } catch (final SQLException e) {
                                            throw new UncheckedSQLException(e);
                                        }
                                    })
                                    .takeWhile(isNotEmptyResult);
                        };
                    } else if (methodName.equals("list") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class) && paramTypes[1].equals(Jdbc.RowFilter.class)
                            && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowFilter.class) && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowFilter.class) && paramTypes[3].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[2];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[3];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowFilter.class) && paramTypes[3].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[2];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[3];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("stream") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(entityClass);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowFilter.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowFilter, rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowFilter.class) && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowFilter, rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(entityClass);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowFilter.class) && paramTypes[3].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[2];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[3];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowFilter, rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("stream") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowFilter.class) && paramTypes[3].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[2];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[3];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query())
                                            .configureStatement(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters(), collParamsSetter)
                                            .stream(rowFilter, rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equals("forEach") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("forEach") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[1];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("forEach") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowFilter.class) && paramTypes[2].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[1];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("forEach") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowFilter.class) && paramTypes[2].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[1];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("forEach") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("forEach") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[2];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("forEach") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowFilter.class) && paramTypes[3].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[2];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[3];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("forEach") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowFilter.class) && paramTypes[3].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[2];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[3];
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowFilter, cs.rowFilter);
                            N.checkArgNotNull(rowConsumer, cs.rowConsumer);

                            final Condition limitedCond = handleLimit(cond, -1, false);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                            proxy.prepareQuery(sp.query())
                                    .configureStatement(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])
                            && Condition.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> updateProps = (Map<String, Object>) args[0];
                            final Condition cond = (Condition) args[1];

                            N.checkArgNotEmpty(updateProps, cs.updateProps);
                            N.checkArgNotNull(cond, cs.cond);

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass)
                                    .set(typedUpdatePropsFunc.apply(updateProps))
                                    .append(cond)
                                    .build();
                            return proxy.prepareQuery(sp.query()).settParameters(sp.parameters(), typedCollParamsSetter).update();
                        };
                    } else if (methodName.equals("update") && paramLen == 3 && !Map.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && Condition.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            final Condition cond = (Condition) args[2];

                            N.checkArgNotNull(entity, cs.entity);
                            N.checkArgNotEmpty(propNamesToUpdate, cs.propNamesToUpdate);
                            N.checkArgNotNull(cond, cs.cond);
                            // Validated before the UPDATE SQL is built, as the CrudDao twins do: an unknown (or null) name used to be
                            // rejected only after the query builder had run on it, so a builder failure could mask the property error.
                            checkPropNamesToUpdate(propNamesToUpdate, entityInfo);

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).append(cond).build();

                            // set(Collection) renders a raw SET expression (e.g. "version = version + 1", see isRawSetExpression) without a
                            // placeholder (CrudDao.update(entity, propNamesToUpdate) supports it the same way), so only the other elements
                            // bind a value: binding every element failed on such an expression with a NullPointerException.
                            final List<PropInfo> propInfosToBind = new ArrayList<>(propNamesToUpdate.size());

                            for (final String propName : propNamesToUpdate) {
                                if (!isRawSetExpression(propName)) {
                                    propInfosToBind.add(entityInfo.getPropInfo(propName));
                                }
                            }

                            final Jdbc.BiParametersSetter<AbstractQuery, Object> parametersSetter = (pq, p) -> {
                                final PreparedStatement stmt = pq.stmt;
                                int columnIndex = 1;

                                for (final PropInfo propInfo : propInfosToBind) {
                                    propInfo.dbType.set(stmt, columnIndex++, propInfo.getPropValue(p));
                                }

                                if (sp.parameters().size() > 0) {
                                    for (final Object param : sp.parameters()) {
                                        pq.setObject(columnIndex++, param);
                                    }
                                }
                            };

                            return proxy.prepareQuery(sp.query()).settParameters(entity, parametersSetter).update();
                        };
                    } else if (methodName.equals("delete") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final SP sp = parameterizedDeleteFromFunc.apply(tableName, entityClass).append(cond).build();
                            return proxy.prepareQuery(sp.query()).settParameters(sp.parameters(), collParamsSetter).update();
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + method);
                        };
                    }
                } else if (DaoUtil.isCrudDaoOperationDeclaringClass(declaringClass)) {
                    if (methodName.equals("insert") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, cs.entity);

                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            // Puts back the ID this call writes onto the entity if a transaction it runs in is rolled back later.
                            JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                    () -> RollbackActionLog.captureIdValues(List.of(entity), idPropNameList, entityInfo));

                            ParsedSql namedInsertSql = null;
                            Runnable generatedIdRestorer = null;

                            if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                if (callGenerateIdForInsert) {
                                    generatedIdRestorer = RollbackActionLog.captureIdValues(List.of(entity), idPropNameList, entityInfo);

                                    // An ID setter can fail after writing part of the ID (e.g. one property of a composite ID): no row was
                                    // inserted, so the ID values are put back (see restoreGeneratedIds), as the batch paths do.
                                    try {
                                        idSetter.accept(DaoUtil.generateId(proxy), entity);
                                    } catch (final Throwable e) { //NOSONAR
                                        restoreGeneratedIds(generatedIdRestorer, e);
                                        throw e;
                                    }

                                    namedInsertSql = namedInsertWithIdSQL;
                                } else {
                                    namedInsertSql = namedInsertWithoutIdSQL;
                                }
                            } else {
                                namedInsertSql = namedInsertWithIdSQL;
                            }

                            final Optional<Object> generatedKey;
                            final ExecutionMark executionMark = generatedIdRestorer == null ? null : new ExecutionMark();

                            try {
                                generatedKey = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                        .settParameters(entity, objParamsSetter)
                                        .insert(keyExtractor, isDefaultIdTester, executionMark);
                            } catch (final Throwable e) { //NOSONAR
                                restoreGeneratedIdsUnlessExecuted(generatedIdRestorer, executionMark, e);
                                throw e;
                            }

                            return generatedKey.map(ret -> {
                                idSetter.accept(ret, entity);

                                // A composite id may come back partial (e.g. only the auto-increment column, possibly under a
                                // driver label such as GENERATED_KEY); the setter merged it into the entity, so return the full id.
                                return isOneId ? ret : idGetter.apply(entity);
                            }).orElse(idGetter.apply(entity));
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToInsert = (Collection<String>) args[1];
                            N.checkArgNotNull(entity, cs.entity);
                            N.checkArgNotEmpty(propNamesToInsert, cs.propNamesToInsert);

                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            // Puts back the ID this call writes onto the entity if a transaction it runs in is rolled back later.
                            JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                    () -> RollbackActionLog.captureIdValues(List.of(entity), idPropNameList, entityInfo));

                            final String namedInsertSql = namedInsertSqlBuilderFunc.apply(propNamesToInsert).build().query();
                            Runnable generatedIdRestorer = null;

                            if ((callGenerateIdForInsert && !N.disjoint(propNamesToInsert, idPropNameSet)) && isDefaultIdTester.test(idGetter.apply(entity))) {
                                generatedIdRestorer = RollbackActionLog.captureIdValues(List.of(entity), idPropNameList, entityInfo);

                                // An ID setter can fail after writing part of the ID (e.g. one property of a composite ID): no row was
                                // inserted, so the ID values are put back (see restoreGeneratedIds), as the batch paths do.
                                try {
                                    idSetter.accept(DaoUtil.generateId(proxy), entity);
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIds(generatedIdRestorer, e);
                                    throw e;
                                }
                            }

                            final Optional<Object> generatedKey;
                            final ExecutionMark executionMark = generatedIdRestorer == null ? null : new ExecutionMark();

                            try {
                                generatedKey = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                        .settParameters(entity, objParamsSetter)
                                        .insert(keyExtractor, isDefaultIdTester, executionMark);
                            } catch (final Throwable e) { //NOSONAR
                                restoreGeneratedIdsUnlessExecuted(generatedIdRestorer, executionMark, e);
                                throw e;
                            }

                            return generatedKey.map(ret -> {
                                idSetter.accept(ret, entity);

                                // A composite id may come back partial (e.g. only the auto-increment column, possibly under a
                                // driver label such as GENERATED_KEY); the setter merged it into the entity, so return the full id.
                                return isOneId ? ret : idGetter.apply(entity);
                            }).orElse(idGetter.apply(entity));
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final String namedInsertSql = (String) args[0];
                            final Object entity = args[1];
                            N.checkArgNotEmpty(namedInsertSql, cs.namedInsertSql);
                            N.checkArgNotNull(entity, cs.entity);

                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            // Puts back the ID this call writes onto the entity if a transaction it runs in is rolled back later.
                            JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                    () -> RollbackActionLog.captureIdValues(List.of(entity), idPropNameList, entityInfo));

                            Runnable generatedIdRestorer = null;

                            if (callGenerateIdForInsertWithSql && isDefaultIdTester.test(idGetter.apply(entity))) {
                                generatedIdRestorer = RollbackActionLog.captureIdValues(List.of(entity), idPropNameList, entityInfo);

                                // An ID setter can fail after writing part of the ID (e.g. one property of a composite ID): no row was
                                // inserted, so the ID values are put back (see restoreGeneratedIds), as the batch paths do.
                                try {
                                    idSetter.accept(DaoUtil.generateId(proxy), entity);
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIds(generatedIdRestorer, e);
                                    throw e;
                                }
                            }

                            final Optional<Object> generatedKey;
                            final ExecutionMark executionMark = generatedIdRestorer == null ? null : new ExecutionMark();

                            try {
                                generatedKey = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                        .settParameters(entity, objParamsSetter)
                                        .insert(keyExtractor, isDefaultIdTester, executionMark);
                            } catch (final Throwable e) { //NOSONAR
                                restoreGeneratedIdsUnlessExecuted(generatedIdRestorer, executionMark, e);
                                throw e;
                            }

                            return generatedKey.map(ret -> {
                                idSetter.accept(ret, entity);

                                // A composite id may come back partial (e.g. only the auto-increment column, possibly under a
                                // driver label such as GENERATED_KEY); the setter merged it into the entity, so return the full id.
                                return isOneId ? ret : idGetter.apply(entity);
                            }).orElse(idGetter.apply(entity));
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, cs.batchSize);

                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            // The elements are validated before the rollback action is registered: inside a transaction the registration
                            // takes the ID snapshot at once, which failed with a NullPointerException on a null element that the ID getter
                            // rejects with an IllegalArgumentException outside a transaction. The same pass finds whether every, and
                            // whether any, entity has a default ID.
                            boolean allDefaultIdValue = true;
                            boolean anyDefaultIdValue = false;

                            for (final Object entity : entities) {
                                N.checkArgNotNull(entity, cs.entity);

                                if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                    anyDefaultIdValue = true;
                                } else {
                                    allDefaultIdValue = false;
                                }
                            }

                            // Puts back the IDs this call writes onto the entities if a transaction it runs in is rolled back later.
                            JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                    () -> RollbackActionLog.captureIdValues(entities, idPropNameList, entityInfo));

                            // Puts back the IDs generated below if this call fails (see restoreGeneratedIds). The snapshot of every entity's
                            // ID is only taken when an ID will be generated: it was wasted on a batch whose entities all carry explicit IDs.
                            final Runnable generatedIdRestorer = callGenerateIdForInsert && anyDefaultIdValue
                                    ? RollbackActionLog.captureIdValues(entities, idPropNameList, entityInfo)
                                    : null;

                            if (callGenerateIdForInsert) {
                                try {
                                    for (final Object entity : entities) {
                                        if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                            idSetter.accept(DaoUtil.generateId(proxy), entity);
                                        }
                                    }
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIds(generatedIdRestorer, e);
                                    throw e;
                                }

                                allDefaultIdValue = false;
                            }

                            if (!allDefaultIdValue) {
                                // Reuses the validation pass above instead of a second reflective pass over every entity: without ID
                                // generation the answer is anyDefaultIdValue; after generation only the entities that had a default ID
                                // were touched, so a default ID can remain only if there was one and generateId() returned a default value.
                                final boolean hasDefaultIdEntity = anyDefaultIdValue
                                        && (!callGenerateIdForInsert || N.anyMatch(entities, entity -> isDefaultIdTester.test(idGetter.apply(entity))));

                                if (hasDefaultIdEntity) {
                                    // Preserve the caller's iteration order: explicit IDs can affect database identity state before a later generated-ID insert.
                                    final SqlTransaction tran = beginInsertTransaction(proxy.dataSource(), generatedIdRestorer);
                                    // Returned IDs are buffered and written back only after this call's transaction scope commits (like the
                                    // single-SQL paths below): assigning them run by run left entities of an earlier run holding the IDs of
                                    // rows that a later run's failure rolled back.
                                    final List<Pair<Object, Object>> returnedIdEntityPairs = new ArrayList<>();
                                    Throwable failure = null;
                                    Throwable failureAfterCommit = null;

                                    try {
                                        try (NamedQuery withoutIdQuery = JdbcUtil
                                                .prepareNamedQuery(proxy.dataSource(), namedInsertWithoutIdSQL, generatedKeyColumnNames)
                                                .closeAfterExecution(false);
                                             NamedQuery withIdQuery = JdbcUtil
                                                     .prepareNamedQuery(proxy.dataSource(), namedInsertWithIdSQL, generatedKeyColumnNames)
                                                     .closeAfterExecution(false)) {
                                            final List<Object> chunk = new ArrayList<>(Math.min(batchSize, entities.size()));
                                            boolean runUsesDefaultIdSql = isDefaultIdTester.test(idGetter.apply(N.firstOrNullIfEmpty(entities)));
                                            // Created before the loop below starts iterating the input; see DeferredIdAssignments.
                                            final DeferredIdAssignments deferredIds = new DeferredIdAssignments(entities, returnedIdEntityPairs);

                                            for (final Object entity : entities) {
                                                final boolean entityUsesDefaultIdSql = isDefaultIdTester.test(idGetter.apply(entity));

                                                if (entityUsesDefaultIdSql != runUsesDefaultIdSql) {
                                                    deferredIds.insertChunk(runUsesDefaultIdSql ? withoutIdQuery : withIdQuery, chunk, keyExtractor,
                                                            isDefaultIdTester);
                                                    chunk.clear();
                                                    deferredIds.finishRun(daoLogger);
                                                    runUsesDefaultIdSql = entityUsesDefaultIdSql;
                                                }

                                                chunk.add(entity);

                                                if (chunk.size() == batchSize) {
                                                    deferredIds.insertChunk(runUsesDefaultIdSql ? withoutIdQuery : withIdQuery, chunk, keyExtractor,
                                                            isDefaultIdTester);
                                                    chunk.clear();
                                                }
                                            }

                                            deferredIds.insertChunk(runUsesDefaultIdSql ? withoutIdQuery : withIdQuery, chunk, keyExtractor, isDefaultIdTester);
                                            deferredIds.finishRun(daoLogger);
                                        }

                                        tran.commit();
                                    } catch (final Throwable e) { //NOSONAR
                                        failure = e;

                                        // commit() can still throw from the connection cleanup after the commit went through: the rows
                                        // exist then, so their IDs are kept (and the returned ones assigned below) before rethrowing.
                                        if (tran.status() != Transaction.Status.COMMITTED) {
                                            restoreGeneratedIds(generatedIdRestorer, e);
                                            throw e;
                                        }

                                        failureAfterCommit = e;
                                    } finally {
                                        JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                    }

                                    for (final Pair<Object, Object> idEntityPair : returnedIdEntityPairs) {
                                        idSetter.accept(idEntityPair.left(), idEntityPair.right());
                                    }

                                    if (failureAfterCommit != null) {
                                        throw failureAfterCommit;
                                    }

                                    return Stream.of(entities).map(idGetter).toList();
                                }
                            }

                            final ParsedSql namedInsertSql = allDefaultIdValue ? namedInsertWithoutIdSQL : namedInsertWithIdSQL;
                            List<Object> ids = null;
                            Throwable failureAfterCommit = null;

                            if (entities.size() == 1) {
                                final ExecutionMark executionMark = generatedIdRestorer == null ? null : new ExecutionMark();

                                try {
                                    ids = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                            .addBatchParameters(entities)
                                            .batchInsert(keyExtractor, isDefaultIdTester, executionMark);
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIdsUnlessExecuted(generatedIdRestorer, executionMark, e);
                                    throw e;
                                }
                            } else {
                                final SqlTransaction tran = beginInsertTransaction(proxy.dataSource(), generatedIdRestorer);
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                            .closeAfterExecution(false)) {
                                        ids = Seq.of(entities)
                                                .split(batchSize)
                                                .flatmap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor, isDefaultIdTester))
                                                .toList();
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;

                                    // commit() can still throw from the connection cleanup after the commit went through: the rows exist
                                    // then, so their IDs are kept (and the returned ones assigned below) before rethrowing.
                                    if (tran.status() != Transaction.Status.COMMITTED) {
                                        restoreGeneratedIds(generatedIdRestorer, e);
                                        throw e;
                                    }

                                    failureAfterCommit = e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                                ids = new ArrayList<>();
                            }

                            if (N.notEmpty(ids) && N.notEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (final Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            if ((N.notEmpty(ids) && N.size(ids) != N.size(entities)) && daoLogger.isWarnEnabled()) {
                                daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                        entities.size());
                            }

                            // Return the entities' ids unless the keys are exactly the single-property ids just assigned: a composite
                            // id may come back partial (see insert), and keys that don't pair up with the entities were not assigned.
                            if (!isOneId || N.size(ids) != N.size(entities)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            if (failureAfterCommit != null) {
                                throw failureAfterCommit;
                            }

                            return ids;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];

                            final Collection<String> propNamesToInsert = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgNotEmpty(propNamesToInsert, cs.propNamesToInsert);
                            N.checkArgPositive(batchSize, cs.batchSize);

                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            final boolean generatesIds = callGenerateIdForInsert && !N.disjoint(propNamesToInsert, idPropNameSet);
                            boolean anyDefaultIdValue = false;

                            // The elements are validated before the rollback action is registered (see batchInsert(entities, batchSize)).
                            for (final Object entity : entities) {
                                N.checkArgNotNull(entity, cs.entity);
                                anyDefaultIdValue = anyDefaultIdValue || (generatesIds && isDefaultIdTester.test(idGetter.apply(entity)));
                            }

                            // Puts back the IDs this call writes onto the entities if a transaction it runs in is rolled back later.
                            JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                    () -> RollbackActionLog.captureIdValues(entities, idPropNameList, entityInfo));

                            final String namedInsertSql = namedInsertSqlBuilderFunc.apply(propNamesToInsert).build().query();
                            // Puts back the IDs generated below if this call fails (see restoreGeneratedIds); only taken when an ID will be generated.
                            final Runnable generatedIdRestorer = anyDefaultIdValue ? RollbackActionLog.captureIdValues(entities, idPropNameList, entityInfo)
                                    : null;

                            if (generatesIds) {
                                try {
                                    for (final Object entity : entities) {
                                        if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                            idSetter.accept(DaoUtil.generateId(proxy), entity);
                                        }
                                    }
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIds(generatedIdRestorer, e);
                                    throw e;
                                }
                            }
                            List<Object> ids = null;
                            Throwable failureAfterCommit = null;

                            if (entities.size() == 1) {
                                final ExecutionMark executionMark = generatedIdRestorer == null ? null : new ExecutionMark();

                                try {
                                    ids = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                            .addBatchParameters(entities)
                                            .batchInsert(keyExtractor, isDefaultIdTester, executionMark);
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIdsUnlessExecuted(generatedIdRestorer, executionMark, e);
                                    throw e;
                                }
                            } else {
                                final SqlTransaction tran = beginInsertTransaction(proxy.dataSource(), generatedIdRestorer);
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                            .closeAfterExecution(false)) {
                                        ids = Seq.of(entities)
                                                .split(batchSize)
                                                .flatmap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor, isDefaultIdTester))
                                                .toList();
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;

                                    // commit() can still throw from the connection cleanup after the commit went through: the rows exist
                                    // then, so their IDs are kept (and the returned ones assigned below) before rethrowing.
                                    if (tran.status() != Transaction.Status.COMMITTED) {
                                        restoreGeneratedIds(generatedIdRestorer, e);
                                        throw e;
                                    }

                                    failureAfterCommit = e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                                ids = new ArrayList<>();
                            }

                            if (N.notEmpty(ids) && N.notEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (final Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            if ((N.notEmpty(ids) && N.size(ids) != N.size(entities)) && daoLogger.isWarnEnabled()) {
                                daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                        entities.size());
                            }

                            // Return the entities' ids unless the keys are exactly the single-property ids just assigned: a composite
                            // id may come back partial (see insert), and keys that don't pair up with the entities were not assigned.
                            if (!isOneId || N.size(ids) != N.size(entities)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            if (failureAfterCommit != null) {
                                throw failureAfterCommit;
                            }

                            return ids;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 3 && String.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final String namedInsertSql = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgNotEmpty(namedInsertSql, cs.namedInsertSql);
                            N.checkArgPositive(batchSize, cs.batchSize);

                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            boolean anyDefaultIdValue = false;

                            // The elements are validated before the rollback action is registered (see batchInsert(entities, batchSize)).
                            for (final Object entity : entities) {
                                N.checkArgNotNull(entity, cs.entity);
                                anyDefaultIdValue = anyDefaultIdValue || (callGenerateIdForInsertWithSql && isDefaultIdTester.test(idGetter.apply(entity)));
                            }

                            // Puts back the IDs this call writes onto the entities if a transaction it runs in is rolled back later.
                            JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                    () -> RollbackActionLog.captureIdValues(entities, idPropNameList, entityInfo));

                            // Puts back the IDs generated below if this call fails (see restoreGeneratedIds); only taken when an ID will be generated.
                            final Runnable generatedIdRestorer = anyDefaultIdValue ? RollbackActionLog.captureIdValues(entities, idPropNameList, entityInfo)
                                    : null;

                            if (callGenerateIdForInsertWithSql) {
                                try {
                                    for (final Object entity : entities) {
                                        if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                            idSetter.accept(DaoUtil.generateId(proxy), entity);
                                        }
                                    }
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIds(generatedIdRestorer, e);
                                    throw e;
                                }
                            }

                            List<Object> ids = null;
                            Throwable failureAfterCommit = null;

                            if (entities.size() == 1) {
                                final ExecutionMark executionMark = generatedIdRestorer == null ? null : new ExecutionMark();

                                try {
                                    ids = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                            .addBatchParameters(entities)
                                            .batchInsert(keyExtractor, isDefaultIdTester, executionMark);
                                } catch (final Throwable e) { //NOSONAR
                                    restoreGeneratedIdsUnlessExecuted(generatedIdRestorer, executionMark, e);
                                    throw e;
                                }
                            } else {
                                final SqlTransaction tran = beginInsertTransaction(proxy.dataSource(), generatedIdRestorer);
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                            .closeAfterExecution(false)) {
                                        ids = Seq.of(entities)
                                                .split(batchSize)
                                                .flatmap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor, isDefaultIdTester))
                                                .toList();
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;

                                    // commit() can still throw from the connection cleanup after the commit went through: the rows exist
                                    // then, so their IDs are kept (and the returned ones assigned below) before rethrowing.
                                    if (tran.status() != Transaction.Status.COMMITTED) {
                                        restoreGeneratedIds(generatedIdRestorer, e);
                                        throw e;
                                    }

                                    failureAfterCommit = e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                                ids = new ArrayList<>();
                            }

                            if ((N.notEmpty(ids) && N.size(ids) != N.size(entities)) && daoLogger.isWarnEnabled()) {
                                daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                        entities.size());
                            }

                            if (N.notEmpty(ids) && N.notEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (final Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            // Return the entities' ids unless the keys are exactly the single-property ids just assigned: a composite
                            // id may come back partial (see insert), and keys that don't pair up with the entities were not assigned.
                            if (!isOneId || N.size(ids) != N.size(entities)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            if (failureAfterCommit != null) {
                                throw failureAfterCommit;
                            }

                            return ids;
                        };
                    } else if (methodName.equals("queryForBoolean") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForBytes") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForBytes();
                        };
                    } else if (methodName.equals("queryForSingleValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForSingleValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForSingleNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(id, idParamSetter)
                                    .query((Jdbc.ResultExtractor<Optional<?>>) rs -> rs.next() ? Optional.of(rowMapper.apply(rs)) : Optional.empty());
                        };
                    } else if (methodName.equals("queryForUniqueValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(2).settParameters(id, idParamSetter).queryForUniqueValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(2).settParameters(id, idParamSetter).queryForUniqueNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String singleSelectPropName = (String) args[0];
                            final Object id = args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(singleSelectPropName, cs.singleSelectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1, limitRequiresOrderBy);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(singleSelectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query())
                                    .setFetchSize(2)
                                    .settParameters(id, idParamSetter)
                                    .query((Jdbc.ResultExtractor<Optional<?>>) rs -> {
                                        if (rs.next()) {
                                            final Object val = rowMapper.apply(rs);

                                            if (rs.next()) {
                                                throw new DuplicateResultException("More than one record found for query by id");
                                            }

                                            return Optional.of(val);
                                        }

                                        return Optional.empty();
                                    });
                        };
                    } else if (methodName.equals("getOrNull")) {
                        if (paramLen == 1) {
                            call = (proxy, args) -> {
                                final Object id = args[0];
                                N.checkArgNotNull(id, cs.id);

                                return proxy.prepareNamedQuery(namedGetByIdSQL)
                                        .setFetchSize(2)
                                        .settParameters(id, idParamSetter)
                                        .findOnlyOneOrNull(entityClass);
                            };
                        } else {
                            call = (proxy, args) -> {
                                final Object id = args[0];
                                N.checkArgNotNull(id, cs.id);

                                final Collection<String> selectPropNames = (Collection<String>) args[1];

                                if (N.isEmpty(selectPropNames)) {
                                    return proxy.prepareNamedQuery(namedGetByIdSQL)
                                            .setFetchSize(2)
                                            .settParameters(id, idParamSetter)
                                            .findOnlyOneOrNull(entityClass);

                                } else {
                                    return proxy.prepareNamedQuery(namedSelectSqlBuilderFunc.apply(selectPropNames, idCond).build().query())
                                            .setFetchSize(2)
                                            .settParameters(id, idParamSetter)
                                            .findOnlyOneOrNull(entityClass);
                                }
                            };
                        }
                    } else if (methodName.equals("exists") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Object id = args[0];
                            N.checkArgNotNull(id, cs.id);

                            return proxy.prepareNamedQuery(namedExistsByIdSQL).setFetchSize(1).settParameters(id, idParamSetter).exists();
                        };
                    } else if (methodName.equals("update") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, cs.entity);

                            if (namedUpdateByIdSQL == null) {
                                return 0;
                            }

                            return proxy.prepareNamedQuery(namedUpdateByIdSQL).settParameters(entity, objParamsSetter).update();
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && !Map.class.equals(paramTypes[0]) && Collection.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            N.checkArgNotNull(entity, cs.entity);
                            N.checkArgNotEmpty(propNamesToUpdate, cs.propNamesToUpdate);
                            checkPropNamesToUpdate(propNamesToUpdate, entityInfo);

                            final String query = namedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).where(idCond).build().query();

                            return proxy.prepareNamedQuery(query).settParameters(entity, objParamsSetter).update();
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> updateProps = (Map<String, Object>) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(updateProps, cs.updateProps);
                            N.checkArgNotNull(id, cs.id);

                            final Condition cond = id2CondFunc.apply(id);

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass)
                                    .set(typedUpdatePropsFunc.apply(updateProps))
                                    .append(cond)
                                    .build();
                            return proxy.prepareQuery(sp.query()).settParameters(sp.parameters(), typedCollParamsSetter).update();
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 2 && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities) || (namedUpdateByIdSQL == null)) {
                                return 0;
                            }

                            long result = 0;

                            if (entities.size() == 1) {
                                result = sumUpdateCounts(proxy.prepareNamedQuery(namedUpdateByIdSQL).addBatchParameters(entities).batchUpdate());
                            } else {
                                // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedUpdateByIdSQL).closeAfterExecution(false)) {
                                        result = Seq.of(entities)
                                                .split(batchSize)
                                                .sumLong(bp -> sumUpdateCounts(nameQuery.addBatchParameters(bp).batchUpdate()));
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            return Numbers.toIntExact(result);
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 3 && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgNotEmpty(propNamesToUpdate, cs.propNamesToUpdate);
                            checkPropNamesToUpdate(propNamesToUpdate, entityInfo);
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return 0;
                            }

                            final String query = namedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).where(idCond).build().query();
                            long result = 0;

                            if (entities.size() == 1) {
                                result = sumUpdateCounts(proxy.prepareNamedQuery(query).addBatchParameters(entities).batchUpdate());
                            } else {
                                // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(query).closeAfterExecution(false)) {
                                        result = Seq.of(entities)
                                                .split(batchSize)
                                                .sumLong(bp -> sumUpdateCounts(nameQuery.addBatchParameters(bp).batchUpdate()));
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            return Numbers.toIntExact(result);
                        };
                    } else if (methodName.equals("deleteById")) {
                        call = (proxy, args) -> {
                            final Object id = args[0];
                            N.checkArgNotNull(id, cs.id);

                            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(id, idParamSetter).update();
                        };
                    } else if (methodName.equals("delete") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, cs.entity);

                            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        };
                    } else if ((methodName.equals("batchDelete") || methodName.equals("batchDeleteByIds")) && paramLen == 2
                            && int.class.equals(paramTypes[1])) {

                        final Jdbc.BiParametersSetter<NamedQuery, Object> paramSetter = methodName.equals("batchDeleteByIds") ? idParamSetter
                                : idParamSetterByEntity;

                        call = (proxy, args) -> {
                            final Collection<Object> idsOrEntities = (Collection) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(idsOrEntities)) {
                                return 0;
                            }

                            if (idsOrEntities.size() == 1) {
                                return Numbers.toIntExact(sumUpdateCounts(
                                        proxy.prepareNamedQuery(namedDeleteByIdSQL).addBatchParameters(idsOrEntities, paramSetter).batchUpdate()));
                            } else {
                                // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                long result = 0;
                                Throwable failure = null;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedDeleteByIdSQL).closeAfterExecution(false)) {
                                        result = Seq.of(idsOrEntities)
                                                .split(batchSize)
                                                .sumLong(bp -> sumUpdateCounts(nameQuery.addBatchParameters(bp, paramSetter).batchUpdate()));
                                    }

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }

                                return Numbers.toIntExact(result);
                            }
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + method);
                        };
                    }
                } else if (DaoUtil.isJoinEntityHelperDeclaringClass(declaringClass)) {
                    if (methodName.equals("loadJoinEntities") && paramLen == 3 && !Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1]) && Collection.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final String joinEntityPropName = (String) args[1];
                            final Collection<String> selectPropNames = (Collection<String>) args[2];

                            N.checkArgNotNull(entity, cs.entity);
                            N.checkArgNotEmpty(joinEntityPropName, cs.joinEntityPropName);

                            final JoinInfo propJoinInfo = joinBeanInfo.get(joinEntityPropName);

                            N.checkArgument(propJoinInfo != null, "No join entity property found by name: \"{}\" in class: {}", joinEntityPropName,
                                    ClassUtil.getCanonicalClassName(entityClass));

                            final Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                    .selectSqlPlan(parameterizedDsl);

                            final DaoBase<?, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource, proxy);

                            final PreparedQuery preparedQuery = joinEntityDao.prepareQuery(tp._1.apply(selectPropNames)).setParameters(entity, tp._2);

                            if (propJoinInfo.joinPropInfo.type.isCollection()) {
                                final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                if (propJoinInfo.joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                                    propJoinInfo.joinPropInfo.setPropValue(entity, propEntities);
                                } else {
                                    final Collection<Object> c = N.newCollection((Class) propJoinInfo.joinPropInfo.clazz);
                                    c.addAll(propEntities);
                                    propJoinInfo.joinPropInfo.setPropValue(entity, c);
                                }
                            } else if (propJoinInfo.joinPropInfo.type.isMap()) {
                                final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                if (propEntities.size() > 1) {
                                    throw new IllegalArgumentException("Multiple join entities found for map property: " + propJoinInfo.joinPropInfo.name);
                                }

                                final Map<Object, Object> m = N.newMap((Class) propJoinInfo.joinPropInfo.clazz, 1);

                                if (!propEntities.isEmpty()) {
                                    m.put(propJoinInfo.srcEntityKeyExtractor.apply(entity), propEntities.get(0));
                                }

                                // An explicit load replaces an old association even when no row now matches.
                                propJoinInfo.joinPropInfo.setPropValue(entity, m);
                            } else {
                                propJoinInfo.joinPropInfo.setPropValue(entity, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orElseNull());
                            }

                            return null;
                        };
                    } else if (methodName.equals("loadJoinEntities") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1]) && Collection.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection) args[0];
                            final String joinEntityPropName = (String) args[1];
                            final Collection<String> selectPropNames = (Collection<String>) args[2];

                            N.checkArgNotEmpty(joinEntityPropName, cs.joinEntityPropName);

                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, tableName, joinEntityPropName);

                            final DaoBase<?, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource, proxy);

                            if (N.isEmpty(entities)) {
                                // Do nothing.
                            } else if (entities.size() == 1) {
                                final Object first = N.firstOrNullIfEmpty(entities);

                                final Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                        .selectSqlPlan(parameterizedDsl);

                                final PreparedQuery preparedQuery = joinEntityDao.prepareQuery(tp._1.apply(selectPropNames)).setParameters(first, tp._2);

                                if (propJoinInfo.joinPropInfo.type.isCollection()) {
                                    final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                    if (propJoinInfo.joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                                        propJoinInfo.joinPropInfo.setPropValue(first, propEntities);
                                    } else {
                                        final Collection<Object> c = N.newCollection((Class) propJoinInfo.joinPropInfo.clazz);
                                        c.addAll(propEntities);
                                        propJoinInfo.joinPropInfo.setPropValue(first, c);
                                    }
                                } else if (propJoinInfo.joinPropInfo.type.isMap()) {
                                    final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                    if (propEntities.size() > 1) {
                                        throw new IllegalArgumentException("Multiple join entities found for map property: " + propJoinInfo.joinPropInfo.name);
                                    }

                                    final Map<Object, Object> m = N.newMap((Class) propJoinInfo.joinPropInfo.clazz, 1);

                                    if (!propEntities.isEmpty()) {
                                        m.put(propJoinInfo.srcEntityKeyExtractor.apply(first), propEntities.get(0));
                                    }

                                    propJoinInfo.joinPropInfo.setPropValue(first, m);
                                } else {
                                    propJoinInfo.joinPropInfo.setPropValue(first, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orElseNull());
                                }
                            } else {
                                final Tuple2<BiFunction<Collection<String>, Integer, String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                                        .batchSelectSqlPlan(parameterizedDsl);

                                Stream.of(entities).split(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(bp -> {
                                    // Keep missing keys absent: JoinInfo clears them with a separate empty collection per
                                    // source entity, avoiding both a copied grouping map and shared mutable empty lists.
                                    if (propJoinInfo.isManyToManyJoin()) {
                                        final Jdbc.BiRowMapper<Pair<Object, Object>> pairBiRowMapper = new Jdbc.BiRowMapper<>() {
                                            private Jdbc.BiRowMapper<Object> biRowMapper = null;
                                            private int columnCount = 0;
                                            private List<String> selectCls = null;

                                            /**
                                             * Reads the junction-table key and maps the remaining columns to the joined entity.
                                             *
                                             * @param rs the result set positioned on the row to map
                                             * @param cls the selected column labels, ending with the junction-table key column
                                             * @return the typed source join key and the mapped joined entity
                                             * @throws SQLException if reading the join key or the joined entity's columns fails
                                             * @throws IllegalArgumentException if the selected columns cannot be mapped to the joined entity type
                                             * @throws UnsupportedOperationException if mapping requires writing a read-only entity property
                                             */
                                            @Override
                                            public Pair<Object, Object> apply(final ResultSet rs, final List<String> cls)
                                                    throws SQLException, IllegalArgumentException, UnsupportedOperationException {
                                                if (columnCount == 0) {
                                                    columnCount = cls.size();
                                                    selectCls = cls.subList(0, cls.size() - 1);
                                                    biRowMapper = Jdbc.BiRowMapper.to(propJoinInfo.referencedEntityClass);
                                                }

                                                // Read the trailing intermediate-table key through the source join property's type:
                                                // the groups are matched against srcEntityKeyExtractor's typed values, which a raw
                                                // JDBC value (e.g. Integer/BigDecimal for a long property) would never equal.
                                                return Pair.of(propJoinInfo.srcPropInfos[0].dbType.get(rs, columnCount), biRowMapper.apply(rs, selectCls));
                                            }
                                        };

                                        final List<Pair<Object, Object>> joinPropEntities = joinEntityDao.prepareQuery(tp._1.apply(selectPropNames, bp.size()))
                                                .setParameters(bp, tp._2)
                                                .list(pairBiRowMapper);

                                        // Insertion-ordered: JoinInfo merges the groups of array-typed keys with equal content
                                        // (e.g. byte[], one group per row) in this order, which keeps the row order.
                                        propJoinInfo.setJoinPropEntities(bp,
                                                Stream.of(joinPropEntities).groupTo(Pair::left, Pair::right, Suppliers.ofLinkedHashMap()));
                                    } else {
                                        final List<?> joinPropEntities = joinEntityDao.prepareQuery(tp._1.apply(selectPropNames, bp.size()))
                                                .setParameters(bp, tp._2)
                                                .list(propJoinInfo.referencedEntityClass);

                                        // JoinInfo groups them by join key (by content for array-typed keys such as byte[]).
                                        propJoinInfo.setJoinPropEntities(bp, joinPropEntities);
                                    }
                                });
                            }

                            return null;
                        };
                    } else if (methodName.equals("deleteJoinEntities") && paramLen == 2 && !Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final String joinEntityPropName = (String) args[1];

                            N.checkArgNotNull(entity, cs.entity);
                            N.checkArgNotEmpty(joinEntityPropName, cs.joinEntityPropName);

                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, tableName, joinEntityPropName);
                            final Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo.deleteSqlPlan(parameterizedDsl);

                            final DaoBase<?, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource, proxy,
                                    true);

                            // tp._2 would delete a many-to-many join's intermediate-table rows. JoinInfo currently always leaves it
                            // null (those rows are left to the database, e.g. ON DELETE CASCADE, as documented), so only tp._1 runs;
                            // the transactional two-statement branches here and below apply only if client-side cascade is re-enabled.
                            if (Strings.isEmpty(tp._2)) {
                                return joinEntityDao.prepareQuery(tp._1).setParameters(entity, tp._3).update();
                            } else {
                                long result = 0;
                                final SqlTransaction tran = JdbcUtil.beginTransaction(joinEntityDao.dataSource());
                                Throwable failure = null;

                                try {
                                    result = joinEntityDao.prepareQuery(tp._1).setParameters(entity, tp._3).update();
                                    result += joinEntityDao.prepareQuery(tp._2).setParameters(entity, tp._3).update();

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }

                                return Numbers.toIntExact(result);
                            }
                        };
                    } else if (methodName.equals("deleteJoinEntities") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection) args[0];
                            final String joinEntityPropName = (String) args[1];

                            N.checkArgNotEmpty(joinEntityPropName, cs.joinEntityPropName);

                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, tableName, joinEntityPropName);

                            final DaoBase<?, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource, proxy,
                                    true);

                            if (N.isEmpty(entities)) {
                                return 0;
                            } else if (entities.size() == 1) {
                                final Object first = N.firstOrNullIfEmpty(entities);

                                final Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                        .deleteSqlPlan(parameterizedDsl);

                                if (Strings.isEmpty(tp._2)) {
                                    return joinEntityDao.prepareQuery(tp._1).setParameters(first, tp._3).update();
                                } else {
                                    long result = 0;
                                    final SqlTransaction tran = JdbcUtil.beginTransaction(joinEntityDao.dataSource());
                                    Throwable failure = null;

                                    try {
                                        result = joinEntityDao.prepareQuery(tp._1).setParameters(first, tp._3).update();
                                        result += joinEntityDao.prepareQuery(tp._2).setParameters(first, tp._3).update();

                                        tran.commit();
                                    } catch (final Throwable e) { //NOSONAR
                                        failure = e;
                                        throw e;
                                    } finally {
                                        JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                    }

                                    return Numbers.toIntExact(result);
                                }
                            } else {
                                long result = 0;
                                final SqlTransaction tran = JdbcUtil.beginTransaction(joinEntityDao.dataSource());
                                Throwable failure = null;

                                try {
                                    final Tuple3<IntFunction<String>, IntFunction<String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                                            .batchDeleteSqlPlan(parameterizedDsl);

                                    result = Seq.of(entities).split(JdbcUtil.DEFAULT_BATCH_SIZE).sumLong(bp -> {
                                        if (tp._2 == null) {
                                            return joinEntityDao.prepareQuery(tp._1.apply(bp.size())).setParameters(bp, tp._3).update();
                                        } else {
                                            return (long) joinEntityDao.prepareQuery(tp._1.apply(bp.size())).setParameters(bp, tp._3).update()
                                                    + joinEntityDao.prepareQuery(tp._2.apply(bp.size())).setParameters(bp, tp._3).update();
                                        }
                                    });

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }

                                return Numbers.toIntExact(result);
                            }
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + method);
                        };
                    }
                } else {
                    if (java.util.Optional.class.isAssignableFrom(returnType) || java.util.OptionalInt.class.isAssignableFrom(returnType)
                            || java.util.OptionalLong.class.isAssignableFrom(returnType) || java.util.OptionalDouble.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException("the return type of the method: " + fullClassMethodName + " can't be: " + returnType
                                + ". Please use the OptionalXXX classes defined in com.landawn.abacus.util.u");
                    }

                    // No dispatch produces these: they fell through to the single-value dispatch, which read the first column
                    // as the stream/iterator and failed every call. Only the abacus Stream is supported for lazy results.
                    if (java.util.stream.BaseStream.class.isAssignableFrom(returnType) || Iterator.class.isAssignableFrom(returnType)
                            || (BaseStream.class.isAssignableFrom(returnType) && !Stream.class.isAssignableFrom(returnType))
                            || Seq.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException("the return type of the method: " + fullClassMethodName + " can't be: " + returnType
                                + ". Please use com.landawn.abacus.util.stream.Stream or a Collection type");
                    }

                    if (!(isNonDBOperation || isUncheckedDao || throwsSQLException || throwsUncheckedSQLException || isStreamReturn)) {
                        throw new UnsupportedOperationException("Neither 'throws SQLException' nor 'throws UncheckedSQLException' is declared on method: "
                                + fullClassMethodName
                                + ". This is required for interface extending Dao. If you do not want to declare SQLException, extend UncheckedDao instead");
                    }

                    if (isStreamReturn && (throwsSQLException || throwsUncheckedSQLException)) {
                        throw new UnsupportedOperationException("'throws SQLException' or 'throws UncheckedSQLException' is not allowed on method: "
                                + fullClassMethodName + " because its return type is Stream, which is lazily evaluated");
                    }

                    if (throwsSQLException && throwsUncheckedSQLException) {
                        throw new UnsupportedOperationException(
                                "Invalid method declaration: both 'throws SQLException' and 'throws UncheckedSQLException' are declared on method: "
                                        + fullClassMethodName);
                    }

                    if (sqlAnno == null) {
                        throw new UnsupportedOperationException(
                                "No SQL annotation found on method: " + fullClassMethodName + ". Custom DAO methods must be annotated with @Query");
                    }

                    final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];

                    // Includes primitive AND wrapper update return types: the converter at the bottom of the isUpdate
                    // branch (LongFunction<?> updateResultConverter) already handles Boolean/Integer via ClassUtil.wrap,
                    // and isLargeUpdate explicitly recognizes Long.class for QueryOperation.DEFAULT — keeping the predicate
                    // primitive-only meant those wrapper branches were unreachable and update @Query methods declared with
                    // Integer/Long/Boolean returns failed at the QueryOperation.DEFAULT dispatch with the "Unsupported combination" error.
                    final boolean isUpdateReturnType = isUpdateReturnType(returnType);

                    final QueryInfo queryInfo = sqlAnnoMap.get(sqlAnno.annotationType()).apply(sqlAnno, newSqlMapper);
                    final String query = N.checkArgNotEmpty(queryInfo.sql, "sql can't be null or empty");
                    final ParsedSql parsedSql = queryInfo.parsedSql;
                    final boolean isBatch = queryInfo.isBatch;
                    final int tmpBatchSize = queryInfo.batchSize;
                    final QueryOperation queryOperation = queryInfo.queryOperation;
                    final boolean isSingleParameter = queryInfo.isSingleParameter;
                    final boolean isProcedure = queryInfo.isProcedure;
                    final boolean isUpdate = !queryInfo.isSelect && !queryInfo.isInsert && (queryOperation == QueryOperation.update
                            || queryOperation == QueryOperation.largeUpdate || (queryOperation == QueryOperation.DEFAULT && isUpdateReturnType));

                    final boolean isQuery = isExecutedAsQuery(queryInfo, returnType);

                    // op = update/largeUpdate is only honored for a statement that is not a SELECT. On a plain SELECT it was
                    // silently ignored and the method ran as a query, so an int return got the first column of the first row
                    // instead of an update count. Reject it like the other operation/return-type mismatches, but only for a
                    // SELECT that is a plain read under every lexical reading: one that may write (a data-modifying CTE, a
                    // data-change delta table, SELECT ... INTO) keeps running as before. SqlParser does not recognize a delta
                    // table, so JdbcUtil's write scan is checked as well.
                    if (queryInfo.isSelect && (queryOperation == QueryOperation.update || queryOperation == QueryOperation.largeUpdate)
                            && SqlParser.isSyntacticallyReadQuery(query) && !JdbcUtil.mayWriteUnderAnyDialect(query)) {
                        throw new UnsupportedOperationException(
                                "The SELECT statement of method: " + fullClassMethodName + " is not supported by the specified queryOperation: "
                                        + queryOperation + ". Remove 'op' or declare a query operation, e.g. QueryOperation.queryForSingle");
                    }

                    final boolean isNamedQuery = queryInfo.isNamedQuery;

                    // Custom annotated methods execute through JdbcUtil.prepareQuery/prepareNamedQuery directly,
                    // bypassing the proxy's prepareSqlGate, so enforce the read-only/non-update SQL-kind
                    // restriction here at DAO-creation time with the same SqlParser checks the gate uses.
                    if (isReadOnlyDao && !SqlParser.isReadOnlyQuery(query)) {
                        throw new UnsupportedOperationException("Only SELECT queries are supported in a read-only DAO. Method: " + fullClassMethodName);
                    } else if (isNonUpdateDao && !SqlParser.isReadOrInsertQuery(query)) {
                        throw new UnsupportedOperationException(
                                "Only SELECT and INSERT queries are supported in a non-update DAO. Method: " + fullClassMethodName);
                    }

                    if (parsedSql.parameterCount() == 0 && !isNamedQuery) {
                        daoLogger.debug("Non-named query without parameters will be created for method {}", fullClassMethodName);
                    }

                    final Predicate<Class<?>> isRowMapperOrResultExtractor = it -> Jdbc.ResultExtractor.class.isAssignableFrom(it)
                            || Jdbc.BiResultExtractor.class.isAssignableFrom(it) || Jdbc.RowMapper.class.isAssignableFrom(it)
                            || Jdbc.BiRowMapper.class.isAssignableFrom(it);

                    if (isNamedQuery || isProcedure) {
                        // @Bind parameters are not always required for named query. It's not required if parameter is Entity/Map/EntityId/...
                        //    if (IntStreamEx.range(0, paramLen)
                        //            .noneMatch(i -> StreamEx.of(m.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(Dao.Bind.class)))) {
                        //        throw new UnsupportedOperationException(
                        //                "@Bind parameters are required for named query but none is defined in method: " + fullClassMethodName);
                        //    }

                        if (isNamedQuery && !queryInfo.fragmentsContainNamedParameters) {
                            final List<String> tmp = IntStream.range(0, paramLen)
                                    .mapToObj(i -> Stream.of(method.getParameterAnnotations()[i]).select(Bind.class).first().orElseNull())
                                    .skipNulls()
                                    .map(Bind::value)
                                    .filter(it -> !parsedSql.namedParameters().contains(it))
                                    .toList();

                            if (N.notEmpty(tmp)) {
                                throw new IllegalArgumentException(
                                        "Named parameters bound with names: " + tmp + " are not found in the sql annotated in method: " + fullClassMethodName);
                            }
                        }
                    } else {
                        if (IntStream.range(0, paramLen)
                                .anyMatch(i -> Stream.of(method.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(Bind.class)))) {
                            throw new UnsupportedOperationException("@Bind parameters are defined for non-named query in method: " + fullClassMethodName);
                        }
                    }

                    final int[] tmp = IntStream.range(0, paramLen).filter(i -> !isRowMapperOrResultExtractor.test(paramTypes[i])).toArray();

                    if (N.notEmpty(tmp) && tmp[tmp.length - 1] != tmp.length - 1) {
                        throw new UnsupportedOperationException(
                                "RowMapper/ResultExtractor must be the last parameter but not in method: " + fullClassMethodName);
                    }

                    final Predicate<Class<?>> isRowFilter = it -> Jdbc.RowFilter.class.isAssignableFrom(it) || Jdbc.BiRowFilter.class.isAssignableFrom(it);

                    final int[] tmp2 = IntStream.of(tmp).filter(i -> !isRowFilter.test(paramTypes[i])).toArray();

                    if (N.notEmpty(tmp2) && tmp2[tmp2.length - 1] != tmp2.length - 1) {
                        throw new UnsupportedOperationException(
                                "RowFilter/BiRowFilter must be the last parameter or just before RowMapper/ResultExtractor but not in method: "
                                        + fullClassMethodName);
                    }

                    final Predicate<Class<?>> isParameterSetter = it -> Jdbc.ParametersSetter.class.isAssignableFrom(it)
                            || Jdbc.BiParametersSetter.class.isAssignableFrom(it) || Jdbc.TriParametersSetter.class.isAssignableFrom(it);

                    final int[] tmp3 = IntStream.of(tmp2).filter(i -> !isParameterSetter.test(paramTypes[i])).toArray();

                    if (N.notEmpty(tmp3) && tmp3[tmp3.length - 1] != tmp3.length - 1) {
                        throw new UnsupportedOperationException(
                                "ParametersSetter/BiParametersSetter/TriParametersSetter must be the last parameter or just before RowFilter/BiRowFilter/RowMapper/ResultExtractor but not in method: "
                                        + fullClassMethodName);
                    }

                    final boolean hasRowMapperOrResultExtractor = paramLen > 0 && isRowMapperOrResultExtractor.test(lastParamType);

                    final int[] rowFilterParamIndexes = IntStream.range(0, paramLen).filter(i -> isRowFilter.test(paramTypes[i])).toArray();
                    final boolean hasRowFilter = N.notEmpty(rowFilterParamIndexes);

                    if (N.len(rowFilterParamIndexes) > 1) {
                        throw new UnsupportedOperationException("Only one RowFilter/BiRowFilter parameter is supported in method: " + fullClassMethodName);
                    }

                    if (hasRowFilter && !(rowFilterParamIndexes[0] == paramLen - 2 && hasRowMapperOrResultExtractor
                            && (Jdbc.RowMapper.class.isAssignableFrom(lastParamType) || Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)))) {
                        throw new UnsupportedOperationException(
                                "Parameter 'RowFilter/BiRowFilter' is not supported without last parameter to be 'RowMapper/BiRowMapper' in method: "
                                        + fullClassMethodName);
                    }

                    if (hasRowMapperOrResultExtractor
                            && (Jdbc.RowMapper.class.isAssignableFrom(lastParamType) || Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType))
                            && !(queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.findOnlyOne
                                    || queryOperation == QueryOperation.list || queryOperation == QueryOperation.stream
                                    || queryOperation == QueryOperation.listAll || queryOperation == QueryOperation.DEFAULT)) {
                        throw new UnsupportedOperationException("Parameter 'RowMapper/BiRowMapper' is not supported by queryOperation = " + queryOperation
                                + " in method: " + fullClassMethodName);
                    }

                    if (hasRowMapperOrResultExtractor
                            && (Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType) || Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType))
                            && !(queryOperation == QueryOperation.query || queryOperation == QueryOperation.queryAll
                                    || queryOperation == QueryOperation.streamAll || queryOperation == QueryOperation.DEFAULT)) {
                        throw new UnsupportedOperationException("Parameter 'ResultExtractor/BiResultExtractor' is not supported by queryOperation = "
                                + queryOperation + " in method: " + fullClassMethodName);
                    }

                    // TODO may enable it later.
                    if (hasRowMapperOrResultExtractor) {
                        throw new UnsupportedOperationException(
                                "Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present. Can't use it in method: "
                                        + fullClassMethodName);
                    }

                    final int[] fragmentParamIndexes = IntStream.of(tmp3)
                            .filter(i -> N.anyMatch(method.getParameterAnnotations()[i],
                                    it -> it.annotationType().equals(SqlFragment.class) || it.annotationType().equals(SqlFragmentList.class)
                                            || it.annotationType().equals(BindList.class)))
                            .toArray();

                    final int fragmentParamLen = N.len(fragmentParamIndexes);

                    if (fragmentParamLen > 0) {
                        //    if (fragmentParamIndexes[0] != 0 || fragmentParamIndexes[fragmentParamLen - 1] - fragmentParamIndexes[0] + 1 != fragmentParamLen) {
                        //        throw new UnsupportedOperationException(
                        //                "Parameters annotated with @SqlFragment must be at the head of the parameter list of method: " + fullClassMethodName);
                        //    }

                        for (int i = 0; i < fragmentParamLen; i++) {
                            if ((paramTypes[fragmentParamIndexes[i]].isArray() || Collection.class.isAssignableFrom(paramTypes[fragmentParamIndexes[i]]))
                                    && N.noneMatch(method.getParameterAnnotations()[fragmentParamIndexes[i]],
                                            it -> it.annotationType().equals(SqlFragmentList.class) || it.annotationType().equals(BindList.class))) {
                                throw new UnsupportedOperationException("Array/Collection type of parameter[" + fragmentParamIndexes[i]
                                        + "] must be annotated with @SqlFragmentList or @BindList, not @SqlFragment, in method: " + fullClassMethodName);
                            }
                        }

                        if (IntStream.of(fragmentParamIndexes)
                                .filter(i -> N.anyMatch(method.getParameterAnnotations()[i], it -> it.annotationType().equals(SqlFragmentList.class)))
                                .anyMatch(i -> !(Collection.class.isAssignableFrom(paramTypes[i]) || paramTypes[i].isArray()))) {
                            throw new UnsupportedOperationException(
                                    "Type of parameter annotated with @SqlFragmentList(method: " + fullClassMethodName + ") must be Collection/Array.");
                        }

                        if (IntStream.of(fragmentParamIndexes)
                                .filter(i -> N.anyMatch(method.getParameterAnnotations()[i], it -> it.annotationType().equals(BindList.class)))
                                .anyMatch(i -> !(Collection.class.isAssignableFrom(paramTypes[i]) || paramTypes[i].isArray()))) {
                            throw new UnsupportedOperationException(
                                    "Type of parameter annotated with @BindList(method: " + fullClassMethodName + ") must be Collection/Array.");
                        }

                        if ((isNamedQuery || isProcedure) && IntStream.of(fragmentParamIndexes)
                                .flatMapToObj(i -> Stream.of(method.getParameterAnnotations()[i]))
                                .anyMatch(it -> BindList.class.isAssignableFrom(it.annotationType()))) {
                            throw new UnsupportedOperationException(
                                    "@BindList on method: " + fullClassMethodName + " is not supported for named or callable query.");
                        }
                    }

                    final BiFunction<Annotation, Object, String> fragmentParamMapper = (anno, param) -> N.stringOf(param);
                    // Elements are joined verbatim: serializing them as JSON would escape quoted identifiers such as
                    // "\"FIRST_NAME\"" into \"FIRST_NAME\" (and backslashes/control characters), producing invalid SQL.
                    final BiFunction<Annotation, Object, String> arraySqlFragmentListParamMapper = (anno, param) -> param == null || Array.getLength(param) == 0
                            ? ""
                            : Strings.join(IntStream.range(0, Array.getLength(param)).mapToObj(i -> N.stringOf(Array.get(param, i))).toList(), SK.COMMA_SPACE);
                    final BiFunction<Annotation, Object, String> collSqlFragmentListParamMapper = (anno, param) -> param == null ? ""
                            : Strings.join(Stream.of((Collection<?>) param).map(N::stringOf).toList(), SK.COMMA_SPACE);

                    final BiFunction<Annotation, Object, String> arrayBindListParamMapper = (anno, param) -> param == null || Array.getLength(param) == 0 ? ""
                            : Strings.repeat(SK.QUESTION_MARK, Array.getLength(param), SK.COMMA_SPACE, ((BindList) anno).prefixForNonEmpty(),
                                    ((BindList) anno).suffixForNonEmpty());

                    final BiFunction<Annotation, Object, String> collBindListParamMapper = (anno, param) -> param == null || ((Collection) param).size() == 0
                            ? ""
                            : Strings.repeat(SK.QUESTION_MARK, N.size((Collection) param), SK.COMMA_SPACE, ((BindList) anno).prefixForNonEmpty(),
                                    ((BindList) anno).suffixForNonEmpty());

                    final Tuple2<Annotation, String>[] fragmentAnnos = IntStream.of(fragmentParamIndexes)
                            .mapToObj(i -> resolveFragmentAnnoAndPlaceholder(method, i, fullClassMethodName))
                            .toArray(Tuple2[]::new);

                    final Set<String> fragmentPlaceholderSet = new HashSet<>();
                    final List<String> duplicateFragmentPlaceholders = new ArrayList<>();

                    for (final Tuple2<Annotation, String> fragmentAnno : fragmentAnnos) {
                        if (!fragmentPlaceholderSet.add(fragmentAnno._2) && !duplicateFragmentPlaceholders.contains(fragmentAnno._2)) {
                            duplicateFragmentPlaceholders.add(fragmentAnno._2);
                        }
                    }

                    if (N.notEmpty(duplicateFragmentPlaceholders)) {
                        throw new IllegalArgumentException("Multiple method parameters target the same SQL fragment placeholder in method: "
                                + fullClassMethodName + ": " + duplicateFragmentPlaceholders);
                    }

                    // A @BindList placeholder that appears more than once in the SQL would be expanded at
                    // every textual occurrence (Strings.replaceAll), but the collection values are bound only
                    // once, so execution would fail with a parameter-count mismatch. Fail at proxy creation
                    // instead. @SqlFragment/@SqlFragmentList inject text without binding values, so repeated
                    // occurrences of their placeholders remain supported.
                    for (int i = 0; i < fragmentParamLen; i++) {
                        if (N.anyMatch(method.getParameterAnnotations()[fragmentParamIndexes[i]], it -> it.annotationType().equals(BindList.class))
                                && Strings.countMatches(query, fragmentAnnos[i]._2) > 1) {
                            throw new UnsupportedOperationException(
                                    "The placeholder: " + fragmentAnnos[i]._2 + " of the parameter annotated with @BindList in method: " + fullClassMethodName
                                            + " appears more than once in the SQL. A @BindList placeholder can be referenced only once.");
                        }
                    }

                    final BiFunction<Annotation, Object, String>[] fragmentMappers = IntStream.of(fragmentParamIndexes)
                            .mapToObj(i -> Stream.of(method.getParameterAnnotations()[i]).map(Annotation::annotationType).map(it -> {
                                if (SqlFragment.class.isAssignableFrom(it)) {
                                    return fragmentParamMapper;
                                } else if (SqlFragmentList.class.isAssignableFrom(it)) {
                                    return Collection.class.isAssignableFrom(paramTypes[i]) ? collSqlFragmentListParamMapper : arraySqlFragmentListParamMapper;
                                } else if (BindList.class.isAssignableFrom(it)) {
                                    return Collection.class.isAssignableFrom(paramTypes[i]) ? collBindListParamMapper : arrayBindListParamMapper;
                                } else {
                                    return null;
                                }
                            }).skipNulls().first())
                            .filter(u.Optional::isPresent)
                            .map(u.Optional::get)
                            .toArray(BiFunction[]::new);

                    if (N.notEmpty(fragmentAnnos) && N.anyMatch(fragmentAnnos, it -> !query.contains(it._2))) {
                        throw new IllegalArgumentException("SqlFragments: " + N.filter(fragmentAnnos, it -> !query.contains(it._2))
                                + " are not found in sql annotated in method: " + fullClassMethodName);
                    }

                    final int[] stmtParamIndexes = IntStream.of(tmp3)
                            .filter(i -> Stream.of(method.getParameterAnnotations()[i])
                                    .noneMatch(it -> it.annotationType().equals(SqlFragment.class) || it.annotationType().equals(SqlFragmentList.class)))
                            .toArray();

                    final boolean[] bindListParamFlags = IntStream.of(stmtParamIndexes)
                            .mapToObj(i -> Stream.of(method.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(BindList.class)))
                            .toListThenApply(N::toBooleanArray);

                    final int stmtParamLen = stmtParamIndexes.length;

                    // Whether the sole statement argument of a custom INSERT (each batch row for a batch) may be the DAO entity,
                    // decided from its declared type; an unresolvable element type (raw collection, wildcard, type variable) may
                    // still hold DAO entities. Only such a method writes a generated key back onto the entity and returns the
                    // entity's id (see isEntity in the insert branches below).
                    final boolean mayInsertEntity;
                    // Whether every row of a custom batch INSERT is the DAO entity (or null) by declaration: the declared element
                    // type is the entity class or a subclass of it. Such a batch is an entity batch even when all its rows are null.
                    final boolean insertsEntitiesByDeclaration;

                    if (!queryInfo.isInsert || entityClass == null || !(stmtParamLen == 1 || (isBatch && stmtParamLen == 2))) {
                        mayInsertEntity = false;
                        insertsEntitiesByDeclaration = false;
                    } else {
                        final java.lang.reflect.Type insertedParamGenericType = method.getGenericParameterTypes()[stmtParamIndexes[0]];
                        final java.lang.reflect.Type insertedParamType = !isBatch ? paramTypes[stmtParamIndexes[0]]
                                : (insertedParamGenericType instanceof final ParameterizedType pt && N.len(pt.getActualTypeArguments()) == 1
                                        ? pt.getActualTypeArguments()[0]
                                        : null);
                        // WHY: resolved as a class or a parameterized type only, a wildcard-bounded element type
                        // (Collection<? extends Entity>) counted as unresolvable, so such a batch holding only null rows was never
                        // recognised as an entity batch and its null rows were inserted instead of being rejected.
                        final Class<?> insertedParamClass = classOf(insertedParamType);

                        // WHY: an interface the entity class does not implement (e.g. a Serializable element bound) was taken as
                        // excluding the entity, but a subclass of a non-final entity class may implement any interface and is a
                        // valid argument: such an entity committed its row and never received its generated key. A Map or
                        // Collection (row list) type is bound as such and never written back to, so it stays a non-entity
                        // argument: counting it made a void INSERT into another table request the entity's id column.
                        mayInsertEntity = insertedParamClass == null || insertedParamClass.isAssignableFrom(entityClass)
                                || entityClass.isAssignableFrom(insertedParamClass)
                                || (insertedParamClass.isInterface() && !Modifier.isFinal(entityClass.getModifiers())
                                        && !Map.class.isAssignableFrom(insertedParamClass) && !Collection.class.isAssignableFrom(insertedParamClass));
                        insertsEntitiesByDeclaration = isBatch && insertedParamClass != null && entityClass.isAssignableFrom(insertedParamClass);
                    }

                    // A custom INSERT requests the DAO entity's generated-key columns only when the keys can be consumed:
                    // the method returns them, or its sole statement argument (each batch row for a batch) may be the DAO
                    // entity whose id is written back. Requesting them unconditionally made a void INSERT into another
                    // table fail on drivers that validate the requested column names (e.g. H2: 'Column "id" not found').
                    final boolean returnGeneratedKeys = !isNoId && queryInfo.isInsert && (!void.class.equals(returnType) || mayInsertEntity);

                    // Only a Map is exempt for positional procedure SQL: its keys are bound through the driver's named callable
                    // setters. A bean/record/EntityId still cannot supply positional procedure parameters, even with @Bind.
                    if (stmtParamLen == 1
                            && (Beans.isBeanClass(paramTypes[stmtParamIndexes[0]])
                                    || (Map.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]) && !isProcedure)
                                    || EntityId.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]) || Beans.isRecordClass(paramTypes[stmtParamIndexes[0]]))
                            && !isNamedQuery) {
                        throw new UnsupportedOperationException(isProcedure
                                ? "A parameter of type Entity/EntityId cannot supply the parameters of a positional procedure call;"
                                        + " bind them individually with @Bind or pass a Map, in method: " + fullClassMethodName
                                : "A parameter of type Entity/Map/EntityId requires @Query with named parameters (:name syntax) in method: "
                                        + fullClassMethodName);
                    }

                    if (isSingleParameter && stmtParamLen != 1 && !(isBatch && stmtParamLen == 2 && int.class.equals(paramTypes[stmtParamIndexes[1]]))) {
                        throw new UnsupportedOperationException(
                                "Don't set 'collectionAsSingleParameter' to true if the count of statement/query parameters is not one in method: "
                                        + fullClassMethodName);
                    }

                    final List<OutParameter> outParameterList = Stream.of(method.getAnnotations())
                            .select(OutParameter.class)
                            .append(Stream.of(method.getAnnotations()).select(OutParameters.class).flatMapArray(OutParameters::value))
                            .toList();

                    if (N.notEmpty(outParameterList)) {
                        if (!isProcedure) {
                            throw new UnsupportedOperationException(
                                    "@OutParameter annotations are only supported by @Query methods with procedure=true, not supported in method: "
                                            + fullClassMethodName);
                        }

                        if (Stream.of(outParameterList).anyMatch(it -> Strings.isEmpty(it.name()) && it.position() < 0)) {
                            throw new UnsupportedOperationException(
                                    "One of the attribute: (name, position) of @OutParameter must be set in method: " + fullClassMethodName);
                        }

                        if (Stream.of(outParameterList).anyMatch(it -> Strings.isEmpty(it.name()) && it.position() == 0)) {
                            throw new UnsupportedOperationException("@OutParameter position must be greater than 0 in method: " + fullClassMethodName);
                        }

                        if (Stream.of(outParameterList).anyMatch(it -> Strings.isNotEmpty(it.name()) && it.position() >= 0)) {
                            throw new UnsupportedOperationException(
                                    "Only one of the attribute: (name, position) of @OutParameter can be set in method: " + fullClassMethodName);
                        }

                        final Set<String> outParameterNameSet = new HashSet<>();
                        final Set<Integer> outParameterPositionSet = new HashSet<>();

                        for (final OutParameter outParameter : outParameterList) {
                            if (Strings.isNotEmpty(outParameter.name()) && !outParameterNameSet.add(outParameter.name())) {
                                throw new UnsupportedOperationException(
                                        "Duplicate @OutParameter name '" + outParameter.name() + "' in method: " + fullClassMethodName);
                            }

                            if (outParameter.position() > 0 && !outParameterPositionSet.add(outParameter.position())) {
                                throw new UnsupportedOperationException(
                                        "Duplicate @OutParameter position " + outParameter.position() + " in method: " + fullClassMethodName);
                            }
                        }
                    }

                    if ((queryOperation == QueryOperation.listAll || queryOperation == QueryOperation.queryAll || queryOperation == QueryOperation.streamAll
                            || queryOperation == QueryOperation.executeAndGetOutParameters) && !isProcedure) {
                        throw new UnsupportedOperationException(
                                "QueryOperation.listAll/queryAll/streamAll/executeAndGetOutParameters are only supported by @Query methods with procedure=true but method: "
                                        + fullClassMethodName + " does not have procedure=true");
                    }

                    if (isBatch && !((stmtParamLen == 1 && Collection.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])) || (stmtParamLen == 2
                            && Collection.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]) && int.class.equals(paramTypes[stmtParamIndexes[1]])))) {
                        throw new UnsupportedOperationException("For batch operations(" + fullClassMethodName
                                + "), the first parameter must be Collection. The second parameter is optional, it only can be int if it's set");
                    }

                    if (isBatch && Stream.of(bindListParamFlags).anyMatch(it -> it)) {
                        throw new UnsupportedOperationException("For batch operations(" + fullClassMethodName
                                + "), @BindList parameters are not supported: the Collection parameter supplies the batch rows and cannot also be expanded into IN-clause placeholders");
                    }

                    // Only the INSERT and update paths execute the batch rows; a query binds no statement parameters for a batch
                    // method, so it either failed on every call with an unset parameter or silently ignored the batch Collection.
                    if (isBatch && isQuery) {
                        throw new UnsupportedOperationException("Batch execution is only supported for INSERT/UPDATE/DELETE statements and procedures"
                                + " executed as updates, not for the query of method: " + fullClassMethodName);
                    }

                    final MappedByKey mappedByKeyAnno = method.getAnnotation(MappedByKey.class);
                    final String mappedByKey = mappedByKeyAnno == null ? null
                            : Strings.isNotEmpty(mappedByKeyAnno.value()) ? mappedByKeyAnno.value() : oneIdPropName;

                    if (mappedByKeyAnno != null && Strings.isEmpty(mappedByKey)) {
                        throw new IllegalArgumentException("Mapped Key name can't be null or empty in method: " + fullClassMethodName);
                    }

                    if (Strings.isNotEmpty(mappedByKey)) {
                        final Method mappedByKeyMethod = Beans.getPropGetter(entityClass, mappedByKey);

                        if (mappedByKeyMethod == null) {
                            throw new IllegalArgumentException(
                                    "No method found by mapped key: " + mappedByKey + " in entity class: " + ClassUtil.getCanonicalClassName(entityClass));
                        }

                        if (!(queryOperation == QueryOperation.DEFAULT || queryOperation == QueryOperation.list)) {
                            throw new IllegalArgumentException("QueryOperation for method annotated by @MappedByKey can't be: " + queryOperation
                                    + " in method: " + fullClassMethodName + ". It must be QueryOperation.DEFAULT or QueryOperation.list");
                        }

                        final Class<?> firstReturnEleType = getFirstReturnEleType(method);
                        final Class<?> secondReturnEleType = getSecondReturnEleType(method);

                        if (!(Map.class.isAssignableFrom(returnType)
                                && (firstReturnEleType != null && firstReturnEleType.isAssignableFrom(ClassUtil.wrap(mappedByKeyMethod.getReturnType())))
                                && (secondReturnEleType != null) && secondReturnEleType.isAssignableFrom(entityClass))) {
                            throw new IllegalArgumentException("The return type of method(" + fullClassMethodName
                                    + ") annotated by @MappedByKey must be: Map<K, V> with concrete (non-wildcard) type arguments, K being "
                                    + ClassUtil.getSimpleClassName(ClassUtil.wrap(mappedByKeyMethod.getReturnType())) + " or a supertype and V being "
                                    + ClassUtil.getSimpleClassName(entityClass) + " or a supertype. It can't be: " + method.getGenericReturnType());
                        }

                        final Class<? extends Map> mapClass = mappedByKeyAnno.mapClass();

                        // The proxy returns an instance of mapClass, so it must also satisfy a more-specific
                        // declared return type (for example LinkedHashMap). Without this check a method that
                        // returned LinkedHashMap while using the default HashMap factory initialized normally,
                        // then failed with ClassCastException only after the query had executed.
                        if (!returnType.isAssignableFrom(mapClass)) {
                            throw new IllegalArgumentException("The mapClass: " + ClassUtil.getCanonicalClassName(mapClass)
                                    + " configured by @MappedByKey on method: " + fullClassMethodName + " is not assignable to the declared return type: "
                                    + ClassUtil.getCanonicalClassName(returnType));
                        }

                        if (mapClass.isInterface() || Modifier.isAbstract(mapClass.getModifiers())) {
                            throw new IllegalArgumentException(
                                    "The mapClass: " + ClassUtil.getCanonicalClassName(mapClass) + " configured by @MappedByKey on method: "
                                            + fullClassMethodName + " must be a concrete Map implementation with a no-argument constructor");
                        }

                        try {
                            mapClass.getDeclaredConstructor();
                        } catch (final NoSuchMethodException e) {
                            throw new IllegalArgumentException("The mapClass: " + ClassUtil.getCanonicalClassName(mapClass)
                                    + " configured by @MappedByKey on method: " + fullClassMethodName + " must have a no-argument constructor", e);
                        }
                    }

                    final MergedById mergedByIdAnno = method.getAnnotation(MergedById.class);
                    final List<String> mergedByIds = mergedByIdAnno == null ? null
                            : Splitter.with(',')
                                    .trimResults()
                                    // split("") yields [""]: without this an id-less entity skipped the empty check below.
                                    .omitEmptyStrings()
                                    .split(Strings.isNotEmpty(mergedByIdAnno.value()) ? mergedByIdAnno.value()
                                            : (Strings.isNotEmpty(mappedByKey) ? mappedByKey : Strings.join(idPropNameList, ",")));

                    if (mergedByIdAnno != null && N.isEmpty(mergedByIds)) {
                        throw new IllegalArgumentException("Merged id name(s) can't be null or empty in method: " + fullClassMethodName);
                    }

                    if (N.notEmpty(mergedByIds)) {
                        if (Strings.isNotEmpty(mappedByKey)) {
                            if (!(mergedByIds.size() == 1 && mappedByKey.equals(mergedByIds.get(0)))) {
                                throw new IllegalArgumentException("The key/id annotated by @MappedByKey and @MergedById on method(" + fullClassMethodName
                                        + ") must be same if both MappedByKey and MergedById are annotated. But they are: \"" + mappedByKey + "\", "
                                        + mergedByIds);
                            }
                        } else {
                            for (final String mergedById : mergedByIds) {
                                final Method mergedByIdMethod = Beans.getPropGetter(entityClass, mergedById);

                                if (mergedByIdMethod == null) {
                                    throw new IllegalArgumentException("No method found by merged id: " + mergedById + " in entity class: "
                                            + ClassUtil.getCanonicalClassName(entityClass));
                                }
                            }

                            if (!(queryOperation == QueryOperation.DEFAULT || queryOperation == QueryOperation.findFirst
                                    || queryOperation == QueryOperation.findOnlyOne || queryOperation == QueryOperation.list)) {
                                throw new IllegalArgumentException("QueryOperation for method annotated by @MergedById can't be: " + queryOperation
                                        + " in method: " + fullClassMethodName
                                        + ". It must be QueryOperation.DEFAULT, QueryOperation.findFirst, QueryOperation.findOnlyOne or QueryOperation.list");
                            }

                            final Class<?> firstReturnEleType = getFirstReturnEleType(method);

                            // java.util.Optional return types are already rejected unconditionally above,
                            // so only u.Optional can reach here.
                            if (!(((Collection.class.isAssignableFrom(queryReturnType)
                                    && (queryOperation == QueryOperation.list || queryOperation == QueryOperation.DEFAULT))
                                    || (u.Optional.class.isAssignableFrom(returnType) && (queryOperation == QueryOperation.findFirst
                                            || queryOperation == QueryOperation.findOnlyOne || queryOperation == QueryOperation.DEFAULT)))
                                    && (firstReturnEleType != null && firstReturnEleType.isAssignableFrom(entityClass)))) {
                                throw new IllegalArgumentException("The return type of method(" + fullClassMethodName
                                        + ") annotated by @MergedById must be: Optional/List/Collection with a concrete (non-wildcard) element type that is "
                                        + ClassUtil.getSimpleClassName(entityClass) + " or a supertype. It can't be: " + method.getGenericReturnType());
                            }
                        }
                    }

                    final PrefixFieldMapping prefixFieldMappingAnno = method.getAnnotation(PrefixFieldMapping.class);

                    final Map<String, String> prefixFieldMap = prefixFieldMappingAnno == null || Strings.isEmpty(prefixFieldMappingAnno.value()) ? null
                            : MapSplitter.with(",", "=").trimResults().split(prefixFieldMappingAnno.value());

                    if (N.notEmpty(prefixFieldMap)) {
                        if (!(queryOperation == QueryOperation.DEFAULT || queryOperation == QueryOperation.findFirst
                                || queryOperation == QueryOperation.findOnlyOne || queryOperation == QueryOperation.list
                                || queryOperation == QueryOperation.query || queryOperation == QueryOperation.stream)) {
                            throw new IllegalArgumentException("QueryOperation for method annotated by @PrefixFieldMapping can't be: " + queryOperation
                                    + " in method: " + fullClassMethodName
                                    + ". It must be QueryOperation.DEFAULT, QueryOperation.findFirst, QueryOperation.findOnlyOne, QueryOperation.list, QueryOperation.stream and QueryOperation.query");
                        }

                        final Class<?> firstReturnEleType = getFirstReturnEleType(method);

                        if (!(Strings.isNotEmpty(mappedByKey) || N.notEmpty(mergedByIds) || Dataset.class.isAssignableFrom(returnType)
                                || entityClass.isAssignableFrom(returnType)
                                || (firstReturnEleType != null && firstReturnEleType.isAssignableFrom(entityClass)))) {
                            throw new IllegalArgumentException("The return type of method(" + fullClassMethodName
                                    + ") annotated by @PrefixFieldMapping must be: Optional/List/Collection/Stream with a concrete (non-wildcard) element type that is "
                                    + ClassUtil.getSimpleClassName(entityClass) + " or a supertype, Dataset, or " + ClassUtil.getSimpleClassName(entityClass)
                                    + ". It can't be: " + method.getGenericReturnType());
                        }

                        // A Dataset is only built through the entity class (where the prefix mapping applies) when
                        // fetchColumnByEntityClass is enabled; otherwise the annotation would be silently ignored.
                        if (Dataset.class.isAssignableFrom(returnType) && !fetchColumnByEntityClass) {
                            throw new IllegalArgumentException("@PrefixFieldMapping on the Dataset-returning method (" + fullClassMethodName
                                    + ") requires fetchColumnByEntityClass=true (see @FetchColumnByEntityClass/DaoConfig)");
                        }
                    }

                    final Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter = createParametersSetter(queryInfo, fullClassMethodName, method,
                            paramTypes, paramLen, fragmentParamLen, stmtParamIndexes, bindListParamFlags, stmtParamLen);

                    // Constant per method: computed once here so prepareQuery does not re-derive them reflectively on every invocation.
                    final boolean isExistsQueryMethod = isExistsQuery(method, queryOperation, fullClassMethodName);
                    final boolean isSingleReturnTypeMethod = isSingleReturnType(queryReturnType);
                    final boolean isListQueryMethod = isListQuery(method, queryReturnType, queryOperation, fullClassMethodName);

                    if (isQuery) {
                        final Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> queryFunc = createQueryFunctionByMethod(entityClass, method,
                                mappedByKey, mergedByIds, prefixFieldMap, fetchColumnByEntityClass, hasRowMapperOrResultExtractor, hasRowFilter, queryOperation,
                                isProcedure, fullClassMethodName);

                        call = (proxy, args) -> {
                            final AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, queryReturnType, args, fragmentParamIndexes,
                                    fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter,
                                    isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod);

                            try {
                                return queryFunc.apply(preparedQuery, args);
                            } catch (final SQLException | RuntimeException | Error e) {
                                // The query function can fail before it reaches the execution method that closes the query, e.g. while
                                // building its row mapper (a @PrefixFieldMapping target that is not a bean class) or its result
                                // collection supplier. Close the query so its statement and connection are not leaked; closing a
                                // query that the execution method already closed is a no-op.
                                preparedQuery.closeSuppressingFailure(e);
                                throw e;
                            }
                        };
                    } else if (queryInfo.isInsert) {
                        if (isNoId && !returnType.isAssignableFrom(void.class)) {
                            throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                    + ") for no id entities only can be: void. It can't be: " + returnType);
                        }

                        // A non-CRUD DAO declares no ID type, but the ids it returns still have one: the id property's type, or
                        // EntityId for a composite id (see isCompositeIdAsEntityId). Without checking against it, a narrower return
                        // type (e.g. int for a long id) was accepted and the method failed with ClassCastException only after the
                        // row had been inserted. A composite EntityId is returned as a Seid, so Seid (or any supertype) is accepted too.
                        // The check applies only to a method that may insert the DAO entity (mayInsertEntity): an INSERT into another
                        // table (e.g. an audit row from a String) returns that table's key, not the entity's id, and keeps any return type.
                        final Class<?> returnedIdClass = isCompositeIdAsEntityId ? Seid.class
                                : (idClass != null || isNoId ? idClass : (isOneId ? idPropInfo.clazz : EntityId.class));

                        if (!isBatch) {
                            final boolean isOptionalIdReturn = u.Optional.class.equals(returnType);
                            final Class<?> declaredIdReturnType = isOptionalIdReturn ? getFirstReturnEleType(method) : returnType;

                            if (mayInsertEntity && !(returnType.equals(void.class) || (declaredIdReturnType != null
                                    && (returnedIdClass == null || ClassUtil.wrap(declaredIdReturnType).isAssignableFrom(ClassUtil.wrap(returnedIdClass)))))) {
                                throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                        + ") only can be: void, the 'ID' type (or a supertype), or Optional<ID>. It can't be: "
                                        + method.getGenericReturnType());
                            }

                            // The generated key is read with the DAO entity's id type (see getIdExtractor); the key of an INSERT into
                            // another table is returned as the declared type instead (see convertGeneratedKey).
                            final TriFunction<Optional<Object>, Object, Boolean, ?> insertResultConverter = void.class.equals(returnType)
                                    ? (ret, entity, isEntity) -> null
                                    : (isOptionalIdReturn
                                            ? (ret, entity, isEntity) -> isEntity ? ret : ret.map(key -> convertGeneratedKey(key, declaredIdReturnType))
                                            : (ret, entity, isEntity) -> isEntity ? ret.orElse(idGetter.apply(entity)) //NOSONAR
                                                    : ret.map(key -> convertGeneratedKey(key, declaredIdReturnType)).orElse(N.defaultValueOf(returnType)));

                            call = (proxy, args) -> {
                                // Only the DAO entity carries the id property a generated key is written to (and read from);
                                // any other bean argument, e.g. of an INSERT into another table, must be left untouched.
                                final boolean isEntity = stmtParamLen == 1 && entityClass != null && entityClass.isInstance(args[stmtParamIndexes[0]]);
                                final Object entity = isEntity ? args[stmtParamIndexes[0]] : null;

                                if (!returnGeneratedKeys) {
                                    // No generated key was requested, so none can be read back: calling getGeneratedKeys() anyway
                                    // fails on drivers that require keys to be requested up front (e.g. MySQL Connector/J).
                                    prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args, fragmentParamIndexes, fragmentAnnos, fragmentMappers,
                                            returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter, isExistsQueryMethod,
                                            isSingleReturnTypeMethod, isListQueryMethod).update();

                                    return insertResultConverter.apply(Optional.empty(), entity, isEntity);
                                }

                                final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);

                                if (isEntity && !isNoId) {
                                    // Puts back the ID written onto the entity below if a transaction this insert runs in is rolled back later.
                                    JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                            () -> RollbackActionLog.captureIdValues(List.of(entity), idPropNameList, entityInfo));
                                }

                                final Optional<Object> id = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args, fragmentParamIndexes,
                                        fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter,
                                        isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod).insert(keyExtractor, isDefaultIdTester);

                                if (isEntity && id.isPresent()) {
                                    idSetter.accept(id.get(), entity);

                                    if (!isOneId) {
                                        // A composite id may come back partial (see the built-in insert); return the entity's full id.
                                        return insertResultConverter.apply(Optional.of(idGetter.apply(entity)), entity, isEntity);
                                    }
                                }

                                return insertResultConverter.apply(id, entity, isEntity);
                            };
                        } else {
                            final boolean canReturnArrayList = List.class.isAssignableFrom(returnType) && returnType.isAssignableFrom(ArrayList.class);
                            final Class<?> declaredBatchIdType = canReturnArrayList ? getFirstReturnEleType(method) : null;

                            // A batch INSERT that cannot insert the DAO entity still needs a List return type: its keys come back as a List.
                            if (!(returnType.equals(void.class) || (canReturnArrayList && (!mayInsertEntity || (declaredBatchIdType != null
                                    && (returnedIdClass == null || ClassUtil.wrap(declaredBatchIdType).isAssignableFrom(ClassUtil.wrap(returnedIdClass)))))))) {
                                throw new UnsupportedOperationException("The return type of batch insert operations(" + fullClassMethodName
                                        + ") only can be: void/List<ID>. It can't be: " + method.getGenericReturnType());
                            }

                            call = (proxy, args) -> {
                                final Collection<Object> batchParameters = (Collection) args[stmtParamIndexes[0]];
                                int batchSize = tmpBatchSize;

                                if (stmtParamLen == 2) {
                                    batchSize = (Integer) args[stmtParamIndexes[1]];
                                }

                                if (batchSize == 0) {
                                    batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                                }

                                N.checkArgPositive(batchSize, cs.batchSize);

                                final Jdbc.BiRowMapper<Object> keyExtractor = returnGeneratedKeys ? getIdExtractor(idExtractorHolder, idExtractor, proxy)
                                        : null;

                                // Only DAO entities carry the id property generated keys are written to (see the non-batch branch). Whether
                                // the batch holds DAO entities is decided by the declared element type or, failing that, by its first
                                // non-null row: decided by the first row, a leading null row disguised an entity batch as a non-entity
                                // one, so the null row was inserted as an all-null row (one-parameter SQL) and the real entities never
                                // received their generated keys, while the same batch with the null row last was rejected below.
                                // A null or empty batch executes no statement (see @Query#batch()): it is classified as nothing, so
                                // neither the row validation nor the classification iterates it (iterating a null batch threw a
                                // NullPointerException instead of the documented no-op).
                                final boolean isEntityBatch = N.notEmpty(batchParameters) && (insertsEntitiesByDeclaration
                                        || (entityClass != null && entityClass.isInstance(firstNonNullElement(batchParameters))));
                                final boolean writesIdsBack = returnGeneratedKeys && !isNoId && isEntityBatch;

                                if (writesIdsBack) {
                                    // A null row of an entity batch cannot take its generated key: every row is validated before anything runs.
                                    // It used to be inserted as an all-null row (see NamedQuery.addBatchParameters) and the key assignment then
                                    // failed, after the batch had been committed, with the IllegalArgumentException thrown here (inside a
                                    // transaction the ID snapshot below failed first, with a NullPointerException). Null rows of a non-entity
                                    // batch stay as they are.
                                    for (final Object entity : batchParameters) {
                                        N.checkArgNotNull(entity, cs.entity);
                                    }

                                    // Puts back the IDs written onto the entities below if a transaction this call runs in is rolled back later.
                                    JdbcUtil.registerEnclosingTransactionRollbackAction(proxy.dataSource(),
                                            () -> RollbackActionLog.captureIdValues(batchParameters, idPropNameList, entityInfo));
                                }

                                List<Object> ids = null;
                                Throwable failureAfterCommit = null;

                                if (N.isEmpty(batchParameters)) {
                                    ids = new ArrayList<>(0);
                                } else if (batchParameters.size() == 1) {
                                    AbstractQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args, fragmentParamIndexes, fragmentAnnos,
                                                fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter,
                                                isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod).addBatchParameters(batchParameters,
                                                        ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args, fragmentParamIndexes, fragmentAnnos,
                                                fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter,
                                                isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod).addBatchParameters(batchParameters);
                                    }

                                    ids = executeCustomBatchInsert(preparedQuery, returnGeneratedKeys, keyExtractor, isDefaultIdTester);
                                } else {
                                    // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                    // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                    // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                    final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                    Throwable failure = null;

                                    try {
                                        try (AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args,
                                                fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames,
                                                outParameterList, parametersSetter, isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod)
                                                        .closeAfterExecution(false)) {

                                            if (isSingleParameter) {
                                                ids = Seq.of(batchParameters)
                                                        .split(batchSize)
                                                        .flatmap(bp -> executeCustomBatchInsert(preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT),
                                                                returnGeneratedKeys, keyExtractor, isDefaultIdTester))
                                                        .toList();
                                            } else {
                                                ids = Seq.of((Collection<List<?>>) (Collection) batchParameters)
                                                        .split(batchSize) //
                                                        .flatmap(bp -> executeCustomBatchInsert(preparedQuery.addBatchParameters(bp), returnGeneratedKeys,
                                                                keyExtractor, isDefaultIdTester))
                                                        .toList();
                                            }
                                        }

                                        tran.commit();
                                    } catch (final Throwable e) { //NOSONAR
                                        failure = e;

                                        // commit() can still throw from the connection cleanup after the commit went through: the rows
                                        // exist then, so the keys they returned are still written back below (as the built-in batchInsert
                                        // does) before rethrowing; the entities used to keep null IDs for committed rows.
                                        if (tran.status() != Transaction.Status.COMMITTED) {
                                            throw e;
                                        }

                                        failureAfterCommit = e;
                                    } finally {
                                        JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                    }
                                }

                                // Only DAO entities carry the id property generated keys are written to (see isEntityBatch above).
                                final boolean isEntity = isEntityBatch;

                                if (JdbcUtil.isAllNullIds(ids, isDefaultIdTester)) {
                                    ids = new ArrayList<>();
                                }

                                if ((N.notEmpty(ids) && N.size(ids) != N.size(batchParameters)) && daoLogger.isWarnEnabled()) {
                                    daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            batchParameters.size());
                                }

                                if (isEntity) {
                                    @SuppressWarnings("UnnecessaryLocalVariable")
                                    final Collection<Object> entities = batchParameters;

                                    if (N.notEmpty(ids) && N.notEmpty(entities) && ids.size() == N.size(entities)) {
                                        int idx = 0;

                                        for (final Object e : entities) {
                                            idSetter.accept(ids.get(idx++), e);
                                        }
                                    }

                                    // Return the entities' ids unless the keys are exactly the single-property ids just assigned: a composite
                                    // id may come back partial (see the built-in insert), and keys that don't pair up with the entities were
                                    // not assigned.
                                    if (!isOneId || N.size(ids) != N.size(entities)) {
                                        ids = Stream.of(entities).map(idGetter).toList();
                                    }
                                } else if (declaredBatchIdType != null && N.notEmpty(ids)) {
                                    // The keys of an INSERT into another table were read with the DAO entity's id type (see the non-batch branch).
                                    ids = Stream.of(ids).map(key -> convertGeneratedKey(key, declaredBatchIdType)).toList();
                                }

                                if (failureAfterCommit != null) {
                                    throw failureAfterCommit;
                                }

                                if (void.class.equals(returnType)) {
                                    return null;
                                }

                                return returnType.isInstance(ids) ? ids : new ArrayList<>(ids);
                            };
                        }
                    } else if (isUpdate) {
                        if (!isUpdateReturnType) {
                            throw new UnsupportedOperationException("The return type of update/delete operations(" + fullClassMethodName
                                    + ") only can be: int/Integer/long/Long/boolean/Boolean/void. It can't be: " + returnType);
                        }

                        final LongFunction<?> updateResultConverter = void.class.equals(returnType) ? updatedRecordCount -> null
                                : (Boolean.class.equals(ClassUtil.wrap(returnType)) ? updatedRecordCount -> updatedRecordCount > 0
                                        : (Integer.class.equals(ClassUtil.wrap(returnType)) ? Numbers::toIntExact : LongFunction.identity()));

                        final boolean isLargeUpdate = queryOperation == QueryOperation.largeUpdate
                                || (queryOperation == QueryOperation.DEFAULT && (returnType.equals(long.class) || returnType.equals(Long.class)));

                        if (!isBatch) {
                            call = (proxy, args) -> {
                                final AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args, fragmentParamIndexes,
                                        fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter,
                                        isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod);

                                final long updatedRecordCount = isLargeUpdate ? preparedQuery.largeUpdate() : preparedQuery.update();

                                return updateResultConverter.apply(updatedRecordCount);
                            };

                        } else {
                            call = (proxy, args) -> {
                                final Collection<Object> batchParameters = (Collection) args[stmtParamIndexes[0]];
                                int batchSize = tmpBatchSize;

                                if (stmtParamLen == 2) {
                                    batchSize = (Integer) args[stmtParamIndexes[1]];
                                }

                                if (batchSize == 0) {
                                    batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                                }

                                N.checkArgPositive(batchSize, cs.batchSize);

                                long updatedRecordCount = 0;

                                if (N.isEmpty(batchParameters)) {
                                    updatedRecordCount = 0;
                                } else if (batchParameters.size() == 1) {
                                    AbstractQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args, fragmentParamIndexes, fragmentAnnos,
                                                fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter,
                                                isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod).addBatchParameters(batchParameters,
                                                        ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args, fragmentParamIndexes, fragmentAnnos,
                                                fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter,
                                                isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod).addBatchParameters(batchParameters);
                                    }

                                    if (isLargeUpdate) {
                                        updatedRecordCount = sumUpdateCounts(preparedQuery.largeBatchUpdate());
                                    } else {
                                        updatedRecordCount = sumUpdateCounts(preparedQuery.batchUpdate());
                                    }
                                } else {
                                    // Transactional whenever there is more than one element, also when all fit in one JDBC batch: every chunk of this
                                    // multi-row call commits or rolls back together (CHANGES.md 4.9.3), and inside a Spring-managed transaction the
                                    // scope participates in it (its outcome is Spring's) instead of committing Spring's connection.
                                    final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                    Throwable failure = null;

                                    try {
                                        try (AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, returnType, args,
                                                fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames,
                                                outParameterList, parametersSetter, isExistsQueryMethod, isSingleReturnTypeMethod, isListQueryMethod)
                                                        .closeAfterExecution(false)) {

                                            if (isSingleParameter) {
                                                updatedRecordCount = Seq.of(batchParameters)
                                                        .split(batchSize) //
                                                        .sumLong(bp -> isLargeUpdate
                                                                ? sumUpdateCounts(preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT).largeBatchUpdate())
                                                                : sumUpdateCounts(preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT).batchUpdate()));
                                            } else {
                                                updatedRecordCount = Seq.of((Collection<List<?>>) (Collection) batchParameters)
                                                        .split(batchSize) //
                                                        .sumLong(bp -> isLargeUpdate
                                                                //
                                                                ? sumUpdateCounts(preparedQuery.addBatchParameters(bp).largeBatchUpdate())
                                                                : sumUpdateCounts(preparedQuery.addBatchParameters(bp).batchUpdate()));
                                            }
                                        }

                                        tran.commit();
                                    } catch (final Throwable e) { //NOSONAR
                                        failure = e;
                                        throw e;
                                    } finally {
                                        JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                    }
                                }

                                return updateResultConverter.apply(updatedRecordCount);
                            };
                        }
                    } else {
                        throw new UnsupportedOperationException("Unsupported combination of SQL kind, QueryOperation (" + queryOperation + ") and return type ("
                                + returnType + ") in method: " + fullClassMethodName);
                    }
                }

                if (!throwsSQLException) {
                    final Throwables.BiFunction<DaoBase, Object[], ?, SQLException> tmp = (Throwables.BiFunction) call;

                    call = (proxy, args) -> {
                        try {
                            return tmp.apply(proxy, args);
                        } catch (final SQLException e) {
                            throw new UncheckedSQLException(e);
                        }
                    };
                }
            }

            if (isNonDBOperation) {
                nonDBOperationSet.add(method);

                if (daoLogger.isDebugEnabled()) {
                    daoLogger.debug("Non-DB operation method: {}", simpleClassMethodName);
                }

                // ignore
            } else {
                final Transactional transactionalAnno = Stream.of(method.getAnnotations()).select(Transactional.class).last().orElseNull();

                if (transactionalAnno != null && transactionalAnno.propagation() != Propagation.SUPPORTS
                        && transactionalAnno.propagation() != Propagation.MANDATORY
                        && (BaseStream.class.isAssignableFrom(returnType) || java.util.stream.BaseStream.class.isAssignableFrom(returnType)
                                || Seq.class.isAssignableFrom(returnType) || Iterator.class.isAssignableFrom(returnType))) {
                    // REQUIRED/REQUIRES_NEW can complete an invocation-owned transaction before consumption.
                    // NOT_SUPPORTED/NEVER enforce a non-transactional context only for the invocation, but lazy query
                    // setup can occur later after an outer transaction is restored or a new one starts. SUPPORTS and
                    // MANDATORY are safe when the caller consumes the stream before changing/completing its context. A Seq or an
                    // Iterator returned by a default method is just as lazy.
                    throw new UnsupportedOperationException("@Transactional with propagation " + transactionalAnno.propagation()
                            + " is not supported on stream-returning (or Seq/Iterator-returning) method: " + fullClassMethodName
                            + ". Consume the stream inside a non-streaming transactional method instead");
                }

                //    if (transactionalAnno != null && Modifier.isAbstract(m.getModifiers())) {
                //        throw new UnsupportedOperationException(
                //                "Annotation @Transactional is only supported by interface methods with default implementation: default xxx dbOperationABC(someParameters, String ... sqls), not supported by abstract method: "
                //                       + fullClassMethodName);
                //    }

                final SqlLogEnabled daoClassSqlLogAnno = Stream.of(allInterfaces)
                        .flatMapArray(Class::getAnnotations)
                        .select(SqlLogEnabled.class)
                        .filter(it -> Stream.of(it.filter()).anyMatch(filterByMethodName))
                        .first()
                        .orElseNull();

                final PerfLog daoClassPerfLogAnno = Stream.of(allInterfaces)
                        .flatMapArray(Class::getAnnotations)
                        .select(PerfLog.class)
                        .filter(it -> Stream.of(it.filter()).anyMatch(filterByMethodName))
                        .first()
                        .orElseNull();

                final SqlLogEnabled sqlLogAnno = Stream.of(method.getAnnotations()).select(SqlLogEnabled.class).last().orElse(daoClassSqlLogAnno);
                final PerfLog perfLogAnno = Stream.of(method.getAnnotations()).select(PerfLog.class).last().orElse(daoClassPerfLogAnno);
                final boolean hasSqlLogAnno = sqlLogAnno != null;
                final boolean hasPerfLogAnno = perfLogAnno != null;

                //    final boolean isSqlLogEnabled = hasSqlLogAnno && JdbcUtil.isSqlLogAllowed;
                //    final boolean isPerfLogEnabled = hasPerfLogAnno && (JdbcUtil.isSqlPerfLogAllowed || JdbcUtil.isDaoMethodPerfLogAllowed);
                //    final boolean isSqlPerfLogEnabled = hasPerfLogAnno && JdbcUtil.isSqlPerfLogAllowed;
                //    final boolean isDaoPerfLogEnabled = hasPerfLogAnno && JdbcUtil.isDaoMethodPerfLogAllowed;

                final Throwables.BiFunction<DaoBase, Object[], ?, Throwable> tmp = call;

                if (transactionalAnno == null || transactionalAnno.propagation() == Propagation.SUPPORTS) {
                    if (hasSqlLogAnno || hasPerfLogAnno) {
                        call = (proxy, args) -> {
                            final SqlLogConfig sqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
                            final boolean prevSqlLogEnabled = sqlLogConfig.isEnabled;
                            final int prevMaxSqlLogLength = sqlLogConfig.maxSqlLogLength;
                            final SqlLogConfig sqlPerfLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
                            final long prevMinExecutionTimeForSqlPerfLog = sqlPerfLogConfig.sqlPerfLogThresholdMillis;
                            final int prevMaxPerfSqlLogLength = sqlPerfLogConfig.maxSqlLogLength;

                            if (hasSqlLogAnno) {
                                JdbcUtil.setSqlLogEnabled(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                            }

                            if (hasPerfLogAnno) {
                                JdbcUtil.setSqlPerfLogThresholdMillis(perfLogAnno.sqlPerfLogThresholdMillis(), perfLogAnno.maxSqlLogLength());
                            }

                            final long startTimeNanos = hasPerfLogAnno ? System.nanoTime() : -1;

                            try {
                                return tmp.apply(proxy, args);
                            } finally {
                                if (hasPerfLogAnno) {
                                    logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTimeNanos);
                                }

                                if (hasPerfLogAnno) {
                                    JdbcUtil.setSqlPerfLogThresholdMillis(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                }

                                if (hasSqlLogAnno) {
                                    JdbcUtil.setSqlLogEnabled(prevSqlLogEnabled, prevMaxSqlLogLength);
                                }
                            }
                        };
                    } else {
                        // Do not need to do anything.
                    }
                } else if (transactionalAnno.propagation() == Propagation.REQUIRED || transactionalAnno.propagation() == Propagation.MANDATORY) {
                    if (hasSqlLogAnno || hasPerfLogAnno) {
                        call = (proxy, args) -> {
                            final javax.sql.DataSource dataSource = proxy.dataSource();

                            if (transactionalAnno.propagation() == Propagation.MANDATORY && !JdbcUtil.isInTransaction(dataSource)) {
                                throw new IllegalStateException("The method: " + fullClassMethodName + " with @Transactional(propagation = "
                                        + transactionalAnno.propagation() + ") must be called in a transaction.");
                            }

                            final SqlLogConfig sqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
                            final boolean prevSqlLogEnabled = sqlLogConfig.isEnabled;
                            final int prevMaxSqlLogLength = sqlLogConfig.maxSqlLogLength;
                            final SqlLogConfig sqlPerfLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
                            final long prevMinExecutionTimeForSqlPerfLog = sqlPerfLogConfig.sqlPerfLogThresholdMillis;
                            final int prevMaxPerfSqlLogLength = sqlPerfLogConfig.maxSqlLogLength;

                            if (hasSqlLogAnno) {
                                JdbcUtil.setSqlLogEnabled(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                            }

                            if (hasPerfLogAnno) {
                                JdbcUtil.setSqlPerfLogThresholdMillis(perfLogAnno.sqlPerfLogThresholdMillis(), perfLogAnno.maxSqlLogLength());
                            }

                            final long startTimeNanos = hasPerfLogAnno ? System.nanoTime() : -1;

                            SqlTransaction tran = null;
                            Object result = null;
                            Throwable failure = null;

                            try {
                                // Participates in a Spring-managed transaction that owns the data source (see JdbcUtil.beginTransaction).
                                tran = JdbcUtil.beginTransaction(dataSource, transactionalAnno.isolationLevel());

                                result = tmp.apply(proxy, args);

                                tran.commit();
                            } catch (final Throwable e) { //NOSONAR
                                failure = e;
                                throw e;
                            } finally {
                                try {
                                    if (tran != null) {
                                        JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                    }
                                } finally {
                                    if (hasPerfLogAnno) {
                                        logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTimeNanos);
                                        JdbcUtil.setSqlPerfLogThresholdMillis(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                    }

                                    if (hasSqlLogAnno) {
                                        JdbcUtil.setSqlLogEnabled(prevSqlLogEnabled, prevMaxSqlLogLength);
                                    }
                                }
                            }

                            return result;
                        };
                    } else {
                        call = (proxy, args) -> {
                            final javax.sql.DataSource dataSource = proxy.dataSource();

                            if (transactionalAnno.propagation() == Propagation.MANDATORY && !JdbcUtil.isInTransaction(dataSource)) {
                                throw new IllegalStateException("The method: " + fullClassMethodName + " with @Transactional(propagation = "
                                        + transactionalAnno.propagation() + ") must be called in a transaction.");
                            }

                            // Participates in a Spring-managed transaction that owns the data source (see JdbcUtil.beginTransaction).
                            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, transactionalAnno.isolationLevel());
                            Object result = null;
                            Throwable failure = null;

                            try {
                                result = tmp.apply(proxy, args);

                                tran.commit();
                            } catch (final Throwable e) { //NOSONAR
                                failure = e;
                                throw e;
                            } finally {
                                JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                            }

                            return result;
                        };
                    }
                } else if (transactionalAnno.propagation() == Propagation.REQUIRES_NEW) {
                    call = (proxy, args) -> {
                        final javax.sql.DataSource dataSource = proxy.dataSource();

                        return JdbcUtil.callOutsideTransaction(dataSource, () -> {
                            if (hasSqlLogAnno || hasPerfLogAnno) {
                                final SqlLogConfig sqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
                                final boolean prevSqlLogEnabled = sqlLogConfig.isEnabled;
                                final int prevMaxSqlLogLength = sqlLogConfig.maxSqlLogLength;
                                final SqlLogConfig sqlPerfLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
                                final long prevMinExecutionTimeForSqlPerfLog = sqlPerfLogConfig.sqlPerfLogThresholdMillis;
                                final int prevMaxPerfSqlLogLength = sqlPerfLogConfig.maxSqlLogLength;

                                if (hasSqlLogAnno) {
                                    JdbcUtil.setSqlLogEnabled(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                                }

                                if (hasPerfLogAnno) {
                                    JdbcUtil.setSqlPerfLogThresholdMillis(perfLogAnno.sqlPerfLogThresholdMillis(), perfLogAnno.maxSqlLogLength());
                                }

                                final long startTimeNanos = hasPerfLogAnno ? System.nanoTime() : -1;

                                SqlTransaction tran = null;
                                Object result = null;
                                Throwable failure = null;

                                try {
                                    tran = JdbcUtil.beginTransaction(proxy.dataSource(), transactionalAnno.isolationLevel());
                                    result = tmp.apply(proxy, args);

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    try {
                                        if (tran != null) {
                                            JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                        }
                                    } finally {
                                        if (hasPerfLogAnno) {
                                            logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTimeNanos);
                                            JdbcUtil.setSqlPerfLogThresholdMillis(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                        }

                                        if (hasSqlLogAnno) {
                                            JdbcUtil.setSqlLogEnabled(prevSqlLogEnabled, prevMaxSqlLogLength);
                                        }
                                    }
                                }

                                return result;
                            } else {
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource(), transactionalAnno.isolationLevel());
                                Object result = null;
                                Throwable failure = null;

                                try {
                                    result = tmp.apply(proxy, args);

                                    tran.commit();
                                } catch (final Throwable e) { //NOSONAR
                                    failure = e;
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }

                                return result;
                            }
                        });
                    };
                } else if (transactionalAnno.propagation() == Propagation.NOT_SUPPORTED || transactionalAnno.propagation() == Propagation.NEVER) {
                    call = (proxy, args) -> {
                        final javax.sql.DataSource dataSource = proxy.dataSource();

                        if (transactionalAnno.propagation() == Propagation.NEVER && JdbcUtil.isInTransaction(dataSource)) {
                            throw new IllegalStateException("The method: " + fullClassMethodName + " with @Transactional(propagation = "
                                    + transactionalAnno.propagation() + ") can't be called in a transaction.");
                        }

                        if (hasSqlLogAnno || hasPerfLogAnno) {
                            return JdbcUtil.callOutsideTransaction(dataSource, () -> {
                                final SqlLogConfig sqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
                                final boolean prevSqlLogEnabled = sqlLogConfig.isEnabled;
                                final int prevMaxSqlLogLength = sqlLogConfig.maxSqlLogLength;
                                final SqlLogConfig sqlPerfLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
                                final long prevMinExecutionTimeForSqlPerfLog = sqlPerfLogConfig.sqlPerfLogThresholdMillis;
                                final int prevMaxPerfSqlLogLength = sqlPerfLogConfig.maxSqlLogLength;

                                if (hasSqlLogAnno) {
                                    JdbcUtil.setSqlLogEnabled(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                                }

                                if (hasPerfLogAnno) {
                                    JdbcUtil.setSqlPerfLogThresholdMillis(perfLogAnno.sqlPerfLogThresholdMillis(), perfLogAnno.maxSqlLogLength());
                                }

                                final long startTimeNanos = hasPerfLogAnno ? System.nanoTime() : -1;

                                try {
                                    return tmp.apply(proxy, args);
                                } finally {
                                    if (hasPerfLogAnno) {
                                        logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTimeNanos);
                                    }

                                    if (hasPerfLogAnno) {
                                        JdbcUtil.setSqlPerfLogThresholdMillis(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                    }

                                    if (hasSqlLogAnno) {
                                        JdbcUtil.setSqlLogEnabled(prevSqlLogEnabled, prevMaxSqlLogLength);
                                    }
                                }
                            });
                        } else {
                            return JdbcUtil.callOutsideTransaction(dataSource, () -> tmp.apply(proxy, args));
                        }
                    };
                }

                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method,
                        ImmutableList.wrap(N.toList(method.getParameterTypes())), method.getReturnType());

                final CacheResult methodCacheResultAnno = Stream.of(method.getAnnotations()).select(CacheResult.class).last().orElseNull();
                // A type-level annotation applies through the first one whose filter matches the method (the rule of
                // @PerfLog/@SqlLogEnabled), even when that one is disabled: a sub-interface's disabled annotation opts its
                // matching methods out of a super-interface's.
                final CacheResult daoClassCacheResultAnno = Strings.containsAnyIgnoreCase(method.getName(), "page", "paginate") ? null
                        : Stream.of(daoClassCacheResultAnnos).filter(it -> N.anyMatch(it.filter(), filterByMethodName)).first().orElseNull();
                final CacheResult cacheResultAnno = methodCacheResultAnno != null ? (methodCacheResultAnno.enabled() ? methodCacheResultAnno : null)
                        : (daoClassCacheResultAnno != null && daoClassCacheResultAnno.enabled() ? daoClassCacheResultAnno : null);

                final RefreshCache methodRefreshCacheAnno = Stream.of(method.getAnnotations()).select(RefreshCache.class).last().orElseNull();
                final RefreshCache daoClassRefreshCacheAnno = Stream.of(daoClassRefreshCacheAnnos)
                        .filter(it -> N.anyMatch(it.filter(), filterByMethodName))
                        .first()
                        .orElseNull();
                final RefreshCache refreshResultAnno = methodRefreshCacheAnno != null ? (methodRefreshCacheAnno.enabled() ? methodRefreshCacheAnno : null)
                        : (daoClassRefreshCacheAnno != null && daoClassRefreshCacheAnno.enabled() ? daoClassRefreshCacheAnno : null);

                final long cacheLiveTime = cacheResultAnno == null ? 0 : cacheResultAnno.maxLiveTimeMillis();
                final long cacheMaxIdleTime = cacheResultAnno == null ? 0 : cacheResultAnno.maxIdleTimeMillis();

                // The thread-local DAO cache otherwise classifies a method only by its name prefix. A custom @Query write
                // (INSERT/UPDATE/DELETE...) must invalidate it whatever the method is called, and must never be served from
                // it: an UPDATE named "reset..." left stale query results behind, one named "get..." was not executed again.
                final QueryInfo customQueryInfo = queryAnno != null && Modifier.isAbstract(method.getModifiers())
                        ? sqlAnnoMap.get(Query.class).apply(queryAnno, newSqlMapper)
                        : null;
                // A custom @Query is a write exactly when the custom-method dispatch executes it as an update rather than a
                // query (same classification, e.g. a procedure with op DEFAULT and an int return is a write).
                final boolean isCustomWriteQuery = customQueryInfo != null && !isExecutedAsQuery(customQueryInfo, returnType);

                final boolean isQueryMethod = JdbcUtil.IS_QUERY_METHOD.test(method) && !isCustomWriteQuery;
                final boolean isUpdateMethod = JdbcUtil.IS_UPDATE_METHOD.test(method) || isCustomWriteQuery;
                // Live or single-use results (streams, iterators) and void can't be cached by the local thread cache
                // either: cloning them for the cache fails (or consumes the stream) and a cached stream can't be re-read.
                final boolean isLocalThreadCacheableQueryMethod = isQueryMethod
                        && Stream.of(notCacheableTypes).noneMatch(it -> it.isAssignableFrom(returnType));
                final boolean isAnnotatedCacheResult = cacheResultAnno != null && cacheResultAnno.enabled();
                final boolean isAnnotatedRefreshResult = refreshResultAnno != null && refreshResultAnno.enabled();

                if (isAnnotatedCacheResult || isAnnotatedRefreshResult || (isQueryMethod || isUpdateMethod)) {
                    if (daoLogger.isDebugEnabled()) {
                        if (isAnnotatedCacheResult) {
                            daoLogger.debug("Add CacheResult method: {}", method);
                        } else if (isAnnotatedRefreshResult) {
                            daoLogger.debug("Add RefreshCache method: {}", method);
                        }
                    }

                    if (isAnnotatedCacheResult && Stream.of(notCacheableTypes).anyMatch(it -> it.isAssignableFrom(returnType))) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + simpleClassMethodName + " is not cacheable: " + method.getReturnType());
                    }

                    final CacheSerialization serialization = cacheResultAnno == null ? (kryoParser != null ? CacheSerialization.KRYO : CacheSerialization.JSON)
                            : cacheResultAnno.serialization();

                    if (cacheResultAnno != null) {
                        if (cacheResultAnno.maxLiveTimeMillis() < 0) {
                            throw new UnsupportedOperationException("Invalid 'maxLiveTimeMillis': " + cacheResultAnno.maxLiveTimeMillis()
                                    + " (must be >= 0) in annotation 'CacheResult' on method: " + simpleClassMethodName);
                        }

                        if (cacheResultAnno.maxIdleTimeMillis() < 0) {
                            throw new UnsupportedOperationException("Invalid 'maxIdleTimeMillis': " + cacheResultAnno.maxIdleTimeMillis()
                                    + " (must be >= 0) in annotation 'CacheResult' on method: " + simpleClassMethodName);
                        }

                        if (cacheResultAnno.minSize() < 0 || cacheResultAnno.maxSize() < 0 || cacheResultAnno.minSize() > cacheResultAnno.maxSize()) {
                            throw new UnsupportedOperationException(
                                    "Invalid ('minSize', 'maxSize'): (" + cacheResultAnno.minSize() + ", " + cacheResultAnno.maxSize()
                                            + ") (require 0 <= minSize <= maxSize) in annotation 'CacheResult' on method: " + simpleClassMethodName);
                        }
                    }

                    final Predicate<Object> serializationNotRequired = r -> {
                        final Class<?> cls = r.getClass();
                        // Immutable containers protect their structure, but their elements/values may still be mutable
                        // entities. Copy them on both cache writes and reads so callers cannot modify cached state.
                        return !(r instanceof Collection || r instanceof Map) && !isValuePresentMap.getOrDefault(cls, Fn.alwaysFalse()).test(r)
                                && isImmutableTester.test(cls);
                    };

                    // A JSON round trip through the runtime class alone erases generic type arguments (a cached
                    // List<User> came back as a List of Maps), so resolve the declared element/key/value types.
                    com.landawn.abacus.type.Type<?> tmpDeclaredReturnType = null;

                    if (serialization == CacheSerialization.JSON && method.getGenericReturnType() instanceof ParameterizedType) {
                        try {
                            tmpDeclaredReturnType = com.landawn.abacus.type.Type.of(method.getGenericReturnType());
                        } catch (final RuntimeException e) {
                            // Unresolvable type arguments (e.g. type variables): fall back to the runtime class below.
                        }
                    }

                    final com.landawn.abacus.type.Type<?> declaredReturnType = tmpDeclaredReturnType;
                    // The declared value type of a single-value holder return type (Optional<User> -> User), if any.
                    final com.landawn.abacus.type.Type<?> declaredHeldValueType = declaredReturnType != null && N.size(declaredReturnType.parameterTypes()) == 1
                            ? declaredReturnType.parameterTypes().get(0)
                            : null;

                    final Function<Object, Object> cloneFunc = switch (serialization) {
                        case NONE -> Fn.identity();
                        case KRYO -> r -> {
                            if (serializationNotRequired.test(r)) {
                                return r;
                            }

                            if (kryoParser == null) {
                                throw new UnsupportedOperationException("Kryo is not available for cache serialization: " + KryoUnavailability.REASON
                                        + " Use CacheSerialization.JSON when its copy semantics are suitable.");
                            }

                            // Enter the same Kryo graph at the original root: copying a temporary mutable container
                            // loses references back to the immutable root and cannot handle nested immutable containers.
                            return kryoParser.deepCopy((Object) new KryoCacheCopy(r));
                        };
                        case JSON -> r -> {
                            if (serializationNotRequired.test(r)) {
                                return r;
                            }

                            if (r instanceof Collection || r instanceof Map || r instanceof Object[] || r instanceof u.Optional || r instanceof u.Nullable
                                    || r instanceof java.util.Optional) {
                                final Map<Object, Boolean> sortedContainers = new java.util.IdentityHashMap<>();
                                if (requiresJsonContainerTreeCopy(r, sortedContainers)) {
                                    return copyJsonContainerTree(r, declaredReturnType, sortedContainers, true);
                                }
                            }

                            // A value holder is copied by its value and re-wrapped. Built-in methods declare holders over type
                            // variables (Optional<T> get(ID), <V> Nullable<V> queryForSingleValue(...)), so a round trip of the
                            // whole holder read the value back untyped (a cached entity came back as its JSON String), and it
                            // also turned a Nullable holding a present null (a row with a SQL NULL) into an empty one (no row).
                            if (r instanceof final u.Optional<?> opt) {
                                return opt.isPresent() ? u.Optional.of(copyHeldValue(opt.get(), declaredHeldValueType)) : r;
                            } else if (r instanceof final u.Nullable<?> nullable) {
                                return nullable.isPresent() ? u.Nullable.of(copyHeldValue(nullable.get(), declaredHeldValueType)) : r;
                            } else if (r instanceof final java.util.Optional<?> jdkOpt) {
                                return jdkOpt.isPresent() ? java.util.Optional.of(copyHeldValue(jdkOpt.get(), declaredHeldValueType)) : r;
                            }

                            // A root java.util.Date is written in its whole-second string form: a JSON round trip dropped its
                            // milliseconds (see isSecondPrecisionJsonDate).
                            if (isSecondPrecisionJsonDate(r)) {
                                return ((java.util.Date) r).clone();
                            }

                            // A Timestamp lost its sub-millisecond nanos, and some java.time types can't be parsed at all.
                            if (isExactlyCopiedJsonDateTime(r)) {
                                return copyJsonDateTime(r);
                            }

                            // A Dataset is written with its column types: without them its values were read back untyped
                            // (e.g. a BIGINT id came back as an Integer).
                            final String json = r instanceof Dataset
                                    ? jsonParser.serialize(r, com.landawn.abacus.parser.JsonSerConfig.create().setWriteColumnType(true))
                                    : jsonParser.serialize(r);

                            // Built-in DAO methods declare generic returns such as List<T> whose type variables resolve
                            // to Object only, so an unresolved element (or key/value) type is derived from the runtime
                            // result instead of collapsing the cached beans into Maps.
                            if (r instanceof final Collection<?> c) {
                                final com.landawn.abacus.type.Type<?> elementType = resolvedOrRuntimeType(
                                        declaredReturnType != null && declaredReturnType.isCollection() ? declaredReturnType.elementType() : null, c);

                                if (elementType != null) {
                                    return jsonParser.deserialize(json, com.landawn.abacus.parser.JsonDeserConfig.create().setElementType(elementType),
                                            r.getClass());
                                }
                            } else if (r instanceof final Map<?, ?> m) {
                                final boolean isDeclaredMap = declaredReturnType != null && declaredReturnType.isMap()
                                        && N.size(declaredReturnType.parameterTypes()) == 2;
                                final com.landawn.abacus.type.Type<?> keyType = resolvedOrRuntimeType(
                                        isDeclaredMap ? declaredReturnType.parameterTypes().get(0) : null, m.keySet());
                                final com.landawn.abacus.type.Type<?> valueType = resolvedOrRuntimeType(
                                        isDeclaredMap ? declaredReturnType.parameterTypes().get(1) : null, m.values());

                                if (keyType != null || valueType != null) {
                                    final com.landawn.abacus.parser.JsonDeserConfig config = com.landawn.abacus.parser.JsonDeserConfig.create();

                                    if (keyType != null) {
                                        config.setMapKeyType(keyType);
                                    }

                                    if (valueType != null) {
                                        config.setMapValueType(valueType);
                                    }

                                    return jsonParser.deserialize(json, config, r.getClass());
                                }
                            } else if (declaredReturnType != null && declaredReturnType.javaType().isAssignableFrom(r.getClass())) {
                                return jsonParser.deserialize(json, declaredReturnType);
                            }

                            return jsonParser.deserialize(json, r.getClass());
                        };
                    };

                    final Throwables.BiFunction<DaoBase, Object[], ?, Throwable> uncachedCall = call;
                    // Only an opted-in method pays for the suppression scope; every other method invokes its body directly.
                    final Throwables.BiFunction<DaoBase, Object[], ?, Throwable> temp = isAnnotatedCacheResult && !cacheResultAnno.cacheNestedResults()
                            ? (proxy, args) -> {
                                if (Boolean.TRUE.equals(suppressInheritedResultCaching_TL.get())) {
                                    return uncachedCall.apply(proxy, args);
                                }

                                suppressInheritedResultCaching_TL.set(true);

                                try {
                                    return uncachedCall.apply(proxy, args);
                                } finally {
                                    suppressInheritedResultCaching_TL.remove();
                                }
                            }
                            : uncachedCall;

                    call = (proxy, args) -> {
                        final boolean cacheResultEnabled = isAnnotatedCacheResult
                                && (methodCacheResultAnno != null || !Boolean.TRUE.equals(suppressInheritedResultCaching_TL.get()));
                        // Read on use: a cache created by createDao is only set once the DAO has passed every validation.
                        final Jdbc.DaoCache daoCacheToUseInMethod = cacheResultEnabled || isAnnotatedRefreshResult ? daoCacheRef.get() : null;
                        final Jdbc.DaoCache localThreadCache = JdbcUtil.localThreadCache_TL.get();
                        final boolean isLocalThreadCacheEnabled = isLocalThreadCacheableQueryMethod && localThreadCache != null;
                        final boolean isRefreshLocalThreadCacheRequired = isUpdateMethod && localThreadCache != null;

                        final String cacheKey = cacheResultEnabled || isAnnotatedRefreshResult || isLocalThreadCacheEnabled || isRefreshLocalThreadCacheRequired
                                ? JdbcUtil.createCacheKey(tableName, fullClassMethodName, daoId, args, daoLogger)
                                : null;

                        Object result = null;

                        if (Strings.isNotEmpty(cacheKey)) {
                            if (isLocalThreadCacheEnabled) {
                                result = localThreadCache.get(cacheKey, proxy, args, methodSignature);
                            } else if (cacheResultEnabled) {
                                result = daoCacheToUseInMethod.get(cacheKey, proxy, args, methodSignature);
                            }
                        }

                        if (result != null) {
                            return cloneFunc.apply(result);
                        }

                        if (isAnnotatedRefreshResult || isRefreshLocalThreadCacheRequired) {
                            Throwable invocationFailure = null;

                            try {
                                result = temp.apply(proxy, args);
                            } catch (final Throwable t) {
                                invocationFailure = t;
                                throw t;
                            } finally {
                                // Cache-key serialization is optional for query caching, but it must never
                                // suppress write invalidation. If an argument cannot be serialized, retain the
                                // stable method/table/DAO portion so built-in and custom caches can still identify
                                // the affected table (and custom caches still receive args/methodSignature).
                                final String refreshCacheKey = Strings.isNotEmpty(cacheKey) ? cacheKey
                                        : Strings.concat(fullClassMethodName, JdbcUtil.CACHE_KEY_SEPARATOR, tableName, JdbcUtil.CACHE_KEY_SEPARATOR, daoId,
                                                JdbcUtil.CACHE_KEY_SEPARATOR);

                                Throwable refreshFailure = null;

                                if (isRefreshLocalThreadCacheRequired) {
                                    try {
                                        localThreadCache.update(refreshCacheKey, result, proxy, args, methodSignature);
                                    } catch (final Throwable t) {
                                        if (t != invocationFailure) {
                                            refreshFailure = t;
                                        }
                                    }
                                }

                                if (isAnnotatedRefreshResult) {
                                    try {
                                        daoCacheToUseInMethod.update(refreshCacheKey, result, proxy, args, methodSignature);
                                    } catch (final Throwable t) {
                                        if (t == invocationFailure) {
                                            // The invocation failure is already being propagated. Do not attach it
                                            // to another refresh failure and create a circular suppressed chain.
                                        } else if (refreshFailure == null) {
                                            refreshFailure = t;
                                        } else if (refreshFailure != t) {
                                            refreshFailure.addSuppressed(t);
                                        }
                                    }
                                }

                                if (refreshFailure != null) {
                                    if (invocationFailure == null) {
                                        throw refreshFailure;
                                    } else if (invocationFailure != refreshFailure) {
                                        invocationFailure.addSuppressed(refreshFailure);
                                    }
                                }
                            }
                        } else {
                            result = temp.apply(proxy, args);
                        }

                        if (Strings.isNotEmpty(cacheKey) && result != null) {
                            if (isLocalThreadCacheEnabled) {
                                localThreadCache.put(cacheKey, cloneFunc.apply(result), proxy, args, methodSignature);
                            } else if (cacheResultEnabled) {
                                // Map and object-array results are bounded too: they skipped minSize/maxSize, so maxSize did not
                                // guard the memory of a large @MappedByKey map.
                                final int resultSize = cacheResultSize(result);

                                if (resultSize < 0 || (resultSize >= cacheResultAnno.minSize() && resultSize <= cacheResultAnno.maxSize())) {
                                    daoCacheToUseInMethod.put(cacheKey, cloneFunc.apply(result), cacheLiveTime, cacheMaxIdleTime, proxy, args, methodSignature);
                                }
                            }
                        }

                        return result;
                    };
                }

                final List<Tuple2<Jdbc.Handler, Boolean>> handlerList = Stream.of(method.getAnnotations())
                        .filter(anno -> anno.annotationType().equals(Handler.class) || anno.annotationType().equals(Handlers.class))
                        .flatmap(anno -> anno.annotationType().equals(Handler.class) ? N.asList((Handler) anno) : N.toList(((Handlers) anno).value()))
                        .prepend(Stream.of(daoClassHandlerList).filter(h -> Stream.of(h.filter()).anyMatch(filterByMethodName)))
                        .map(handlerAnno -> {
                            final Jdbc.Handler<?> handler;
                            if (Strings.isNotEmpty(handlerAnno.qualifier())) {
                                // Resolve lazily: a Spring lookup can create a prototype that the DAO-local handler replaces.
                                handler = daoClassHandlerMap.containsKey(handlerAnno.qualifier()) ? daoClassHandlerMap.get(handlerAnno.qualifier())
                                        : HandlerFactory.get(handlerAnno.qualifier());
                            } else {
                                handler = HandlerFactory.getOrCreate(handlerAnno.impl());
                            }

                            return Tuple.of((Jdbc.Handler) handler, handlerAnno.externalCallsOnly());
                        })
                        .onEach(handler -> N.checkArgNotNull(handler._1,
                                "No handler found/registered with qualifier or type in class/method: " + fullClassMethodName))
                        .toList();

                if (N.notEmpty(handlerList)) {
                    final Throwables.BiFunction<DaoBase, Object[], ?, Throwable> temp = call;

                    call = (proxy, args) -> {
                        final boolean isInDaoMethod = isInDaoMethod_TL.get();
                        final int handlerSize = N.size(handlerList);

                        if (isInDaoMethod) {
                            final int[] executedHandlerIndexes = new int[handlerSize];
                            int executedHandlerSize = 0;
                            Object result = null;
                            Throwable failure = null;
                            Throwable afterInvokeFailure = null;

                            for (int i = 0; i < handlerSize; i++) {
                                final Tuple2<Jdbc.Handler, Boolean> tp = handlerList.get(i);

                                if (tp._2) {
                                    continue;
                                }

                                try {
                                    tp._1.beforeInvoke(proxy, args, methodSignature);
                                    executedHandlerIndexes[executedHandlerSize] = i;
                                    executedHandlerSize++;
                                } catch (final Throwable t) {
                                    if (daoLogger.isWarnEnabled()) {
                                        daoLogger.warn(t, "Dao handler beforeInvoke failed(method={}, handlerIndex={})", fullClassMethodName, i);
                                    }

                                    failure = t;
                                    break;
                                }
                            }

                            if (failure == null) {
                                try {
                                    result = temp.apply(proxy, args);
                                } catch (final Throwable t) {
                                    if (daoLogger.isDebugEnabled()) {
                                        daoLogger.debug(t, "Dao method failed before handler afterInvoke(method={})", fullClassMethodName);
                                    }

                                    failure = t;
                                }
                            }

                            for (int i = executedHandlerSize - 1; i >= 0; i--) {
                                final Tuple2<Jdbc.Handler, Boolean> tp = handlerList.get(executedHandlerIndexes[i]);

                                try {
                                    tp._1.afterInvoke(result, proxy, args, methodSignature);
                                } catch (final Throwable t) {
                                    if (daoLogger.isWarnEnabled()) {
                                        daoLogger.warn(t, "Dao handler afterInvoke failed(method={}, handlerIndex={})", fullClassMethodName,
                                                executedHandlerIndexes[i]);
                                    }

                                    if (t == failure) {
                                        // This failure is already propagated; suppressing it on a cleanup failure would create a cycle.
                                    } else if (afterInvokeFailure == null) {
                                        afterInvokeFailure = t;
                                    } else {
                                        if (afterInvokeFailure != t) {
                                            afterInvokeFailure.addSuppressed(t);
                                        }
                                    }
                                }
                            }

                            if (failure != null) {
                                if (afterInvokeFailure != null) {
                                    if (failure != afterInvokeFailure) {
                                        failure.addSuppressed(afterInvokeFailure);
                                    }
                                }

                                throw failure;
                            } else if (afterInvokeFailure != null) {
                                throw afterInvokeFailure;
                            }

                            return result;
                        } else {
                            isInDaoMethod_TL.set(true);

                            try {
                                int executedHandlerSize = 0;
                                Object result = null;
                                Throwable failure = null;
                                Throwable afterInvokeFailure = null;

                                for (int i = 0; i < handlerSize; i++) {
                                    final Tuple2<Jdbc.Handler, Boolean> tp = handlerList.get(i);

                                    try {
                                        tp._1.beforeInvoke(proxy, args, methodSignature);
                                        executedHandlerSize++;
                                    } catch (final Throwable t) {
                                        if (daoLogger.isWarnEnabled()) {
                                            daoLogger.warn(t, "Dao handler beforeInvoke failed(method={}, handlerIndex={})", fullClassMethodName, i);
                                        }

                                        failure = t;
                                        break;
                                    }
                                }

                                if (failure == null) {
                                    try {
                                        result = temp.apply(proxy, args);
                                    } catch (final Throwable t) {
                                        if (daoLogger.isDebugEnabled()) {
                                            daoLogger.debug(t, "Dao method failed before handler afterInvoke(method={})", fullClassMethodName);
                                        }

                                        failure = t;
                                    }
                                }

                                for (int i = executedHandlerSize - 1; i >= 0; i--) {
                                    try {
                                        handlerList.get(i)._1.afterInvoke(result, proxy, args, methodSignature);
                                    } catch (final Throwable t) {
                                        if (daoLogger.isWarnEnabled()) {
                                            daoLogger.warn(t, "Dao handler afterInvoke failed(method={}, handlerIndex={})", fullClassMethodName, i);
                                        }

                                        if (t == failure) {
                                            // This failure is already propagated; suppressing it on a cleanup failure would create a cycle.
                                        } else if (afterInvokeFailure == null) {
                                            afterInvokeFailure = t;
                                        } else {
                                            if (afterInvokeFailure != t) {
                                                afterInvokeFailure.addSuppressed(t);
                                            }
                                        }
                                    }
                                }

                                if (failure != null) {
                                    if (afterInvokeFailure != null) {
                                        if (failure != afterInvokeFailure) {
                                            failure.addSuppressed(afterInvokeFailure);
                                        }
                                    }

                                    throw failure;
                                } else if (afterInvokeFailure != null) {
                                    throw afterInvokeFailure;
                                }

                                return result;
                            } finally {
                                isInDaoMethod_TL.remove();
                            }
                        }
                    };
                }

                if (isAnnotatedCacheResult) {
                    hasCacheResult.set(true);
                }

                if (isAnnotatedRefreshResult) {
                    hasRefreshCache.set(true);
                }

                // TODO maybe it's not a good idea to support Cache in general Dao which supports update/delete operations.
                // Base this check on the current method's own annotation flags (not the cumulative atomics): the loop runs
                // in parallel, so reading the shared flags here could report an unrelated method in the message and be
                // non-deterministic about which method is named.
                if ((isAnnotatedRefreshResult || isAnnotatedCacheResult) && !DaoUtil.isCacheable(daoInterface)) {
                    throw new UnsupportedOperationException(
                            "Cache is only supported for methods declared in Cacheable DAOs (NonUpdate/ReadOnly and their Unchecked variants), not supported for method: "
                                    + fullClassMethodName);
                }
            }

            methodInvokerMap.put(method, call);

            if (daoLogger.isDebugEnabled()) {
                daoLogger.debug("Registered Dao method invoker(method={}, nonDBOperation={})", fullClassMethodName, isNonDBOperation);
            }
        });

        if (hasRefreshCache.get() && !hasCacheResult.get()) {
            throw new UnsupportedOperationException("Class: " + daoInterface
                    + " or its super interfaces or methods are annotated by @RefreshCache, but none of them is annotated with @CacheResult. "
                    + "Please remove the unnecessary @RefreshCache annotations or Add @CacheResult annotation if it's really needed.");
        }

        final Throwables.TriFunction<DaoBase, Method, Object[], ?, Throwable> proxyInvoker = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                final String methodName = method.getName();

                if ("toString".equals(methodName)) {
                    return daoInterface.getSimpleName() + "Proxy@" + Integer.toHexString(System.identityHashCode(proxy));
                } else if ("hashCode".equals(methodName)) {
                    return System.identityHashCode(proxy);
                } else if ("equals".equals(methodName)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
            }

            final Throwables.BiFunction<DaoBase, Object[], ?, Throwable> invoker = methodInvokerMap.get(method);

            if (invoker == null) {
                throw new UnsupportedOperationException(
                        "No method invoker found for Dao method: " + ClassUtil.getCanonicalClassName(method.getDeclaringClass()) + "." + method.getName());
            }

            return invoker.apply(proxy, args);
        };
        final Class<TD>[] interfaceClasses = N.asArray(daoInterface);

        final InvocationHandler h = (proxy, method, args) -> {
            final boolean shouldLogInvocation = daoLogger.isDebugEnabled() && !nonDBOperationSet.contains(method);

            if (shouldLogInvocation) {
                daoLogger.debug("Invoking Dao method(method={}, argCount={})", method.getName(), args == null ? 0 : args.length);
            }

            try {
                return proxyInvoker.apply((DaoBase) proxy, method, args);
            } catch (final Throwable t) {
                if (shouldLogInvocation) {
                    daoLogger.debug(t, "Dao method invocation failed(method={})", method.getName());
                }

                throw t;
            }
        };

        final TD daoInstance = N.newProxyInstance(interfaceClasses, h);

        if (joinBeanInfo != null) {
            // Join plans are built per dialect on first use. Build this DAO's dialect now, as the last check after every
            // validation above and before a cache is created, so a join whose generated SQL cannot be planned fails DAO
            // creation, as documented, instead of the first join operation.
            for (final JoinInfo joinInfo : joinBeanInfo.values()) {
                joinInfo.selectSqlPlan(parameterizedDsl);
            }
        }

        // The DAO can no longer be rejected and the proxy is not yet published: create the cache now (see daoCacheRef).
        if (isDaoCacheCreatedHere) {
            daoCacheRef.set(daoClassCacheAnno == null ? Jdbc.DaoCache.create(capacity, evictDelay) // annotation members can't be null: impl() defaults to DefaultDaoCache
                    : ClassUtil.invokeConstructor(daoCacheConstructor, capacity, evictDelay));
        }

        if (joinBeanInfo != null) {
            // The registered proxy keeps joinBeanInfo; let its per-call metadata lookups reuse that map while it is pooled.
            // Retained before publication, so that its release on eviction (which follows publication) can never precede it.
            JoinInfo.retainDaoJoinInfo(daoInterface, entityClass, tableName, joinBeanInfo);
        }

        final PooledDao pooledDao = new PooledDao(daoCacheKey, daoInstance, daoInterface, entityClass, tableName, joinBeanInfo,
                isDaoCacheCreatedHere ? daoCacheRef.get() : null, daoLogger);
        final PooledDao existingPooledDao;
        List<PooledDao> evictedDaos = null;

        synchronized (daoPoolQueue) {
            existingPooledDao = daoPool.putIfAbsent(daoCacheKey, pooledDao);

            if (existingPooledDao == null) {
                // Evict before queueing the new entry so the proxy this caller is about to return is not the one evicted.
                evictedDaos = evictDaoPoolOverCapacity();
                daoPoolQueue.addLast(pooledDao);
            }
        }

        if (existingPooledDao != null) {
            if (joinBeanInfo != null) {
                JoinInfo.releaseDaoJoinInfo(daoInterface, entityClass, tableName, joinBeanInfo);
            }

            if (isDaoCacheCreatedHere) {
                // This proxy is discarded, so nothing can use the cache just created for it.
                releaseDiscardedDaoCache(daoCacheRef.get(), daoLogger);
            }

            markRecentlyUsed(existingPooledDao);

            if (daoLogger.isDebugEnabled()) {
                daoLogger.debug("Discarding concurrently created Dao proxy(interface={}, targetTableName={}); using cached instance", daoClassName,
                        targetTableName);
            }

            return (TD) existingPooledDao.dao;
        }

        if (evictedDaos != null) {
            for (final PooledDao evictedDao : evictedDaos) {
                releaseEvictedDao(evictedDao);
            }
        }

        synchronized (joinEntityDaoMisses) {
            daoRegistrationVersion++;
            joinEntityDaoMisses.clear();
        }

        daoLogger.info("Created Dao proxy(interface={}, targetTableName={}, methods={})", daoClassName, targetTableName, methodInvokerMap.size());

        return daoInstance;
    }

    /**
     * Returns the element count that {@code @CacheResult(minSize, maxSize)} is compared against: the rows of a
     * {@code Dataset}, the elements of a {@code Collection} or an object array, or the entries of a {@code Map}.
     *
     * @param result the non-null method result
     * @return the element count, or {@code -1} for any other result (including a primitive array such as a
     *         {@code byte[]} column value), which the size bounds do not apply to
     */
    private static int cacheResultSize(final Object result) {
        if (result instanceof final Dataset dataset) {
            return dataset.size();
        } else if (result instanceof final Collection<?> c) {
            return c.size();
        } else if (result instanceof final Map<?, ?> m) {
            return m.size();
        } else if (result instanceof final Object[] array) {
            return array.length;
        }

        return -1;
    }

    /**
     * Releases a cache that {@code createDao} created for a proxy it then discarded because another thread published an
     * equivalent proxy first, or for a proxy evicted from {@link #daoPool} that has since become unreachable (see
     * {@link #releaseEvictedDao(PooledDao)}). A cache that implements {@link AutoCloseable} is closed: for a {@link Jdbc.DefaultDaoCache}
     * this closes its backing pool, which cancels the eviction task and removes the JVM shutdown hook. Any other cache is
     * left as it is. A release failure is only logged: the proxy published by the other thread is still returned.
     *
     * @param daoCache the cache created for the discarded proxy
     * @param daoLogger the logger of the DAO, used to report a release failure
     */
    private static void releaseDiscardedDaoCache(final Jdbc.DaoCache daoCache, final Logger daoLogger) {
        try {
            if (daoCache instanceof final AutoCloseable closeable) {
                closeable.close();
            }
        } catch (final Exception e) {
            daoLogger.warn(e, "Failed to release the cache created for a discarded Dao proxy");
        }
    }

    /**
     * Marks a {@link #daoPool} entry as used since the previous eviction scan, giving it a second chance.
     *
     * @param pooledDao the entry that was just looked up
     */
    private static void markRecentlyUsed(final PooledDao pooledDao) {
        // Read first so a hot entry's cache line is not rewritten by every hit.
        if (!pooledDao.recentlyUsed) {
            pooledDao.recentlyUsed = true;
        }
    }

    /**
     * Evicts queued entries until {@link #daoPool} is within its capacity. Must be called while holding the
     * {@link #daoPoolQueue} monitor. A recently used entry is requeued once instead of evicted; the number of second
     * chances is limited to one pass over the queue so that concurrent hits cannot prolong the scan.
     *
     * @return the evicted entries, to be released by {@link #releaseEvictedDao(PooledDao)} outside the monitor, or
     *         {@code null} if none was evicted
     */
    private static List<PooledDao> evictDaoPoolOverCapacity() {
        List<PooledDao> evicted = null;
        int secondChances = daoPoolQueue.size();

        while (daoPool.size() > daoPoolCapacity && !daoPoolQueue.isEmpty()) {
            final PooledDao eldest = daoPoolQueue.removeFirst();

            if (eldest.recentlyUsed && secondChances-- > 0) {
                eldest.recentlyUsed = false;
                daoPoolQueue.addLast(eldest);
            } else if (daoPool.remove(eldest.key, eldest)) {
                if (evicted == null) {
                    evicted = new ArrayList<>(1);
                }

                evicted.add(eldest);
            }
        }

        if (evicted != null && !daoPoolCapacityWarned) {
            daoPoolCapacityWarned = true;
            JdbcUtil.logger.warn("The DAO proxy pool reached its capacity of {} proxies and evicts the least recently used ones. Create each DAO once and "
                    + "reuse it, with the same DataSource and configuration instances, instead of calling createDao per request", daoPoolCapacity);
        }

        return evicted;
    }

    /**
     * Releases what {@code createDao} registered or allocated for a proxy evicted from {@link #daoPool}. The proxy may
     * still be in use by whoever obtained it, and keeps working: its join DAO lookups no longer return it, and its join
     * metadata is no longer retained (unless another pooled proxy of the same DAO interface, entity class and table
     * retains it), since the proxy holds what it needs itself. A cache that {@code createDao} created for it is closed
     * (see {@link #releaseDiscardedDaoCache(Jdbc.DaoCache, Logger)}) only once the proxy has become unreachable, because
     * a closed cache fails the proxy's cached methods.
     *
     * @param evicted the entry removed from {@link #daoPool}
     */
    private static void releaseEvictedDao(final PooledDao evicted) {
        // Matched by identity rather than by recomputed keys, so the proxy is not called (its targetEntityClass() may be a
        // DAO-defined default method). The map holds at most two entries per pooled proxy. A lookup that found the proxy
        // just before its eviction removes its entry again itself (see getApplicableDaoForJoinEntity).
        joinEntityDaoPool.values().removeIf(dao -> dao == evicted.dao);

        if (evicted.joinBeanInfo != null) {
            JoinInfo.releaseDaoJoinInfo(evicted.daoInterface, evicted.entityClass, evicted.tableName, evicted.joinBeanInfo);
        }

        if (evicted.createdCache instanceof AutoCloseable) {
            // The cleaning action must not reference the proxy, or the proxy could never become unreachable.
            final Jdbc.DaoCache cache = evicted.createdCache;
            final Logger daoLogger = evicted.daoLogger;

            EvictedDaoCacheCleaner.CLEANER.register(evicted.dao, () -> releaseDiscardedDaoCache(cache, daoLogger));
        }

        if (evicted.daoLogger.isDebugEnabled()) {
            evicted.daoLogger.debug("Evicted Dao proxy from the DAO pool(interface={}, targetTableName={})",
                    ClassUtil.getCanonicalClassName(evicted.daoInterface), evicted.key.targetTableName);
        }
    }

    /**
     * Sets the maximum number of DAO proxies kept by the process-wide DAO pool. For tests only.
     *
     * @param capacity the new capacity, applied when the next proxy is registered
     * @return the previous capacity
     */
    static int setDaoPoolCapacity(final int capacity) {
        synchronized (daoPoolQueue) {
            final int previous = daoPoolCapacity;
            daoPoolCapacity = capacity;
            return previous;
        }
    }

    /**
     * Classifies structural container paths requiring comparator-aware or order-preserving copying. Bean
     * properties remain governed by the JSON parser, including its annotation and property-format rules. Identity
     * memoization avoids rescanning shared/deep container trees; a null entry marks an active path, since JSON
     * cannot copy cycles.
     *
     * <p>Besides comparator-bearing sorted containers, plain {@code ImmutableSet}, {@code ImmutableMap} and
     * {@code ImmutableBiMap} instances are flagged: their own factories promise insertion order, which the JSON
     * parser's HashSet/HashMap/BiMap stand-ins for those target classes would replace with hash order. Maps holding a
     * {@code null} key are flagged too: JSON writes map keys as quoted strings, so the parser would read that key back
     * as the String {@code "null"}, or fail to parse it as a typed key. So are maps holding a {@code java.util.Date}
     * key, whose quoted form has whole seconds only, and containers directly holding a value for which
     * {@link #isExactlyCopiedJsonDateTime(Object)} holds (as an element, map key, map value or held value). Leaf values
     * are not memoized; each container's entries are inspected once per copy.</p>
     *
     * @param value the container or value to inspect
     * @param classified per-copy identity map of completed results and active paths
     * @return whether this container tree must be copied by {@code copyJsonContainerTree}: it contains a sorted
     *         map or set with a comparator, a plain immutable set, map or bi-map wrapper, a map with a {@code null}
     *         or {@code java.util.Date} key, or a {@code Timestamp}, {@code Calendar} or {@code java.time} value
     */
    private static boolean requiresJsonContainerTreeCopy(final Object value, final Map<Object, Boolean> classified) {
        if (!(value instanceof Collection || value instanceof Map || value instanceof Object[] || value instanceof u.Optional || value instanceof u.Nullable
                || value instanceof java.util.Optional)) {
            return false;
        }
        if (classified.containsKey(value)) {
            final Boolean result = classified.get(value);
            if (result == null) {
                throw new ParsingException("Cyclic containers are not supported by JSON cache serialization");
            }
            return result;
        }
        classified.put(value, null);
        boolean requiresTreeCopy = value instanceof final java.util.SortedMap<?, ?> map && map.comparator() != null
                || value instanceof final java.util.SortedSet<?> set && set.comparator() != null || value.getClass() == ImmutableSet.class
                || value.getClass() == ImmutableMap.class || value.getClass() == com.landawn.abacus.util.ImmutableBiMap.class;
        if (value instanceof final Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                // JSON writes every map key as a quoted string: a null key came back as the String "null", or failed to
                // parse as a typed key such as an Integer, and a java.util.Date key lost its milliseconds. The tree copy
                // puts such keys back as they are.
                requiresTreeCopy |= entry.getKey() == null || isSecondPrecisionJsonDate(entry.getKey());
                // A Timestamp, Calendar or java.time key or value is put back as it is (see isExactlyCopiedJsonDateTime).
                requiresTreeCopy |= isExactlyCopiedJsonDateTime(entry.getKey()) || isExactlyCopiedJsonDateTime(entry.getValue());
                requiresTreeCopy |= requiresJsonContainerTreeCopy(entry.getKey(), classified);
                requiresTreeCopy |= requiresJsonContainerTreeCopy(entry.getValue(), classified);
            }
        } else if (value instanceof final Collection<?> collection) {
            for (final Object element : collection) {
                requiresTreeCopy |= isExactlyCopiedJsonDateTime(element);
                requiresTreeCopy |= requiresJsonContainerTreeCopy(element, classified);
            }
        } else if (value instanceof final Object[] array) {
            for (final Object element : array) {
                requiresTreeCopy |= isExactlyCopiedJsonDateTime(element);
                requiresTreeCopy |= requiresJsonContainerTreeCopy(element, classified);
            }
        } else if (value instanceof final u.Optional<?> optional) {
            requiresTreeCopy = optional.isPresent()
                    && (isExactlyCopiedJsonDateTime(optional.get()) || requiresJsonContainerTreeCopy(optional.get(), classified));
        } else if (value instanceof final u.Nullable<?> nullable) {
            requiresTreeCopy = nullable.isPresent()
                    && (isExactlyCopiedJsonDateTime(nullable.get()) || requiresJsonContainerTreeCopy(nullable.get(), classified));
        } else if (value instanceof final java.util.Optional<?> optional) {
            requiresTreeCopy = optional.isPresent()
                    && (isExactlyCopiedJsonDateTime(optional.get()) || requiresJsonContainerTreeCopy(optional.get(), classified));
        }
        classified.put(value, requiresTreeCopy);
        return requiresTreeCopy;
    }

    /**
     * Copies only container paths containing sorted nodes or plain immutable set/map wrappers; other values retain
     * the parser's declared-type and Object/default-number behavior. Standard/library containers receive the
     * source comparator before parsing: a natural-order intermediate can reject valid non-Comparable keys or
     * irreversibly merge comparator-distinct entries. Custom sorted classes retain their constructor-created
     * comparator and validate the copied contents. Plain {@code ImmutableSet}, {@code ImmutableMap} and
     * {@code ImmutableBiMap} copies are built on insertion-ordered backing containers so that they iterate like
     * their source. A map with a {@code null} key is copied entry by entry, keeping that key {@code null}; so is a
     * map with a {@code java.util.Date} key, keeping its milliseconds. A {@code Timestamp}, {@code Calendar} or
     * {@code java.time} value is copied exactly by {@link #copyJsonDateTime(Object)} in every position, so a container
     * holding one is copied element by element (entry by entry).
     *
     * @param value the value to copy
     * @param declaredType the resolved type at this position, or null for the JSON default type
     * @param sortedContainers the classification for this source container tree (sorted nodes, plain
     *        immutable set/map wrappers and maps with a {@code null} or {@code java.util.Date} key are flagged
     *        {@code true})
     * @param inferTypes whether this is the root result, retaining its runtime class and common-runtime-type fallback
     * @return the JSON copy, retaining source comparators for supported standard/library containers and
     *         constructor-created comparators for custom sorted classes
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object copyJsonContainerTree(final Object value, final com.landawn.abacus.type.Type<?> declaredType,
            final Map<Object, Boolean> sortedContainers, final boolean inferTypes) {
        if (isExactlyCopiedJsonDateTime(value)) {
            return copyJsonDateTime(value);
        }

        if (value == null || !Boolean.TRUE.equals(sortedContainers.get(value))) {
            // A String reads back as an equal String in a String or untyped position; skip the round trip, which a map
            // copied entry by entry (e.g. a row holding a Timestamp) would otherwise pay for each such value.
            if (value instanceof String && (declaredType == null || declaredType.isObject() || declaredType.javaType() == String.class)) {
                return value;
            }

            // Parse in element/value context. Root deserialization does not accept every scalar type, and
            // Object positions must keep JSON's normal number and bean-to-map conversions.
            final com.landawn.abacus.parser.JsonDeserConfig config = com.landawn.abacus.parser.JsonDeserConfig.create();
            if (declaredType != null) {
                config.setElementType(declaredType);
            }
            return jsonParser.deserialize(jsonParser.serialize(java.util.Collections.singletonList(value)), config, List.class).get(0);
        }

        if (value instanceof u.Optional || value instanceof u.Nullable || value instanceof java.util.Optional) {
            final Object held = value instanceof u.Optional ? ((u.Optional<?>) value).get()
                    : value instanceof u.Nullable ? ((u.Nullable<?>) value).get() : ((java.util.Optional<?>) value).get();
            final com.landawn.abacus.type.Type<?> heldType = declaredType != null && N.size(declaredType.parameterTypes()) == 1
                    ? declaredType.parameterTypes().get(0)
                    : null;
            final Object copy = copyJsonContainerTree(held, resolvedOrRuntimeType(heldType, java.util.Collections.singletonList(held)), sortedContainers,
                    false);
            return value instanceof u.Optional ? u.Optional.of(copy) : value instanceof u.Nullable ? u.Nullable.of(copy) : java.util.Optional.of(copy);
        }
        if (value instanceof final Object[] array) {
            if (!inferTypes && (declaredType == null || declaredType.isObject())) {
                final List<Object> copy = new ArrayList<>(array.length);
                for (final Object element : array) {
                    copy.add(copyJsonContainerTree(element, null, sortedContainers, false));
                }
                return copy;
            }
            // Nested arrays are constructed from their declared component type. A Map[] slot can hold an
            // ImmutableSortedMap[] source, but comparator-aware children may be ordinary TreeMap copies.
            final Class<?> componentClass = !inferTypes && declaredType != null && declaredType.isArray() ? declaredType.javaType().getComponentType()
                    : value.getClass().getComponentType();
            final Object[] copy = (Object[]) java.lang.reflect.Array.newInstance(componentClass, array.length);
            com.landawn.abacus.type.Type<?> elementType = declaredType != null && declaredType.isArray() ? declaredType.elementType()
                    : N.typeOf(value.getClass().getComponentType());
            if (inferTypes && (elementType == null || elementType.isObject())) {
                // The root copy keeps its runtime component type, so unresolved elements must be parsed as
                // something storable in it: a declared Object[] holding a TreeMap[] would otherwise read a
                // natural-order member back as a Map and fail to store it.
                elementType = resolvedOrRuntimeType(elementType, java.util.Arrays.asList(array));
                if (elementType == null || elementType.isObject()) {
                    elementType = N.typeOf(componentClass);
                }
            }
            for (int i = 0; i < array.length; i++) {
                copy[i] = copyJsonContainerTree(array[i], elementType, sortedContainers, false);
            }
            return copy;
        }

        final Class<?> runtimeClass = value.getClass();
        // A nested declared List/Map can contain a private JDK view. The parser normally constructs the
        // declared interface's default implementation, rather than trying to instantiate that view class.
        final boolean declaredContainer = declaredType != null && (declaredType.isCollection() || declaredType.isMap());
        final boolean untypedChild = !inferTypes && (declaredType == null || declaredType.isObject());
        // Untyped non-sorted collections, including plain ImmutableSet values,
        // use the JSON parser's default List representation.
        final Class<?> declaredContainerClass = !inferTypes && declaredContainer ? declaredType.javaType()
                : untypedChild && !(value instanceof java.util.SortedSet || value instanceof java.util.SortedMap)
                        ? value instanceof Collection ? List.class : Map.class
                        : runtimeClass;
        // Immutable sorted wrappers are also valid values of their immutable base declarations. Keep that
        // compatible sorted family, since a plain ImmutableMap/Set has no place to retain a comparator. A plain
        // sorted runtime container (e.g. a TreeMap under a declared ImmutableMap) is not assignable to the base,
        // so it is rebuilt as the base's navigable member: an empty ImmutableMap/Set cannot be populated.
        final Class<?> containerClass;
        if (value instanceof java.util.SortedMap && declaredContainerClass == ImmutableMap.class) {
            containerClass = declaredContainerClass.isAssignableFrom(runtimeClass) ? runtimeClass : com.landawn.abacus.util.ImmutableNavigableMap.class;
        } else if (value instanceof java.util.SortedSet
                && (declaredContainerClass == ImmutableSet.class || declaredContainerClass == com.landawn.abacus.util.ImmutableCollection.class)) {
            containerClass = declaredContainerClass.isAssignableFrom(runtimeClass) ? runtimeClass : com.landawn.abacus.util.ImmutableNavigableSet.class;
        } else {
            containerClass = declaredContainerClass;
        }
        if (value instanceof final Collection<?> source) {
            com.landawn.abacus.type.Type<?> elementType = declaredType != null && declaredType.isCollection() ? declaredType.elementType() : null;
            if (inferTypes || source instanceof java.util.SortedSet) {
                elementType = resolvedOrRuntimeType(elementType, source);
            }
            final com.landawn.abacus.parser.JsonDeserConfig config = com.landawn.abacus.parser.JsonDeserConfig.create();
            if (elementType != null) {
                config.setElementType(elementType);
            }
            final Collection copy;
            boolean verifySortedCopy = false;
            if (source instanceof final java.util.SortedSet<?> sorted) {
                final java.util.Comparator comparator = sorted.comparator();
                if (containerClass == com.landawn.abacus.util.ImmutableNavigableSet.class || containerClass == com.landawn.abacus.util.ImmutableSortedSet.class
                        || containerClass.isAssignableFrom(java.util.TreeSet.class) || untypedChild) {
                    copy = new java.util.TreeSet(comparator);
                } else if (containerClass == java.util.concurrent.ConcurrentSkipListSet.class) {
                    copy = new java.util.concurrent.ConcurrentSkipListSet(comparator);
                } else {
                    copy = (Collection) jsonParser.deserialize("[]", config, containerClass);
                    verifySortedCopy = true;
                }
            } else if (ImmutableList.class.isAssignableFrom(containerClass) || containerClass == com.landawn.abacus.util.ImmutableCollection.class) {
                copy = new ArrayList<>(source.size());
            } else if (containerClass == ImmutableSet.class) {
                // ImmutableSet's own factories back onto a LinkedHashSet and promise insertion order, so the copy
                // keeps the source's current iteration order instead of reordering it by hash code.
                copy = N.newLinkedHashSet(source.size());
            } else {
                copy = (Collection) jsonParser.deserialize("[]", config, containerClass);
            }

            if (source.stream().noneMatch(element -> Boolean.TRUE.equals(sortedContainers.get(element)) || isExactlyCopiedJsonDateTime(element))) {
                jsonParser.parseInto(jsonParser.serialize(source), config, copy);
            } else {
                for (final Object element : source) {
                    copy.add(copyJsonContainerTree(element, elementType, sortedContainers, false));
                }
            }
            if (verifySortedCopy) {
                verifyJsonSortedCopy(source, copy, ((java.util.SortedSet<?>) source).comparator(), containerClass);
            }
            if (containerClass == com.landawn.abacus.util.ImmutableNavigableSet.class) {
                return com.landawn.abacus.util.ImmutableNavigableSet.wrap((java.util.NavigableSet) copy);
            } else if (containerClass == com.landawn.abacus.util.ImmutableSortedSet.class) {
                return com.landawn.abacus.util.ImmutableSortedSet.wrap((java.util.SortedSet) copy);
            } else if (ImmutableList.class.isAssignableFrom(containerClass)) {
                return ImmutableList.wrap((List) copy);
            } else if (containerClass == ImmutableSet.class) {
                return ImmutableSet.wrap((Set) copy);
            } else if (containerClass == com.landawn.abacus.util.ImmutableCollection.class) {
                return com.landawn.abacus.util.ImmutableCollection.wrap(copy);
            }
            return copy;
        }

        final Map<?, ?> source = (Map<?, ?>) value;
        final boolean declaredMap = declaredType != null && declaredType.isMap() && N.size(declaredType.parameterTypes()) == 2;
        com.landawn.abacus.type.Type<?> keyType = declaredMap ? declaredType.parameterTypes().get(0) : null;
        com.landawn.abacus.type.Type<?> valueType = declaredMap ? declaredType.parameterTypes().get(1) : null;
        if (inferTypes || source instanceof java.util.SortedMap) {
            keyType = resolvedOrRuntimeType(keyType, source.keySet());
        }
        if (inferTypes) {
            valueType = resolvedOrRuntimeType(valueType, source.values());
        }
        final com.landawn.abacus.parser.JsonDeserConfig config = com.landawn.abacus.parser.JsonDeserConfig.create();
        if (keyType != null) {
            config.setMapKeyType(keyType);
        }
        if (valueType != null) {
            config.setMapValueType(valueType);
        }
        final Map copy;
        boolean verifySortedCopy = false;
        if (source instanceof final java.util.SortedMap<?, ?> sorted) {
            final java.util.Comparator comparator = sorted.comparator();
            if (containerClass == com.landawn.abacus.util.ImmutableNavigableMap.class || containerClass == com.landawn.abacus.util.ImmutableSortedMap.class
                    || containerClass.isAssignableFrom(java.util.TreeMap.class) || untypedChild) {
                copy = new java.util.TreeMap(comparator);
            } else if (containerClass.isAssignableFrom(java.util.concurrent.ConcurrentSkipListMap.class)) {
                copy = new java.util.concurrent.ConcurrentSkipListMap(comparator);
            } else {
                copy = (Map) jsonParser.deserialize("{}", config, containerClass);
                verifySortedCopy = true;
            }
        } else if (containerClass == ImmutableMap.class) {
            // ImmutableMap's and ImmutableBiMap's own factories back onto LinkedHashMaps and promise insertion
            // order, so the copies keep the source's current entry order instead of reordering it by hash code.
            copy = N.newLinkedHashMap(source.size());
        } else if (containerClass == com.landawn.abacus.util.ImmutableBiMap.class) {
            copy = new com.landawn.abacus.util.BiMap(Suppliers.ofLinkedHashMap(), Suppliers.ofLinkedHashMap());
        } else {
            copy = (Map) jsonParser.deserialize("{}", config, containerClass);
        }

        // A HashMap-like map, or a sorted map with a null-friendly comparator, can hold a real null key, which JSON's
        // quoted map-key syntax would change to the String "null" (or fail to parse as a typed key), so such a map is
        // copied entry by entry, in iteration order, with the null key put back as is. A java.util.Date key, whose
        // quoted form has whole seconds only, is copied the same way (see copyJsonMapKey), and so is a map with a
        // Timestamp, Calendar or java.time key or value (see isExactlyCopiedJsonDateTime).
        final boolean hasNullOrDateKey = source.keySet().stream().anyMatch(key -> key == null || isSecondPrecisionJsonDate(key));
        if (!hasNullOrDateKey && source.entrySet()
                .stream()
                .noneMatch(entry -> Boolean.TRUE.equals(sortedContainers.get(entry.getKey())) || Boolean.TRUE.equals(sortedContainers.get(entry.getValue()))
                        || isExactlyCopiedJsonDateTime(entry.getKey()) || isExactlyCopiedJsonDateTime(entry.getValue()))) {
            jsonParser.parseInto(jsonParser.serialize(source), config, copy);
        } else {
            final com.landawn.abacus.parser.JsonDeserConfig keyConfig = com.landawn.abacus.parser.JsonDeserConfig.create();
            if (keyType != null) {
                keyConfig.setMapKeyType(keyType);
            }
            for (final Map.Entry<?, ?> entry : source.entrySet()) {
                final Object key = entry.getKey() == null ? null
                        : Boolean.TRUE.equals(sortedContainers.get(entry.getKey())) ? copyJsonContainerTree(entry.getKey(), keyType, sortedContainers, false)
                                : copyJsonMapKey(entry.getKey(), keyConfig);
                copy.put(key, copyJsonContainerTree(entry.getValue(), valueType, sortedContainers, false));
            }
        }
        if (verifySortedCopy) {
            verifyJsonSortedCopy(source.keySet(), copy.keySet(), ((java.util.SortedMap<?, ?>) source).comparator(), containerClass);
        }
        if (containerClass == com.landawn.abacus.util.ImmutableNavigableMap.class) {
            return com.landawn.abacus.util.ImmutableNavigableMap.wrap((java.util.NavigableMap) copy);
        } else if (containerClass == com.landawn.abacus.util.ImmutableSortedMap.class) {
            return com.landawn.abacus.util.ImmutableSortedMap.wrap((java.util.SortedMap) copy);
        } else if (containerClass == ImmutableMap.class) {
            return ImmutableMap.wrap(copy);
        } else if (containerClass == com.landawn.abacus.util.ImmutableBiMap.class) {
            return com.landawn.abacus.util.ImmutableBiMap.wrap((com.landawn.abacus.util.BiMap) copy);
        }
        return copy;
    }

    /**
     * Verifies a custom sorted class's current JSON snapshot without assuming comparator identity or equality.
     * This does not establish comparator equivalence for values outside the snapshot.
     *
     * @param source the source keys or set elements in iteration order
     * @param copy the copied keys or set elements in iteration order
     * @param comparator the source comparator, or null for natural ordering
     * @param containerClass the reconstructed container class for diagnostics
     * @throws UnsupportedOperationException if copying changes the size, comparison keys or order, or the
     *         source comparator cannot compare a copied value
     */
    @SuppressWarnings("unchecked")
    private static void verifyJsonSortedCopy(final Collection<?> source, final Collection<?> copy, final java.util.Comparator<?> comparator,
            final Class<?> containerClass) {
        // A constructor can create an equivalent comparator with its own mutable owner state. Retain that state
        // instead of injecting the source comparator. Compare corresponding keys as well as size: a constructor's
        // prepopulated key could otherwise conceal an entry collapsed by a different comparison policy.
        if (source.size() != copy.size()) {
            throw new UnsupportedOperationException("JSON cache copying changed the size of sorted container class: " + containerClass.getName());
        }
        final Iterator<?> copiedElements = copy.iterator();
        for (final Object sourceElement : source) {
            final Object copiedElement = copiedElements.next();
            final int comparison;
            try {
                comparison = comparator == null ? ((Comparable<Object>) sourceElement).compareTo(copiedElement)
                        : ((java.util.Comparator<Object>) comparator).compare(sourceElement, copiedElement);
            } catch (final RuntimeException e) {
                throw new UnsupportedOperationException("JSON cache copying cannot verify the order of sorted container class: " + containerClass.getName(), e);
            }
            if (comparison != 0) {
                throw new UnsupportedOperationException(
                        "JSON cache copying changed the order or keys/elements of sorted container class: " + containerClass.getName());
            }
        }
    }

    /**
     * Copies a key in map context, where JSON quotes scalar keys by default. Reading the same scalar as a root
     * or collection element changes Object-typed numeric and boolean keys. A {@code null} key is never passed here:
     * map context would read it back as the String {@code "null"}, so the caller keeps it {@code null}.
     *
     * <p>A {@code java.util.Date} key read back as a date (its key type is a date type) is copied by
     * {@link java.util.Date#clone()} instead: its quoted form has whole seconds only.</p>
     *
     * <p>A {@code Timestamp}, {@code Calendar} or {@code java.time} key is copied by {@link #copyJsonDateTime(Object)},
     * whatever the key type.</p>
     *
     * @param key the non-null source key
     * @param config the declared key type, without a map-value type
     * @return the copied key: the clone of such a date key, the exact copy of such a date/time key, otherwise the key
     *         produced by the JSON parser
     */
    private static Object copyJsonMapKey(final Object key, final com.landawn.abacus.parser.JsonDeserConfig config) {
        if (isExactlyCopiedJsonDateTime(key)) {
            return copyJsonDateTime(key);
        }

        final com.landawn.abacus.type.Type<?> keyType = config.getMapKeyType();

        // A String key reads back as an equal String in a String or untyped key position.
        if (key instanceof String && (keyType == null || keyType.isObject() || keyType.javaType() == String.class)) {
            return key;
        }

        // An untyped (Object) key position keeps the JSON parser's default String key.
        if (keyType != null && java.util.Date.class.isAssignableFrom(keyType.javaType()) && isSecondPrecisionJsonDate(key)) {
            return ((java.util.Date) key).clone();
        }

        return jsonParser.deserialize(jsonParser.serialize(java.util.Collections.singletonMap(key, Boolean.TRUE)), config, Map.class)
                .keySet()
                .iterator()
                .next();
    }

    /**
     * Returns whether a JSON round trip of {@code value} as the root value, a value held by an optional/nullable
     * wrapper or a map key drops its milliseconds: those positions write a date in its type's string form, which has
     * whole seconds for a {@code java.util.Date} (including subclasses other than {@code java.sql.Date},
     * {@code java.sql.Time} and {@code java.sql.Timestamp}), while collection elements, map values and bean properties
     * write it as epoch milliseconds. Such a value is copied by {@link java.util.Date#clone()}, which keeps its
     * milliseconds and runtime class.
     *
     * @param value the value to check
     * @return {@code true} if {@code value} is a {@code java.util.Date} whose JSON string form has whole seconds only
     */
    private static boolean isSecondPrecisionJsonDate(final Object value) {
        return value instanceof java.util.Date && !(value instanceof java.sql.Date || value instanceof java.sql.Time || value instanceof java.sql.Timestamp);
    }

    /**
     * Returns whether the JSON cache copy reproduces {@code value} itself, by {@link #copyJsonDateTime(Object)}, rather
     * than through the JSON parser, wherever it handles the value: as the root result, as the value held by an
     * optional/nullable wrapper, and as an element, map key or map value in the result's container tree. The parser
     * writes a {@code java.sql.Timestamp} as epoch milliseconds in every position, dropping its sub-millisecond nanos,
     * and a {@code Calendar}, {@code ZonedDateTime}, {@code OffsetDateTime} or {@code Instant} as epoch milliseconds
     * in a collection, array or map value, dropping any sub-millisecond part and reading a zone or offset back in the
     * JVM default time zone; it cannot write some {@code java.time} types at all (e.g. {@code OffsetTime},
     * {@code Period}, {@code YearMonth}). All {@code java.time} values are included, so that one is copied the same
     * way whatever else its container holds, and an untyped ({@code Object}) position keeps it rather than the
     * parser's number or string.
     *
     * @param value the value to check; may be {@code null}
     * @return {@code true} if {@code value} is a {@code java.sql.Timestamp}, a {@code Calendar} or a value of a
     *         {@code java.time} class
     */
    private static boolean isExactlyCopiedJsonDateTime(final Object value) {
        return value instanceof java.sql.Timestamp || value instanceof java.util.Calendar
                || ((value instanceof java.time.temporal.TemporalAccessor || value instanceof java.time.temporal.TemporalAmount
                        || value instanceof java.time.ZoneId) && value.getClass().getName().startsWith("java.time."));
    }

    /**
     * Copies a value for which {@link #isExactlyCopiedJsonDateTime(Object)} holds: a {@code Timestamp} or
     * {@code Calendar} is mutable and cloned, which keeps its nanos or time zone (and its runtime class); a
     * {@code java.time} value is immutable and returned as it is.
     *
     * @param value the date/time value
     * @return the exact copy
     */
    private static Object copyJsonDateTime(final Object value) {
        if (value instanceof final java.util.Date date) {
            return date.clone();
        } else if (value instanceof final java.util.Calendar calendar) {
            return calendar.clone();
        }

        return value;
    }

    /**
     * Copies the value held by an {@code Optional}/{@code Nullable} result for the JSON-serialized DAO cache, through
     * {@code declaredValueType} when it is resolved, otherwise through the value's runtime class. A single held value
     * has no untyped fallback: a raw holder declares nothing, and an {@code Object} type would read the value back as
     * its JSON String. So a runtime container class the parser cannot construct is still used here, and the parser's
     * own "no default constructor" failure names the class.
     *
     * @param value the held value; may be {@code null} (a present {@code null} of a {@code Nullable})
     * @param declaredValueType the declared value type of the holder, or {@code null} if unknown
     * @return a copy of {@code value}, or {@code null} if {@code value} is {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Object copyHeldValue(final Object value, final com.landawn.abacus.type.Type<?> declaredValueType) {
        if (value == null) {
            return null;
        }

        // stringOf writes a java.util.Date with whole seconds only (see isSecondPrecisionJsonDate).
        if (isSecondPrecisionJsonDate(value)) {
            return ((java.util.Date) value).clone();
        }

        if (isExactlyCopiedJsonDateTime(value)) {
            return copyJsonDateTime(value);
        }

        final com.landawn.abacus.type.Type<?> resolvedType = resolvedOrRuntimeType(declaredValueType, java.util.Collections.singletonList(value));
        final com.landawn.abacus.type.Type<Object> valueType = (com.landawn.abacus.type.Type<Object>) (resolvedType == null || resolvedType.isObject()
                ? N.typeOf(value.getClass())
                : resolvedType);

        return valueType.valueOf(valueType.stringOf(value));
    }

    /**
     * Configures immutable-container copying on the DAO's private, copy-only Kryo parser. Keeping the Kryo-linked
     * implementation in a nested class allows DAO creation and JSON/NONE caching without Kryo on the classpath.
     *
     * @param source the non-null cached result graph to copy
     */
    private record KryoCacheCopy(Object source) implements com.esotericsoftware.kryo.KryoCopyable<Object> {
        @Override
        @SuppressWarnings("unchecked")
        public Object copy(final com.esotericsoftware.kryo.Kryo kryo) {
            // ParserFactory creates a separate parser/pool for DaoImpl. Defaults are installed before any result
            // graph is visited; explicit application serializers remain authoritative. Kryo's context survives
            // graph resets, so a pooled instance receives the defaults only once.
            if (!kryo.getContext().containsKey(KryoCacheCopy.class)) {
                final ImmutableContainerCopySerializer serializer = new ImmutableContainerCopySerializer();
                for (final Class<?> type : serializer.supportedTypes()) {
                    kryo.addDefaultSerializer(type, serializer);
                    final com.esotericsoftware.kryo.Registration registration = kryo.getClassResolver().getRegistration(type);
                    if (registration != null) {
                        final Class<?> registeredSerializer = registration.getSerializer().getClass();
                        if (!registration.getSerializer().isImmutable()
                                && (registeredSerializer == com.esotericsoftware.kryo.serializers.CollectionSerializer.class
                                        || registeredSerializer == com.esotericsoftware.kryo.serializers.MapSerializer.class)) {
                            // Class/ID-only ParserFactory registrations precede these defaults. Replace only the
                            // stock copy implementation, retaining its registration ID and any application serializer.
                            registration.setSerializer(serializer);
                        }
                    }
                }
                kryo.getContext().put(KryoCacheCopy.class, Boolean.TRUE);
            }

            final Object copy = kryo.copy(source);
            final List<Runnable> viewRepairs = (List<Runnable>) kryo.getGraphContext().get(ContainerCopyLayout.class);
            if (viewRepairs != null) {
                viewRepairs.forEach(Runnable::run);
            }
            return copy;
        }
    }

    /** Copies wrapper structure while delegating native backing containers and arbitrary values to Kryo. */
    private static final class ImmutableContainerCopySerializer extends com.esotericsoftware.kryo.Serializer<Object> {
        private final ClassValue<ContainerCopyLayout> layouts = new ClassValue<>() {
            @Override
            protected ContainerCopyLayout computeValue(final Class<?> type) {
                return new ContainerCopyLayout(type);
            }
        };

        /** Returns the bounded wrapper families and JDK views whose fields need constructor-free copying. */
        private Set<Class<?>> supportedTypes() {
            final Set<Class<?>> types = new java.util.LinkedHashSet<>(List.of(com.landawn.abacus.util.ImmutableCollection.class, ImmutableList.class,
                    ImmutableSet.class, com.landawn.abacus.util.ImmutableSortedSet.class, com.landawn.abacus.util.ImmutableNavigableSet.class,
                    ImmutableMap.class, com.landawn.abacus.util.ImmutableSortedMap.class, com.landawn.abacus.util.ImmutableNavigableMap.class,
                    com.landawn.abacus.util.ImmutableBiMap.class, com.landawn.abacus.util.BiMap.class));
            final Class<?>[] permittedListTypes = ImmutableList.class.getPermittedSubclasses();
            if (permittedListTypes != null) {
                java.util.Collections.addAll(types, permittedListTypes);
            }

            // Obtain private JDK implementation classes from public factories instead of naming VM internals.
            final List<Object> list = new ArrayList<>();
            final java.util.TreeSet<Object> set = new java.util.TreeSet<>();
            final java.util.TreeMap<Object, Object> map = new java.util.TreeMap<>();
            types.add(java.util.Collections.unmodifiableCollection(list).getClass());
            types.add(java.util.Collections.unmodifiableList(list).getClass());
            types.add(java.util.Collections.unmodifiableList(new java.util.LinkedList<>()).getClass());
            types.add(java.util.Collections.unmodifiableSet(set).getClass());
            types.add(java.util.Collections.unmodifiableSortedSet(set).getClass());
            types.add(java.util.Collections.unmodifiableNavigableSet(set).getClass());
            types.add(java.util.Collections.unmodifiableMap(map).getClass());
            types.add(java.util.Collections.unmodifiableSortedMap(map).getClass());
            types.add(java.util.Collections.unmodifiableNavigableMap(map).getClass());
            types.add(java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()).getClass());
            types.add(list.subList(0, 0).getClass());
            types.add(new java.util.LinkedList<>().subList(0, 0).getClass());
            types.add(java.util.Arrays.asList(new Object[0]).subList(0, 0).getClass());
            types.add(map.headMap(0, true).getClass());
            types.add(map.descendingMap().getClass());
            return types;
        }

        @Override
        public Object copy(final com.esotericsoftware.kryo.Kryo kryo, final Object original) {
            ContainerCopyAccess.requireAvailable();
            return layouts.get(original.getClass()).copy(kryo, original);
        }

        @Override
        public void write(final com.esotericsoftware.kryo.Kryo kryo, final com.esotericsoftware.kryo.io.Output output, final Object object) {
            throw new UnsupportedOperationException("DAO cache container serializers support copying only");
        }

        @Override
        public Object read(final com.esotericsoftware.kryo.Kryo kryo, final com.esotericsoftware.kryo.io.Input input, final Class<?> type) {
            throw new UnsupportedOperationException("DAO cache container serializers support copying only");
        }
    }

    /** Checks and caches actual Unsafe access only when an adapted wrapper needs copying. */
    private static final class ContainerCopyAccess {
        private static final Availability availability = checkAvailability();

        private static void requireAvailable() {
            if (availability.failure() != null) {
                // Keep one capability failure, but give each invocation its own exception and call-site stack.
                throw new UnsupportedOperationException(
                        "KRYO cache copying of immutable containers requires sun.misc.Unsafe memory access. " + availability.failure()
                                + " Register an application serializer that avoids Unsafe, or choose a compatible cache serialization mode.",
                        availability.cause());
            }
        }

        private static Availability checkAvailability() {
            try {
                if (!com.esotericsoftware.kryo.util.Util.unsafe) {
                    return new Availability("false".equals(System.getProperty("kryo.unsafe")) ? "Kryo support is disabled by -Dkryo.unsafe=false."
                            : "Kryo reports Unsafe unavailable in this runtime.", null);
                }

                // Kryo can retain a non-null Unsafe handle while the JVM denies its memory operations. Probe
                // offsets, allocation and primitive/reference access on a private disposable object before
                // entering ClassValue: failed computeValue calls are otherwise retried for every cache copy.
                final sun.misc.Unsafe unsafe = com.esotericsoftware.kryo.unsafe.UnsafeUtil.unsafe;
                final long referenceOffset = unsafe.objectFieldOffset(AccessProbe.class.getDeclaredField("reference"));
                final long numberOffset = unsafe.objectFieldOffset(AccessProbe.class.getDeclaredField("number"));
                final AccessProbe probe = (AccessProbe) unsafe.allocateInstance(AccessProbe.class);
                unsafe.putObject(probe, referenceOffset, probe);
                unsafe.putLong(probe, numberOffset, 1L);
                if (unsafe.getObject(probe, referenceOffset) != probe || unsafe.getLong(probe, numberOffset) != 1L) {
                    throw new IllegalStateException("Unsafe field access did not preserve the probe values");
                }
                return new Availability(null, null);
            } catch (final UnsupportedOperationException e) {
                return new Availability("The JVM does not permit the required operations (for example, --sun-misc-unsafe-memory-access=deny).", e);
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                return new Availability("The required Unsafe operations are unavailable under the current JVM or dependency settings.", e);
            }
        }

        private record Availability(String failure, Throwable cause) {
        }

        private static final class AccessProbe {
            private Object reference;
            private long number;
        }
    }

    /**
     * Cached field metadata for wrapper copying. Unsafe is loaded only when a supported wrapper is visited;
     * ordinary Kryo graphs keep working when Unsafe is unavailable. FieldSerializer cannot handle the JDK's
     * unmodifiable decorators without module opens. Copying their fields preserves backing equality policies,
     * view relationships and application-subclass state without making those fields reflectively accessible.
     */
    private static final class ContainerCopyLayout {
        private static final sun.misc.Unsafe unsafe = com.esotericsoftware.kryo.unsafe.UnsafeUtil.unsafe;
        private final Class<?> type;
        private final CopiedContainerField[] fields;
        private final long setMapOffset;
        private final long setViewOffset;
        private final long listRootOffset;
        private final long listModCountOffset;

        private ContainerCopyLayout(final Class<?> type) {
            this.type = type;
            final List<CopiedContainerField> copiedFields = new ArrayList<>();
            long setMap = -1;
            long setView = -1;
            long listRoot = -1;
            long listModCount = -1;
            for (Class<?> declaring = type; declaring != Object.class; declaring = declaring.getSuperclass()) {
                // "Is this a JDK class?" is the question: every adapted view lives in java.util, so key off the
                // module name rather than relying on the bootstrap loader being represented as null.
                final boolean jdkType = "java.base".equals(declaring.getModule().getName());
                for (final java.lang.reflect.Field field : declaring.getDeclaredFields()) {
                    final int modifiers = field.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(modifiers)) {
                        continue;
                    }
                    final long offset = unsafe.objectFieldOffset(field);
                    if (jdkType && declaring.getSimpleName().equals("SetFromMap")) {
                        if (field.getName().equals("m")) {
                            setMap = offset;
                        } else if (field.getName().equals("s")) {
                            setView = offset;
                        }
                    }
                    if (jdkType && field.getName().equals("root") && java.util.AbstractList.class.isAssignableFrom(declaring)) {
                        listRoot = offset;
                    } else if (declaring == java.util.AbstractList.class && field.getName().equals("modCount")) {
                        listModCount = offset;
                    }

                    // JDK view caches are derived from their backing container. BiMap's inverse participates in
                    // the graph, but its key/value/entry-set caches can be rebuilt lazily; suppliers are policies.
                    if (java.lang.reflect.Modifier.isTransient(modifiers)
                            && (jdkType || declaring == com.landawn.abacus.util.BiMap.class && !field.getName().equals("invertedView"))) {
                        continue;
                    }
                    final boolean shared = jdkType && java.util.Comparator.class.isAssignableFrom(field.getType())
                            || declaring == com.landawn.abacus.util.BiMap.class && java.util.function.Supplier.class.isAssignableFrom(field.getType());
                    copiedFields.add(new CopiedContainerField(offset, field.getType(), shared));
                }
            }
            fields = copiedFields.toArray(new CopiedContainerField[0]);
            setMapOffset = setMap;
            setViewOffset = setView;
            listRootOffset = listRoot;
            listModCountOffset = listModCount;
        }

        @SuppressWarnings("unchecked")
        private Object copy(final com.esotericsoftware.kryo.Kryo kryo, final Object original) {
            final Object copy;
            try {
                copy = unsafe.allocateInstance(type);
            } catch (final InstantiationException e) {
                throw new com.esotericsoftware.kryo.KryoException("Cannot allocate cache container: " + type.getName(), e);
            }
            // Reference the final runtime type before descending so all cycles and aliases point to this copy.
            kryo.reference(copy);
            for (final CopiedContainerField field : fields) {
                field.copy(kryo, original, copy);
            }
            if (setViewOffset >= 0) {
                // SetFromMap's transient key view is required state, unlike an optional unmodifiable-map cache.
                unsafe.putObject(copy, setViewOffset, ((Map<?, ?>) unsafe.getObject(copy, setMapOffset)).keySet());
            }
            if (listRootOffset >= 0 && listModCountOffset >= 0) {
                // Kryo rebuilds the native list through add(), so a sublist must observe the copied root's new
                // modification count. Defer until the graph is complete: the root may itself contain this view.
                List<Runnable> viewRepairs = (List<Runnable>) kryo.getGraphContext().get(ContainerCopyLayout.class);
                if (viewRepairs == null) {
                    viewRepairs = new ArrayList<>();
                    kryo.getGraphContext().put(ContainerCopyLayout.class, viewRepairs);
                }
                viewRepairs.add(() -> unsafe.putInt(copy, listModCountOffset, unsafe.getInt(unsafe.getObject(copy, listRootOffset), listModCountOffset)));
            }
            return copy;
        }
    }

    /**
     * One cached field operation; policy objects are retained as in Kryo's sorted-container serializers.
     *
     * @param offset the instance-field offset supplied by Unsafe
     * @param type the declared field type
     * @param shared whether the field is a comparator or BiMap factory to retain by reference
     */
    private record CopiedContainerField(long offset, Class<?> type, boolean shared) {
        private void copy(final com.esotericsoftware.kryo.Kryo kryo, final Object source, final Object target) {
            final sun.misc.Unsafe unsafe = ContainerCopyLayout.unsafe;
            if (!type.isPrimitive()) {
                final Object value = unsafe.getObject(source, offset);
                unsafe.putObject(target, offset, shared ? value : kryo.copy(value));
            } else if (type == boolean.class) {
                unsafe.putBoolean(target, offset, unsafe.getBoolean(source, offset));
            } else if (type == byte.class) {
                unsafe.putByte(target, offset, unsafe.getByte(source, offset));
            } else if (type == char.class) {
                unsafe.putChar(target, offset, unsafe.getChar(source, offset));
            } else if (type == short.class) {
                unsafe.putShort(target, offset, unsafe.getShort(source, offset));
            } else if (type == int.class) {
                unsafe.putInt(target, offset, unsafe.getInt(source, offset));
            } else if (type == long.class) {
                unsafe.putLong(target, offset, unsafe.getLong(source, offset));
            } else if (type == float.class) {
                unsafe.putFloat(target, offset, unsafe.getFloat(source, offset));
            } else if (type == double.class) {
                unsafe.putDouble(target, offset, unsafe.getDouble(source, offset));
            }
        }
    }

    /**
     * Pairs the generated IDs of consecutive same-shape insert runs with their entities and stages the assignments
     * until commit. Within a run the k-th returned key belongs to the k-th entity, as if the run's keys had been
     * collected into one list; a run whose key count differs from its entity count, or whose keys are all default
     * values, assigns nothing.
     *
     * <p>Entities are reached through a second iterator over the input collection instead of being buffered, so a
     * driver that returns fewer keys than rows (or none) adds no retained references. Only keys returned ahead of
     * their entities are queued. The iterator must be created before the caller starts iterating the input; it then
     * trails that iteration and only advances over entities whose chunks have been executed.</p>
     */
    private static final class DeferredIdAssignments {
        private static final Object NULL_ID = new Object();

        private final Iterator<?> entityCursor;
        private final List<Pair<Object, Object>> assignments;
        private final ArrayDeque<Object> pendingIds = new ArrayDeque<>();
        private int runStart;
        private int runEntityCount;
        private int runIdCount;
        private int runPairedCount;
        private boolean runHasOnlyDefaultIds = true;

        DeferredIdAssignments(final Collection<?> entities, final List<Pair<Object, Object>> assignments) {
            entityCursor = entities.iterator();
            this.assignments = assignments;
            runStart = assignments.size();
        }

        /**
         * Executes {@code chunk} as one batch of the current run and pairs the returned keys with the run's entities
         * in order. Keys beyond the run's entity count so far are queued for the entities of a later chunk.
         *
         * @param query the prepared insert statement of the current run's SQL shape
         * @param chunk the entities of this batch, in input order; an empty chunk is ignored
         * @param keyExtractor the mapper reading one generated key
         * @param isDefaultIdTester tests whether a key is the id type's default (unset) value
         * @throws SQLException if executing the batch or reading its generated keys fails
         */
        void insertChunk(final NamedQuery query, final List<Object> chunk, final Jdbc.BiRowMapper<Object> keyExtractor,
                final Predicate<Object> isDefaultIdTester) throws SQLException {
            if (chunk.isEmpty()) {
                return;
            }

            final List<Object> ids = query.addBatchParameters(chunk).batchInsert(keyExtractor, isDefaultIdTester);
            runEntityCount += chunk.size();
            runIdCount += ids.size();

            // Keys returned by an earlier chunk precede this chunk's own keys.
            while (!pendingIds.isEmpty() && runPairedCount < runEntityCount) {
                final Object id = pendingIds.removeFirst();
                pairNextEntity(id == NULL_ID ? null : id);
            }

            for (final Object id : ids) {
                if (runHasOnlyDefaultIds) {
                    runHasOnlyDefaultIds = isDefaultIdTester.test(id);
                }

                if (runPairedCount < runEntityCount) {
                    pairNextEntity(id);
                } else {
                    // ArrayDeque rejects null elements.
                    pendingIds.addLast(id == null ? NULL_ID : id);
                }
            }
        }

        /**
         * Stages {@code id} for the next entity of the current run that has no key yet.
         *
         * @param id the generated key, possibly {@code null}
         */
        private void pairNextEntity(final Object id) {
            assignments.add(Pair.of(id, entityCursor.next()));
            runPairedCount++;
        }

        /**
         * Ends the current run: keeps its staged assignments only when it returned exactly one key per entity and not
         * every key is a default value, otherwise discards them (logging a warning unless every key was a default
         * value), then moves the entity cursor to the first entity of the next run.
         *
         * @param daoLogger the logger used to warn about a key/entity count mismatch
         */
        void finishRun(final Logger daoLogger) {
            if (runHasOnlyDefaultIds || runIdCount != runEntityCount) {
                assignments.subList(runStart, assignments.size()).clear();

                if (!runHasOnlyDefaultIds && daoLogger.isWarnEnabled()) {
                    daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", runIdCount, runEntityCount);
                }
            }

            // Skip the entities that received no key so the next run starts at its own first entity.
            for (; runPairedCount < runEntityCount; runPairedCount++) {
                entityCursor.next();
            }

            pendingIds.clear();
            runStart = assignments.size();
            runEntityCount = 0;
            runIdCount = 0;
            runPairedCount = 0;
            runHasOnlyDefaultIds = true;
        }
    }

    /**
     * Executes the pending batch of a custom {@code @Query} INSERT method. Generated keys are read back only when
     * the query was prepared to return them; otherwise the batch is executed as a plain update, because calling
     * {@code getGeneratedKeys()} on a statement that did not request them fails on some drivers (e.g. MySQL
     * Connector/J).
     *
     * @param preparedQuery the query holding the batch to execute
     * @param returnGeneratedKeys whether the query was prepared to return generated keys
     * @param keyExtractor the mapper reading one generated id; ignored when {@code returnGeneratedKeys} is {@code false}
     * @param isDefaultIdTester tests whether an extracted id is the type's default (unset) value; ignored when
     *        {@code returnGeneratedKeys} is {@code false}
     * @return the generated ids in batch order, or an empty list when no keys were requested
     * @throws IllegalArgumentException if {@code returnGeneratedKeys} is {@code true} and {@code keyExtractor} or
     *         {@code isDefaultIdTester} is {@code null}
     * @throws SQLException if executing the batch or extracting generated keys fails
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<Object> executeCustomBatchInsert(final AbstractQuery preparedQuery, final boolean returnGeneratedKeys,
            final Jdbc.BiRowMapper<Object> keyExtractor, final Predicate<Object> isDefaultIdTester) throws IllegalArgumentException, SQLException {
        if (returnGeneratedKeys) {
            return preparedQuery.batchInsert(keyExtractor, isDefaultIdTester);
        }

        preparedQuery.batchUpdate();

        return new ArrayList<>(0);
    }

    /**
     * Resolves the most appropriate DAO instance for loading a join entity referenced by a {@code @JoinedBy} relationship.
     *
     * <p>This method searches the internal DAO pool for a registered DAO whose target entity class matches the
     * specified {@code referencedEntityClass} and whose data source matches the provided {@code ds}. If a matching
     * DAO is found, it is cached in the join entity DAO pool for subsequent lookups and returned. If no matching
     * DAO is found, a warning is logged and the {@code defaultDao} is returned as a fallback.</p>
     *
     * <p>The fallback {@code defaultDao} is never cached. A bounded miss cache avoids repeated registry scans and
     * warnings; every successful DAO registration invalidates it so a later registration can take over.</p>
     *
     * @param referencedEntityClass the entity class of the join target (the entity being referenced)
     * @param ds the {@link javax.sql.DataSource} that the returned DAO should be associated with
     * @param defaultDao the fallback DAO to return if no specific DAO is found for the referenced entity class
     * @return the DAO instance best suited for loading instances of {@code referencedEntityClass}, or
     *         {@code defaultDao} if no matching DAO has been registered
     */
    @SuppressWarnings("rawtypes")
    static DaoBase getApplicableDaoForJoinEntity(final Class<?> referencedEntityClass, final javax.sql.DataSource ds, final DaoBase defaultDao) {
        return getApplicableDaoForJoinEntity(referencedEntityClass, ds, defaultDao, false);
    }

    /**
     * Resolves a join DAO that supports the requested operation, falling back to the source DAO.
     *
     * @param referencedEntityClass the joined entity type
     * @param ds the data source, compared by identity
     * @param defaultDao the source DAO used when no suitable target DAO is registered
     * @param forDelete whether the target DAO must support DELETE statements
     * @return the matching DAO, or {@code defaultDao}
     */
    @SuppressWarnings("rawtypes")
    private static DaoBase getApplicableDaoForJoinEntity(final Class<?> referencedEntityClass, final javax.sql.DataSource ds, final DaoBase defaultDao,
            final boolean forDelete) {
        // A read-only/non-update DAO can load a join, but its SQL gate rejects DELETE. Keep the
        // operation in the cache key so a previously loaded join cannot poison a later delete.
        final JoinEntityDaoCacheKey key = new JoinEntityDaoCacheKey(referencedEntityClass, ds, forDelete);
        final DaoBase joinEntityDao = joinEntityDaoPool.get(key);

        if (joinEntityDao != null) {
            return joinEntityDao;
        }

        final long registrationVersion;

        synchronized (joinEntityDaoMisses) {
            registrationVersion = daoRegistrationVersion;

            if (joinEntityDaoMisses.get(key) != null) {
                return defaultDao;
            }
        }

        for (final PooledDao pooledDao : daoPool.values()) {
            final DaoBase dao = pooledDao.dao;

            if (dao.targetEntityClass() == referencedEntityClass && dao.dataSource() == ds && (!forDelete || dao instanceof Dao)) {
                final DaoBase existingDao = joinEntityDaoPool.putIfAbsent(key, dao);

                if (existingDao == null && daoPool.get(pooledDao.key) != pooledDao) {
                    // Evicted meanwhile: its eviction may have run before this entry was added, so drop the entry again
                    // rather than retaining the evicted proxy. It is still fine to use it for this call.
                    joinEntityDaoPool.remove(key, dao);
                }

                return existingDao == null ? dao : existingDao;
            }
        }

        synchronized (joinEntityDaoMisses) {
            if (registrationVersion == daoRegistrationVersion) {
                joinEntityDaoMisses.put(key, Boolean.TRUE);
            }
        }

        JdbcUtil.logger.warn("No Dao interface/instance found for join operations(entityClass={}, dataSourceId={}, dao={})", referencedEntityClass,
                System.identityHashCode(ds), defaultDao.getClass());

        // Don't cache defaultDao - the correct DAO may be registered later (e.g., during initialization).
        // Caching the wrong DAO here would permanently prevent the correct one from being found.

        return defaultDao;
    }

    /**
     * Immutable value object holding parsed metadata about a SQL query derived from a DAO method's
     * {@link Query @Query} annotation (or an equivalent SQL-script reference).
     *
     * <p>Each field corresponds either to an attribute of the source annotation (e.g., {@code queryTimeout},
     * {@code fetchSize}, {@code QueryOperation}) or to a property derived from the SQL text itself (e.g., {@code isSelect},
     * {@code isInsert}, {@code isNamedQuery}, the parsed {@link ParsedSql}). Instances are created once during DAO
     * proxy initialization and reused for every invocation of the associated DAO method.</p>
     */
    static final class QueryInfo {
        /**
         * The SQL text with any single trailing semicolon stripped; never blank. For SQL resolved by id from a
         * {@link SqlMapper} it is the parameterized form ({@code ?} markers), while {@link #parsedSql} keeps the
         * original named-parameter text.
         */
        final String sql;
        /** The parsed form of {@link #sql}. */
        final ParsedSql parsedSql;
        /** The query timeout in seconds; a negative value means not set. */
        final int queryTimeout;
        /** The JDBC fetch size hint; only applied when positive. */
        final int fetchSize;
        /** Whether the query is executed as a batch operation. */
        final boolean isBatch;
        /** The number of statements per batch execution. */
        final int batchSize;
        /** The operation type controlling execution behavior. */
        final QueryOperation queryOperation;
        /** Whether a single method parameter is bound as-is rather than decomposed. */
        final boolean isSingleParameter;
        /** Whether the reserved named parameters ({@code :now}, {@code :sysTime}, {@code :sysDate}) are auto-bound to the current time. */
        final boolean autoSetSysTimeParam;
        /** Whether this is a SELECT statement. */
        final boolean isSelect;
        /** Whether this is an INSERT statement. */
        final boolean isInsert;
        /** Whether this SQL represents a stored procedure call. */
        final boolean isProcedure;
        /** Whether the SQL uses named parameters (derived from the parsed SQL or the fragment hint). */
        final boolean isNamedQuery;
        /** Whether SQL fragments may introduce named parameters absent from the static SQL. */
        final boolean fragmentsContainNamedParameters;

        /**
         * Constructs a new {@code QueryInfo} from annotation attributes and derived SQL properties.
         *
         * <p>A single trailing semicolon in the SQL string is automatically stripped. If {@code parsedSql} is {@code null},
         * the SQL string is parsed automatically. The {@code isNamedQuery} flag is derived from the presence of
         * named parameters in the parsed SQL or from the {@code fragmentsContainNamedParameters} hint.</p>
         *
         * @param sql the raw SQL string (must not be blank); a single trailing semicolon is removed
         * @param parsedSql the pre-parsed SQL, or {@code null} to parse from {@code sql}
         * @param queryTimeout the query timeout in seconds; a negative value means not set (0 is passed through to JDBC as "no limit")
         * @param fetchSize the JDBC fetch size hint; only applied when positive (otherwise SELECT queries get a default derived from the QueryOperation/return type)
         * @param isBatch {@code true} if this query should be executed as a batch operation
         * @param batchSize the number of statements per batch execution
         * @param queryOperation the {@link QueryOperation} operation type controlling execution behavior
         * @param isSingleParameter {@code true} if a single method parameter should be bound as-is rather than decomposed
         * @param autoSetSysTimeParam {@code true} to automatically bind the current time to the reserved named parameters
         *        ({@code :now}, {@code :sysTime}, {@code :sysDate}) when they appear in the SQL
         * @param isSelect {@code true} if this is a SELECT statement
         * @param isInsert {@code true} if this is an INSERT statement
         * @param isProcedure {@code true} if this SQL represents a stored procedure call
         * @param fragmentsContainNamedParameters {@code true} if the SQL text injected via {@code @SqlFragment}/{@code @SqlFragmentList} parameters contains named parameters
         * @throws IllegalArgumentException if {@code sql} is blank, if {@code parsedSql} is {@code null} and parsing
         *         {@code sql} rejects mixed or malformed parameter syntax, or if
         *         {@code fragmentsContainNamedParameters} is {@code true} but the resolved SQL has positional
         *         parameters and no named parameters
         */
        QueryInfo(final String sql, final ParsedSql parsedSql, final int queryTimeout, final int fetchSize, final boolean isBatch, final int batchSize,
                final QueryOperation queryOperation, final boolean isSingleParameter, final boolean autoSetSysTimeParam, final boolean isSelect,
                final boolean isInsert, final boolean isProcedure, final boolean fragmentsContainNamedParameters) throws IllegalArgumentException {
            this.sql = N.checkArgNotBlank(sql != null && sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql, cs.sql);
            this.parsedSql = parsedSql == null ? ParsedSql.parse(this.sql) : parsedSql;
            this.queryTimeout = queryTimeout;
            this.fetchSize = fetchSize;
            this.isBatch = isBatch;
            this.batchSize = batchSize;
            this.queryOperation = queryOperation;
            this.isSingleParameter = isSingleParameter;
            this.autoSetSysTimeParam = autoSetSysTimeParam;
            this.isSelect = isSelect;
            this.isInsert = isInsert;
            this.isProcedure = isProcedure;
            this.fragmentsContainNamedParameters = fragmentsContainNamedParameters;
            isNamedQuery = N.notEmpty(this.parsedSql.namedParameters()) || fragmentsContainNamedParameters;

            if (fragmentsContainNamedParameters && (this.parsedSql.parameterCount() > 0 && N.isEmpty(this.parsedSql.namedParameters()))) {
                throw new IllegalArgumentException("'fragmentsContainNamedParameters' is true, but the resolved SQL has no named parameters");
            }
        }
    }
}
