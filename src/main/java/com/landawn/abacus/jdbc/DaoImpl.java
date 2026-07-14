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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.exception.DuplicateResultException;
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
import com.landawn.abacus.parser.JsonSerConfig;
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
import com.landawn.abacus.query.condition.SqlExpression;
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
 * <p>This class itself holds no mutable instance state and is safe for concurrent use. Method metadata and SQL
 * parsing results are cached in {@link ConcurrentHashMap concurrent maps}. The underlying database connections and
 * transactions are managed per-thread through {@link SqlTransaction} and connection pooling.</p>
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

    private DaoImpl() {
        // utility class - prevent instantiation.
    }

    private static final String _1 = "1";

    private static final JsonParser jsonParser = ParserFactory.createJsonParser();
    private static final KryoParser kryoParser = ParserFactory.isKryoParserAvailable() ? ParserFactory.createKryoParser() : null;

    private static final JsonSerConfig jsc_no_bracket = JsonSerConfig.create().setStringQuotation(JdbcUtil.CHAR_ZERO).setBracketRootValue(false);

    static final ThreadLocal<Boolean> isInDaoMethod_TL = ThreadLocal.withInitial(() -> false);

    @SuppressWarnings("rawtypes")
    private static final Map<String, DaoBase> daoPool = new ConcurrentHashMap<>();

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

            if (!Strings.containsWhitespace(sql) && sqlMapper != null && sqlMapper.get(sql) != null) {
                sql = sqlMapper.get(sql).parameterizedSql();
            }

            final String id = N.notEmpty(tmp.id()) ? tmp.id()[0] : Strings.isNotEmpty(sql) && sqlMapper != null && sqlMapper.get(sql) != null ? sql : null;

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

    private static final Predicate<? super Class<?>> isImmutableTester = Immutable.class::isAssignableFrom;

    private static final Set<Class<?>> notCacheableTypes = N.asSet(void.class, Void.class, Iterator.class, java.util.stream.BaseStream.class, BaseStream.class,
            EntryStream.class, Stream.class, Seq.class);

    @SuppressWarnings("rawtypes")
    private static final Jdbc.BiParametersSetter<AbstractQuery, Collection> collParamsSetter = AbstractQuery::setParameters;

    private static final Jdbc.BiParametersSetter<NamedQuery, Object> objParamsSetter = NamedQuery::setParameters; // NOSONAR

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
    private static MethodHandle createMethodHandle(final Method method) {
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

    private static Dsl parameterizedDsl(final Dsl dsl) {
        if (dsl.sqlDialect().sqlPolicy() == SqlPolicy.PARAMETERIZED_SQL) {
            return dsl;
        }

        return dslWithPolicy(dsl, SqlPolicy.PARAMETERIZED_SQL);
    }

    private static Dsl namedDsl(final Dsl dsl) {
        if (dsl.sqlDialect().sqlPolicy() == SqlPolicy.NAMED_SQL) {
            return dsl;
        }

        return dslWithPolicy(dsl, SqlPolicy.NAMED_SQL);
    }

    @SuppressWarnings({ "unchecked" })
    private static Dsl dslWithPolicy(final Dsl dsl, final SqlPolicy sqlPolicy) {
        final SqlDialect sqlDialect = dsl.sqlDialect();

        final SqlDialect newSqlDialect = SqlDialect.builder()
                .productInfo(sqlDialect.productInfo())
                .namingPolicy(sqlDialect.namingPolicy())
                .identifierQuote(sqlDialect.identifierQuote())
                .sqlPolicy(sqlPolicy)
                .build();

        return Dsl.forDialect(newSqlDialect);
    }

    /**
     * Determines whether the specified method should be dispatched as a list-style (multi-row) query, based on its
     * return type, its declared {@link QueryOperation}, the presence of {@code @MappedByKey}/{@code @MergedById} annotations,
     * and (for {@link QueryOperation#DEFAULT}) the method's name prefix.
     *
     * <p>The decision rules are:</p>
     * <ul>
     *   <li>{@link QueryOperation#list} or {@link QueryOperation#listAll}: treated as a list query, but only if the return type is a proper
     *       {@link Collection} subtype (not raw {@code Collection.class} itself); otherwise an
     *       {@link UnsupportedOperationException} is raised.</li>
     *   <li>Methods annotated with {@link MappedByKey @MappedByKey}: always treated as list queries.</li>
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
     * @param QueryOperation the operation type declared for the method ({@link QueryOperation#DEFAULT} when not explicitly specified)
     * @param fullClassMethodName the fully qualified class and method name, used for error messages
     * @return {@code true} if the method should be dispatched as a list query, {@code false} otherwise
     * @throws UnsupportedOperationException if {@code QueryOperation} is {@link QueryOperation#list} or {@link QueryOperation#listAll} but the return
     *         type is neither a proper {@link Collection} subtype nor one of the supported exemptions
     *         ({@code Map} via {@link MappedByKey}, {@code Tuple2} for procedures with out parameters)
     */
    private static boolean isListQuery(final Method method, final Class<?> returnType, final QueryOperation queryOperation, final String fullClassMethodName) {
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

    private static boolean isExistsQuery(final Method method, final QueryOperation queryOperation, final String fullClassMethodName) {
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

    private static boolean isNotExistsMethodName(final String methodName) {
        return (methodName.startsWith("notExists") && (methodName.length() == 9 || Character.isUpperCase(methodName.charAt(9))))
                || (methodName.startsWith("notExist") && (methodName.length() == 8 || Character.isUpperCase(methodName.charAt(8))));
    }

    private static boolean isFindFirst(final Method method, final QueryOperation queryOperation) {
        if (queryOperation == QueryOperation.findFirst) {
            return true;
        }

        return queryOperation == QueryOperation.DEFAULT && !(method.getName().startsWith("findOnlyOne") || method.getName().startsWith("queryForSingle")
                || method.getName().startsWith("queryForUnique"));
    }

    private static boolean isFindOnlyOne(final Method method, final QueryOperation queryOperation) {
        if (queryOperation == QueryOperation.findOnlyOne) {
            return true;
        }

        // Both "OnlyOne" prefixes from singleQueryPrefix promise at-most-one-row semantics.
        return queryOperation == QueryOperation.DEFAULT && (method.getName().startsWith("findOnlyOne") || method.getName().startsWith("selectOnlyOne"));
    }

    private static boolean isQueryForUnique(final Method method, final QueryOperation queryOperation) {
        if (queryOperation == QueryOperation.queryForUnique) {
            return true;
        }

        return queryOperation == QueryOperation.DEFAULT && method.getName().startsWith("queryForUnique");
    }

    private static final ImmutableSet<Class<?>> singleReturnTypeSet = ImmutableSet.wrap((N.toSet(u.Nullable.class, u.Optional.class, u.OptionalBoolean.class,
            u.OptionalChar.class, u.OptionalByte.class, u.OptionalShort.class, u.OptionalInt.class, u.OptionalLong.class, u.OptionalFloat.class,
            u.OptionalDouble.class, java.util.Optional.class, java.util.OptionalInt.class, java.util.OptionalLong.class, java.util.OptionalDouble.class)));

    private static boolean isSingleReturnType(final Class<?> returnType) {
        return singleReturnTypeSet.contains(returnType) || ClassUtil.isPrimitiveType(ClassUtil.unwrap(returnType));
    }

    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractQuery, Object[], R, SQLException> createSingleQueryFunction(final Class<?> returnType, final Method method,
            final QueryOperation queryOperation) {
        if (u.OptionalBoolean.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForBoolean();
        } else if (u.OptionalChar.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForChar();
        } else if (u.OptionalByte.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForByte();
        } else if (u.OptionalShort.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForShort();
        } else if (u.OptionalInt.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForInt();
        } else if (u.OptionalLong.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForLong();
        } else if (u.OptionalFloat.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForFloat();
        } else if (u.OptionalDouble.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForDouble();
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

    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractQuery, Object[], R, SQLException> createQueryFunctionByMethod(final Class<?> entityClass,
            final Method method, final String mappedByKey, final List<String> mergedByIds, final Map<String, String> prefixFieldMap,
            final boolean fetchColumnByEntityClass, final boolean hasRowMapperOrExtractor, final boolean hasRowFilter, final QueryOperation queryOperation,
            final boolean isProcedure, final String fullClassMethodName) {
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final Class<?> firstReturnEleType = getFirstReturnEleType(method);
        final Class<?> secondReturnEleType = getSecondReturnEleType(method);
        final Class<?> firstReturnEleEleType = getFirstReturnEleEleType(method);

        final int paramLen = paramTypes.length;
        final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];
        final boolean isListQuery = isListQuery(method, returnType, queryOperation, fullClassMethodName);
        final boolean isExists = isExistsQuery(method, queryOperation, fullClassMethodName);

        if ((queryOperation == QueryOperation.stream && !Stream.class.isAssignableFrom(returnType)) || (queryOperation == QueryOperation.query
                && !(Dataset.class.isAssignableFrom(returnType) || Tuple2.class.isAssignableFrom(returnType) || (lastParamType != null
                        && (Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType) || Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)))))) {
            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                    + " is not supported by the specified queryOperation: " + queryOperation);
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

        if ((u.Optional.class.isAssignableFrom(returnType) || java.util.Optional.class.isAssignableFrom(returnType))
                && !(queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.findOnlyOne
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
            if (queryOperation == QueryOperation.executeAndGetOutParameters) {
                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).executeAndGetOutParameters();
            } else if (queryOperation == QueryOperation.listAll) {
                if (Tuple2.class.isAssignableFrom(returnType)) {
                    if (firstReturnEleType == null || !List.class.isAssignableFrom(firstReturnEleType)) {
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
                        if (firstReturnEleEleType == null) {
                            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultSetsAndGetOutParameters(firstReturnEleEleType);
                    }
                } else {
                    if (!List.class.isAssignableFrom(returnType)) {
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
                        // For a return type of List<List<User>>, each result set's rows are mapped to the innermost element type (User),
                        // so prefer firstReturnEleEleType; fall back to firstReturnEleType for a single-level List<User> declaration.
                        final Class<?> rowType = firstReturnEleEleType != null ? firstReturnEleEleType : firstReturnEleType;

                        if (rowType == null) {
                            throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                    + " is not supported by the specified queryOperation: " + queryOperation);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultSets(rowType);
                    }
                }
            } else if (queryOperation == QueryOperation.queryAll) {
                if (Tuple2.class.isAssignableFrom(returnType)) {
                    if (firstReturnEleType == null || !List.class.isAssignableFrom(firstReturnEleType)) {
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
                    if (!List.class.isAssignableFrom(returnType)) {
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

                    if (firstReturnEleEleType == null) {
                        throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + fullClassMethodName
                                + " is not supported by the specified queryOperation: " + queryOperation);
                    }

                    return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAndGetOutParameters(firstReturnEleEleType);
                }
            }
        }

        final MappedByKey mappedByKeyAnno = method.getAnnotation(MappedByKey.class);
        final Class<? extends Map> targetMapClass = Strings.isEmpty(mappedByKey) ? null : mappedByKeyAnno.mapClass();

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
                            return (preparedQuery, args) -> (R) preparedQuery.stream((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1])
                                    .toCollection(Suppliers.ofCollection((Class<Collection>) returnType));
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.stream((Jdbc.RowMapper) args[paramLen - 1])
                                    .toCollection(Suppliers.ofCollection((Class<Collection>) returnType));
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
                                    args) -> (R) preparedQuery.stream((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1])
                                            .toCollection(Suppliers.ofCollection((Class<Collection>) returnType));
                        } else {
                            return (preparedQuery, args) -> (R) preparedQuery.stream((Jdbc.BiRowMapper) args[paramLen - 1])
                                    .toCollection(Suppliers.ofCollection((Class<Collection>) returnType));
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
            if (Collection.class.isAssignableFrom(returnType) || u.Optional.class.isAssignableFrom(returnType)
                    || java.util.Optional.class.isAssignableFrom(returnType)) {
                final Class<?> targetEntityClass = !Beans.isBeanClass(firstReturnEleType) ? entityClass : firstReturnEleType;
                final boolean isCollection = Collection.class.isAssignableFrom(returnType);
                final boolean isJavaOption = java.util.Optional.class.isAssignableFrom(returnType);

                return (preparedQuery, args) -> {
                    final Dataset dataset = (Dataset) preparedQuery.query(Jdbc.ResultExtractor.toDataset(targetEntityClass, prefixFieldMap));
                    final List<Object> mergedEntities = dataset.toMergedEntities(mergedByIds, dataset.columnNames(), prefixFieldMap, targetEntityClass);

                    if (isCollection) {
                        if (returnType.isAssignableFrom(mergedEntities.getClass())) {
                            return (R) mergedEntities;
                        } else {
                            final Collection<Object> c = N.newCollection((Class<Collection>) returnType);
                            c.addAll(mergedEntities);

                            return (R) c;
                        }
                    } else {
                        if (isFindOnlyOne(method, queryOperation) && N.size(mergedEntities) > 1) {
                            throw new DuplicateResultException("More than one record found by the query defined or generated in method: " + method.getName());
                        }

                        if (isJavaOption) {
                            return (R) (N.isEmpty(mergedEntities) ? java.util.Optional.empty() : java.util.Optional.of(N.firstOrNullIfEmpty(mergedEntities)));
                        } else {
                            return (R) (N.isEmpty(mergedEntities) ? Optional.empty() : Optional.of(N.firstOrNullIfEmpty(mergedEntities)));
                        }
                    }
                };
            } else {
                throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName()
                        + " is not supported by method annotated with @MergedById. Only Optional/List/Collection are supported at present");
            }
        } else if (isExists) {
            if (isNotExistsMethodName(method.getName())) {
                return (preparedQuery, args) -> (R) (Boolean) preparedQuery.notExists();
            } else {
                return (preparedQuery, args) -> (R) (Boolean) preparedQuery.exists();
            }
        } else if (isListQuery) {
            checkReturnEleTypeResolved(firstReturnEleType, returnType, fullClassMethodName);

            if (returnType.equals(List.class)) {
                return (preparedQuery, args) -> (R) preparedQuery.list(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
            } else {
                return (preparedQuery, args) -> (R) preparedQuery.stream(BiRowMapper.to(firstReturnEleType, prefixFieldMap))
                        .toCollection(Suppliers.ofCollection((Class<Collection>) returnType));
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
        } else if (u.Optional.class.isAssignableFrom(returnType) || java.util.Optional.class.isAssignableFrom(returnType)
                || Nullable.class.isAssignableFrom(returnType)) {
            checkReturnEleTypeResolved(firstReturnEleType, returnType, fullClassMethodName);

            if (Nullable.class.isAssignableFrom(returnType)) {
                if (isQueryForUnique(method, queryOperation)) {
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
            } else if (java.util.Optional.class.isAssignableFrom(returnType)) {
                if (isFindFirst(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(firstReturnEleType, prefixFieldMap)).toJdkOptional();
                } else if (isFindOnlyOne(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne(BiRowMapper.to(firstReturnEleType, prefixFieldMap)).toJdkOptional();
                } else if (isQueryForUnique(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueNonNull(firstReturnEleType).toJdkOptional();
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(firstReturnEleType).toJdkOptional();
                }
            } else {
                throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName() + " is not supported at present");
            }
        } else {
            if (isFindOrListTargetClass(returnType)) {
                if (isFindOnlyOne(method, queryOperation)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne(Jdbc.BiRowMapper.to(returnType, prefixFieldMap)).orElseNull();
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(Jdbc.BiRowMapper.to(returnType, prefixFieldMap)).orElseNull();
                }
            } else {
                return createSingleQueryFunction(returnType, method, queryOperation);
            }
        }
    }

    // Fails DAO creation instead of every invocation when a raw or wildcard generic return type (e.g.
    // List, Stream, Nullable<?>) leaves the element type unresolved.
    private static void checkReturnEleTypeResolved(final Class<?> firstReturnEleType, final Class<?> returnType, final String fullClassMethodName) {
        if (firstReturnEleType == null) {
            throw new UnsupportedOperationException("The element type of the return type: " + returnType + " in method: " + fullClassMethodName
                    + " can't be resolved. Raw or wildcard generic return types are not supported");
        }
    }

    private static boolean isFindOrListTargetClass(final Class<?> cls) {
        return Beans.isBeanClass(cls) || Map.class.isAssignableFrom(cls) || List.class.isAssignableFrom(cls) || Object[].class.isAssignableFrom(cls)
                || Beans.isRecordClass(cls);
    }

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

    @SuppressWarnings("rawtypes")
    private static Jdbc.BiParametersSetter<AbstractQuery, Object[]> createParametersSetter(final QueryInfo queryInfo, final String fullClassMethodName,
            final Method method, final Class<?>[] paramTypes, final int paramLen, final int fragmentParamLen, final int[] stmtParamIndexes,
            final boolean[] bindListParamFlags, final int stmtParamLen) {

        Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter = null;

        // ParametersSetter/BiParametersSetter/TriParametersSetter method parameters are detected but the
        // feature is not enabled at present: reject at DAO creation instead of building setters that are never used.
        if ((paramLen - fragmentParamLen > 0 && Jdbc.ParametersSetter.class.isAssignableFrom(paramTypes[fragmentParamLen]))
                || (paramLen - fragmentParamLen > 1 && (Jdbc.BiParametersSetter.class.isAssignableFrom(paramTypes[fragmentParamLen + 1])
                        || Jdbc.TriParametersSetter.class.isAssignableFrom(paramTypes[fragmentParamLen + 1])))) {
            throw new UnsupportedOperationException(
                    "Setting parameters by 'ParametersSetter/BiParametersSetter/TriParametersSetter' is not enabled at present. Can't use it in method: "
                            + fullClassMethodName);
        }

        if (stmtParamLen == 0) {
            if (queryInfo.isNamedQuery) {
                validateNamedParameterBindings(queryInfo, java.util.Collections.emptyList(), fullClassMethodName);
            }
        } else if (queryInfo.isBatch) { //NOSONAR
            // ignore
        } else if (stmtParamLen == 1) {
            final Class<?> paramTypeOne = paramTypes[stmtParamIndexes[0]];

            if (queryInfo.isProcedure) {
                final String paramName = Stream.of(method.getParameterAnnotations()[stmtParamIndexes[0]])
                        .select(Bind.class)
                        .map(Bind::value)
                        .first()
                        .orElseNull();

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
                final String paramName = Stream.of(method.getParameterAnnotations()[stmtParamIndexes[0]])
                        .select(Bind.class)
                        .map(Bind::value)
                        .first()
                        .orElseNull();

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

        if (queryInfo.isNamedQuery && queryInfo.autoSetSysTimeParam) {
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

    private static void validateNamedParameterBindings(final QueryInfo queryInfo, final Collection<String> boundParamNames, final String fullClassMethodName) {
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
        final List<String> extraParamNames = N.difference(boundParamNameSet, sqlParamNameSet);

        if (N.notEmpty(missingParamNames) || N.notEmpty(extraParamNames)) {
            throw new UnsupportedOperationException("In method: " + fullClassMethodName
                    + ", named SQL parameters and @Bind names do not match. Missing bindings: " + missingParamNames + "; extra bindings: " + extraParamNames);
        }
    }

    // A procedure whose SQL uses named parameters is prepared as a CallableQuery, not a NamedQuery;
    // both declare named setters but share no common interface for them.
    private static void setSysTimestampParam(@SuppressWarnings("rawtypes") final AbstractQuery preparedQuery, final String parameterName,
            final java.sql.Timestamp value) throws SQLException {
        if (preparedQuery instanceof CallableQuery) {
            ((CallableQuery) preparedQuery).setTimestamp(parameterName, value);
        } else {
            ((NamedQuery) preparedQuery).setTimestamp(parameterName, value);
        }
    }

    private static void setSysDateParam(@SuppressWarnings("rawtypes") final AbstractQuery preparedQuery, final String parameterName, final java.sql.Date value)
            throws SQLException {
        if (preparedQuery instanceof CallableQuery) {
            ((CallableQuery) preparedQuery).setDate(parameterName, value);
        } else {
            ((NamedQuery) preparedQuery).setDate(parameterName, value);
        }
    }

    @SuppressWarnings({ "rawtypes", "unused" })
    private static AbstractQuery prepareQuery(final DaoBase proxy, final QueryInfo queryInfo, final MergedById mergedByIdAnno, final String fullClassMethodName,
            final Method method, final Class<?> returnType, final Object[] args, final int[] fragmentParamIndexes,
            final Tuple2<Annotation, String>[] fragmentAnnos, final BiFunction<Annotation, Object, String>[] fragmentMappers, final boolean returnGeneratedKeys,
            final String[] generatedKeyColumnNames, final List<OutParameter> outParameterList,
            final Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter) throws SQLException {
        final QueryOperation queryOperation = queryInfo.queryOperation;
        String query = queryInfo.sql;
        ParsedSql parsedSql = queryInfo.parsedSql;

        if (N.notEmpty(fragmentAnnos)) {
            for (int i = 0, len = fragmentAnnos.length; i < len; i++) {
                query = Strings.replaceAll(query, fragmentAnnos[i]._2, fragmentMappers[i].apply(fragmentAnnos[i]._1, args[fragmentParamIndexes[i]]));
            }

            if (N.notEmpty(parsedSql.namedParameters()) || parsedSql.parameterCount() == 0) {
                parsedSql = ParsedSql.parse(query);
                query = parsedSql.originalSql();
            }
        }

        AbstractQuery preparedQuery = null;
        boolean noException = false;

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
                        || queryOperation == QueryOperation.exists || isExistsQuery(method, queryOperation, fullClassMethodName)
                        || isSingleReturnType(returnType)) {
                    preparedQuery.setFetchSize(1);
                } else if (queryOperation == QueryOperation.stream || queryOperation == QueryOperation.streamAll || Stream.class.isAssignableFrom(returnType)) {
                    preparedQuery.configureStatement(JdbcUtil.stmtSetterForStream);
                } else if (queryOperation == QueryOperation.list || queryOperation == QueryOperation.listAll || queryOperation == QueryOperation.query
                        || queryOperation == QueryOperation.queryAll || isListQuery(method, returnType, queryOperation, fullClassMethodName)) {
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
                    final boolean hasNow = queryInfo.parsedSql.namedParameters().contains(JdbcUtil.PN_NOW);
                    final boolean hasSysTime = queryInfo.parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_TIME);
                    final boolean hasSysDate = queryInfo.parsedSql.namedParameters().contains(JdbcUtil.PN_SYS_DATE);

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
            }

            noException = true;
        } finally {
            if (!noException && preparedQuery != null) {
                preparedQuery.close();
            }
        }

        return preparedQuery;
    }

    private static Condition handleLimit(final Condition cond, final int count) {
        // A non-positive count means "no framework-added limit": return the condition unchanged. Any existing Limit
        // (standalone or inside a Criteria) is preserved as-is and rendered per dialect later by the SQL builder.

        if (count <= 0) {
            return cond;
        } else if (cond == null) {
            return Filters.limit(count);
        } else if (cond instanceof final Limit limit) {
            return limit;
        } else if (cond instanceof final Criteria criteria) {
            final Limit limit = criteria.limit();

            if (limit != null) {
                return cond;
            }

            return criteria.toBuilder().limit(count).build();
        } else {
            if (cond instanceof final SqlExpression expr //
                    && Strings.containsAnyIgnoreCase(expr.literal(), " LIMIT ", " OFFSET ", " FETCH NEXT ", " FETCH FIRST ")) {
                try {
                    return Filters.limit(expr.literal());
                } catch (Exception e) {
                    // The literal carries a limit clause but isn't a parseable standalone Limit
                    // (e.g. "id > 0 FETCH FIRST 10 ROWS ONLY"); return it as-is rather than appending
                    // a second limit, which would produce invalid SQL.
                    return cond;
                }
            }

            return Criteria.builder().limit(count).add(cond).build();
        }
    }

    private static long sumUpdateCounts(final int[] updateCounts) {
        long total = 0;

        for (final int updateCount : updateCounts) {
            if (updateCount > 0) {
                total += updateCount;
            }
        }

        return total;
    }

    private static long sumUpdateCounts(final long[] updateCounts) {
        long total = 0;

        for (final long updateCount : updateCounts) {
            if (updateCount > 0) {
                total = Math.addExact(total, updateCount);
            }
        }

        return total;
    }

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

    private static void logDaoMethodPerf(final Logger daoLogger, final String simpleClassMethodName, final PerfLog perfLogAnno, final long startTimeNanos) {
        if (JdbcUtil.isDaoMethodPerfLogAllowed && perfLogAnno.daoMethodPerfLogThresholdMillis() >= 0 && daoLogger.isInfoEnabled()) {
            final long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);

            if (elapsedTime >= perfLogAnno.daoMethodPerfLogThresholdMillis()) {
                daoLogger.info(Strings.concat("[DAO-QUERY-OPERATION-PERF]-[", simpleClassMethodName, "]: ", String.valueOf(elapsedTime), " ms"));
            }
        }
    }

    private static String getTableName(final Class<?> entityClass, final BeanInfo entityInfo, final NamingPolicy namingPolicy, final String targetTableName) {
        if (Strings.isNotEmpty(targetTableName)) {
            return targetTableName;
        }

        return entityInfo.tableName.orElseGet(() -> namingPolicy.convert(ClassUtil.getSimpleClassName(entityClass)));
    }

    private static <T extends Condition> T checkCondForPaginate(final T cond) {
        N.checkArgNotNull(cond, "Condition for \"paginate\" cannot be null");

        if ((cond instanceof Criteria && ((Criteria) cond).orderBy() != null) || Strings.containsIgnoreCase(cond.toString(), " ORDER BY ")) {
            // okay, has order by
        } else {
            throw new IllegalArgumentException("Condition for \"paginate\" must have \"orderBy\"");
        }

        return cond;
    }

    private static Tuple2<Annotation, String> resolveFragmentAnnoAndPlaceholder(final Method method, final int paramIndex, final String fullClassMethodName) {
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

    private static String normalizeSqlFragmentPlaceholder(final String configuredName, final Method method, final int paramIndex,
            final String fullClassMethodName, final Class<? extends Annotation> annotationType) {
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

    @SuppressWarnings("rawtypes")
    private static final Map<String, DaoBase> joinEntityDaoPool = new ConcurrentHashMap<>();

    /**
     * Creates a dynamic proxy implementation of the specified DAO interface backed by the given {@link javax.sql.DataSource}.
     *
     * <p>This is the core factory method for DAO proxy creation. It processes the supported annotations
     * (e.g., {@code @Query}, {@code @Transactional}, {@code @CacheResult}, {@code @RefreshCache}, {@code @Handler},
     * {@code @PerfLog}, {@code @SqlLogEnabled}) declared on the DAO interface and its methods, and builds an
     * {@link InvocationHandler} that intercepts each method call to execute the corresponding SQL operation.</p>
     *
     * <p>Created DAO instances are cached by a composite key derived from the interface class, target table name,
     * data source identity, DSL dialect identity, SQL mapper identity, DAO cache identity, and executor identity.
     * Subsequent calls with the same parameters return the cached instance.</p>
     *
     * <p>In addition to method-level annotations, the method also processes type-level configuration:</p>
     * <ul>
     *   <li>{@code @SqlSource} annotations to load external SQL mapper files</li>
     *   <li>{@code @SqlScript} annotated fields for embedded SQL definitions</li>
     *   <li>{@code @DaoConfig} for configuration options like {@code addLimitForSingleQuery}</li>
     *   <li>{@code @Handler} and {@code @Handlers} for custom method handlers</li>
     *   <li>{@code @PerfLog} for performance logging of DAO method invocations</li>
     *   <li>{@code @SqlLogEnabled} for SQL statement logging control</li>
     * </ul>
     *
     * @param <TD> the DAO interface type, must extend {@link DaoBase}
     * @param daoInterface the DAO interface class to create a proxy for; must be an interface
     * @param targetTableName the database table name to use, or {@code null}/{@code ""} to derive from the entity class
     * @param ds the {@link javax.sql.DataSource} providing database connections
     * @param dsl the {@link Dsl} dialect used to generate CRUD SQL; must not be {@code null}
     * @param sqlMapper an optional {@link SqlMapper} containing pre-defined SQL statements keyed by ID; may be {@code null}
     * @param inputDaoCache an optional {@link Jdbc.DaoCache} for caching query results of methods annotated with
     *        {@code @CacheResult} (and invalidated by {@code @RefreshCache}). When {@code null}, the cache is
     *        derived from the class-level {@code @Cache} annotation (using its {@code impl()}, {@code capacity()}
     *        and {@code evictDelayMillis()}) if present, otherwise a {@link Jdbc.DefaultDaoCache} with
     *        {@link JdbcUtil#DEFAULT_CACHE_CAPACITY default capacity} and
     *        {@link JdbcUtil#DEFAULT_CACHE_EVICT_DELAY default evict delay} is used. Note: result caching is
     *        currently only supported for cacheable DAOs — {@code NonUpdateDao}/{@code ReadOnlyDao} and their
     *        {@code Unchecked} variants — supplying a non-{@code null} {@code inputDaoCache} (or declaring
     *        {@code @Cache}) on a DAO that supports update/delete operations will fail with
     *        {@link UnsupportedOperationException}
     * @param executor an optional {@link Executor} for asynchronous operations; if {@code null}, the default async executor is used
     * @return a proxy instance implementing the specified DAO interface
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null} or is not an interface, if {@code ds}
     *         is {@code null}, if {@code dsl} is {@code null}, if {@code dsl}'s SQL policy is neither {@code null}
     *         nor {@link SqlPolicy#PARAMETERIZED_SQL}, if duplicate SQL keys are defined, or if the
     *         DAO interface has invalid annotation configurations or generic type arguments
     * @throws UnsupportedOperationException if a DAO method uses an unsupported annotation configuration, an
     *         incompatible return type for the declared {@link QueryOperation}, or a feature not yet enabled (e.g., cache on a
     *         non-cacheable interface that supports update/delete operations, or a {@code RowMapper}/{@code ResultExtractor} parameter that is not the last method parameter)
     * @throws UncheckedSQLException if obtaining database product info from {@code ds} fails
     */
    @SuppressWarnings({ "rawtypes", "null", "resource" })
    static <TD extends DaoBase> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds, final Dsl dsl,
            final SqlMapper sqlMapper, final Jdbc.DaoCache inputDaoCache, final Executor executor) {
        N.checkArgNotNull(daoInterface, cs.daoInterface);
        N.checkArgNotNull(ds, cs.ds);
        N.checkArgNotNull(dsl, "dsl");

        N.checkArgument(daoInterface.isInterface(), "'daoInterface' must be an interface. It can't be {}", daoInterface);

        N.checkArgument(dsl.sqlDialect().sqlPolicy() == null || dsl.sqlDialect().sqlPolicy() == SqlPolicy.PARAMETERIZED_SQL,
                "'dsl.sqlDialect.sqlPolicy' must be null or SqlPolicy.PARAMETERIZED_SQL. It can't be {}", dsl.sqlDialect().sqlPolicy());

        final Logger daoLogger = LoggerFactory.getLogger(daoInterface);
        final String daoClassName = ClassUtil.getCanonicalClassName(daoInterface);
        final String daoCacheKey = daoClassName + "_" + targetTableName + "_" + System.identityHashCode(ds) + "_" + System.identityHashCode(dsl) + "_"
                + (sqlMapper == null ? "null" : System.identityHashCode(sqlMapper)) + "_"
                + (inputDaoCache == null ? "null" : System.identityHashCode(inputDaoCache)) + "_"
                + (executor == null ? "null" : System.identityHashCode(executor));

        TD daoInstance = (TD) daoPool.get(daoCacheKey);

        if (daoInstance != null) {
            if (daoLogger.isDebugEnabled()) {
                daoLogger.debug("Reusing cached Dao proxy(interface={}, targetTableName={}, dataSourceId={})", daoClassName, targetTableName,
                        System.identityHashCode(ds));
            }

            return daoInstance;
        }

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

                        newSqlMapper.add(key, it.get(key));
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

        final java.lang.reflect.Type[] typeArguments = Stream.of(allInterfaces)
                .flatMapArray(Class::getGenericInterfaces)
                .select(ParameterizedType.class)
                .filter(it -> {
                    if (!(it.getRawType() instanceof final Class<?> rawType)) {
                        return false;
                    }

                    return DaoBase.class.isAssignableFrom(rawType);
                })
                .map(ParameterizedType::getActualTypeArguments)
                .filter(it -> N.notEmpty(it) && it[0] instanceof Class)
                .first()
                .orElseNull();

        if (isCrudDao && (N.isEmpty(typeArguments) || (typeArguments.length < 2) || !(typeArguments[1] instanceof Class))) {
            throw new IllegalArgumentException("Failed to resolve entity/id generic type parameters for DAO interface: " + daoClassName);
        }

        if (N.notEmpty(typeArguments)) {
            if ((typeArguments.length >= 1 && typeArguments[0] instanceof Class) && !Beans.isBeanClass((Class) typeArguments[0])) {
                throw new IllegalArgumentException(
                        "Entity Type parameter of Dao interface must be: Object.class or entity class with getter/setter methods. Can't be: "
                                + typeArguments[0]);
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
        final NamingPolicy namingPolicy = sqlDialect.namingPolicy() == null ? NamingPolicy.SNAKE_CASE : sqlDialect.namingPolicy();
        final Dsl parameterizedDsl = parameterizedDsl(dsl);
        final Dsl namedDsl = namedDsl(dsl);

        final Class<Object> entityClass = N.isEmpty(typeArguments) ? null : (Class) typeArguments[0];
        final BeanInfo entityInfo = entityClass == null ? null : ParserUtil.getBeanInfo(entityClass);
        final String tableName = entityInfo == null ? null : getTableName(entityClass, entityInfo, namingPolicy, targetTableName);

        final Class<?> idClass = isCrudDao ? (Class) typeArguments[1] : null;
        final boolean isEntityId = idClass != null && EntityId.class.isAssignableFrom(idClass);
        final BeanInfo idBeanInfo = Beans.isBeanClass(idClass) ? ParserUtil.getBeanInfo(idClass) : null;

        final Function<Condition, SqlBuilder.SP> selectFromSqlBuilderFunc = cond -> cond instanceof final Criteria criteria
                && Strings.isNotEmpty(criteria.selectModifier())
                        ? parameterizedDsl.select(entityClass).selectModifier(criteria.selectModifier()).from(tableName).append(cond).build()
                        : parameterizedDsl.select(entityClass).from(tableName).append(cond).build();

        final BiFunction<String, Condition, SqlBuilder.SP> singleQuerySqlBuilderFunc = (selectPropName,
                cond) -> cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.selectModifier())
                        ? parameterizedDsl.select(selectPropName).selectModifier(criteria.selectModifier()).from(tableName, entityClass).append(cond).build()
                        : parameterizedDsl.select(selectPropName).from(tableName, entityClass).append(cond).build();

        final BiFunction<String, Condition, SqlBuilder.SP> singleQueryByIdSqlBuilderFunc = (selectPropName,
                cond) -> namedDsl.select(selectPropName).from(tableName, entityClass).append(cond).build();

        final BiFunction<Collection<String>, Condition, SqlBuilder> selectSqlBuilderFunc = (selectPropNames, cond) -> N.isEmpty(selectPropNames)
                ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.selectModifier())
                        ? parameterizedDsl.select(entityClass).selectModifier(criteria.selectModifier()).from(tableName).append(cond)
                        : parameterizedDsl.select(entityClass).from(tableName).append(cond))
                : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.selectModifier())
                        ? parameterizedDsl.select(selectPropNames).selectModifier(criteria.selectModifier()).from(tableName, entityClass).append(cond)
                        : parameterizedDsl.select(selectPropNames).from(tableName, entityClass).append(cond);

        final BiFunction<Collection<String>, Condition, SqlBuilder> namedSelectSqlBuilderFunc = (selectPropNames, cond) -> N.isEmpty(selectPropNames)
                ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.selectModifier())
                        ? namedDsl.select(entityClass).selectModifier(criteria.selectModifier()).from(tableName).append(cond)
                        : namedDsl.select(entityClass).from(tableName).append(cond))
                : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.selectModifier())
                        ? namedDsl.select(selectPropNames).selectModifier(criteria.selectModifier()).from(tableName, entityClass).append(cond)
                        : namedDsl.select(selectPropNames).from(tableName, entityClass).append(cond);

        final Function<Collection<String>, SqlBuilder> namedInsertSqlBuilderFunc = propNamesToInsert -> N.isEmpty(propNamesToInsert)
                ? namedDsl.insert(entityClass).into(tableName)
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
        final Function<Object, Condition> id2CondFunc = isNoId || idClass == null ? null
                : (isEntityId ? id -> Filters.id2Cond((EntityId) id)
                        : Map.class.isAssignableFrom(idClass) ? id -> Filters.allEqual((Map<String, ?>) id)
                                : Beans.isBeanClass(idClass) || Beans.isRecordClass(idClass) ? Filters::allEqual : id -> Filters.eq(oneIdPropName, id));

        N.checkArgument(idPropNameList.size() > 1 || !(isEntityId || (idClass != null && (Beans.isBeanClass(idClass) || Map.class.isAssignableFrom(idClass)))),
                "Id type/class cannot be EntityId/Map or Entity for single id");

        String sql_getById = null;
        String sql_existsById = null;
        String sql_insertWithId = null;
        String sql_insertWithoutId = null;
        String sql_updateById = null;
        String sql_deleteById = null;

        final boolean noOtherInsertPropNameExceptIdPropNames = entityClass != null
                && (idPropNameSet.containsAll(Beans.getPropNameList(entityClass)) || N.isEmpty(QueryUtil.insertPropNames(entityClass, idPropNameSet)));
        final Collection<String> propNamesToUpdateById = entityClass == null ? N.emptyList() : QueryUtil.updatePropNames(entityClass, idPropNameSet);
        final boolean noPropNameToUpdateById = N.isEmpty(propNamesToUpdateById);

        sql_getById = isNoId ? null : namedDsl.select(entityClass).from(tableName).where(idCond).build().query();
        sql_existsById = isNoId ? null : namedDsl.select(_1).from(tableName, entityClass).where(idCond).build().query();
        sql_insertWithId = entityClass == null ? null : namedDsl.insert(entityClass).into(tableName).build().query();
        sql_insertWithoutId = entityClass == null ? null
                : (noOtherInsertPropNameExceptIdPropNames ? sql_insertWithId : namedDsl.insert(entityClass, idPropNameSet).into(tableName).build().query());
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

        final Tuple3<Jdbc.BiRowMapper<Object>, Function<Object, Object>, BiConsumer<Object, Object>> tp3 = JdbcUtil.getIdGeneratorGetterSetter(daoInterface,
                entityClass, namingPolicy, idClass);

        final Holder<Jdbc.BiRowMapper<Object>> idExtractorHolder = new Holder<>();
        final Jdbc.BiRowMapper<Object> idExtractor = tp3._1;
        final Function<Object, Object> idGetter = tp3._2;
        final BiConsumer<Object, Object> idSetter = tp3._3;

        final Predicate<Object> isDefaultIdTester = isNoId ? id -> true
                : (isOneId ? JdbcUtil::isDefaultIdPropValue
                        : (isEntityId ? id -> Stream.of(((EntityId) id).entrySet()).allMatch(it -> JdbcUtil.isDefaultIdPropValue(it.getValue())) : id -> {
                            if (idBeanInfo == null) {
                                throw new IllegalStateException("ID class " + idClass + " is not a bean class and cannot be used for composite ID");
                            }
                            return Stream.of(idPropNameList).allMatch(idName -> JdbcUtil.isDefaultIdPropValue(idBeanInfo.getPropValue(id, idName)));
                        }));

        final Jdbc.BiParametersSetter<NamedQuery, Object> idParamSetter = isOneId ? (pq, id) -> pq.setObject(oneIdPropName, id, idPropInfo.dbType)
                : (isEntityId ? (pq, id) -> {
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

                    PropInfo propInfo = null;

                    for (final String idName : idPropNameList) {
                        propInfo = idBeanInfo.getPropInfo(idName);
                        pq.setObject(idName, propInfo.getPropValue(id), propInfo.dbType);
                    }
                });

        final Jdbc.BiParametersSetter<NamedQuery, Object> idParamSetterByEntity = isOneId
                ? (pq, entity) -> pq.setObject(oneIdPropName, idPropInfo.getPropValue(entity), idPropInfo.dbType)
                : (pq, entity) -> pq.settParameters(entity, objParamsSetter);

        CacheResult tmpDaoClassCacheResultAnno = null;
        RefreshCache tmpDaoClassRefreshCacheAnno = null;
        com.landawn.abacus.jdbc.annotation.Cache tmpDaoClassCacheAnno = null;

        for (final Class<?> itf : allInterfaces) {
            for (final Annotation anno : itf.getAnnotations()) {
                if (tmpDaoClassCacheResultAnno == null && anno instanceof CacheResult cr) {
                    tmpDaoClassCacheResultAnno = cr;
                } else if (tmpDaoClassRefreshCacheAnno == null && anno instanceof RefreshCache rc) {
                    tmpDaoClassRefreshCacheAnno = rc;
                } else if (tmpDaoClassCacheAnno == null && anno instanceof com.landawn.abacus.jdbc.annotation.Cache ca) {
                    tmpDaoClassCacheAnno = ca;
                }
            }

            if (tmpDaoClassCacheResultAnno != null && tmpDaoClassRefreshCacheAnno != null && tmpDaoClassCacheAnno != null) {
                break;
            }
        }

        final CacheResult daoClassCacheResultAnno = tmpDaoClassCacheResultAnno;
        final RefreshCache daoClassRefreshCacheAnno = tmpDaoClassRefreshCacheAnno;

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
                && (daoClassCacheResultAnno != null || daoClassRefreshCacheAnno != null || inputDaoCache != null || daoClassCacheAnno != null)) {
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

        final Jdbc.DaoCache daoCache = inputDaoCache == null //
                ? (daoClassCacheAnno == null ? Jdbc.DaoCache.create(capacity, evictDelay) // annotation members can't be null: impl() defaults to DefaultDaoCache
                        : ClassUtil.invokeConstructor(ClassUtil.getDeclaredConstructor(daoClassCacheAnno.impl(), int.class, long.class), capacity, evictDelay))
                : inputDaoCache;

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
                sqlList = Stream.of(queryAnno.value())
                        .append(queryAnno.id())
                        .map(Fn.strip())
                        .filter(Fn.notEmpty())
                        .map(it -> newSqlMapper.get(it) == null ? it : newSqlMapper.get(it).parameterizedSql())
                        .map(sql -> sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql)
                        .toList();
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

            if (!Modifier.isAbstract(method.getModifiers())) {
                final MethodHandle methodHandle = createMethodHandle(method);

                call = (proxy, args) -> {
                    if (prepareSqlGate != 0) {
                        final String sqlToCheck;

                        if (prepareSqlIsParsed) {
                            N.checkArgNotNull(args[0], "namedSql");
                            sqlToCheck = ((ParsedSql) args[0]).originalSql();
                        } else {
                            // Validated before the SQL-kind check so null/empty SQL fails with the same IAE
                            // a full DAO would throw, not a misleading "Only SELECT ..." UOE.
                            sqlToCheck = N.checkArgNotEmpty((String) args[0], cs.sql);
                        }

                        if (prepareSqlGate == 1) {
                            if (!SqlParser.isReadOnlyQuery(sqlToCheck)) {
                                throw new UnsupportedOperationException("Only SELECT queries are supported in a read-only DAO");
                            }
                        } else if (!SqlParser.isNoUpdateQuery(sqlToCheck)) {
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

                    return methodHandle.bindTo(proxy).invokeWithArguments(args == null ? N.EMPTY_OBJECT_ARRAY : args);
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
                call = (proxy, args) -> {
                    final Collection<String> selectPropNames = (Collection<String>) args[0];
                    final Condition cond = N.checkArgNotNull((Condition) args[1], cs.cond);
                    final Condition limitedCond = handleLimit(cond, -1);
                    final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                    return proxy.prepareQuery(sp.query()).settParameters(sp.parameters(), collParamsSetter);
                };
            } else if (methodName.equals("prepareNamedQuery") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                    && Condition.class.isAssignableFrom(paramTypes[1])) {
                call = (proxy, args) -> {
                    final Collection<String> selectPropNames = (Collection<String>) args[0];
                    final Condition cond = N.checkArgNotNull((Condition) args[1], cs.cond);
                    final Condition limitedCond = handleLimit(cond, -1);
                    final SP sp = namedSelectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();

                    return proxy.prepareNamedQuery(sp.query()).settParameters(sp.parameters(), collParamsSetter);
                };
            } else {
                final boolean isStreamReturn = Stream.class.isAssignableFrom(returnType);
                final boolean throwsSQLException = Stream.of(method.getExceptionTypes()).anyMatch(e -> e.isAssignableFrom(SQLException.class));
                final boolean throwsUncheckedSQLException = Stream.of(method.getExceptionTypes())
                        .anyMatch(e -> e.isAssignableFrom(UncheckedSQLException.class));
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

                            final ParsedSql namedInsertSql = isNoId || N.allMatch(entities, entity -> isDefaultIdTester.test(idGetter.apply(entity)))
                                    ? namedInsertWithoutIdSQL
                                    : namedInsertWithIdSQL;

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSql).addBatchParameters(entities).batchUpdate();
                            } else {
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

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSql).addBatchParameters(entities).batchUpdate();
                            } else {
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

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSql).addBatchParameters(entities).batchUpdate();
                            } else {
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(_1, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).exists();
                        };
                    } else if (methodName.equals("count") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(AbstractQueryBuilder.COUNT_ALL, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForInt().orElseZero();
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = selectFromSqlBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
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

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = selectSqlBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query()).setFetchSize(2).settParameters(sp.parameters(), collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("queryForBoolean") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForBytes") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query()).setFetchSize(1).settParameters(sp.parameters(), collParamsSetter).queryForBytes();
                        };
                    } else if (methodName.equals("queryForSingleValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForSingleValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForSingleNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .query((Jdbc.ResultExtractor<Optional<?>>) rs -> rs.next() ? Optional.of(rowMapper.apply(rs)) : Optional.empty());
                        };
                    } else if (methodName.equals("queryForUniqueValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(2)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForUniqueValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query())
                                    .setFetchSize(2)
                                    .settParameters(sp.parameters(), collParamsSetter)
                                    .queryForUniqueNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(cond, cs.cond);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = singleQuerySqlBuilderFunc.apply(selectPropName, limitedCond);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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
                            final int pageSize = N.checkArgPositive((Integer) args[1], "pageSize");
                            final Jdbc.BiParametersSetter<PreparedQuery, Dataset> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[2],
                                    "paramSetter");
                            final Jdbc.ResultExtractor<Dataset> resultExtractor = fetchColumnByEntityClass ? Jdbc.ResultExtractor.toDataset(entityClass)
                                    : Jdbc.ResultExtractor.TO_DATASET;

                            final Condition limitedCond = handleLimit(cond, pageSize);
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
                            final int pageSize = N.checkArgPositive((Integer) args[1], "pageSize");
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[2],
                                    "paramSetter");
                            final Jdbc.ResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.ResultExtractor) args[3], "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, pageSize);
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
                            final int pageSize = N.checkArgPositive((Integer) args[1], "pageSize");
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[2],
                                    "paramSetter");
                            final Jdbc.BiResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.BiResultExtractor) args[3], "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, pageSize);
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
                            final int pageSize = N.checkArgPositive((Integer) args[2], "pageSize");
                            final Jdbc.BiParametersSetter<PreparedQuery, Dataset> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[3],
                                    "paramSetter");
                            final Jdbc.ResultExtractor<Dataset> resultExtractor = fetchColumnByEntityClass ? Jdbc.ResultExtractor.toDataset(entityClass)
                                    : Jdbc.ResultExtractor.TO_DATASET;

                            final Condition limitedCond = handleLimit(cond, pageSize);
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
                            final int pageSize = N.checkArgPositive((Integer) args[2], "pageSize");
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[3],
                                    "paramSetter");
                            final Jdbc.ResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.ResultExtractor) args[4], "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, pageSize);
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
                            final int pageSize = N.checkArgPositive((Integer) args[2], "pageSize");
                            final Jdbc.BiParametersSetter<PreparedQuery, Object> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[3],
                                    "paramSetter");
                            final Jdbc.BiResultExtractor<Object> resultExtractor = N.checkArgNotNull((Jdbc.BiResultExtractor) args[4], "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, pageSize);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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

                            final Condition limitedCond = handleLimit(cond, -1);
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
                            final Map<String, Object> propsToUpdate = (Map<String, Object>) args[0];
                            final Condition cond = (Condition) args[1];

                            N.checkArgNotEmpty(propsToUpdate, cs.propsToUpdate);
                            N.checkArgNotNull(cond, cs.cond);

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass).set(propsToUpdate).append(cond).build();
                            return proxy.prepareQuery(sp.query()).settParameters(sp.parameters(), collParamsSetter).update();
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

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).append(cond).build();

                            final Jdbc.BiParametersSetter<AbstractQuery, Object> parametersSetter = (pq, p) -> {
                                final PreparedStatement stmt = pq.stmt;
                                PropInfo propInfo = null;
                                int columnIndex = 1;

                                for (final String propName : propNamesToUpdate) {
                                    propInfo = entityInfo.getPropInfo(propName);
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
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, cs.entity);

                            ParsedSql namedInsertSql = null;

                            if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                if (callGenerateIdForInsert) {
                                    idSetter.accept(DaoUtil.generateId(proxy), entity);

                                    namedInsertSql = namedInsertWithIdSQL;
                                } else {
                                    namedInsertSql = namedInsertWithoutIdSQL;
                                }
                            } else {
                                namedInsertSql = namedInsertWithIdSQL;
                            }

                            return JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                    .settParameters(entity, objParamsSetter)
                                    .insert(keyExtractor, isDefaultIdTester)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final Object entity = args[0];
                            final Collection<String> propNamesToInsert = (Collection<String>) args[1];
                            N.checkArgNotNull(entity, cs.entity);
                            N.checkArgNotEmpty(propNamesToInsert, cs.propNamesToInsert);

                            if ((callGenerateIdForInsert && !N.disjoint(propNamesToInsert, idPropNameSet)) && isDefaultIdTester.test(idGetter.apply(entity))) {
                                idSetter.accept(DaoUtil.generateId(proxy), entity);
                            }

                            final String namedInsertSql = namedInsertSqlBuilderFunc.apply(propNamesToInsert).build().query();

                            return JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                    .settParameters(entity, objParamsSetter)
                                    .insert(keyExtractor, isDefaultIdTester)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final String namedInsertSql = (String) args[0];
                            final Object entity = args[1];
                            N.checkArgNotEmpty(namedInsertSql, cs.namedInsertSql);
                            N.checkArgNotNull(entity, cs.entity);

                            if (callGenerateIdForInsertWithSql && isDefaultIdTester.test(idGetter.apply(entity))) {
                                idSetter.accept(DaoUtil.generateId(proxy), entity);
                            }

                            return JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                    .settParameters(entity, objParamsSetter)
                                    .insert(keyExtractor, isDefaultIdTester)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            boolean allDefaultIdValue = N.allMatch(entities, entity -> isDefaultIdTester.test(idGetter.apply(entity)));

                            if (callGenerateIdForInsert) {
                                for (final Object entity : entities) {
                                    if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                        idSetter.accept(DaoUtil.generateId(proxy), entity);
                                    }
                                }

                                allDefaultIdValue = false;
                            }

                            final ParsedSql namedInsertSql = allDefaultIdValue ? namedInsertWithoutIdSQL : namedInsertWithIdSQL;
                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                        .addBatchParameters(entities)
                                        .batchInsert(keyExtractor, isDefaultIdTester);
                            } else {
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
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
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            if (JdbcUtil.isAllNullIds(ids)) {
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

                            if (N.isEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final Collection<?> entities = (Collection<Object>) args[0];

                            final Collection<String> propNamesToInsert = (Collection<String>) args[1];
                            N.checkArgNotEmpty(propNamesToInsert, cs.propNamesToInsert);

                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            if (callGenerateIdForInsert && !N.disjoint(propNamesToInsert, idPropNameSet)) {
                                for (final Object entity : entities) {
                                    if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                        idSetter.accept(DaoUtil.generateId(proxy), entity);
                                    }
                                }
                            }

                            final String namedInsertSql = namedInsertSqlBuilderFunc.apply(propNamesToInsert).build().query();
                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                        .addBatchParameters(entities)
                                        .batchInsert(keyExtractor, isDefaultIdTester);
                            } else {
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
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
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            if (JdbcUtil.isAllNullIds(ids)) {
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

                            if (N.isEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 3 && String.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final String namedInsertSql = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgNotEmpty(namedInsertSql, cs.namedInsertSql);
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            if (callGenerateIdForInsertWithSql) {
                                for (final Object entity : entities) {
                                    if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                        idSetter.accept(DaoUtil.generateId(proxy), entity);
                                    }
                                }
                            }

                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = JdbcUtil.prepareNamedQuery(proxy.dataSource(), namedInsertSql, generatedKeyColumnNames)
                                        .addBatchParameters(entities)
                                        .batchInsert(keyExtractor, isDefaultIdTester);
                            } else {
                                final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
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
                                    throw e;
                                } finally {
                                    JdbcUtil.rollbackAfterTransactionCommand(tran, failure);
                                }
                            }

                            if (JdbcUtil.isAllNullIds(ids)) {
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

                            if (N.isEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("queryForBoolean") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForBytes") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForBytes();
                        };
                    } else if (methodName.equals("queryForSingleValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForSingleValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(1).settParameters(id, idParamSetter).queryForSingleNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query())
                                    .setFetchSize(1)
                                    .settParameters(id, idParamSetter)
                                    .query((Jdbc.ResultExtractor<Optional<?>>) rs -> rs.next() ? Optional.of(rowMapper.apply(rs)) : Optional.empty());
                        };
                    } else if (methodName.equals("queryForUniqueValue") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(2).settParameters(id, idParamSetter).queryForUniqueValue(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(targetValueType, cs.targetValueType);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query()).setFetchSize(2).settParameters(id, idParamSetter).queryForUniqueNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, cs.selectPropName);
                            N.checkArgNotNull(id, cs.id);
                            N.checkArgNotNull(rowMapper, cs.rowMapper);

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1);
                            final SP sp = singleQueryByIdSqlBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query())
                                    .setFetchSize(2)
                                    .settParameters(id, idParamSetter)
                                    .query((Jdbc.ResultExtractor<Optional<?>>) rs -> {
                                        if (rs.next()) {
                                            final Object val = rowMapper.apply(rs);

                                            if (rs.next()) {
                                                throw new com.landawn.abacus.exception.DuplicateResultException("More than one record found for query by id");
                                            }

                                            return Optional.of(val);
                                        }

                                        return Optional.empty();
                                    });
                        };
                    } else if (methodName.equals("gett")) {
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

                            final String query = namedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).where(idCond).build().query();

                            return proxy.prepareNamedQuery(query).settParameters(entity, objParamsSetter).update();
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> props = (Map<String, Object>) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(props, cs.propsToUpdate);
                            N.checkArgNotNull(id, cs.id);

                            // TODO not set by PropInfo.dbType of ids? it should be okay because Id should be simple type(int, long, String, UUID, Timestamp).
                            // If want to use idParamSetter, it has to be named sql. How to prepare/set named parameters? it's a problem to resolve.
                            final Condition cond = id2CondFunc.apply(id);

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass).set(props).append(cond).build();
                            return proxy.prepareQuery(sp.query()).settParameters(sp.parameters(), collParamsSetter).update();
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

                            if (entities.size() <= batchSize) {
                                result = sumUpdateCounts(proxy.prepareNamedQuery(namedUpdateByIdSQL).addBatchParameters(entities).batchUpdate());
                            } else {
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
                            N.checkArgPositive(batchSize, cs.batchSize);

                            if (N.isEmpty(entities)) {
                                return 0;
                            }

                            final String query = namedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).where(idCond).build().query();
                            long result = 0;

                            if (entities.size() <= batchSize) {
                                result = sumUpdateCounts(proxy.prepareNamedQuery(query).addBatchParameters(entities).batchUpdate());
                            } else {
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

                            if (idsOrEntities.size() <= batchSize) {
                                return Numbers.toIntExact(sumUpdateCounts(
                                        proxy.prepareNamedQuery(namedDeleteByIdSQL).addBatchParameters(idsOrEntities, paramSetter).batchUpdate()));
                            } else {
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

                                if (!propEntities.isEmpty()) {
                                    final Map<Object, Object> m = N.newMap((Class) propJoinInfo.joinPropInfo.clazz, 1);
                                    m.put(propJoinInfo.srcEntityKeyExtractor.apply(entity), propEntities.get(0));
                                    propJoinInfo.joinPropInfo.setPropValue(entity, m);
                                }
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

                                    if (!propEntities.isEmpty()) {
                                        final Map<Object, Object> m = N.newMap((Class) propJoinInfo.joinPropInfo.clazz, 1);
                                        m.put(propJoinInfo.srcEntityKeyExtractor.apply(first), propEntities.get(0));
                                        propJoinInfo.joinPropInfo.setPropValue(first, m);
                                    }
                                } else {
                                    propJoinInfo.joinPropInfo.setPropValue(first, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orElseNull());
                                }
                            } else {
                                final Tuple2<BiFunction<Collection<String>, Integer, String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                                        .batchSelectSqlPlan(parameterizedDsl);

                                Stream.of(entities).split(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(bp -> {
                                    if (propJoinInfo.isManyToManyJoin()) {
                                        final Jdbc.BiRowMapper<Pair<Object, Object>> pairBiRowMapper = new Jdbc.BiRowMapper<>() {
                                            private Jdbc.BiRowMapper<Object> biRowMapper = null;
                                            private int columnCount = 0;
                                            private List<String> selectCls = null;

                                            @Override
                                            public Pair<Object, Object> apply(final ResultSet rs, final List<String> cls) throws SQLException {
                                                if (columnCount == 0) {
                                                    columnCount = cls.size();
                                                    selectCls = cls.subList(0, cls.size() - 1);
                                                    biRowMapper = Jdbc.BiRowMapper.to(propJoinInfo.referencedEntityClass);
                                                }

                                                return Pair.of(JdbcUtil.getColumnValue(rs, columnCount), biRowMapper.apply(rs, selectCls));
                                            }
                                        };

                                        final List<Pair<Object, Object>> joinPropEntities = joinEntityDao.prepareQuery(tp._1.apply(selectPropNames, bp.size()))
                                                .setParameters(bp, tp._2)
                                                .list(pairBiRowMapper);

                                        propJoinInfo.setJoinPropEntities(bp, Stream.of(joinPropEntities).groupTo(Pair::left, Pair::right));
                                    } else {
                                        final List<?> joinPropEntities = joinEntityDao.prepareQuery(tp._1.apply(selectPropNames, bp.size()))
                                                .setParameters(bp, tp._2)
                                                .list(propJoinInfo.referencedEntityClass);

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

                            final DaoBase<?, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource, proxy);

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

                            final DaoBase<?, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource, proxy);

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
                    // primitive-only meant those wrapper branches were unreachable and @Update methods declared with
                    // Integer/Long/Boolean returns failed at the QueryOperation.DEFAULT dispatch with "Unsupported sql annotation".
                    final boolean isUpdateReturnType = returnType.equals(int.class) || returnType.equals(long.class) || returnType.equals(boolean.class)
                            || returnType.equals(void.class) || returnType.equals(Integer.class) || returnType.equals(Long.class)
                            || returnType.equals(Boolean.class);

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

                    final boolean isQuery = queryInfo.isSelect
                            || (isProcedure && !(queryOperation == QueryOperation.update || queryOperation == QueryOperation.largeUpdate)
                                    && (queryOperation != QueryOperation.DEFAULT || !isUpdateReturnType));

                    final boolean returnGeneratedKeys = !isNoId && queryInfo.isInsert;

                    final boolean isNamedQuery = queryInfo.isNamedQuery;

                    // Custom annotated methods execute through JdbcUtil.prepareQuery/prepareNamedQuery directly,
                    // bypassing the proxy's prepareSqlGate, so enforce the read-only/non-update SQL-kind
                    // restriction here at DAO-creation time with the same SqlParser checks the gate uses.
                    if (isReadOnlyDao && !SqlParser.isReadOnlyQuery(query)) {
                        throw new UnsupportedOperationException("Only SELECT queries are supported in a read-only DAO. Method: " + fullClassMethodName);
                    } else if (isNonUpdateDao && !SqlParser.isNoUpdateQuery(query)) {
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

                        if (isNamedQuery) {
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
                    final BiFunction<Annotation, Object, String> arraySqlFragmentListParamMapper = (anno,
                            param) -> param == null || Array.getLength(param) == 0 ? "" : N.toJson(param, jsc_no_bracket);
                    final BiFunction<Annotation, Object, String> collSqlFragmentListParamMapper = (anno, param) -> param == null ? ""
                            : N.toJson(param, jsc_no_bracket);

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

                    if (stmtParamLen == 1
                            && (Beans.isBeanClass(paramTypes[stmtParamIndexes[0]]) || Map.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])
                                    || EntityId.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]) || Beans.isRecordClass(paramTypes[stmtParamIndexes[0]]))
                            && !isNamedQuery) {
                        throw new UnsupportedOperationException(
                                "A parameter of type Entity/Map/EntityId requires @Query with named parameters (:name syntax) in method: "
                                        + fullClassMethodName);
                    }

                    if (isSingleParameter && stmtParamLen != 1) {
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
                            throw new IllegalArgumentException(
                                    "The return type of method(" + fullClassMethodName + ") annotated by @MappedByKey must be: Map<? super "
                                            + ClassUtil.getSimpleClassName(ClassUtil.wrap(mappedByKeyMethod.getReturnType())) + ", ? super "
                                            + ClassUtil.getSimpleClassName(entityClass) + ">. It can't be: " + method.getGenericReturnType());
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

                            if (!(((Collection.class.isAssignableFrom(returnType)
                                    && (queryOperation == QueryOperation.list || queryOperation == QueryOperation.DEFAULT))
                                    || ((u.Optional.class.isAssignableFrom(returnType) || java.util.Optional.class.isAssignableFrom(returnType))
                                            && (queryOperation == QueryOperation.findFirst || queryOperation == QueryOperation.findOnlyOne
                                                    || queryOperation == QueryOperation.DEFAULT)))
                                    && (firstReturnEleType != null && firstReturnEleType.isAssignableFrom(entityClass)))) {
                                throw new IllegalArgumentException("The return type of method(" + fullClassMethodName
                                        + ") annotated by @MergedById must be: Optional/List/Collection<? super " + ClassUtil.getSimpleClassName(entityClass)
                                        + ">. It can't be: " + method.getGenericReturnType());
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
                                    + ") annotated by @PrefixFieldMapping must be: Optional/List/Collection<? super "
                                    + ClassUtil.getSimpleClassName(entityClass) + ">/Dataset/" + ClassUtil.getSimpleClassName(entityClass) + ". It can't be: "
                                    + method.getGenericReturnType());
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

                    if (isQuery) {
                        final Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> queryFunc = createQueryFunctionByMethod(entityClass, method,
                                mappedByKey, mergedByIds, prefixFieldMap, fetchColumnByEntityClass, hasRowMapperOrResultExtractor, hasRowFilter, queryOperation,
                                isProcedure, fullClassMethodName);

                        call = (proxy, args) -> queryFunc.apply(
                                prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args, fragmentParamIndexes,
                                        fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList, parametersSetter),
                                args);
                    } else if (queryInfo.isInsert) {
                        if (isNoId && !returnType.isAssignableFrom(void.class)) {
                            throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                    + ") for no id entities only can be: void. It can't be: " + returnType);
                        }

                        final TriFunction<Optional<Object>, Object, Boolean, ?> insertResultConverter = void.class.equals(returnType)
                                ? (ret, entity, isEntity) -> null
                                : (u.Optional.class.equals(returnType) ? (ret, entity, isEntity) -> ret
                                        : (ret, entity, isEntity) -> ret.orElse(isEntity ? idGetter.apply(entity) : N.defaultValueOf(returnType))); //NOSONAR

                        if (!isBatch) {
                            if (!(returnType.equals(void.class) || idClass == null || ClassUtil.wrap(idClass).isAssignableFrom(ClassUtil.wrap(returnType))
                                    || u.Optional.class.isAssignableFrom(returnType))) {
                                throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                        + ") only can be: void, the 'ID' type, or Optional<ID>. It can't be: " + returnType);
                            }

                            call = (proxy, args) -> {
                                final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                                final boolean isEntity = stmtParamLen == 1 && args[stmtParamIndexes[0]] != null
                                        && Beans.isBeanClass(args[stmtParamIndexes[0]].getClass());
                                final Object entity = isEntity ? args[stmtParamIndexes[0]] : null;

                                final Optional<Object> id = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                        fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames, outParameterList,
                                        parametersSetter).insert(keyExtractor, isDefaultIdTester);

                                if (isEntity && id.isPresent()) {
                                    idSetter.accept(id.get(), entity);
                                }

                                return insertResultConverter.apply(id, entity, isEntity);
                            };
                        } else {
                            if (!(returnType.equals(void.class) || List.class.isAssignableFrom(returnType))) {
                                throw new UnsupportedOperationException("The return type of batch insert operations(" + fullClassMethodName
                                        + ") only can be: void/List<ID>. It can't be: " + returnType);
                            }

                            call = (proxy, args) -> {
                                final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                                final Collection<Object> batchParameters = (Collection) args[stmtParamIndexes[0]];
                                int batchSize = tmpBatchSize;

                                if (stmtParamLen == 2) {
                                    batchSize = (Integer) args[stmtParamIndexes[1]];
                                }

                                if (batchSize == 0) {
                                    batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                                }

                                N.checkArgPositive(batchSize, cs.batchSize);

                                List<Object> ids = null;

                                if (N.isEmpty(batchParameters)) {
                                    ids = new ArrayList<>(0);
                                } else if (batchParameters.size() <= batchSize) {
                                    AbstractQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames,
                                                outParameterList, parametersSetter).addBatchParameters(batchParameters, ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames,
                                                outParameterList, parametersSetter).addBatchParameters(batchParameters);
                                    }

                                    ids = preparedQuery.batchInsert(keyExtractor, isDefaultIdTester);
                                } else {
                                    final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                    Throwable failure = null;

                                    try {
                                        try (AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method,
                                                returnType, args, fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys,
                                                generatedKeyColumnNames, outParameterList, parametersSetter).closeAfterExecution(false)) {

                                            if (isSingleParameter) {
                                                ids = Seq.of(batchParameters)
                                                        .split(batchSize)
                                                        .flatmap(bp -> preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT)
                                                                .batchInsert(keyExtractor, isDefaultIdTester))
                                                        .toList();
                                            } else {
                                                ids = Seq.of((Collection<List<?>>) (Collection) batchParameters)
                                                        .split(batchSize) //
                                                        .flatmap(bp -> preparedQuery.addBatchParameters(bp).batchInsert(keyExtractor, isDefaultIdTester))
                                                        .toList();
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

                                final Object firstElement = N.firstOrNullIfEmpty(batchParameters);
                                final boolean isEntity = firstElement != null && Beans.isBeanClass(firstElement.getClass());

                                if (JdbcUtil.isAllNullIds(ids)) {
                                    ids = new ArrayList<>();
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

                                    if (N.isEmpty(ids)) {
                                        ids = Stream.of(entities).map(idGetter).toList();
                                    }
                                }

                                if ((N.notEmpty(ids) && N.size(ids) != N.size(batchParameters)) && daoLogger.isWarnEnabled()) {
                                    daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            batchParameters.size());
                                }

                                return void.class.equals(returnType) ? null : ids;
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
                                final AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType,
                                        args, fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames,
                                        outParameterList, parametersSetter);

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
                                } else if (batchParameters.size() <= batchSize) {
                                    AbstractQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames,
                                                outParameterList, parametersSetter).addBatchParameters(batchParameters, ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys, generatedKeyColumnNames,
                                                outParameterList, parametersSetter).addBatchParameters(batchParameters);
                                    }

                                    if (isLargeUpdate) {
                                        updatedRecordCount = sumUpdateCounts(preparedQuery.largeBatchUpdate());
                                    } else {
                                        updatedRecordCount = sumUpdateCounts(preparedQuery.batchUpdate());
                                    }
                                } else {
                                    final SqlTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                    Throwable failure = null;

                                    try {
                                        try (AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method,
                                                returnType, args, fragmentParamIndexes, fragmentAnnos, fragmentMappers, returnGeneratedKeys,
                                                generatedKeyColumnNames, outParameterList, parametersSetter).closeAfterExecution(false)) {

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
                        && (BaseStream.class.isAssignableFrom(returnType) || java.util.stream.BaseStream.class.isAssignableFrom(returnType))) {
                    // REQUIRED/REQUIRES_NEW can complete an invocation-owned transaction before consumption.
                    // NOT_SUPPORTED/NEVER enforce a non-transactional context only for the invocation, but lazy query
                    // setup can occur later after an outer transaction is restored or a new one starts. SUPPORTS and
                    // MANDATORY are safe when the caller consumes the stream before changing/completing its context.
                    throw new UnsupportedOperationException(
                            "@Transactional with propagation " + transactionalAnno.propagation() + " is not supported on stream-returning method: "
                                    + fullClassMethodName + ". Consume the stream inside a non-streaming transactional method instead");
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
                            final SqlLogConfig sqlPerfLogConfig = JdbcUtil.perfLogThresholdMillis_TL.get();
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
                            final SqlLogConfig sqlPerfLogConfig = JdbcUtil.perfLogThresholdMillis_TL.get();
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
                        };
                    } else {
                        call = (proxy, args) -> {
                            if (transactionalAnno.propagation() == Propagation.MANDATORY && !JdbcUtil.isInTransaction(proxy.dataSource())) {
                                throw new IllegalStateException("The method: " + fullClassMethodName + " with @Transactional(propagation = "
                                        + transactionalAnno.propagation() + ") must be called in a transaction.");
                            }

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
                                final SqlLogConfig sqlPerfLogConfig = JdbcUtil.perfLogThresholdMillis_TL.get();
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
                                final SqlLogConfig sqlPerfLogConfig = JdbcUtil.perfLogThresholdMillis_TL.get();
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
                final CacheResult cacheResultAnno = methodCacheResultAnno != null ? (methodCacheResultAnno.enabled() ? methodCacheResultAnno : null)
                        : ((daoClassCacheResultAnno != null //
                                && daoClassCacheResultAnno.enabled() //
                                && !Strings.containsAnyIgnoreCase(method.getName(), "page", "paginate") //
                                && N.anyMatch(daoClassCacheResultAnno.filter(), filterByMethodName)) //
                                        ? daoClassCacheResultAnno
                                        : null);

                final RefreshCache methodRefreshCacheAnno = Stream.of(method.getAnnotations()).select(RefreshCache.class).last().orElseNull();
                final RefreshCache refreshResultAnno = methodRefreshCacheAnno != null ? (methodRefreshCacheAnno.enabled() ? methodRefreshCacheAnno : null)
                        : ((daoClassRefreshCacheAnno != null //
                                && daoClassRefreshCacheAnno.enabled() //
                                && N.anyMatch(daoClassRefreshCacheAnno.filter(), filterByMethodName)) //
                                        ? daoClassRefreshCacheAnno
                                        : null);

                final long cacheLiveTime = cacheResultAnno == null ? 0 : cacheResultAnno.maxLiveTimeMillis();
                final long cacheMaxIdleTime = cacheResultAnno == null ? 0 : cacheResultAnno.maxIdleTimeMillis();

                final boolean isQueryMethod = JdbcUtil.IS_QUERY_METHOD.test(method);
                final boolean isUpdateMethod = JdbcUtil.IS_UPDATE_METHOD.test(method);
                final boolean isAnnotatedCacheResult = cacheResultAnno != null && cacheResultAnno.enabled();
                final boolean isAnnotatedRefreshResult = refreshResultAnno != null && refreshResultAnno.enabled();
                final Jdbc.DaoCache daoCacheToUseInMethod = isAnnotatedCacheResult || isAnnotatedRefreshResult ? daoCache : null;

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
                        return !isValuePresentMap.getOrDefault(cls, Fn.alwaysFalse()).test(r) && isImmutableTester.test(cls);
                    };

                    final Function<Object, Object> cloneFunc = switch (serialization) {
                        case NONE -> Fn.identity();
                        case KRYO -> r -> {
                            if (serializationNotRequired.test(r)) {
                                return r;
                            }

                            if (kryoParser == null) {
                                throw new UnsupportedOperationException(
                                        "Kryo is not available for cache serialization. Please add Kryo to the classpath or use CacheSerialization.JSON instead.");
                            }

                            return kryoParser.deepCopy(r);
                        };
                        case JSON -> r -> serializationNotRequired.test(r) ? r : jsonParser.deserialize(jsonParser.serialize(r), r.getClass());
                    };

                    final Throwables.BiFunction<DaoBase, Object[], ?, Throwable> temp = call;

                    call = (proxy, args) -> {
                        final Jdbc.DaoCache localThreadCache = JdbcUtil.localThreadCache_TL.get();
                        final boolean isLocalThreadCacheEnabled = isQueryMethod && localThreadCache != null;
                        final boolean isRefreshLocalThreadCacheRequired = isUpdateMethod && localThreadCache != null;

                        final String cacheKey = isAnnotatedCacheResult || isAnnotatedRefreshResult || isLocalThreadCacheEnabled
                                || isRefreshLocalThreadCacheRequired ? JdbcUtil.createCacheKey(tableName, fullClassMethodName, args, daoLogger) : null;

                        Object result = null;

                        if (Strings.isNotEmpty(cacheKey)) {
                            if (isLocalThreadCacheEnabled) {
                                result = localThreadCache.get(cacheKey, proxy, args, methodSignature);
                            } else if (isAnnotatedCacheResult) {
                                result = daoCacheToUseInMethod.get(cacheKey, proxy, args, methodSignature);
                            }
                        }

                        if (result != null) {
                            return cloneFunc.apply(result);
                        }

                        if (isAnnotatedRefreshResult || isRefreshLocalThreadCacheRequired) {
                            try {
                                result = temp.apply(proxy, args);
                            } finally {
                                // Cache-key serialization is optional for query caching, but it must never
                                // suppress write invalidation. If an argument cannot be serialized, retain the
                                // stable method/table portion so built-in and custom caches can still identify
                                // the affected table (and custom caches still receive args/methodSignature).
                                final String refreshCacheKey = Strings.isNotEmpty(cacheKey) ? cacheKey
                                        : Strings.concat(fullClassMethodName, JdbcUtil.CACHE_KEY_SPLITOR, tableName, JdbcUtil.CACHE_KEY_SPLITOR);

                                if (isRefreshLocalThreadCacheRequired) {
                                    localThreadCache.update(refreshCacheKey, result, proxy, args, methodSignature);
                                }

                                if (isAnnotatedRefreshResult) {
                                    daoCacheToUseInMethod.update(refreshCacheKey, result, proxy, args, methodSignature);
                                }
                            }
                        } else {
                            result = temp.apply(proxy, args);
                        }

                        if (Strings.isNotEmpty(cacheKey) && result != null) {
                            if (isLocalThreadCacheEnabled) {
                                localThreadCache.put(cacheKey, cloneFunc.apply(result), proxy, args, methodSignature);
                            } else if (isAnnotatedCacheResult) {
                                if (result instanceof final Dataset dataset) {
                                    if (dataset.size() >= cacheResultAnno.minSize() && dataset.size() <= cacheResultAnno.maxSize()) {
                                        daoCacheToUseInMethod.put(cacheKey, cloneFunc.apply(result), cacheLiveTime, cacheMaxIdleTime, proxy, args,
                                                methodSignature);
                                    }
                                } else if (result instanceof Collection) {
                                    final Collection<Object> c = (Collection<Object>) result;

                                    if (c.size() >= cacheResultAnno.minSize() && c.size() <= cacheResultAnno.maxSize()) {
                                        daoCacheToUseInMethod.put(cacheKey, cloneFunc.apply(result), cacheLiveTime, cacheMaxIdleTime, proxy, args,
                                                methodSignature);
                                    }
                                } else {
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
                        .map(handlerAnno -> Tuple.of((Jdbc.Handler) (Strings.isNotEmpty(handlerAnno.qualifier())
                                ? daoClassHandlerMap.getOrDefault(handlerAnno.qualifier(), HandlerFactory.get(handlerAnno.qualifier()))
                                : HandlerFactory.getOrCreate(handlerAnno.impl())), handlerAnno.externalCallsOnly()))
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

                                    if (afterInvokeFailure == null) {
                                        afterInvokeFailure = t;
                                    } else {
                                        afterInvokeFailure.addSuppressed(t);
                                    }
                                }
                            }

                            if (failure != null) {
                                if (afterInvokeFailure != null) {
                                    failure.addSuppressed(afterInvokeFailure);
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

                                        if (afterInvokeFailure == null) {
                                            afterInvokeFailure = t;
                                        } else {
                                            afterInvokeFailure.addSuppressed(t);
                                        }
                                    }
                                }

                                if (failure != null) {
                                    if (afterInvokeFailure != null) {
                                        failure.addSuppressed(afterInvokeFailure);
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

        daoInstance = N.newProxyInstance(interfaceClasses, h);

        final DaoBase existingDaoInstance = daoPool.putIfAbsent(daoCacheKey, daoInstance);

        if (existingDaoInstance != null) {
            if (daoLogger.isDebugEnabled()) {
                daoLogger.debug("Discarding concurrently created Dao proxy(interface={}, targetTableName={}); using cached instance", daoClassName,
                        targetTableName);
            }

            return (TD) existingDaoInstance;
        }

        daoLogger.info("Created Dao proxy(interface={}, targetTableName={}, methods={})", daoClassName, targetTableName, methodInvokerMap.size());

        return daoInstance;
    }

    /**
     * Resolves the most appropriate DAO instance for loading a join entity referenced by a {@code @JoinedBy} relationship.
     *
     * <p>This method searches the internal DAO pool for a registered DAO whose target entity class matches the
     * specified {@code referencedEntityClass} and whose data source matches the provided {@code ds}. If a matching
     * DAO is found, it is cached in the join entity DAO pool for subsequent lookups and returned. If no matching
     * DAO is found, a warning is logged and the {@code defaultDao} is returned as a fallback.</p>
     *
     * <p><b>Note:</b> The fallback {@code defaultDao} is intentionally not cached to allow the correct DAO to be
     * found on later invocations if it is registered after this call (e.g., during application initialization).</p>
     *
     * @param referencedEntityClass the entity class of the join target (the entity being referenced)
     * @param ds the {@link javax.sql.DataSource} that the returned DAO should be associated with
     * @param defaultDao the fallback DAO to return if no specific DAO is found for the referenced entity class
     * @return the DAO instance best suited for loading instances of {@code referencedEntityClass}, or
     *         {@code defaultDao} if no matching DAO has been registered
     */
    @SuppressWarnings("rawtypes")
    static DaoBase getApplicableDaoForJoinEntity(final Class<?> referencedEntityClass, final javax.sql.DataSource ds, final DaoBase defaultDao) {
        final String key = ClassUtil.getCanonicalClassName(referencedEntityClass) + "_" + System.identityHashCode(ds);
        final DaoBase joinEntityDao = joinEntityDaoPool.get(key);

        if (joinEntityDao != null && Objects.equals(joinEntityDao.targetEntityClass(), referencedEntityClass)
                && Objects.equals(joinEntityDao.dataSource(), ds)) {
            return joinEntityDao;
        } else {
            for (final DaoBase dao : daoPool.values()) {
                if (Objects.equals(dao.targetEntityClass(), referencedEntityClass) && Objects.equals(dao.dataSource(), ds)) {
                    joinEntityDaoPool.put(key, dao);
                    return dao;
                }
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
        final String sql;
        final ParsedSql parsedSql;
        final int queryTimeout;
        final int fetchSize;
        final boolean isBatch;
        final int batchSize;
        final QueryOperation queryOperation;
        final boolean isSingleParameter;
        final boolean autoSetSysTimeParam;
        final boolean isSelect;
        final boolean isInsert;
        final boolean isProcedure;
        final boolean isNamedQuery;

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
         * @param QueryOperation the {@link QueryOperation} operation type controlling execution behavior
         * @param isSingleParameter {@code true} if a single method parameter should be bound as-is rather than decomposed
         * @param autoSetSysTimeParam {@code true} to automatically set system time parameters (e.g., create/update timestamps)
         * @param isSelect {@code true} if this is a SELECT statement
         * @param isInsert {@code true} if this is an INSERT statement
         * @param isProcedure {@code true} if this SQL represents a stored procedure call
         * @param fragmentsContainNamedParameters {@code true} if any {@code @SqlFragment} parameters use named parameters
         * @throws IllegalArgumentException if {@code sql} is blank, or if {@code fragmentsContainNamedParameters} is
         *         {@code true} but the SQL uses positional (?) parameters without named parameters
         */
        QueryInfo(final String sql, final ParsedSql parsedSql, final int queryTimeout, final int fetchSize, final boolean isBatch, final int batchSize,
                final QueryOperation queryOperation, final boolean isSingleParameter, final boolean autoSetSysTimeParam, final boolean isSelect,
                final boolean isInsert, final boolean isProcedure, final boolean fragmentsContainNamedParameters) {
            this.sql = N.checkArgNotBlank(sql != null && sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql, "sql");
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
            isNamedQuery = N.notEmpty(this.parsedSql.namedParameters()) || fragmentsContainNamedParameters;

            if (fragmentsContainNamedParameters && (this.parsedSql.parameterCount() > 0 && N.isEmpty(this.parsedSql.namedParameters()))) {
                throw new IllegalArgumentException("'fragmentsContainNamedParameters' is true, but the resolved SQL has no named parameters");
            }
        }
    }
}
