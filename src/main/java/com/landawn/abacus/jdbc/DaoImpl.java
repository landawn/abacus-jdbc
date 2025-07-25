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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc.BiParametersSetter;
import com.landawn.abacus.jdbc.Jdbc.BiRowMapper;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.jdbc.Jdbc.HandlerFactory;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.BindList;
import com.landawn.abacus.jdbc.annotation.CacheResult;
import com.landawn.abacus.jdbc.annotation.Call;
import com.landawn.abacus.jdbc.annotation.Config;
import com.landawn.abacus.jdbc.annotation.Define;
import com.landawn.abacus.jdbc.annotation.DefineList;
import com.landawn.abacus.jdbc.annotation.Delete;
import com.landawn.abacus.jdbc.annotation.FetchColumnByEntityClass;
import com.landawn.abacus.jdbc.annotation.Handler;
import com.landawn.abacus.jdbc.annotation.HandlerList;
import com.landawn.abacus.jdbc.annotation.Insert;
import com.landawn.abacus.jdbc.annotation.MappedByKey;
import com.landawn.abacus.jdbc.annotation.MergedById;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.jdbc.annotation.OutParameter;
import com.landawn.abacus.jdbc.annotation.OutParameterList;
import com.landawn.abacus.jdbc.annotation.PerfLog;
import com.landawn.abacus.jdbc.annotation.PrefixFieldMapping;
import com.landawn.abacus.jdbc.annotation.RefreshCache;
import com.landawn.abacus.jdbc.annotation.Select;
import com.landawn.abacus.jdbc.annotation.SqlField;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.annotation.SqlMapper;
import com.landawn.abacus.jdbc.annotation.Sqls;
import com.landawn.abacus.jdbc.annotation.Transactional;
import com.landawn.abacus.jdbc.annotation.Update;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.CrudDaoL;
import com.landawn.abacus.jdbc.dao.CrudJoinEntityHelper;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.jdbc.dao.JoinEntityHelper;
import com.landawn.abacus.jdbc.dao.NoUpdateDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDaoL;
import com.landawn.abacus.jdbc.dao.UncheckedDao;
import com.landawn.abacus.jdbc.dao.UncheckedJoinEntityHelper;
import com.landawn.abacus.jdbc.dao.UncheckedNoUpdateDao;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.JSONParser;
import com.landawn.abacus.parser.JSONSerializationConfig;
import com.landawn.abacus.parser.JSONSerializationConfig.JSC;
import com.landawn.abacus.parser.KryoParser;
import com.landawn.abacus.parser.ParserFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.AbstractQueryBuilder.SP;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.SQLBuilder.NAC;
import com.landawn.abacus.query.SQLBuilder.NLC;
import com.landawn.abacus.query.SQLBuilder.NSC;
import com.landawn.abacus.query.SQLBuilder.PAC;
import com.landawn.abacus.query.SQLBuilder.PLC;
import com.landawn.abacus.query.SQLBuilder.PSC;
import com.landawn.abacus.query.SQLMapper;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.ConditionFactory;
import com.landawn.abacus.query.condition.ConditionFactory.CF;
import com.landawn.abacus.query.condition.Criteria;
import com.landawn.abacus.query.condition.Expression;
import com.landawn.abacus.query.condition.Limit;
import com.landawn.abacus.query.condition.SubQuery;
import com.landawn.abacus.util.Array;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.Dates;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IntFunctions;
import com.landawn.abacus.util.Suppliers;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.Immutable;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.ImmutableMap;
import com.landawn.abacus.util.ImmutableSet;
import com.landawn.abacus.util.Joiner;
import com.landawn.abacus.util.MutableBoolean;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Numbers;
import com.landawn.abacus.util.Pair;
import com.landawn.abacus.util.Seq;
import com.landawn.abacus.util.Splitter;
import com.landawn.abacus.util.Splitter.MapSplitter;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.WD;
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
import com.landawn.abacus.util.stream.IntStream.IntStreamEx;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

@Internal
@SuppressWarnings({ "deprecation", "java:S1192", "resource" })
final class DaoImpl {

    private DaoImpl() {
        // singleton for utility class.
    }

    private static final Set<String> SUPPORTED_TRANSFER_FOR_CACHE = N.asSet("none", "kryo", "json");

    private static final String _1 = "1";

    private static final String PN_NOW = "now";

    private static final JSONParser jsonParser = ParserFactory.createJSONParser();
    private static final KryoParser kryoParser = ParserFactory.isKryoAvailable() ? ParserFactory.createKryoParser() : null;

    private static final JSONSerializationConfig jsc_no_bracket = JSC.create().setStringQuotation(JdbcUtil.CHAR_ZERO).bracketRootValue(false);

    static final ThreadLocal<Boolean> isInDaoMethod_TL = ThreadLocal.withInitial(() -> false);

    @SuppressWarnings("rawtypes")
    private static final Map<String, Dao> daoPool = new ConcurrentHashMap<>();

    private static final Map<Class<? extends Annotation>, BiFunction<Annotation, SQLMapper, QueryInfo>> sqlAnnoMap = new HashMap<>();

    static {
        sqlAnnoMap.put(Select.class, (final Annotation anno, final SQLMapper sqlMapper) -> {
            final Select tmp = (Select) anno;
            int queryTimeout = tmp.queryTimeout();
            int fetchSize = tmp.fetchSize();
            final boolean isBatch = false;
            final int batchSize = -1;
            final OP op = tmp.op() == null ? OP.DEFAULT : tmp.op();
            final boolean isSingleParameter = tmp.isSingleParameter();

            ParsedSql parsedSql = null;
            String sql = Strings.stripToEmpty(tmp.sql());

            if (Strings.isEmpty(sql)) {
                sql = Strings.trim(tmp.value());
            }

            if (Strings.isNotEmpty(sql) == Strings.isNotEmpty(tmp.id())) {
                throw new IllegalArgumentException("Sql script and id both are empty or both are not empty: " + Strings.concat(sql, ", ", tmp.id()));
            }

            if (!Strings.containsWhitespace(sql) && sqlMapper != null && sqlMapper.get(sql) != null) {
                sql = sqlMapper.get(sql).getParameterizedSql();
            }

            final String id = Strings.isNotEmpty(sql) && sqlMapper != null && sqlMapper.get(sql) != null ? sql : tmp.id();

            if (Strings.isNotEmpty(id)) {
                if (sqlMapper == null || sqlMapper.get(id) == null || Strings.isEmpty(sqlMapper.get(id).getParameterizedSql())) {
                    throw new IllegalArgumentException("No predefined sql found by id: " + id);
                }

                parsedSql = sqlMapper.get(id);
                sql = parsedSql.getParameterizedSql();

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = Numbers.toInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.FETCH_SIZE)) {
                        fetchSize = Numbers.toInt(attrs.get(SQLMapper.FETCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, parsedSql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter, tmp.timestamped(), true, false,
                    tmp.hasDefineWithNamedParameter());
        });

        sqlAnnoMap.put(Insert.class, (final Annotation anno, final SQLMapper sqlMapper) -> {
            final Insert tmp = (Insert) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = OP.DEFAULT;
            final boolean isSingleParameter = tmp.isSingleParameter();

            ParsedSql parsedSql = null;
            String sql = Strings.stripToEmpty(tmp.sql());

            if (Strings.isEmpty(sql)) {
                sql = Strings.trim(tmp.value());
            }

            if (Strings.isNotEmpty(sql) == Strings.isNotEmpty(tmp.id())) {
                throw new IllegalArgumentException("Sql script and id both are empty or both are not empty: " + Strings.concat(sql, ", ", tmp.id()));
            }

            if (!Strings.containsWhitespace(sql) && sqlMapper != null && sqlMapper.get(sql) != null) {
                sql = sqlMapper.get(sql).getParameterizedSql();
            }

            final String id = Strings.isNotEmpty(sql) && sqlMapper != null && sqlMapper.get(sql) != null ? sql : tmp.id();

            if (Strings.isNotEmpty(id)) {
                if (sqlMapper == null || sqlMapper.get(id) == null || Strings.isEmpty(sqlMapper.get(id).getParameterizedSql())) {
                    throw new IllegalArgumentException("No predefined sql found by id: " + id);
                }

                parsedSql = sqlMapper.get(id);
                sql = parsedSql.getParameterizedSql();

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = Numbers.toInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = Numbers.toInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, parsedSql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter, tmp.timestamped(), false, false,
                    tmp.hasDefineWithNamedParameter());
        });

        sqlAnnoMap.put(Update.class, (final Annotation anno, final SQLMapper sqlMapper) -> {
            final Update tmp = (Update) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = tmp.op() == null ? OP.update : tmp.op();
            final boolean isSingleParameter = tmp.isSingleParameter();

            ParsedSql parsedSql = null;
            String sql = Strings.stripToEmpty(tmp.sql());

            if (Strings.isEmpty(sql)) {
                sql = Strings.trim(tmp.value());
            }

            if (Strings.isNotEmpty(sql) == Strings.isNotEmpty(tmp.id())) {
                throw new IllegalArgumentException("Sql script and id both are empty or both are not empty: " + Strings.concat(sql, ", ", tmp.id()));
            }

            if (!Strings.containsWhitespace(sql) && sqlMapper != null && sqlMapper.get(sql) != null) {
                sql = sqlMapper.get(sql).getParameterizedSql();
            }

            final String id = Strings.isNotEmpty(sql) && sqlMapper != null && sqlMapper.get(sql) != null ? sql : tmp.id();

            if (Strings.isNotEmpty(id)) {
                if (sqlMapper == null || sqlMapper.get(id) == null || Strings.isEmpty(sqlMapper.get(id).getParameterizedSql())) {
                    throw new IllegalArgumentException("No predefined sql found by id: " + id);
                }

                parsedSql = sqlMapper.get(id);
                sql = parsedSql.getParameterizedSql();

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = Numbers.toInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = Numbers.toInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, parsedSql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter, tmp.timestamped(), false, false,
                    tmp.hasDefineWithNamedParameter());
        });

        sqlAnnoMap.put(Delete.class, (final Annotation anno, final SQLMapper sqlMapper) -> {
            final Delete tmp = (Delete) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = tmp.isBatch();
            int batchSize = tmp.batchSize();
            final OP op = OP.DEFAULT;
            final boolean isSingleParameter = tmp.isSingleParameter();

            ParsedSql parsedSql = null;
            String sql = Strings.stripToEmpty(tmp.sql());

            if (Strings.isEmpty(sql)) {
                sql = Strings.trim(tmp.value());
            }

            if (Strings.isNotEmpty(sql) == Strings.isNotEmpty(tmp.id())) {
                throw new IllegalArgumentException("Sql script and id both are empty or both are not empty: " + Strings.concat(sql, ", ", tmp.id()));
            }

            if (!Strings.containsWhitespace(sql) && sqlMapper != null && sqlMapper.get(sql) != null) {
                sql = sqlMapper.get(sql).getParameterizedSql();
            }

            final String id = Strings.isNotEmpty(sql) && sqlMapper != null && sqlMapper.get(sql) != null ? sql : tmp.id();

            if (Strings.isNotEmpty(id)) {
                if (sqlMapper == null || sqlMapper.get(id) == null || Strings.isEmpty(sqlMapper.get(id).getParameterizedSql())) {
                    throw new IllegalArgumentException("No predefined sql found by id: " + id);
                }

                parsedSql = sqlMapper.get(id);
                sql = parsedSql.getParameterizedSql();

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notEmpty(attrs)) {
                    if (attrs.containsKey(SQLMapper.TIMEOUT)) {
                        queryTimeout = Numbers.toInt(attrs.get(SQLMapper.TIMEOUT));
                    }

                    if (attrs.containsKey(SQLMapper.BATCH_SIZE)) {
                        batchSize = Numbers.toInt(attrs.get(SQLMapper.BATCH_SIZE));
                    }
                }
            }

            return new QueryInfo(sql, parsedSql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter, tmp.timestamped(), false, false,
                    tmp.hasDefineWithNamedParameter());
        });

        sqlAnnoMap.put(Call.class, (final Annotation anno, final SQLMapper sqlMapper) -> {
            final Call tmp = (Call) anno;
            int queryTimeout = tmp.queryTimeout();
            final int fetchSize = -1;
            final boolean isBatch = false;
            final int batchSize = -1;
            final OP op = OP.DEFAULT;
            final boolean isSingleParameter = tmp.isSingleParameter();

            ParsedSql parsedSql = null;
            String sql = Strings.stripToEmpty(tmp.sql());

            if (Strings.isEmpty(sql)) {
                sql = Strings.trim(tmp.value());
            }

            if (Strings.isNotEmpty(sql) == Strings.isNotEmpty(tmp.id())) {
                throw new IllegalArgumentException("Sql script and id both are empty or both are not empty: " + Strings.concat(sql, ", ", tmp.id()));
            }

            if (!Strings.containsWhitespace(sql) && sqlMapper != null && sqlMapper.get(sql) != null) {
                sql = sqlMapper.get(sql).getParameterizedSql();
            }

            final String id = Strings.isNotEmpty(sql) && sqlMapper != null && sqlMapper.get(sql) != null ? sql : tmp.id();

            if (Strings.isNotEmpty(id)) {
                if (sqlMapper == null || sqlMapper.get(id) == null || Strings.isEmpty(sqlMapper.get(id).getParameterizedSql())) {
                    throw new IllegalArgumentException("No predefined sql found by id: " + id);
                }

                parsedSql = sqlMapper.get(id);
                sql = parsedSql.getParameterizedSql();

                final Map<String, String> attrs = sqlMapper.getAttrs(id);

                if (N.notEmpty(attrs) && attrs.containsKey(SQLMapper.TIMEOUT)) {
                    queryTimeout = Numbers.toInt(attrs.get(SQLMapper.TIMEOUT));
                }
            }

            return new QueryInfo(sql, parsedSql, queryTimeout, fetchSize, isBatch, batchSize, op, isSingleParameter, false, true, true, false);
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

    private static final Predicate<? super Class<?>> isImmutableTester = cls -> Immutable.class.isAssignableFrom(cls);

    private static final Set<Class<?>> notCacheableTypes = N.asSet(void.class, Void.class, Iterator.class, java.util.stream.BaseStream.class, BaseStream.class,
            EntryStream.class, Stream.class, Seq.class);

    @SuppressWarnings("rawtypes")
    private static final Jdbc.BiParametersSetter<AbstractQuery, Collection> collParamsSetter = AbstractQuery::setParameters;

    private static final Jdbc.BiParametersSetter<NamedQuery, Object> objParamsSetter = NamedQuery::setParameters; // NOSONAR

    /**
     * Creates the method handle.
     *
     * @param method
     * @return
     */
    private static MethodHandle createMethodHandle(final Method method) {
        final Class<?> declaringClass = method.getDeclaringClass();

        try {
            return MethodHandles.lookup().in(declaringClass).unreflectSpecial(method, declaringClass);
        } catch (final Exception e) {
            try {
                final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
                ClassUtil.setAccessibleQuietly(constructor, true);

                return constructor.newInstance(declaringClass).in(declaringClass).unreflectSpecial(method, declaringClass);
            } catch (final Exception ex) {
                try {
                    return MethodHandles.lookup()
                            .findSpecial(declaringClass, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                                    declaringClass);
                } catch (final Exception exx) {
                    throw new UnsupportedOperationException(exx);
                }
            }
        }
    }

    private static final Set<String> singleQueryPrefix = N.asSet("get", "findFirst", "findOne", "findOnlyOne", "selectFirst", "selectOne", "selectOnlyOne",
            "exist", "notExist", "has", "is");

    /**
     * Checks if is list query.
     *
     * @param method
     * @return {@code true}, if is list query
     */
    private static boolean isListQuery(final Method method, final Class<?> returnType, final OP op, final String fullClassMethodName) {
        final String methodName = method.getName();
        final Class<?>[] paramTypes = method.getParameterTypes();
        final int paramLen = paramTypes.length;

        if (op == OP.list || op == OP.listAll) {
            if (Collection.class.equals(returnType) || !(Collection.class.isAssignableFrom(returnType))) {
                throw new UnsupportedOperationException(
                        "The result type of list OP must be sub type of Collection, can't be: " + returnType + " in method: " + fullClassMethodName);
            }

            return true;
        } else if (method.getAnnotation(MappedByKey.class) != null) {
            return true;
        } else if (op != OP.DEFAULT) {
            return false;
        }

        if (Collection.class.isAssignableFrom(returnType)) {
            if (method.getAnnotation(MergedById.class) != null) {
                return true;
            }

            // Check if return type is generic List type.
            if (method.getGenericReturnType() instanceof final ParameterizedType parameterizedReturnType) {
                final Class<?> paramClassInReturnType = parameterizedReturnType.getActualTypeArguments()[0] instanceof Class
                        ? (Class<?>) parameterizedReturnType.getActualTypeArguments()[0]
                        : (Class<?>) ((ParameterizedType) parameterizedReturnType.getActualTypeArguments()[0]).getRawType();

                if (paramLen > 0
                        && (Jdbc.RowMapper.class.isAssignableFrom(paramTypes[paramLen - 1])
                                || Jdbc.BiRowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]))
                        && method.getGenericParameterTypes()[paramLen - 1] instanceof final ParameterizedType rowMapperType) {

                    final Class<?> paramClassInRowMapper = rowMapperType.getActualTypeArguments()[0] instanceof Class
                            ? (Class<?>) rowMapperType.getActualTypeArguments()[0]
                            : (Class<?>) ((ParameterizedType) rowMapperType.getActualTypeArguments()[0]).getRawType();

                    if (paramClassInReturnType != null && paramClassInRowMapper != null && paramClassInReturnType.isAssignableFrom(paramClassInRowMapper)) {
                        return true;
                    } else {
                        // if the return type of the method is same as the return type of RowMapper/BiRowMapper parameter, return false;
                        return !parameterizedReturnType.equals(rowMapperType.getActualTypeArguments()[0]);
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

    private static boolean isExistsQuery(final Method method, final OP op, final String fullClassMethodName) {
        final String methodName = method.getName();
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final int paramLen = paramTypes.length;

        final boolean isBooleanReturnType = boolean.class.equals(returnType) || Boolean.class.equals(returnType);

        if (op == OP.exists) {
            if (!isBooleanReturnType) {
                throw new UnsupportedOperationException(
                        "The result type of exists OP must be boolean or Boolean, can't be: " + returnType + " in method: " + fullClassMethodName);
            }

            return true;
        } else if (op != OP.DEFAULT) {
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
                || (methodName.startsWith("notExists") && (methodName.length() == 9 || Character.isUpperCase(methodName.charAt(9))))
                || (methodName.startsWith("notExist") && (methodName.length() == 8 || Character.isUpperCase(methodName.charAt(8)))));
    }

    private static boolean isFindFirst(final Method method, final OP op) {
        if (op == OP.findFirst) {
            return true;
        }

        return op == OP.DEFAULT && !(method.getName().startsWith("findOnlyOne") || method.getName().startsWith("queryForSingle")
                || method.getName().startsWith("queryForUnique"));
    }

    private static boolean isFindOnlyOne(final Method method, final OP op) {
        if (op == OP.findOnlyOne) {
            return true;
        }

        return op == OP.DEFAULT && method.getName().startsWith("findOnlyOne");
    }

    private static boolean isQueryForUnique(final Method method, final OP op) {
        if (op == OP.queryForUnique) {
            return true;
        }

        return op == OP.DEFAULT && method.getName().startsWith("queryForUnique");
    }

    private static final ImmutableSet<Class<?>> singleReturnTypeSet = ImmutableSet.of(u.Nullable.class, u.Optional.class, u.OptionalBoolean.class,
            u.OptionalChar.class, u.OptionalByte.class, u.OptionalShort.class, u.OptionalInt.class, u.OptionalLong.class, u.OptionalDouble.class,
            java.util.Optional.class, java.util.OptionalInt.class, java.util.OptionalLong.class, java.util.OptionalDouble.class);

    private static boolean isSingleReturnType(final Class<?> returnType) {
        return singleReturnTypeSet.contains(returnType) || ClassUtil.isPrimitiveType(ClassUtil.unwrap(returnType));
    }

    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractQuery, Object[], R, SQLException> createSingleQueryFunction(final Class<?> returnType) {
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
        } else if (u.Optional.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(returnType);
        } else if (u.Nullable.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(returnType);
        } else {
            return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(returnType).orElse(N.defaultValueOf(returnType));
        }
    }

    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractQuery, Object[], R, SQLException> createQueryFunctionByMethod(final Class<?> entityClass,
            final Method method, final String mappedByKey, final List<String> mergedByIds, final Map<String, String> prefixFieldMap,
            final boolean fetchColumnByEntityClass, final boolean hasRowMapperOrExtractor, final boolean hasRowFilter, final OP op, final boolean isCall,
            final String fullClassMethodName) {
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final Class<?> firstReturnEleType = getFirstReturnEleType(method);
        final Class<?> secondReturnEleType = getSecondReturnEleType(method);
        final Class<?> firstReturnEleEleType = getFirstReturnEleEleType(method);

        final int paramLen = paramTypes.length;
        final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];
        final boolean isListQuery = isListQuery(method, returnType, op, fullClassMethodName);
        final boolean isExists = isExistsQuery(method, op, fullClassMethodName);

        if ((op == OP.stream && !Stream.class.isAssignableFrom(returnType))
                || (op == OP.query && !(DataSet.class.isAssignableFrom(returnType) || lastParamType == null
                        || Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType) || Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)))) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if ((op == OP.findFirst || op == OP.findOnlyOne) && Nullable.class.isAssignableFrom(returnType)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if ((op == OP.executeAndGetOutParameters && !returnType.isAssignableFrom(Jdbc.OutParamResult.class))
                || (Stream.class.isAssignableFrom(returnType) && !(op == OP.stream || op == OP.DEFAULT))) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if (DataSet.class.isAssignableFrom(returnType) && !(op == OP.query || op == OP.DEFAULT)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if ((u.Optional.class.isAssignableFrom(returnType) || java.util.Optional.class.isAssignableFrom(returnType))
                && !(op == OP.findFirst || op == OP.findOnlyOne || op == OP.queryForSingle || op == OP.queryForUnique || op == OP.DEFAULT)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if (Nullable.class.isAssignableFrom(returnType) && !(op == OP.queryForSingle || op == OP.queryForUnique || op == OP.DEFAULT)) {
            throw new UnsupportedOperationException(
                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
        }

        if (isCall) {
            if (op == OP.executeAndGetOutParameters) {
                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).executeAndGetOutParameters();
            } else if (op == OP.listAll) {
                if (Tuple2.class.isAssignableFrom(returnType)) {
                    if (firstReturnEleType == null || !List.class.isAssignableFrom(firstReturnEleType)) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.RowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                        .listAllResultsetsAndGetOutParameters((Jdbc.RowFilter) args[paramLen - 2], (Jdbc.RowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery,
                                        args) -> (R) ((CallableQuery) preparedQuery).listAllResultsetsAndGetOutParameters((Jdbc.RowMapper) args[paramLen - 1]);
                            }
                        } else if (Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                        .listAllResultsetsAndGetOutParameters((Jdbc.BiRowFilter) args[paramLen - 2], (Jdbc.BiRowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                        .listAllResultsetsAndGetOutParameters((Jdbc.BiRowMapper) args[paramLen - 1]);
                            }
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported the specified op: " + op);
                        }
                    } else {
                        if (firstReturnEleEleType == null) {
                            throw new UnsupportedOperationException(
                                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultsetsAndGetOutParameters(firstReturnEleEleType);
                    }
                } else {
                    if (!List.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.RowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultsets((Jdbc.RowFilter) args[paramLen - 2],
                                        (Jdbc.RowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultsets((Jdbc.RowMapper) args[paramLen - 1]);
                            }
                        } else if (Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)) {
                            if (hasRowFilter) {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultsets((Jdbc.BiRowFilter) args[paramLen - 2],
                                        (Jdbc.BiRowMapper) args[paramLen - 1]);
                            } else {
                                return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultsets((Jdbc.BiRowMapper) args[paramLen - 1]);
                            }
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported the specified op: " + op);
                        }
                    } else {
                        if (firstReturnEleType == null) {
                            throw new UnsupportedOperationException(
                                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).listAllResultsets(firstReturnEleType);
                    }
                }
            } else if (op == OP.queryAll) {
                if (Tuple2.class.isAssignableFrom(returnType)) {
                    if (firstReturnEleType == null || !List.class.isAssignableFrom(firstReturnEleType)) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                    .queryAllResultsetsAndGetOutParameters((Jdbc.ResultExtractor) args[paramLen - 1]);
                        } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery)
                                    .queryAllResultsetsAndGetOutParameters((Jdbc.BiResultExtractor) args[paramLen - 1]);
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported the specified op: " + op);
                        }
                    } else {
                        if (firstReturnEleEleType == null || !DataSet.class.isAssignableFrom(firstReturnEleEleType)) {
                            throw new UnsupportedOperationException(
                                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAllResultsetsAndGetOutParameters();
                    }

                } else {
                    if (!List.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                    }

                    if (hasRowMapperOrExtractor) {
                        if (lastParamType != null && Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAllResultsets((Jdbc.ResultExtractor) args[paramLen - 1]);
                        } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)) {
                            return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAllResultsets((Jdbc.BiResultExtractor) args[paramLen - 1]);
                        } else {
                            throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                    + " is not supported the specified op: " + op);
                        }
                    } else {
                        if (firstReturnEleType == null || !DataSet.class.isAssignableFrom(firstReturnEleType)) {
                            throw new UnsupportedOperationException(
                                    "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                        }

                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAllResultsets();
                    }
                }
            } else if (op == OP.streamAll) {
                if (!Stream.class.isAssignableFrom(returnType)) {
                    throw new UnsupportedOperationException(
                            "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                }

                if (hasRowMapperOrExtractor) {
                    if (lastParamType != null && Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)) {
                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).streamAllResultsets((Jdbc.ResultExtractor) args[paramLen - 1]);
                    } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType)) {
                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).streamAllResultsets((Jdbc.BiResultExtractor) args[paramLen - 1]);
                    } else {
                        throw new UnsupportedOperationException("The last parameter type: " + lastParamType + " of method: " + fullClassMethodName
                                + " is not supported the specified op: " + op);
                    }
                } else {
                    if (firstReturnEleType == null || !DataSet.class.isAssignableFrom(firstReturnEleType)) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                    }

                    return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).streamAllResultsets();
                }
            }

            if (Tuple2.class.isAssignableFrom(returnType) && (op == OP.list || op == OP.query || op == OP.DEFAULT)) {
                //    if (firstReturnEleType == null || !List.class.isAssignableFrom(firstReturnEleType)) {
                //        throw new UnsupportedOperationException(
                //                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
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
                                + " is not supported the specified op: " + op);
                    }
                } else {
                    if (firstReturnEleType == null) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
                    }

                    if (DataSet.class.isAssignableFrom(firstReturnEleType)) {
                        return (preparedQuery, args) -> (R) ((CallableQuery) preparedQuery).queryAndGetOutParameters();
                    }

                    if (firstReturnEleEleType == null) {
                        throw new UnsupportedOperationException(
                                "The return type: " + returnType + " of method: " + fullClassMethodName + " is not supported the specified op: " + op);
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

            if (!(op == OP.findFirst || op == OP.findOnlyOne || op == OP.list || op == OP.listAll || op == OP.query || op == OP.queryAll || op == OP.stream
                    || op == OP.streamAll || op == OP.DEFAULT)) {
                throw new UnsupportedOperationException("RowMapper/ResultExtractor is not supported by OP: " + op + " in method: " + fullClassMethodName);
            }

            if (hasRowFilter && (op == OP.findFirst || op == OP.findOnlyOne || op == OP.list || op == OP.listAll || op == OP.stream || op == OP.DEFAULT)) {
                throw new UnsupportedOperationException("RowFilter is not supported by OP: " + op + " in method: " + fullClassMethodName);
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
                    if (isFindOnlyOne(method, op)) {
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

                    if (isFindOnlyOne(method, op)) {
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
                    if (isFindOnlyOne(method, op)) {
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

                    if (isFindOnlyOne(method, op)) {
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
                if (!(op == OP.query || op == OP.DEFAULT)) {
                    throw new UnsupportedOperationException(
                            "ResultExtractor/BiResultExtractor is not supported by OP: " + op + " in method: " + fullClassMethodName);
                }

                if (method.getGenericParameterTypes()[paramLen - 1] instanceof ParameterizedType) {
                    final java.lang.reflect.Type resultExtractorReturnType = ((ParameterizedType) method.getGenericParameterTypes()[paramLen - 1])
                            .getActualTypeArguments()[0];
                    final Class<?> resultExtractorReturnClass = resultExtractorReturnType instanceof Class ? (Class<?>) resultExtractorReturnType
                            : (Class<?>) ((ParameterizedType) resultExtractorReturnType).getRawType();

                    if (returnType.isAssignableFrom(resultExtractorReturnClass)) {
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
            final Class<?> targetEntityClass = !ClassUtil.isBeanClass(secondReturnEleType) ? entityClass : secondReturnEleType;
            final BeanInfo entityInfo = ParserUtil.getBeanInfo(targetEntityClass);
            final PropInfo propInfo = entityInfo.getPropInfo(mappedByKey);
            final Function<Object, Object> keyExtractor = propInfo::getPropValue;
            final List<String> mergedByKey = N.isEmpty(mergedByIds) ? N.asList(mappedByKey) : mergedByIds;

            return (preparedQuery, args) -> {
                final DataSet dataSet = (DataSet) preparedQuery.query(Jdbc.ResultExtractor.toDataSet(targetEntityClass, prefixFieldMap));
                final List<Object> entities = dataSet.toMergedEntities(mergedByKey, dataSet.columnNameList(), prefixFieldMap, targetEntityClass);

                return (R) Stream.of(entities).toMap(keyExtractor, Fn.identity(), Suppliers.ofMap(targetMapClass));
            };
        } else if (N.notEmpty(mergedByIds)) {
            if (returnType.isAssignableFrom(Collection.class) || returnType.isAssignableFrom(u.Optional.class)
                    || returnType.isAssignableFrom(java.util.Optional.class)) {
                final Class<?> targetEntityClass = !ClassUtil.isBeanClass(firstReturnEleType) ? entityClass : firstReturnEleType;
                ParserUtil.getBeanInfo(targetEntityClass);
                final boolean isCollection = returnType.isAssignableFrom(Collection.class);
                final boolean isJavaOption = returnType.isAssignableFrom(java.util.Optional.class);

                return (preparedQuery, args) -> {
                    final DataSet dataSet = (DataSet) preparedQuery.query(Jdbc.ResultExtractor.toDataSet(targetEntityClass, prefixFieldMap));
                    final List<Object> mergedEntities = dataSet.toMergedEntities(mergedByIds, dataSet.columnNameList(), prefixFieldMap, targetEntityClass);

                    if (isCollection) {
                        if (returnType.isAssignableFrom(mergedEntities.getClass())) {
                            return (R) mergedEntities;
                        } else {
                            final Collection<Object> c = N.newCollection((Class<Collection>) returnType);
                            c.addAll(mergedEntities);

                            return (R) c;
                        }
                    } else {
                        if (isFindOnlyOne(method, op) && N.size(mergedEntities) > 1) {
                            throw new DuplicatedResultException("More than one record found by the query defined or generated in method: " + method.getName());
                        }

                        if (isJavaOption) {
                            return (R) java.util.Optional.ofNullable(N.firstOrNullIfEmpty(mergedEntities));
                        } else {
                            return (R) N.firstNonNull(mergedEntities);
                        }
                    }
                };
            } else {
                throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName()
                        + " is not supported by method annotated with @MergedById. Only Optional/List/Collection are supported at present");
            }
        } else if (isExists) {
            if (method.getName().startsWith("not")) {
                return (preparedQuery, args) -> (R) (Boolean) preparedQuery.notExists();
            } else {
                return (preparedQuery, args) -> (R) (Boolean) preparedQuery.exists();
            }
        } else if (isListQuery) {
            if (returnType.equals(List.class)) {
                return (preparedQuery, args) -> (R) preparedQuery.list(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
            } else {
                return (preparedQuery, args) -> (R) preparedQuery.stream(BiRowMapper.to(firstReturnEleType, prefixFieldMap))
                        .toCollection(Suppliers.ofCollection((Class<Collection>) returnType));
            }
        } else if (DataSet.class.isAssignableFrom(returnType)) {
            if (fetchColumnByEntityClass) {
                return (preparedQuery, args) -> (R) preparedQuery.query(Jdbc.ResultExtractor.toDataSet(entityClass, prefixFieldMap));
            } else {
                return (preparedQuery, args) -> (R) preparedQuery.query();
            }
        } else if (Stream.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.stream(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
        } else if (u.Optional.class.isAssignableFrom(returnType) || java.util.Optional.class.isAssignableFrom(returnType)
                || Nullable.class.isAssignableFrom(returnType)) {
            if (Nullable.class.isAssignableFrom(returnType)) {
                if (isQueryForUnique(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueResult(firstReturnEleType);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(firstReturnEleType);
                }
            } else if (u.Optional.class.isAssignableFrom(returnType)) {
                if (isFindFirst(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
                } else if (isFindOnlyOne(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne(BiRowMapper.to(firstReturnEleType, prefixFieldMap));
                } else if (isQueryForUnique(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueNonNull(firstReturnEleType);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(firstReturnEleType);
                }
            } else if (java.util.Optional.class.isAssignableFrom(returnType)) {
                if (isFindFirst(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(firstReturnEleType, prefixFieldMap)).toJdkOptional();
                } else if (isFindOnlyOne(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne(BiRowMapper.to(firstReturnEleType, prefixFieldMap)).toJdkOptional();
                } else if (isQueryForUnique(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForUniqueNonNull(firstReturnEleType).toJdkOptional();
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(firstReturnEleType).toJdkOptional();
                }
            } else {
                throw new UnsupportedOperationException("The return type: " + returnType + " of method: " + method.getName() + " is not supported at present");
            }
        } else {
            if (isFindOrListTargetClass(returnType)) {
                if (isFindOnlyOne(method, op)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findOnlyOne(Jdbc.BiRowMapper.to(returnType, prefixFieldMap)).orElseNull();
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(Jdbc.BiRowMapper.to(returnType, prefixFieldMap)).orElseNull();
                }
            } else {
                return createSingleQueryFunction(returnType);
            }
        }
    }

    private static boolean isFindOrListTargetClass(final Class<?> cls) {
        return ClassUtil.isBeanClass(cls) || Map.class.isAssignableFrom(cls) || List.class.isAssignableFrom(cls) || Object[].class.isAssignableFrom(cls)
                || ClassUtil.isRecordClass(cls);
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
            final Method method, final Class<?>[] paramTypes, final int paramLen, final int defineParamLen, final int[] stmtParamIndexes,
            final boolean[] bindListParamFlags, final int stmtParamLen) {

        Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter = null;

        boolean hasParameterSetter = true;

        if (paramLen - defineParamLen > 0 && Jdbc.ParametersSetter.class.isAssignableFrom(paramTypes[defineParamLen])) {
            parametersSetter = (preparedQuery, args) -> preparedQuery.settParameters((Jdbc.ParametersSetter) args[defineParamLen]);
        } else if (paramLen - defineParamLen > 1 && Jdbc.BiParametersSetter.class.isAssignableFrom(paramTypes[defineParamLen + 1])) {
            parametersSetter = (preparedQuery, args) -> preparedQuery.settParameters(args[defineParamLen], (Jdbc.BiParametersSetter) args[defineParamLen + 1]);
        } else if (paramLen - defineParamLen > 1 && Jdbc.TriParametersSetter.class.isAssignableFrom(paramTypes[defineParamLen + 1])) {
            parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters(args[defineParamLen],
                    (Jdbc.TriParametersSetter) args[defineParamLen + 1]);
        } else {
            hasParameterSetter = false;
        }

        if (hasParameterSetter) {
            throw new UnsupportedOperationException(
                    "Setting parameters by 'ParametersSetter/BiParametersSetter/TriParametersSetter' is not enabled at present. Can't use it in method: "
                            + fullClassMethodName);
        }

        if (parametersSetter != null || stmtParamLen == 0 || queryInfo.isBatch) { //NOSONAR
            // ignore
        } else if (stmtParamLen == 1) {
            final Class<?> paramTypeOne = paramTypes[stmtParamIndexes[0]];

            if (queryInfo.isCall) {
                final String paramName = StreamEx.of(method.getParameterAnnotations()[stmtParamIndexes[0]])
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
                } else if (ClassUtil.isBeanClass(paramTypeOne) || EntityId.class.isAssignableFrom(paramTypeOne) || ClassUtil.isRecordClass(paramTypeOne)) {
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
                final String paramName = StreamEx.of(method.getParameterAnnotations()[stmtParamIndexes[0]])
                        .select(Bind.class)
                        .map(Bind::value)
                        .first()
                        .orElseNull();

                if (Strings.isNotEmpty(paramName)) {
                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setObject(paramName, args[stmtParamIndexes[0]]);
                } else if (queryInfo.isSingleParameter) {
                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[stmtParamIndexes[0]]);
                } else if (ClassUtil.isBeanClass(paramTypeOne) || ClassUtil.isRecordClass(paramTypeOne)) {
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
            if (queryInfo.isCall) {
                @SuppressWarnings("resource")
                final String[] paramNames = IntStreamEx.of(stmtParamIndexes)
                        .mapToObj(i -> StreamEx.of(method.getParameterAnnotations()[i]).select(Bind.class).first().orElse(null))
                        .skipNulls()
                        .map(Bind::value)
                        .toArray(IntFunctions.ofStringArray());

                if (N.notEmpty(paramNames)) {
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
                final String[] paramNames = IntStreamEx.of(stmtParamIndexes)
                        .mapToObj(i -> StreamEx.of(method.getParameterAnnotations()[i])
                                .select(Bind.class)
                                .first()
                                .orElseThrow(() -> new UnsupportedOperationException("In method: " + fullClassMethodName + ", parameters[" + i + "]: "
                                        + ClassUtil.getSimpleClassName(method.getParameterTypes()[i])
                                        + " is not bound with parameter named through annotation @Bind")))
                        .map(Bind::value)
                        .toArray(IntFunctions.ofStringArray());

                final List<String> diffParamNames = N.difference(queryInfo.parsedSql.getNamedParameters(), N.asList(paramNames));

                if (N.notEmpty(diffParamNames) && (diffParamNames.size() > 1 || (diffParamNames.size() == 1 && !diffParamNames.contains(PN_NOW)))) {
                    throw new UnsupportedOperationException("In method: " + fullClassMethodName
                            + ", The named parameters in sql are different from the names bound by method parameters: " + diffParamNames);
                }

                parametersSetter = (preparedQuery, args) -> {
                    final NamedQuery namedQuery = ((NamedQuery) preparedQuery);

                    for (int i = 0; i < stmtParamLen; i++) {
                        namedQuery.setObject(paramNames[i], args[stmtParamIndexes[i]]);
                    }
                };
            } else {
                if (stmtParamLen == paramLen && StreamEx.of(bindListParamFlags).allMatch(it -> !it)) {
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

        if (queryInfo.isNamedQuery && queryInfo.timestamped && queryInfo.parsedSql.getNamedParameters().contains(PN_NOW)) {
            if (parametersSetter == null) {
                parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setTimestamp(PN_NOW, Dates.currentTimestamp());
            } else {
                final BiParametersSetter<AbstractQuery, Object[]> tmp = parametersSetter;

                parametersSetter = (preparedQuery, args) -> {
                    tmp.accept(preparedQuery, args);
                    ((NamedQuery) preparedQuery).setTimestamp(PN_NOW, Dates.currentTimestamp());
                };
            }
        }

        return parametersSetter == null ? Jdbc.BiParametersSetter.DO_NOTHING : parametersSetter;
    }

    @SuppressWarnings({ "rawtypes", "unused" })
    private static AbstractQuery prepareQuery(final Dao proxy, final QueryInfo queryInfo, final MergedById mergedByIdAnno, final String fullClassMethodName,
            final Method method, final Class<?> returnType, final Object[] args, final int[] defineParamIndexes, final Tuple2<Annotation, String>[] defineAnnos,
            final BiFunction<Annotation, Object, String>[] defineMappers, final boolean returnGeneratedKeys, final String[] returnColumnNames,
            final List<OutParameter> outParameterList, final Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter) throws SQLException {
        final OP op = queryInfo.op;
        String query = queryInfo.sql;
        ParsedSql parsedSql = queryInfo.parsedSql;

        if (N.notEmpty(defineAnnos)) {
            for (int i = 0, len = defineAnnos.length; i < len; i++) {
                query = Strings.replaceAll(query, defineAnnos[i]._2, defineMappers[i].apply(defineAnnos[i]._1, args[defineParamIndexes[i]]));
            }

            if (N.notEmpty(parsedSql.getNamedParameters()) || parsedSql.getParameterCount() == 0) {
                parsedSql = ParsedSql.parse(query);
                query = parsedSql.sql();
            }
        }

        AbstractQuery preparedQuery = null;
        boolean noException = false;

        try {
            preparedQuery = queryInfo.isCall ? proxy.prepareCallableQuery(query)
                    : (queryInfo.isNamedQuery
                            ? (returnGeneratedKeys ? proxy.prepareNamedQuery(parsedSql, returnColumnNames) : proxy.prepareNamedQuery(parsedSql))
                            : (returnGeneratedKeys ? proxy.prepareQuery(query, returnColumnNames) : proxy.prepareQuery(query)));

            if (queryInfo.isCall && N.notEmpty(outParameterList)) {
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
                    preparedQuery.configStmt(JdbcUtil.stmtSetterForBigQueryResult); // ?
                } else if (op == OP.findOnlyOne || op == OP.queryForUnique) {
                    preparedQuery.setFetchSize(2);
                } else if (op == OP.findFirst || op == OP.queryForSingle || op == OP.exists || isExistsQuery(method, op, fullClassMethodName)
                        || isSingleReturnType(returnType)) {
                    preparedQuery.setFetchSize(1);
                } else if (op == OP.stream || op == OP.streamAll || Stream.class.isAssignableFrom(returnType)) {
                    preparedQuery.configStmt(JdbcUtil.stmtSetterForStream);
                } else if (op == OP.list || op == OP.listAll || op == OP.query || op == OP.queryAll
                        || isListQuery(method, returnType, op, fullClassMethodName)) {
                    preparedQuery.configStmt(JdbcUtil.stmtSetterForBigQueryResult);
                } else if (op == OP.DEFAULT && (DataSet.class.isAssignableFrom(returnType))) {
                    preparedQuery.configStmt(JdbcUtil.stmtSetterForBigQueryResult);
                }
            }

            if (queryInfo.queryTimeout >= 0) {
                preparedQuery.setQueryTimeout(queryInfo.queryTimeout);
            }

            if (queryInfo.isBatch) {
                if (queryInfo.isNamedQuery && queryInfo.timestamped && queryInfo.parsedSql.getNamedParameters().contains(PN_NOW)) {
                    preparedQuery.configAddBatchAction((q, s) -> {
                        ((NamedQuery) q).setTimestamp(PN_NOW, Dates.currentTimestamp());
                        ((PreparedStatement) s).addBatch();
                    });
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

    private static Condition handleLimit(final Condition cond, final int count, final DBVersion dbVersion) {
        //    if (count < 0) {
        //        return cond;
        //    }

        if (cond instanceof final Limit limit) {
            switch (dbVersion) { //NOSONAR
                case ORACLE, SQL_SERVER, DB2:
                    if (limit.getCount() > 0 && limit.getOffset() > 0) {
                        return CF.limit("OFFSET " + limit.getOffset() + " ROWS FETCH NEXT " + limit.getCount() + " ROWS ONLY");
                    } else if (limit.getCount() > 0) {
                        return CF.limit("FETCH FIRST " + limit.getCount() + " ROWS ONLY");
                    } else if (limit.getOffset() > 0) {
                        return CF.limit("OFFSET " + limit.getOffset() + " ROWS");
                    } else {
                        return limit;
                    }

                default:
                    return limit;
            }
        } else if (cond instanceof final Criteria criteria) {
            final Limit limit = criteria.getLimit();

            if (limit != null) {
                switch (dbVersion) { //NOSONAR
                    case ORACLE, SQL_SERVER, DB2:

                        if (limit.getCount() > 0 && limit.getOffset() > 0) {
                            criteria.limit("OFFSET " + limit.getOffset() + " ROWS FETCH NEXT " + limit.getCount() + " ROWS ONLY");
                        } else if (limit.getCount() > 0) {
                            criteria.limit("FETCH FIRST " + limit.getCount() + " ROWS ONLY");
                        } else if (limit.getOffset() > 0) {
                            criteria.limit("OFFSET " + limit.getOffset() + " ROWS");
                        }

                        break;

                    default:
                }
            } else if (count > 0) {
                switch (dbVersion) { //NOSONAR
                    case ORACLE, SQL_SERVER, DB2:
                        criteria.limit("FETCH FIRST " + count + " ROWS ONLY");
                        break;

                    default:
                        criteria.limit(count);
                }
            }

            return criteria;
        } else if (cond instanceof final Expression expr //
                && Strings.containsAnyIgnoreCase(expr.getLiteral(), " LIMIT ", " OFFSET ", " FETCH NEXT ")) {
            // ignore.
        } else if (count > 0) {
            final Criteria criteria = CF.criteria();

            if (cond != null) {
                switch (cond.getOperator()) {
                    case LIMIT: // should never happen
                        criteria.limit((Limit) cond);
                        break;

                    case ORDER_BY:
                        criteria.orderBy(cond);
                        break;

                    case GROUP_BY:
                        criteria.groupBy(cond);
                        break;

                    case UNION:
                        criteria.union((SubQuery) cond);
                        break;

                    case UNION_ALL:
                        criteria.unionAll((SubQuery) cond);
                        break;

                    case INTERSECT:
                        criteria.intersect((SubQuery) cond);
                        break;

                    case EXCEPT:
                        criteria.except((SubQuery) cond);
                        break;

                    case MINUS:
                        criteria.minus((SubQuery) cond);
                        break;

                    default:
                        criteria.where(cond);
                }
            }

            switch (dbVersion) { //NOSONAR
                case ORACLE, SQL_SERVER, DB2:
                    criteria.limit("FETCH FIRST " + count + " ROWS ONLY");
                    break;

                default:
                    criteria.limit(count);
            }

            return criteria;
        }

        return cond;
    }

    @SuppressWarnings("rawtypes")
    private static Jdbc.BiRowMapper<Object> getIdExtractor(final Holder<Jdbc.BiRowMapper<Object>> idExtractorHolder,
            final Jdbc.BiRowMapper<Object> defaultIdExtractor, final Dao dao) {
        Jdbc.BiRowMapper<Object> keyExtractor = idExtractorHolder.value();

        if (keyExtractor == null) {
            if (dao instanceof CrudDao) {
                keyExtractor = N.defaultIfNull(((CrudDao) dao).idExtractor(), defaultIdExtractor);
            } else {
                keyExtractor = defaultIdExtractor;
            }

            idExtractorHolder.setValue(keyExtractor);
        }

        return keyExtractor;
    }

    private static void logDaoMethodPerf(final Logger daoLogger, final String simpleClassMethodName, final PerfLog perfLogAnno, final long startTime) {
        if (JdbcUtil.isDaoMethodPerfLogAllowed && perfLogAnno.minExecutionTimeForOperation() >= 0 && daoLogger.isInfoEnabled()) {
            final long elapsedTime = System.currentTimeMillis() - startTime;

            if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                daoLogger.info(Strings.concat("[DAO-OP-PERF]-[", simpleClassMethodName, "]: ", String.valueOf(elapsedTime)));
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
        N.checkArgNotNull(cond, "Condition for \"paginate\" can not be null");

        if ((cond instanceof Criteria && ((Criteria) cond).getOrderBy() != null) || Strings.containsIgnoreCase(cond.toString(), " ORDER BY ")) {
            // okay, has order by
        } else {
            throw new IllegalArgumentException("Condition for \"paginate\" must have \"orderBy\"");
        }

        return cond;
    }

    private static <T> List<T> batchGetById(final PreparedQuery preparedQuery, final Collection<?> ids, final Class<T> entityClass)
            throws DuplicatedResultException, SQLException {
        final List<T> entities = preparedQuery.settParameters(ids, collParamsSetter).list(entityClass);

        if (entities.size() > ids.size()) {
            throw new DuplicatedResultException("The size of result: " + entities.size() + " is bigger than the size of input ids: " + ids.size());
        }

        return entities;
    }

    @SuppressWarnings({ "rawtypes", "null", "resource" })
    static <TD extends Dao> TD createDao(final Class<TD> daoInterface, final String targetTableName, final javax.sql.DataSource ds, final SQLMapper sqlMapper,
            final Jdbc.DaoCache inputDaoCache, final Executor executor) {
        N.checkArgNotNull(daoInterface, "daoInterface");
        N.checkArgNotNull(ds, "dataSource");

        N.checkArgument(daoInterface.isInterface(), "'daoInterface' must be an interface. It can't be {}", daoInterface);

        final String daoCacheKey = ClassUtil.getCanonicalClassName(daoInterface) + "_" + targetTableName + "_" + System.identityHashCode(ds) + "_"
                + (sqlMapper == null ? "null" : System.identityHashCode(sqlMapper)) + "_" + (executor == null ? "null" : System.identityHashCode(executor));

        TD daoInstance = (TD) daoPool.get(daoCacheKey);

        if (daoInstance != null) {
            return daoInstance;
        }

        final Logger daoLogger = LoggerFactory.getLogger(daoInterface);
        final String daoClassName = ClassUtil.getCanonicalClassName(daoInterface);

        @SuppressWarnings("UnnecessaryLocalVariable")
        final javax.sql.DataSource primaryDataSource = ds;
        final DBProductInfo dbProductInfo = JdbcUtil.getDBProductInfo(ds);
        final DBVersion dbVersion = dbProductInfo.version();

        final AsyncExecutor asyncExecutor = executor == null ? JdbcUtil.asyncExecutor : new AsyncExecutor(executor);
        final boolean isUncheckedDao = UncheckedDao.class.isAssignableFrom(daoInterface);
        final boolean isCrudDao = CrudDao.class.isAssignableFrom(daoInterface) || UncheckedCrudDao.class.isAssignableFrom(daoInterface);
        final boolean isCrudDaoL = CrudDaoL.class.isAssignableFrom(daoInterface) || UncheckedCrudDaoL.class.isAssignableFrom(daoInterface);

        final List<Class<?>> allInterfaces = StreamEx.of(ClassUtil.getAllInterfaces(daoInterface)).prepend(daoInterface).toList();

        final SQLMapper newSQLMapper = sqlMapper == null ? new SQLMapper() : sqlMapper.copy();

        StreamEx.of(allInterfaces) //
                .flattmap(Class::getAnnotations)
                .select(SqlMapper.class)
                .map(SqlMapper::value)
                .map(SQLMapper::fromFile)
                .forEach(it -> {
                    for (final String key : it.keySet()) {
                        if (newSQLMapper.get(key) != null) {
                            throw new IllegalArgumentException("Duplicated sql keys: " + key + " defined in SQLMapper for Dao class: " + daoClassName);
                        }

                        newSQLMapper.add(key, it.get(key));
                    }
                });

        final boolean addLimitForSingleQuery = StreamEx.of(allInterfaces)
                .flattmap(Class::getAnnotations)
                .select(Config.class)
                .map(Config::addLimitForSingleQuery)
                .first()
                .orElse(false);

        final boolean callGenerateIdForInsert = StreamEx.of(allInterfaces)
                .flattmap(Class::getAnnotations)
                .select(Config.class)
                .map(Config::callGenerateIdForInsertIfIdNotSet)
                .first()
                .orElse(false);

        final boolean callGenerateIdForInsertWithSql = StreamEx.of(allInterfaces)
                .flattmap(Class::getAnnotations)
                .select(Config.class)
                .map(Config::callGenerateIdForInsertWithSqlIfIdNotSet)
                .first()
                .orElse(false);

        final boolean fetchColumnByEntityClassForDataSetQuery = StreamEx.of(allInterfaces)
                .flattmap(Class::getAnnotations)
                .select(Config.class)
                .map(Config::fetchColumnByEntityClassForDataSetQuery)
                .first()
                .orElse(true);

        final Map<String, String> sqlFieldMap = StreamEx.of(allInterfaces)
                .flattmap(Class::getDeclaredFields)
                .append(StreamEx.of(allInterfaces).flattmap(Class::getDeclaredClasses).flattmap(Class::getDeclaredFields))
                .filter(it -> it.isAnnotationPresent(SqlField.class))
                .onEach(it -> N.checkArgument(Modifier.isStatic(it.getModifiers()) && Modifier.isFinal(it.getModifiers()) && String.class.equals(it.getType()),
                        "Field annotated with @SqlField must be static&final String. but {} is not in Dao class {}.", it, daoInterface))
                .onEach(it -> ClassUtil.setAccessibleQuietly(it, true))
                .map(it -> Tuple.of(it.getAnnotation(SqlField.class), it))
                .map(it -> Tuple.of(Strings.isEmpty(it._1.id()) ? it._2.getName() : it._1.id(), it._2))
                .onEach(it -> N.checkArgument(Strings.isNotEmpty(it._1) && !Strings.containsWhitespace(it._1),
                        "Sql id {} is empty or contains whitespace characters defined by field in Dao class {}.", it._1, daoInterface))
                .distinctBy(it -> it._1, (a, b) -> {
                    throw new IllegalArgumentException(
                            "Two fields annotated with @SqlField have the same id (or name): " + a + "," + b + " in Dao class: " + daoClassName);
                })
                .toMap(it -> it._1, Fn.ff(it -> (String) (it._2.get(null))));

        // Print out the embedded sqls because most of them may be generated by SQLBuilder.
        if (daoLogger.isInfoEnabled() && N.notEmpty(sqlFieldMap)) {
            daoLogger.info("Embedded sqls defined in declared classes for Dao interface: " + daoClassName);
            sqlFieldMap.forEach((key, value) -> daoLogger.info(key + " = " + value));
        }

        if (!N.disjoint(newSQLMapper.keySet(), sqlFieldMap.keySet())) {
            throw new IllegalArgumentException("Duplicated sql keys: " + N.commonSet(newSQLMapper.keySet(), sqlFieldMap.keySet())
                    + " defined by both SQLMapper and SqlField for Dao interface: " + daoClassName);
        }

        for (final Map.Entry<String, String> entry : sqlFieldMap.entrySet()) {
            newSQLMapper.add(entry.getKey(), ParsedSql.parse(entry.getValue()));
        }

        final java.lang.reflect.Type[] typeArguments = Stream.of(allInterfaces)
                .filter(it -> N.notEmpty(it.getGenericInterfaces()) && it.getGenericInterfaces()[0] instanceof ParameterizedType)
                .map(it -> ((ParameterizedType) it.getGenericInterfaces()[0]).getActualTypeArguments())
                .first()
                .orElseNull();

        if (N.notEmpty(typeArguments)) {
            if ((typeArguments.length >= 1 && typeArguments[0] instanceof Class) && !ClassUtil.isBeanClass((Class) typeArguments[0])) {
                throw new IllegalArgumentException(
                        "Entity Type parameter of Dao interface must be: Object.class or entity class with getter/setter methods. Can't be: "
                                + typeArguments[0]);
            }

            if (JoinEntityHelper.class.isAssignableFrom(daoInterface) && (typeArguments.length >= 1 && typeArguments[0] instanceof Class)
                    && ParserUtil.getBeanInfo((Class) typeArguments[0]).propInfoList.stream().noneMatch(it -> it.isSubEntity)) {
                throw new IllegalArgumentException("Dao interface: " + ClassUtil.getCanonicalClassName(daoInterface)
                        + " extends JoinEntityHelper, but the entity class: " + typeArguments[0] + " has no sub-entity properties.");
            }

            if (typeArguments.length >= 2 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[1])) {
                if (!(typeArguments[1].equals(PSC.class) || typeArguments[1].equals(PAC.class) || typeArguments[1].equals(PLC.class))) {
                    throw new IllegalArgumentException("SQLBuilder Type parameter must be: SQLBuilder.PSC/PAC/PLC. Can't be: " + typeArguments[1]);
                }
            } else if ((typeArguments.length >= 3 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[2]))
                    && !(typeArguments[2].equals(PSC.class) || typeArguments[2].equals(PAC.class) || typeArguments[2].equals(PLC.class))) {
                throw new IllegalArgumentException("SQLBuilder Type parameter must be: SQLBuilder.PSC/PAC/PLC. Can't be: " + typeArguments[2]);
            }

            if (isCrudDao) {
                final List<String> idFieldNames = QueryUtil.getIdFieldNames((Class) typeArguments[0]);

                if (idFieldNames.size() == 0) {
                    throw new IllegalArgumentException("To support CRUD operations by extending CrudDao interface, the entity class: " + typeArguments[0]
                            + " must have at least one field annotated with @Id");
                } else if (idFieldNames.size() == 1 && !SQLBuilder.class.isAssignableFrom((Class) typeArguments[1])) {
                    if (!(ClassUtil.wrap((Class) typeArguments[1]))
                            .isAssignableFrom(ClassUtil.wrap(ClassUtil.getPropGetMethod((Class) typeArguments[0], idFieldNames.get(0)).getReturnType()))) {
                        throw new IllegalArgumentException("The 'ID' type declared in Dao: " + ClassUtil.getCanonicalClassName(daoInterface)
                                + " is not assignable from the id property type: "
                                + ClassUtil.getPropGetMethod((Class) typeArguments[0], idFieldNames.get(0)).getReturnType());
                    }
                } else if (idFieldNames.size() > 1 && !(EntityId.class.equals(typeArguments[1]) || ClassUtil.isBeanClass((Class) typeArguments[1])
                        || ClassUtil.isRecordClass((Class) typeArguments[1]))) {
                    throw new IllegalArgumentException(
                            "To support composite ids, the 'ID' type must be EntityId/Entity/Record. It can't be: " + typeArguments[1]);
                }
            }
        }

        final Map<Method, Throwables.BiFunction<Dao, Object[], ?, Throwable>> methodInvokerMap = new HashMap<>();

        final List<Method> sqlMethods = StreamEx.of(allInterfaces)
                .reversed()
                .distinct()
                .flattmap(Class::getDeclaredMethods)
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .toList();

        final Class<? extends SQLBuilder> sbc = N.isEmpty(typeArguments) ? PSC.class
                : (typeArguments.length >= 2 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[1]) ? (Class) typeArguments[1]
                        : (typeArguments.length >= 3 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[2]) ? (Class) typeArguments[2] : PSC.class));

        final NamingPolicy namingPolicy = sbc.equals(PSC.class) ? NamingPolicy.LOWER_CASE_WITH_UNDERSCORE
                : (sbc.equals(PAC.class) ? NamingPolicy.UPPER_CASE_WITH_UNDERSCORE : NamingPolicy.LOWER_CAMEL_CASE);

        final Class<Object> entityClass = N.isEmpty(typeArguments) ? null : (Class) typeArguments[0];
        final BeanInfo entityInfo = entityClass == null ? null : ParserUtil.getBeanInfo(entityClass);
        final String tableName = entityInfo == null ? null : getTableName(entityClass, entityInfo, namingPolicy, targetTableName);

        final Class<?> idClass = isCrudDao ? (isCrudDaoL ? Long.class : (Class) typeArguments[1]) : null;
        final boolean isEntityId = idClass != null && EntityId.class.isAssignableFrom(idClass);
        final BeanInfo idBeanInfo = ClassUtil.isBeanClass(idClass) ? ParserUtil.getBeanInfo(idClass) : null;

        final Function<Condition, SQLBuilder.SP> selectFromSQLBuilderFunc = sbc.equals(PSC.class)
                ? cond -> cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                        ? PSC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond).build()
                        : PSC.select(entityClass).from(tableName).append(cond).build()
                : (sbc.equals(PAC.class)
                        ? cond -> cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? PAC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond).build()
                                : PAC.select(entityClass).from(tableName).append(cond).build()
                        : cond -> cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? PLC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond).build()
                                : PLC.select(entityClass).from(tableName).append(cond).build());

        final BiFunction<String, Condition, SQLBuilder.SP> singleQuerySQLBuilderFunc = sbc.equals(PSC.class)
                ? (selectPropName, cond) -> cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                        ? PSC.select(selectPropName).preselect(criteria.preselect()).from(tableName, entityClass).append(cond).build()
                        : PSC.select(selectPropName).from(tableName, entityClass).append(cond).build()
                : (sbc.equals(PAC.class)
                        ? (selectPropName, cond) -> cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? PAC.select(selectPropName).preselect(criteria.preselect()).from(tableName, entityClass).append(cond).build()
                                : PAC.select(selectPropName).from(tableName, entityClass).append(cond).build()
                        : (selectPropName, cond) -> cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? PLC.select(selectPropName).preselect(criteria.preselect()).from(tableName, entityClass).append(cond).build()
                                : PLC.select(selectPropName).from(tableName, entityClass).append(cond).build());

        final BiFunction<String, Condition, SQLBuilder.SP> singleQueryByIdSQLBuilderFunc = sbc.equals(PSC.class)
                ? ((selectPropName, cond) -> NSC.select(selectPropName).from(tableName, entityClass).append(cond).build())
                : (sbc.equals(PAC.class) ? ((selectPropName, cond) -> NAC.select(selectPropName).from(tableName, entityClass).append(cond).build())
                        : ((selectPropName, cond) -> NLC.select(selectPropName).from(tableName, entityClass).append(cond).build()));

        final BiFunction<Collection<String>, Condition, SQLBuilder> selectSQLBuilderFunc = sbc.equals(PSC.class)
                ? ((selectPropNames, cond) -> N.isEmpty(selectPropNames)
                        ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? PSC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond)
                                : PSC.select(entityClass).from(tableName).append(cond))
                        : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? PSC.select(selectPropNames).preselect(criteria.preselect()).from(tableName, entityClass).append(cond)
                                : PSC.select(selectPropNames).from(tableName, entityClass).append(cond))
                : (sbc.equals(PAC.class)
                        ? ((selectPropNames, cond) -> N.isEmpty(selectPropNames)
                                ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? PAC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond)
                                        : PAC.select(entityClass).from(tableName).append(cond))
                                : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? PAC.select(selectPropNames).preselect(criteria.preselect()).from(tableName, entityClass).append(cond)
                                        : PAC.select(selectPropNames).from(tableName, entityClass).append(cond))
                        : (selectPropNames, cond) -> N.isEmpty(selectPropNames)
                                ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? PLC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond)
                                        : PLC.select(entityClass).from(tableName).append(cond))
                                : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? PLC.select(selectPropNames).preselect(criteria.preselect()).from(tableName, entityClass).append(cond)
                                        : PLC.select(selectPropNames).from(tableName, entityClass).append(cond));

        final BiFunction<Collection<String>, Condition, SQLBuilder> namedSelectSQLBuilderFunc = sbc.equals(PSC.class)
                ? ((selectPropNames, cond) -> N.isEmpty(selectPropNames)
                        ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? NSC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond)
                                : NSC.select(entityClass).from(tableName).append(cond))
                        : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                ? NSC.select(selectPropNames).preselect(criteria.preselect()).from(tableName, entityClass).append(cond)
                                : NSC.select(selectPropNames).from(tableName, entityClass).append(cond))
                : (sbc.equals(PAC.class)
                        ? ((selectPropNames, cond) -> N.isEmpty(selectPropNames)
                                ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? NAC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond)
                                        : NAC.select(entityClass).from(tableName).append(cond))
                                : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? NAC.select(selectPropNames).preselect(criteria.preselect()).from(tableName, entityClass).append(cond)
                                        : NAC.select(selectPropNames).from(tableName, entityClass).append(cond))
                        : (selectPropNames, cond) -> N.isEmpty(selectPropNames)
                                ? (cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? NLC.select(entityClass).preselect(criteria.preselect()).from(tableName).append(cond)
                                        : NLC.select(entityClass).from(tableName).append(cond))
                                : cond instanceof final Criteria criteria && Strings.isNotEmpty(criteria.preselect())
                                        ? NLC.select(selectPropNames).preselect(criteria.preselect()).from(tableName, entityClass).append(cond)
                                        : NLC.select(selectPropNames).from(tableName, entityClass).append(cond));

        final Function<Collection<String>, SQLBuilder> namedInsertSQLBuilderFunc = sbc.equals(PSC.class)
                ? (propNamesToInsert -> N.isEmpty(propNamesToInsert) ? NSC.insert(entityClass).into(tableName)
                        : NSC.insert(propNamesToInsert).into(tableName, entityClass))
                : (sbc.equals(PAC.class)
                        ? (propNamesToInsert -> N.isEmpty(propNamesToInsert) ? NAC.insert(entityClass).into(tableName)
                                : NAC.insert(propNamesToInsert).into(tableName, entityClass))
                        : (propNamesToInsert -> N.isEmpty(propNamesToInsert) ? NLC.insert(entityClass).into(tableName)
                                : NLC.insert(propNamesToInsert).into(tableName, entityClass)));

        final BiFunction<String, Class<?>, SQLBuilder> parameterizedUpdateFunc = sbc.equals(PSC.class) ? PSC::update
                : (sbc.equals(PAC.class) ? PAC::update : PLC::update);

        final BiFunction<String, Class<?>, SQLBuilder> parameterizedDeleteFromFunc = sbc.equals(PSC.class) ? PSC::deleteFrom
                : (sbc.equals(PAC.class) ? PAC::deleteFrom : PLC::deleteFrom);

        final BiFunction<String, Class<?>, SQLBuilder> namedUpdateFunc = sbc.equals(PSC.class) ? NSC::update
                : (sbc.equals(PAC.class) ? NAC::update : NLC::update);

        final List<String> idPropNameList = entityClass == null ? N.emptyList() : QueryUtil.getIdFieldNames(entityClass);
        final boolean isNoId = entityClass == null || N.isEmpty(idPropNameList) || QueryUtil.isFakeId(idPropNameList);
        final Set<String> idPropNameSet = N.newHashSet(idPropNameList);
        final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
        final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
        final boolean isOneId = !isNoId && idPropNameList.size() == 1;
        final Condition idCond = isNoId ? null : isOneId ? CF.eq(oneIdPropName) : CF.and(StreamEx.of(idPropNameList).map(CF::eq).toList());
        final Function<Object, Condition> id2CondFunc = isNoId || idClass == null ? null
                : (isEntityId ? id -> CF.id2Cond((EntityId) id)
                        : Map.class.isAssignableFrom(idClass) ? id -> CF.eqAnd((Map<String, ?>) id)
                                : ClassUtil.isBeanClass(idClass) || ClassUtil.isRecordClass(idClass) ? ConditionFactory::eqAnd
                                        : id -> CF.eq(oneIdPropName, id));

        N.checkArgument(
                idPropNameList.size() > 1 || !(isEntityId || (idClass != null && (ClassUtil.isBeanClass(idClass) || Map.class.isAssignableFrom(idClass)))),
                "Id type/class can not be EntityId/Map or Entity for single id ");

        String sql_getById = null;
        String sql_existsById = null;
        String sql_insertWithId = null;
        String sql_insertWithoutId = null;
        String sql_updateById = null;
        String sql_deleteById = null;

        final boolean noOtherInsertPropNameExceptIdPropNames = idPropNameSet.containsAll(ClassUtil.getPropNameList(entityClass))
                || N.isEmpty(QueryUtil.getInsertPropNames(entityClass, idPropNameSet));

        if (sbc.equals(PSC.class)) {
            sql_getById = isNoId ? null : NSC.select(entityClass).from(tableName).where(idCond).sql();
            sql_existsById = isNoId ? null : NSC.select(_1).from(tableName, entityClass).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NSC.insert(entityClass).into(tableName).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (noOtherInsertPropNameExceptIdPropNames ? sql_insertWithId : NSC.insert(entityClass, idPropNameSet).into(tableName).sql());
            sql_updateById = isNoId ? null : NSC.update(tableName).set(entityClass, idPropNameSet).where(idCond).sql();
            sql_deleteById = isNoId ? null : NSC.deleteFrom(tableName, entityClass).where(idCond).sql();
        } else if (sbc.equals(PAC.class)) {
            sql_getById = isNoId ? null : NAC.select(entityClass).from(tableName).where(idCond).sql();
            sql_existsById = isNoId ? null : NAC.select(_1).from(tableName, entityClass).where(idCond).sql();
            sql_updateById = isNoId ? null : NAC.update(tableName).set(entityClass, idPropNameSet).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NAC.insert(entityClass).into(tableName).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (noOtherInsertPropNameExceptIdPropNames ? sql_insertWithId : NAC.insert(entityClass, idPropNameSet).into(tableName).sql());
            sql_deleteById = isNoId ? null : NAC.deleteFrom(tableName, entityClass).where(idCond).sql();
        } else {
            sql_getById = isNoId ? null : NLC.select(entityClass).from(tableName).where(idCond).sql();
            sql_existsById = isNoId ? null : NLC.select(_1).from(tableName, entityClass).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NLC.insert(entityClass).into(tableName).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (noOtherInsertPropNameExceptIdPropNames ? sql_insertWithId : NLC.insert(entityClass, idPropNameSet).into(tableName).sql());
            sql_updateById = isNoId ? null : NLC.update(tableName).set(entityClass, idPropNameSet).where(idCond).sql();
            sql_deleteById = isNoId ? null : NLC.deleteFrom(tableName, entityClass).where(idCond).sql();
        }

        final ParsedSql namedGetByIdSQL = Strings.isEmpty(sql_getById) ? null : ParsedSql.parse(sql_getById);
        final ParsedSql namedExistsByIdSQL = Strings.isEmpty(sql_existsById) ? null : ParsedSql.parse(sql_existsById);
        final ParsedSql namedInsertWithIdSQL = Strings.isEmpty(sql_insertWithId) ? null : ParsedSql.parse(sql_insertWithId);
        final ParsedSql namedInsertWithoutIdSQL = Strings.isEmpty(sql_insertWithoutId) ? null : ParsedSql.parse(sql_insertWithoutId);
        final ParsedSql namedUpdateByIdSQL = Strings.isEmpty(sql_updateById) ? null : ParsedSql.parse(sql_updateById);
        final ParsedSql namedDeleteByIdSQL = Strings.isEmpty(sql_deleteById) ? null : ParsedSql.parse(sql_deleteById);

        final ImmutableMap<String, String> propColumnNameMap = QueryUtil.getProp2ColumnNameMap(entityClass, namingPolicy);

        final String[] returnColumnNames = isNoId ? N.EMPTY_STRING_ARRAY
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
                        : (isEntityId ? id -> Stream.of(((EntityId) id).entrySet()).allMatch(it -> JdbcUtil.isDefaultIdPropValue(it.getValue()))
                                : id -> Stream.of(idPropNameList).allMatch(idName -> JdbcUtil.isDefaultIdPropValue(idBeanInfo.getPropValue(id, idName)))));

        final Jdbc.BiParametersSetter<NamedQuery, Object> idParamSetter = isOneId ? (pq, id) -> pq.setObject(oneIdPropName, id, idPropInfo.dbType)
                : (isEntityId ? (pq, id) -> {
                    final EntityId entityId = (EntityId) id;
                    PropInfo propInfo = null;

                    for (final String idName : idPropNameList) {
                        propInfo = entityInfo.getPropInfo(idName);
                        pq.setObject(idName, entityId.get(idName), propInfo.dbType);
                    }
                } : (pq, id) -> {
                    PropInfo propInfo = null;

                    for (final String idName : idPropNameList) {
                        propInfo = idBeanInfo.getPropInfo(idName);
                        pq.setObject(idName, propInfo.getPropValue(id), propInfo.dbType);
                    }
                });

        final Jdbc.BiParametersSetter<NamedQuery, Object> idParamSetterByEntity = isOneId
                ? (pq, entity) -> pq.setObject(oneIdPropName, idPropInfo.getPropValue(entity), idPropInfo.dbType)
                : (pq, entity) -> pq.settParameters(entity, objParamsSetter);

        final CacheResult daoClassCacheResultAnno = StreamEx.of(allInterfaces).flattmap(Class::getAnnotations).select(CacheResult.class).first().orElseNull();

        final RefreshCache daoClassRefreshCacheAnno = StreamEx.of(allInterfaces)
                .flattmap(Class::getAnnotations)
                .select(RefreshCache.class)
                .first()
                .orElseNull();

        if (NoUpdateDao.class.isAssignableFrom(daoInterface) || UncheckedNoUpdateDao.class.isAssignableFrom(daoInterface)) {
            // OK
        } else {
            // TODO maybe it's not a good idea to support Cache in general Dao which supports update/delete operations.
            if (daoClassCacheResultAnno != null || daoClassRefreshCacheAnno != null) {
                throw new UnsupportedOperationException(
                        "Cache is only supported for NoUpdateDao/UncheckedNoUpdateDao interface right now, not supported for Dao interface: " + daoClassName);
            }
        }

        final MutableBoolean hasCacheResult = MutableBoolean.of(false);
        final MutableBoolean hasRefreshCache = MutableBoolean.of(false);

        final List<Handler> daoClassHandlerList = StreamEx.of(allInterfaces)
                .reversed()
                .flattmap(Class::getAnnotations)
                .filter(anno -> anno.annotationType().equals(Handler.class) || anno.annotationType().equals(HandlerList.class))
                .flatmap(anno -> anno.annotationType().equals(Handler.class) ? N.asList((Handler) anno) : N.asList(((HandlerList) anno).value()))
                .toList();

        final Map<String, Jdbc.Handler<?>> daoClassHandlerMap = StreamEx.of(allInterfaces)
                .flattmap(Class::getDeclaredFields)
                .append(StreamEx.of(allInterfaces).flattmap(Class::getDeclaredClasses).flattmap(Class::getDeclaredFields))
                .filter(it -> Jdbc.Handler.class.isAssignableFrom(it.getType()))
                .onEach(it -> N.checkArgument(Modifier.isStatic(it.getModifiers()) && Modifier.isFinal(it.getModifiers()),
                        "Handler Fields defined in Dao declared classes must be static&final Handler. but {} is not in Dao class {}.", it, daoInterface))
                .onEach(it -> ClassUtil.setAccessibleQuietly(it, true))
                .distinctBy(Field::getName, (a, b) -> {
                    throw new IllegalArgumentException("Two Handler fields have the same id (or name): " + a + "," + b + " in Dao class: " + daoClassName);
                })
                .toMap(Field::getName, Fn.ff(it -> (Jdbc.Handler<?>) it.get(null)));

        final com.landawn.abacus.jdbc.annotation.Cache daoClassCacheAnno = StreamEx.of(allInterfaces)
                .flattmap(Class::getAnnotations)
                .select(com.landawn.abacus.jdbc.annotation.Cache.class)
                .first()
                .orElseNull();

        if (NoUpdateDao.class.isAssignableFrom(daoInterface)) {
            // OK
        } else {
            // TODO maybe it's not a good idea to support Cache in general Dao which supports update/delete operations.
            if (inputDaoCache != null || daoClassCacheAnno != null) {
                throw new UnsupportedOperationException(
                        "Cache is only supported for NoUpdateDao/UncheckedNoUpdateDao interface right now, not supported for Dao interface: " + daoClassName);
            }
        }

        final int capacity = daoClassCacheAnno == null ? JdbcUtil.DEFAULT_CACHE_CAPACITY : daoClassCacheAnno.capacity();
        final long evictDelay = daoClassCacheAnno == null ? JdbcUtil.DEFAULT_CACHE_EVICT_DELAY : daoClassCacheAnno.evictDelay();

        final Jdbc.DaoCache daoCache = inputDaoCache == null
                ? (daoClassCacheAnno == null || daoClassCacheAnno.impl() == null) ? Jdbc.DaoCache.create(capacity, evictDelay)
                        : ClassUtil.invokeConstructor(ClassUtil.getDeclaredConstructor(daoClassCacheAnno.impl(), int.class, long.class), capacity, evictDelay)
                : inputDaoCache;

        final Set<Method> nonDBOperationSet = new HashSet<>();

        //    final Map<String, String> sqlCache = new ConcurrentHashMap<>(0);
        //    final Map<String, ImmutableList<String>> sqlsCache = new ConcurrentHashMap<>(0);

        final Map<String, JoinInfo> joinBeanInfo = JoinEntityHelper.class.isAssignableFrom(daoInterface)
                ? JoinInfo.getEntityJoinInfo(daoInterface, entityClass, tableName)
                : null;

        if (JoinEntityHelper.class.isAssignableFrom(daoInterface) && N.isEmpty(joinBeanInfo)) {
            throw new IllegalArgumentException(
                    "Entity class: " + ClassUtil.getCanonicalClassName(entityClass) + " must have at least one join entity property for its Dao interface: "
                            + ClassUtil.getCanonicalClassName(daoInterface) + " which extends JoinEntityHelper interface");
        }

        if ((JoinEntityHelper.class.isAssignableFrom(daoInterface) && !Dao.class.isAssignableFrom(daoInterface))
                || (CrudJoinEntityHelper.class.isAssignableFrom(daoInterface) && !CrudDao.class.isAssignableFrom(daoInterface))) {
            throw new IllegalArgumentException("Dao interface: " + ClassUtil.getCanonicalClassName(entityClass)
                    + " extending JoinEntityHelper/CrudJoinEntityHelper must extend the corresponding Dao interface:Dao/CrudDao");
        }

        final Predicate<Object> isNotEmptyResult = ret -> {
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
        };

        for (final Method method : sqlMethods) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }

            final boolean isNonDBOperation = StreamEx.of(method.getAnnotations()).anyMatch(anno -> anno.annotationType().equals(NonDBOperation.class));

            final Predicate<String> filterByMethodNameStartsWith = it -> Strings.isNotEmpty(it)
                    && (Strings.startsWith(method.getName(), it) || Pattern.matches(it, method.getName()));

            final Predicate<String> filterByMethodNameContains = it -> Strings.isNotEmpty(it)
                    && (Strings.containsIgnoreCase(method.getName(), it) || Pattern.matches(it, method.getName()));

            final Class<?> declaringClass = method.getDeclaringClass();
            final String methodName = method.getName();
            final String simpleClassMethodName = declaringClass.getSimpleName() + "." + methodName;
            final String fullClassMethodName = ClassUtil.getCanonicalClassName(declaringClass) + "." + methodName;
            final Class<?>[] paramTypes = method.getParameterTypes();
            final Class<?> returnType = method.getReturnType();
            final int paramLen = paramTypes.length;

            final boolean fetchColumnByEntityClass = StreamEx.of(method.getAnnotations())
                    .select(FetchColumnByEntityClass.class)
                    .map(FetchColumnByEntityClass::value)
                    .onEach(it -> N.checkArgument(DataSet.class.isAssignableFrom(returnType),
                            "@FetchColumnByEntityClass is not supported for method: {} because its return type is not DataSet", fullClassMethodName))
                    .first()
                    .orElse(fetchColumnByEntityClassForDataSetQuery);

            final Sqls sqlsAnno = StreamEx.of(method.getAnnotations()).select(Sqls.class).onlyOne().orElseNull();
            List<String> sqlList = null;

            if (sqlsAnno != null) {
                if (Modifier.isAbstract(method.getModifiers())) {
                    throw new UnsupportedOperationException(
                            "Annotation @Sqls is only supported by interface methods with default implementation: default xxx dbOperationABC(someParameters, String ... sqls), not supported by abstract method: "
                                    + fullClassMethodName);
                }

                if (paramLen == 0 || !paramTypes[paramLen - 1].equals(String[].class)) {
                    throw new UnsupportedOperationException(
                            "To support sqls binding by @Sqls, the type of last parameter must be: String... sqls. It can't be : " + paramTypes[paramLen - 1]
                                    + " on method: " + fullClassMethodName);
                }

                if (newSQLMapper.isEmpty()) {
                    sqlList = Stream.of(sqlsAnno.value())
                            .map(Fn.strip())
                            .filter(Fn.notEmpty())
                            .map(sql -> sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql)
                            .toList();
                } else {
                    sqlList = Stream.of(sqlsAnno.value())
                            .map(Fn.strip())
                            .filter(Fn.notEmpty())
                            .map(it -> newSQLMapper.get(it) == null ? it : newSQLMapper.get(it).getParameterizedSql())
                            .map(sql -> sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql)
                            .toList();
                }
            }

            final String[] sqls = sqlList == null ? N.EMPTY_STRING_ARRAY : sqlList.toArray(new String[0]);

            Throwables.BiFunction<Dao, Object[], ?, Throwable> call = null;

            if (!Modifier.isAbstract(method.getModifiers())) {
                final MethodHandle methodHandle = createMethodHandle(method);

                call = (proxy, args) -> {
                    if (sqlsAnno != null) {
                        if (N.notEmpty((String[]) args[paramLen - 1])) {
                            throw new IllegalArgumentException(
                                    "The last parameter(String[]) of method annotated by @Sqls must be empty, don't specify it. It will be auto-filled by sqls from annotation @Sqls on the method: "
                                            + fullClassMethodName);
                        }

                        args[paramLen - 1] = sqls;
                    }

                    return methodHandle.bindTo(proxy).invokeWithArguments(args);
                };
            } else if (methodName.equals("executor") && Executor.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> asyncExecutor.getExecutor();
            } else if (methodName.equals("asyncExecutor") && AsyncExecutor.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> asyncExecutor;
            } else if (method.getName().equals("targetEntityClass") && Class.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> entityClass;
            } else if (method.getName().equals("targetTableName") && String.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> tableName;
            } else if (method.getName().equals("targetDaoInterface") && Class.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> daoInterface;
            } else if (methodName.equals("dataSource") && javax.sql.DataSource.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> primaryDataSource;
            } else if (methodName.equals("sqlMapper") && SQLMapper.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> newSQLMapper;
                //    } else if (methodName.equals("cacheSql") && void.class.isAssignableFrom(returnType) && paramLen == 2 && paramTypes[0].equals(String.class)
                //            && paramTypes[1].equals(String.class)) {
                //        call = (proxy, args) -> {
                //            sqlCache.put(N.checkArgNotEmpty((String) args[0], "key"), N.checkArgNotEmpty((String) args[1], "sql"));
                //            return null;
                //        };
                //    } else if (methodName.equals("cacheSqls") && void.class.isAssignableFrom(returnType) && paramLen == 2 && paramTypes[0].equals(String.class)
                //            && paramTypes[1].equals(Collection.class)) {
                //        call = (proxy, args) -> {
                //            sqlsCache.put(N.checkArgNotEmpty((String) args[0], "key"),
                //                    ImmutableList.copyOf(N.checkArgNotEmpty((Collection<String>) args[1], "sqls")));
                //            return null;
                //        };
                //    } else if (methodName.equals("getCachedSql") && String.class.isAssignableFrom(returnType) && paramLen == 1 && paramTypes[0].equals(String.class)) {
                //        call = (proxy, args) -> sqlCache.get(args[0]);
                //    } else if (methodName.equals("getCachedSqls") && ImmutableList.class.isAssignableFrom(returnType) && paramLen == 1
                //            && paramTypes[0].equals(String.class)) {
                //        call = (proxy, args) -> sqlsCache.get(args[0]);
            } else {
                final boolean isStreamReturn = Stream.class.isAssignableFrom(returnType);
                final boolean throwsSQLException = StreamEx.of(method.getExceptionTypes()).anyMatch(e -> e.isAssignableFrom(SQLException.class));
                final Annotation sqlAnno = StreamEx.of(method.getAnnotations())
                        .filter(anno -> sqlAnnoMap.containsKey(anno.annotationType()))
                        .first()
                        .orElseNull();

                if (declaringClass.equals(Dao.class) || declaringClass.equals(UncheckedDao.class)) {
                    if (methodName.equals("save") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, "entity");

                            final ParsedSql namedInsertSQL = isNoId || isDefaultIdTester.test(idGetter.apply(entity)) ? namedInsertWithoutIdSQL
                                    : namedInsertWithIdSQL;

                            proxy.prepareNamedQuery(namedInsertSQL).settParameters(entity, objParamsSetter).update();

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToSave = (Collection<String>) args[1];

                            N.checkArgNotNull(entity, "entity");
                            N.checkArgNotEmpty(propNamesToSave, "propNamesToSave");

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                            proxy.prepareNamedQuery(namedInsertSQL).settParameters(entity, objParamsSetter).update();

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Object entity = args[1];
                            N.checkArgNotEmpty(namedInsertSQL, "namedInsertSQL");
                            N.checkArgNotNull(entity, "entity");

                            proxy.prepareNamedQuery(namedInsertSQL).settParameters(entity, objParamsSetter).update();

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return 0;
                            }

                            final ParsedSql namedInsertSQL = isNoId || N.allMatch(entities, entity -> isDefaultIdTester.test(idGetter.apply(entity)))
                                    ? namedInsertWithoutIdSQL
                                    : namedInsertWithIdSQL;

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        Stream.of(entities)
                                                .split(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];

                            final Collection<String> propNamesToSave = (Collection<String>) args[1];
                            N.checkArgNotEmpty(propNamesToSave, "propNamesToSave");

                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return 0;
                            }

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        Stream.of(entities)
                                                .split(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("batchSave") && paramLen == 3 && String.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgNotEmpty(namedInsertSQL, "namedInsertSQL");
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return 0;
                            }

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        Stream.of(entities)
                                                .split(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("exists") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(_1, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).exists();
                        };
                    } else if (methodName.equals("count") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(SQLBuilder.COUNT_ALL, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForInt().orElseZero();
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(2).settParameters(sp.parameters, collParamsSetter).findOnlyOne(entityClass);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(2).settParameters(sp.parameters, collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(2).settParameters(sp.parameters, collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query).setFetchSize(2).settParameters(sp.parameters, collParamsSetter).findOnlyOne(entityClass);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query).setFetchSize(2).settParameters(sp.parameters, collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("findOnlyOne") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query).setFetchSize(2).settParameters(sp.parameters, collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("queryForBoolean") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForBytes") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).queryForBytes();
                        };
                    } else if (methodName.equals("queryForSingleResult") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .queryForSingleResult(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .setFetchSize(1)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .queryForSingleNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("queryForUniqueResult") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .setFetchSize(2)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .queryForUniqueResult(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .setFetchSize(2)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .queryForUniqueNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQuerySQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareQuery(sp.query).setFetchSize(1).settParameters(sp.parameters, collParamsSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("query") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            if (fetchColumnByEntityClass) {
                                return proxy.prepareQuery(sp.query)
                                        .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters, collParamsSetter)
                                        .query(Jdbc.ResultExtractor.toDataSet(entityClass));
                            } else {
                                return proxy.prepareQuery(sp.query)
                                        .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters, collParamsSetter)
                                        .query();
                            }
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], limitedCond).build();

                            if (fetchColumnByEntityClass) {
                                return proxy.prepareQuery(sp.query)
                                        .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters, collParamsSetter)
                                        .query(Jdbc.ResultExtractor.toDataSet(entityClass));
                            } else {
                                return proxy.prepareQuery(sp.query)
                                        .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                        .settParameters(sp.parameters, collParamsSetter)
                                        .query();
                            }
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.ResultExtractor resultExtractor = (Jdbc.ResultExtractor) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(resultExtractor, "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("query") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.ResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.ResultExtractor resultExtractor = (Jdbc.ResultExtractor) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(resultExtractor, "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiResultExtractor resultExtractor = (Jdbc.BiResultExtractor) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(resultExtractor, "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("query") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiResultExtractor.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiResultExtractor resultExtractor = (Jdbc.BiResultExtractor) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(resultExtractor, "resultExtractor");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .query(resultExtractor);
                        };
                    } else if (methodName.equals("paginate") && paramLen == 3 //
                            && paramTypes[0].equals(Condition.class) //
                            && paramTypes[1].equals(int.class) //
                            && paramTypes[2].equals(Jdbc.BiParametersSetter.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = checkCondForPaginate((Condition) args[0]);
                            final int pageSize = N.checkArgPositive((Integer) args[1], "pageSize");
                            final Jdbc.BiParametersSetter<PreparedQuery, DataSet> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[2],
                                    "paramSetter");
                            final Jdbc.ResultExtractor<DataSet> resultExtractor = fetchColumnByEntityClass ? Jdbc.ResultExtractor.toDataSet(entityClass)
                                    : Jdbc.ResultExtractor.TO_DATA_SET;

                            final Condition limitedCond = handleLimit(cond, pageSize, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            return Stream.just(Holder.of((DataSet) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final DataSet ret = proxy.prepareQuery(sp.query)
                                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters, collParamsSetter)
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

                            final Condition limitedCond = handleLimit(cond, pageSize, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            return Stream.just(Holder.of((Object) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query)
                                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters, collParamsSetter)
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

                            final Condition limitedCond = handleLimit(cond, pageSize, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            return Stream.just(Holder.of((Object) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query)
                                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters, collParamsSetter)
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
                            final Jdbc.BiParametersSetter<PreparedQuery, DataSet> paramSetter = N.checkArgNotNull((Jdbc.BiParametersSetter) args[3],
                                    "paramSetter");
                            final Jdbc.ResultExtractor<DataSet> resultExtractor = fetchColumnByEntityClass ? Jdbc.ResultExtractor.toDataSet(entityClass)
                                    : Jdbc.ResultExtractor.TO_DATA_SET;

                            final Condition limitedCond = handleLimit(cond, pageSize, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            return Stream.just(Holder.of((DataSet) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final DataSet ret = proxy.prepareQuery(sp.query)
                                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters, collParamsSetter)
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

                            final Condition limitedCond = handleLimit(cond, pageSize, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            return Stream.just(Holder.of((Object) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query)
                                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters, collParamsSetter)
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

                            final Condition limitedCond = handleLimit(cond, pageSize, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            return Stream.just(Holder.of((Object) null)) //
                                    .cycled()
                                    .map(it -> {
                                        try {
                                            final Object ret = proxy.prepareQuery(sp.query)
                                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                                    .setFetchSize(pageSize)
                                                    .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class) && paramTypes[1].equals(Jdbc.RowFilter.class)
                            && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowFilter.class) && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.RowFilter.class) && paramTypes[3].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[2];
                            final Jdbc.RowMapper rowMapper = (Jdbc.RowMapper) args[3];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(Jdbc.BiRowFilter.class) && paramTypes[3].equals(Jdbc.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[2];
                            final Jdbc.BiRowMapper rowMapper = (Jdbc.BiRowMapper) args[3];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            return proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .list(rowFilter, rowMapper);
                        };
                    } else if (methodName.equals("stream") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
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
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            final Supplier<Stream> supplier = () -> {
                                try {
                                    return proxy.prepareQuery(sp.query)
                                            .configStmt(JdbcUtil.stmtSetterForStream)
                                            .settParameters(sp.parameters, collParamsSetter)
                                            .stream(rowFilter, rowMapper);
                                } catch (final SQLException e) {
                                    throw new UncheckedSQLException(e);
                                }
                            };

                            return Stream.of(supplier).flatMap(Supplier::get);
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[1];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.RowFilter.class) && paramTypes[2].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[1];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(Jdbc.BiRowFilter.class) && paramTypes[2].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[1];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectFromSQLBuilderFunc.apply(limitedCond);
                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[2];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowConsumer);
                            return null;
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 4 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowFilter.class)
                            && paramTypes[3].equals(Jdbc.RowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.RowFilter rowFilter = (Jdbc.RowFilter) args[2];
                            final Jdbc.RowConsumer rowConsumer = (Jdbc.RowConsumer) args[3];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();
                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equalsIgnoreCase("forEach") && paramLen == 4 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.BiRowFilter.class)
                            && paramTypes[3].equals(Jdbc.BiRowConsumer.class)) {
                        call = (proxy, args) -> {
                            final Collection<String> selectPropNames = (Collection<String>) args[0];
                            final Condition cond = (Condition) args[1];
                            final Jdbc.BiRowFilter rowFilter = (Jdbc.BiRowFilter) args[2];
                            final Jdbc.BiRowConsumer rowConsumer = (Jdbc.BiRowConsumer) args[3];
                            N.checkArgNotNull(cond, "cond");
                            N.checkArgNotNull(rowFilter, "rowFilter");
                            N.checkArgNotNull(rowConsumer, "rowConsumer");

                            final Condition limitedCond = handleLimit(cond, -1, dbVersion);
                            final SP sp = selectSQLBuilderFunc.apply(selectPropNames, limitedCond).build();

                            proxy.prepareQuery(sp.query)
                                    .configStmt(JdbcUtil.stmtSetterForBigQueryResult)
                                    .settParameters(sp.parameters, collParamsSetter)
                                    .forEach(rowFilter, rowConsumer);
                            return null;
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])
                            && Condition.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> propsToUpdate = (Map<String, Object>) args[0];
                            final Condition cond = (Condition) args[1];

                            N.checkArgNotEmpty(propsToUpdate, "propsToUpdate");
                            N.checkArgNotNull(cond, "cond");

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass).set(propsToUpdate).append(cond).build();
                            return proxy.prepareQuery(sp.query).settParameters(sp.parameters, collParamsSetter).update();
                        };
                    } else if (methodName.equals("update") && paramLen == 3 && !Map.class.equals(paramTypes[0])
                            && Collection.class.isAssignableFrom(paramTypes[1]) && Condition.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            final Condition cond = (Condition) args[2];

                            N.checkArgNotNull(entity, "entity");
                            N.checkArgNotEmpty(propNamesToUpdate, "propNamesToUpdate");
                            N.checkArgNotNull(cond, "cond");

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).append(cond).build();

                            final Jdbc.BiParametersSetter<AbstractQuery, Object> paramsSetter = (pq, p) -> {
                                final PreparedStatement stmt = pq.stmt;
                                PropInfo propInfo = null;
                                int columnIndex = 1;

                                for (final String propName : propNamesToUpdate) {
                                    propInfo = entityInfo.getPropInfo(propName);
                                    propInfo.dbType.set(stmt, columnIndex++, propInfo.getPropValue(p));
                                }

                                if (sp.parameters.size() > 0) {
                                    for (final Object param : sp.parameters) {
                                        pq.setObject(columnIndex++, param);
                                    }
                                }
                            };

                            return proxy.prepareQuery(sp.query).settParameters(entity, paramsSetter).update();
                        };
                    } else if (methodName.equals("delete") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Condition cond = (Condition) args[0];
                            N.checkArgNotNull(cond, "cond");

                            final SP sp = parameterizedDeleteFromFunc.apply(tableName, entityClass).append(cond).build();
                            return proxy.prepareQuery(sp.query).settParameters(sp.parameters, collParamsSetter).update();
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + method);
                        };
                    }
                } else if (declaringClass.equals(CrudDao.class) || declaringClass.equals(UncheckedCrudDao.class)) {
                    if (methodName.equals("insert") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, "entity");

                            ParsedSql namedInsertSQL = null;

                            if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                if (callGenerateIdForInsert) {
                                    idSetter.accept(((CrudDao) proxy).generateId(), entity);

                                    namedInsertSQL = namedInsertWithIdSQL;
                                } else {
                                    namedInsertSQL = namedInsertWithoutIdSQL;
                                }
                            } else {
                                namedInsertSQL = namedInsertWithIdSQL;
                            }

                            return proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
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
                            N.checkArgNotNull(entity, "entity");
                            N.checkArgNotEmpty(propNamesToInsert, "propNamesToInsert");

                            if ((callGenerateIdForInsert && !N.disjoint(propNamesToInsert, idPropNameSet)) && isDefaultIdTester.test(idGetter.apply(entity))) {
                                idSetter.accept(((CrudDao) proxy).generateId(), entity);
                            }

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToInsert).sql();

                            return proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                    .settParameters(entity, objParamsSetter)
                                    .insert(keyExtractor, isDefaultIdTester)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                            final String namedInsertSQL = (String) args[0];
                            final Object entity = args[1];
                            N.checkArgNotEmpty(namedInsertSQL, "namedInsertSQL");
                            N.checkArgNotNull(entity, "entity");

                            if (callGenerateIdForInsertWithSql && isDefaultIdTester.test(idGetter.apply(entity))) {
                                idSetter.accept(((CrudDao) proxy).generateId(), entity);
                            }

                            return proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
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
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            final boolean allDefaultIdValue = N.allMatch(entities, entity -> isDefaultIdTester.test(idGetter.apply(entity)));

                            if (!allDefaultIdValue && callGenerateIdForInsert) {
                                final CrudDao crudDao = (CrudDao) proxy;

                                for (final Object entity : entities) {
                                    if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                        idSetter.accept(crudDao.generateId(), entity);
                                    }
                                }
                            }

                            final ParsedSql namedInsertSQL = allDefaultIdValue ? namedInsertWithoutIdSQL : namedInsertWithIdSQL;
                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                        .addBatchParameters(entities)
                                        .batchInsert(keyExtractor, isDefaultIdTester);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = Seq.of(entities)
                                                .split(batchSize)
                                                .flatmap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor, isDefaultIdTester))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
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
                            N.checkArgNotEmpty(propNamesToInsert, "propNamesToInsert");

                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            if (callGenerateIdForInsert && !N.disjoint(propNamesToInsert, idPropNameSet)) {
                                final CrudDao crudDao = (CrudDao) proxy;

                                for (final Object entity : entities) {
                                    if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                        idSetter.accept(crudDao.generateId(), entity);
                                    }
                                }
                            }

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToInsert).sql();
                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                        .addBatchParameters(entities)
                                        .batchInsert(keyExtractor, isDefaultIdTester);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = Seq.of(entities)
                                                .split(batchSize)
                                                .flatmap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor, isDefaultIdTester))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
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

                            if ((N.notEmpty(ids) && ids.size() != entities.size()) && daoLogger.isWarnEnabled()) {
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
                            final String namedInsertSQL = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgNotEmpty(namedInsertSQL, "namedInsertSQL");
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return new ArrayList<>();
                            }

                            if (callGenerateIdForInsertWithSql) {
                                final CrudDao crudDao = (CrudDao) proxy;

                                for (final Object entity : entities) {
                                    if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                        idSetter.accept(crudDao.generateId(), entity);
                                    }
                                }
                            }

                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                        .addBatchParameters(entities)
                                        .batchInsert(keyExtractor, isDefaultIdTester);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = Seq.of(entities)
                                                .split(batchSize)
                                                .flatmap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor, isDefaultIdTester))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
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
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForBytes") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForBytes();
                        };
                    } else if (methodName.equals("queryForSingleResult") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForSingleResult(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).queryForSingleNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).findFirst(rowMapper);
                        };
                    } else if (methodName.equals("queryForUniqueResult") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(2).settParameters(id, idParamSetter).queryForUniqueResult(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Class.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Class<?> targetValueType = (Class) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");
                            N.checkArgNotNull(targetValueType, "targetValueType");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 2 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(2).settParameters(id, idParamSetter).queryForUniqueNonNull(targetValueType);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(String.class)
                            && !paramTypes[1].equals(Condition.class) && paramTypes[2].equals(Jdbc.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final String selectPropName = (String) args[0];
                            final Object id = args[1];
                            final Jdbc.RowMapper<?> rowMapper = (Jdbc.RowMapper<?>) args[2];
                            N.checkArgNotEmpty(selectPropName, "selectPropName");
                            N.checkArgNotNull(id, "id");
                            N.checkArgNotNull(rowMapper, "rowMapper");

                            final Condition limitedCond = handleLimit(idCond, addLimitForSingleQuery ? 1 : -1, dbVersion);
                            final SP sp = singleQueryByIdSQLBuilderFunc.apply(selectPropName, limitedCond);
                            return proxy.prepareNamedQuery(sp.query).setFetchSize(1).settParameters(id, idParamSetter).findOnlyOne(rowMapper);
                        };
                    } else if (methodName.equals("gett")) {
                        if (paramLen == 1) {
                            call = (proxy, args) -> {
                                final Object id = args[0];
                                N.checkArgNotNull(id, "id");

                                return proxy.prepareNamedQuery(namedGetByIdSQL)
                                        .setFetchSize(2)
                                        .settParameters(id, idParamSetter)
                                        .findOnlyOneOrNull(entityClass);
                            };
                        } else {
                            call = (proxy, args) -> {
                                final Object id = args[0];
                                N.checkArgNotNull(id, "id");

                                final Collection<String> selectPropNames = (Collection<String>) args[1];

                                if (N.isEmpty(selectPropNames)) {
                                    return proxy.prepareNamedQuery(namedGetByIdSQL)
                                            .setFetchSize(2)
                                            .settParameters(id, idParamSetter)
                                            .findOnlyOneOrNull(entityClass);

                                } else {
                                    return proxy.prepareNamedQuery(namedSelectSQLBuilderFunc.apply(selectPropNames, idCond).sql())
                                            .setFetchSize(2)
                                            .settParameters(id, idParamSetter)
                                            .findOnlyOneOrNull(entityClass);
                                }
                            };
                        }
                    } else if (methodName.equals("batchGet") && paramLen == 3 && Collection.class.equals(paramTypes[0])
                            && Collection.class.equals(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> ids = (Collection<Object>) args[0];
                            final Collection<String> selectPropNames = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(ids)) {
                                return new ArrayList<>();
                            }

                            final Object firstId = N.firstElement(ids).get();
                            final boolean isMap = firstId instanceof Map;
                            final boolean isEntity = firstId != null && ClassUtil.isBeanClass(firstId.getClass());

                            N.checkArgument(idPropNameList.size() > 1 || !(isEntity || isMap || isEntityId),
                                    "Input 'ids' can not be EntityIds/Maps or entities for single id ");

                            final List idList = ids instanceof List ? (List) ids : new ArrayList(ids);
                            final List<Object> resultList = new ArrayList<>(idList.size());
                            List<Object> entities = null;

                            if (idPropNameList.size() == 1) {
                                String sql_selectPart = selectSQLBuilderFunc.apply(selectPropNames, idCond).sql();
                                sql_selectPart = sql_selectPart.substring(0, sql_selectPart.lastIndexOf('=')) + "IN ";

                                if (idList.size() >= batchSize) {
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer();

                                    for (int i = 0; i < batchSize; i++) {
                                        joiner.append('?');
                                    }

                                    final String query = sql_selectPart + joiner.toString();

                                    try (PreparedQuery preparedQuery = proxy.prepareQuery(query)
                                            .setFetchDirection(FetchDirection.FORWARD)
                                            .setFetchSize(batchSize)
                                            .closeAfterExecution(false)) {
                                        for (int i = 0, to = idList.size() - batchSize; i <= to; i += batchSize) {
                                            resultList.addAll(batchGetById(preparedQuery, idList.subList(i, i + batchSize), entityClass));
                                        }
                                    }
                                }

                                if (idList.size() % batchSize != 0) {
                                    final int remaining = idList.size() % batchSize;
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer();

                                    for (int i = 0; i < remaining; i++) {
                                        joiner.append('?');
                                    }

                                    final String query = sql_selectPart + joiner.toString();

                                    entities = batchGetById(proxy.prepareQuery(query).setFetchDirection(FetchDirection.FORWARD).setFetchSize(remaining),
                                            idList.subList(idList.size() - remaining, idList.size()), entityClass);

                                    resultList.addAll(entities);
                                }
                            } else {
                                if (idList.size() >= batchSize) {
                                    for (int i = 0, to = idList.size() - batchSize; i <= to; i += batchSize) {
                                        if (isEntityId) {
                                            entities = proxy.list(CF.id2Cond(idList.subList(i, i + batchSize)));
                                        } else if (isMap) {
                                            entities = proxy.list(CF.eqAndOr(idList.subList(i, i + batchSize)));
                                        } else {
                                            entities = proxy.list(CF.eqAndOr(idList.subList(i, i + batchSize), idPropNameList));
                                        }

                                        if (entities.size() > batchSize) {
                                            throw new DuplicatedResultException(
                                                    "The size of result: " + entities.size() + " is bigger than the size of input ids: " + batchSize);
                                        }

                                        resultList.addAll(entities);
                                    }
                                }

                                if (idList.size() % batchSize != 0) {
                                    final int remaining = idList.size() % batchSize;

                                    if (isEntityId) {
                                        entities = proxy.list(CF.id2Cond(idList.subList(idList.size() - remaining, idList.size())));
                                    } else if (isMap) {
                                        entities = proxy.list(CF.eqAndOr(idList.subList(idList.size() - remaining, idList.size())));
                                    } else {
                                        entities = proxy.list(CF.eqAndOr(idList.subList(idList.size() - remaining, idList.size()), idPropNameList));
                                    }

                                    if (entities.size() > remaining) {
                                        throw new DuplicatedResultException(
                                                "The size of result: " + entities.size() + " is bigger than the size of input ids: " + remaining);
                                    }

                                    resultList.addAll(entities);
                                }
                            }

                            if (resultList.size() > idList.size()) {
                                throw new DuplicatedResultException(
                                        "The size of result: " + resultList.size() + " is bigger than the size of input ids: " + idList.size());
                            }

                            return resultList;
                        };
                    } else if (methodName.equals("exists") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Object id = args[0];
                            N.checkArgNotNull(id, "id");

                            return proxy.prepareNamedQuery(namedExistsByIdSQL).setFetchSize(1).settParameters(id, idParamSetter).exists();
                        };
                    } else if (methodName.equals("count") && paramLen == 1 && Collection.class.equals(paramTypes[0])) {
                        final Collection<String> selectPropNames = N.asList(SQLBuilder.COUNT_ALL);
                        final int batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                        final String sql_selectPart = selectSQLBuilderFunc.apply(selectPropNames, idCond).sql();
                        final String sql_in_query = sql_selectPart.substring(0, sql_selectPart.lastIndexOf('=')) + "IN ";

                        call = (proxy, args) -> {
                            final Collection<Object> ids = (Collection<Object>) args[0];

                            if (N.isEmpty(ids)) {
                                return 0;
                            }

                            final Object firstId = N.firstElement(ids).get();
                            final boolean isMap = firstId instanceof Map;
                            final boolean isEntity = firstId != null && ClassUtil.isBeanClass(firstId.getClass());

                            N.checkArgument(idPropNameList.size() > 1 || !(isEntity || isMap || isEntityId),
                                    "Input 'ids' can not be EntityIds/Maps or entities for single id ");

                            final List idList = ids instanceof Set ? new ArrayList(ids) : N.distinct(ids);
                            int result = 0;

                            if (idPropNameList.size() == 1) {
                                if (idList.size() >= batchSize) {
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer();

                                    for (int i = 0; i < batchSize; i++) {
                                        joiner.append('?');
                                    }

                                    final String query = sql_in_query + joiner.toString();

                                    try (PreparedQuery preparedQuery = proxy.prepareQuery(query).closeAfterExecution(false)) {
                                        for (int i = 0, to = idList.size() - batchSize; i <= to; i += batchSize) {
                                            result += preparedQuery.settParameters(idList.subList(i, i + batchSize), collParamsSetter)
                                                    .queryForInt()
                                                    .orElseZero();
                                        }
                                    }
                                }

                                if (idList.size() % batchSize != 0) {
                                    final int remaining = idList.size() % batchSize;
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer();

                                    for (int i = 0; i < remaining; i++) {
                                        joiner.append('?');
                                    }

                                    final String query = sql_in_query + joiner.toString();
                                    result += proxy.prepareQuery(query)
                                            .setFetchDirection(FetchDirection.FORWARD)
                                            .settParameters(idList.subList(idList.size() - remaining, idList.size()), collParamsSetter)
                                            .queryForInt()
                                            .orElseZero();
                                }
                            } else {
                                if (idList.size() >= batchSize) {
                                    for (int i = 0, to = idList.size() - batchSize; i <= to; i += batchSize) {
                                        if (isEntityId) {
                                            result += proxy.count(CF.id2Cond(idList.subList(i, i + batchSize)));
                                        } else if (isMap) {
                                            result += proxy.count(CF.eqAndOr(idList.subList(i, i + batchSize)));
                                        } else {
                                            result += proxy.count(CF.eqAndOr(idList.subList(i, i + batchSize), idPropNameList));
                                        }
                                    }
                                }

                                if (idList.size() % batchSize != 0) {
                                    final int remaining = idList.size() % batchSize;

                                    if (isEntityId) {
                                        result += proxy.count(CF.id2Cond(idList.subList(idList.size() - remaining, idList.size())));
                                    } else if (isMap) {
                                        result += proxy.count(CF.eqAndOr(idList.subList(idList.size() - remaining, idList.size())));
                                    } else {
                                        result += proxy.count(CF.eqAndOr(idList.subList(ids.size() - remaining, idList.size()), idPropNameList));
                                    }
                                }
                            }

                            return result;
                        };
                    } else if (methodName.equals("update") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, "entity");

                            return proxy.prepareNamedQuery(namedUpdateByIdSQL).settParameters(entity, objParamsSetter).update();
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && !Map.class.equals(paramTypes[0]) && Collection.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            N.checkArgNotNull(entity, "entity");
                            N.checkArgNotEmpty(propNamesToUpdate, "propNamesToUpdate");

                            final String query = namedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).where(idCond).sql();

                            return proxy.prepareNamedQuery(query).settParameters(entity, objParamsSetter).update();
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> props = (Map<String, Object>) args[0];
                            final Object id = args[1];
                            N.checkArgNotEmpty(props, "propsToUpdate");
                            N.checkArgNotNull(id, "id");

                            // TODO not set by PropInfo.dbType of ids? it should be okay because Id should be simple type(int, long, String, UUID, Timestamp).
                            // If want to use idParamSetter, it has to be named sql. How to prepare/set named parameters? it's a problem to resolve.
                            final Condition cond = id2CondFunc.apply(id);

                            final SP sp = parameterizedUpdateFunc.apply(tableName, entityClass).set(props).append(cond).build();
                            return proxy.prepareQuery(sp.query).settParameters(sp.parameters, collParamsSetter).update();
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 2 && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return 0;
                            }

                            long result = 0;

                            if (entities.size() <= batchSize) {
                                result = N.sum(proxy.prepareNamedQuery(namedUpdateByIdSQL).addBatchParameters(entities).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedUpdateByIdSQL).closeAfterExecution(false)) {
                                        result = Seq.of(entities).split(batchSize).sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp).batchUpdate()));
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            return Numbers.toIntExact(result);
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 3 && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgNotEmpty(propNamesToUpdate, "propNamesToUpdate");
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(entities)) {
                                return 0;
                            }

                            final String query = namedUpdateFunc.apply(tableName, entityClass).set(propNamesToUpdate).where(idCond).sql();
                            long result = 0;

                            if (entities.size() <= batchSize) {
                                result = N.sum(proxy.prepareNamedQuery(query).addBatchParameters(entities).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(query).closeAfterExecution(false)) {
                                        result = Seq.of(entities).split(batchSize).sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp).batchUpdate()));
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            return Numbers.toIntExact(result);
                        };
                    } else if (methodName.equals("deleteById")) {
                        call = (proxy, args) -> {
                            final Object id = args[0];
                            N.checkArgNotNull(id, "id");

                            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(id, idParamSetter).update();
                        };
                    } else if (methodName.equals("delete") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            N.checkArgNotNull(entity, "entity");

                            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        };

                        //} else if (methodName.equals("delete") && paramLen == 2 && !Condition.class.isAssignableFrom(paramTypes[0])
                        //        && OnDeleteAction.class.equals(paramTypes[1])) {
                        //    call = (proxy, args) -> {
                        //        final Object entity = (args[0]);
                        //        N.checkArgNotNull(entity, "entity");
                        //        final OnDeleteAction onDeleteAction = (OnDeleteAction) args[1];
                        //
                        //        if (onDeleteAction == null || onDeleteAction == OnDeleteAction.NO_ACTION) {
                        //            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        //        }
                        //
                        //        final Map<String, JoinInfo> entityJoinInfo = JoinInfo.getEntityJoinInfo(entityClass);
                        //
                        //        if (N.isEmpty(entityJoinInfo)) {
                        //            return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        //        } else {
                        //            final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                        //            long result = 0;
                        //
                        //            try {
                        //                Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = null;
                        //
                        //                for (JoinInfo propJoinInfo : entityJoinInfo.values()) {
                        //                    tp = onDeleteAction == OnDeleteAction.SET_NULL ? propJoinInfo.getSetNullSqlAndParamSetter(sbc)
                        //                            : propJoinInfo.getDeleteSqlAndParamSetter(sbc);
                        //
                        //                    result += proxy.prepareQuery(tp._1).setParameters(entity, tp._2).update();
                        //                }
                        //
                        //                result += proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                        //
                        //                tran.commit();
                        //            } finally {
                        //                tran.rollbackIfNotCommitted();
                        //            }
                        //
                        //            return Numbers.toIntExact(result);
                        //        }
                        //    };
                    } else if ((methodName.equals("batchDelete") || methodName.equals("batchDeleteByIds")) && paramLen == 2
                            && int.class.equals(paramTypes[1])) {

                        final Jdbc.BiParametersSetter<NamedQuery, Object> paramSetter = methodName.equals("batchDeleteByIds") ? idParamSetter
                                : idParamSetterByEntity;

                        call = (proxy, args) -> {
                            final Collection<Object> idsOrEntities = (Collection) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isEmpty(idsOrEntities)) {
                                return 0;
                            }

                            if (idsOrEntities.size() <= batchSize) {
                                return N.sum(proxy.prepareNamedQuery(namedDeleteByIdSQL).addBatchParameters(idsOrEntities, paramSetter).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                long result = 0;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedDeleteByIdSQL).closeAfterExecution(false)) {
                                        result = Seq.of(idsOrEntities)
                                                .split(batchSize)
                                                .sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp, paramSetter).batchUpdate()));
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }

                                return Numbers.toIntExact(result);
                            }
                        };
                        //    } else if (methodName.equals("batchDelete") && paramLen == 3 && OnDeleteAction.class.equals(paramTypes[1])
                        //        && int.class.equals(paramTypes[2])) {
                        //    final JdbcUtil.BiParametersSetter<NamedQuery, Object> paramSetter = idParamSetterByEntity;
                        //
                        //    call = (proxy, args) -> {
                        //        final Collection<Object> entities = (Collection) args[0];
                        //        final OnDeleteAction onDeleteAction = (OnDeleteAction) args[1];
                        //        final int batchSize = (Integer) args[2];
                        //        N.checkArgPositive(batchSize, "batchSize");
                        //
                        //        if (N.isEmpty(entities)) {
                        //            return 0;
                        //        } else if (onDeleteAction == null || onDeleteAction == OnDeleteAction.NO_ACTION) {
                        //            return ((CrudDao) proxy).batchDelete(entities, batchSize);
                        //        }
                        //
                        //        final Map<String, JoinInfo> entityJoinInfo = JoinInfo.getEntityJoinInfo(entityClass);
                        //
                        //        if (N.isEmpty(entityJoinInfo)) {
                        //            return ((CrudDao) proxy).batchDelete(entities, batchSize);
                        //        }
                        //
                        //        final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                        //        long result = 0;
                        //
                        //        try {
                        //            try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedDeleteByIdSQL).closeAfterExecution(false)) {
                        //                result = Stream.of(entities) //
                        //                        .splitToList(batchSize).checked() //
                        //                        .sumLong(bp -> {
                        //                            long tmpResult = 0;
                        //
                        //                            Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = null;
                        //
                        //                            for (JoinInfo propJoinInfo : entityJoinInfo.values()) {
                        //                                tp = onDeleteAction == OnDeleteAction.SET_NULL ? propJoinInfo.getSetNullSqlAndParamSetter(sbc)
                        //                                        : propJoinInfo.getDeleteSqlAndParamSetter(sbc);
                        //
                        //                                tmpResult += N.sum(proxy.prepareQuery(tp._1).addBatchParameters2(bp, tp._2).batchUpdate());
                        //                            }
                        //
                        //                            tmpResult += N.sum(nameQuery.addBatchParameters(bp, paramSetter).batchUpdate());
                        //
                        //                            return tmpResult;
                        //                        })
                        //                        .orZero();
                        //            }
                        //
                        //            tran.commit();
                        //        } finally {
                        //            tran.rollbackIfNotCommitted();
                        //        }
                        //
                        //        return Numbers.toIntExact(result);
                        //    };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + method);
                        };
                    }
                } else if (declaringClass.equals(JoinEntityHelper.class) || declaringClass.equals(UncheckedJoinEntityHelper.class)) {
                    if (methodName.equals("loadJoinEntities") && paramLen == 3 && !Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1]) && Collection.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final String joinEntityPropName = (String) args[1];
                            final Collection<String> selectPropNames = (Collection<String>) args[2];

                            N.checkArgNotNull(entity, "entity");
                            N.checkArgNotEmpty(joinEntityPropName, "joinEntityPropName");

                            final JoinInfo propJoinInfo = joinBeanInfo.get(joinEntityPropName);

                            N.checkArgument(propJoinInfo != null, "No join entity property found by name: \"{}\" in class: {}", joinEntityPropName,
                                    entityClass);

                            final Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                    .getSelectSQLBuilderAndParamSetter(sbc);

                            final Dao<?, SQLBuilder, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource,
                                    proxy);

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

                            N.checkArgNotEmpty(joinEntityPropName, "joinEntityPropName");

                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, tableName, joinEntityPropName);

                            final Dao<?, SQLBuilder, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource,
                                    proxy);

                            if (N.isEmpty(entities)) {
                                // Do nothing.
                            } else if (entities.size() == 1) {
                                final Object first = N.firstOrNullIfEmpty(entities);

                                final Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                        .getSelectSQLBuilderAndParamSetter(sbc);

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
                                } else {
                                    propJoinInfo.joinPropInfo.setPropValue(first, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orElseNull());
                                }
                            } else {
                                final Tuple2<BiFunction<Collection<String>, Integer, String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                                        .getBatchSelectSQLBuilderAndParamSetter(sbc);

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

                                        propJoinInfo.setJoinPropEntities(bp, Stream.of(joinPropEntities).groupTo(it -> it.left(), it -> it.right()));
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

                            N.checkArgNotNull(entity, "entity");
                            N.checkArgNotEmpty(joinEntityPropName, "joinEntityPropName");

                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, tableName, joinEntityPropName);
                            final Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo.getDeleteSqlAndParamSetter(sbc);

                            final Dao<?, SQLBuilder, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource,
                                    proxy);

                            if (Strings.isEmpty(tp._2)) {
                                return joinEntityDao.prepareQuery(tp._1).setParameters(entity, tp._3).update();
                            } else {
                                long result = 0;
                                final SQLTransaction tran = JdbcUtil.beginTransaction(joinEntityDao.dataSource());

                                try {
                                    result = joinEntityDao.prepareQuery(tp._1).setParameters(entity, tp._3).update();
                                    result += joinEntityDao.prepareQuery(tp._2).setParameters(entity, tp._3).update();

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }

                                return Numbers.toIntExact(result);
                            }
                        };
                    } else if (methodName.equals("deleteJoinEntities") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection) args[0];
                            final String joinEntityPropName = (String) args[1];

                            N.checkArgNotEmpty(joinEntityPropName, "joinEntityPropName");

                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(daoInterface, entityClass, tableName, joinEntityPropName);

                            final Dao<?, SQLBuilder, ?> joinEntityDao = getApplicableDaoForJoinEntity(propJoinInfo.referencedEntityClass, primaryDataSource,
                                    proxy);

                            if (N.isEmpty(entities)) {
                                return 0;
                            } else if (entities.size() == 1) {
                                final Object first = N.firstOrNullIfEmpty(entities);

                                final Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                        .getDeleteSqlAndParamSetter(sbc);

                                if (Strings.isEmpty(tp._2)) {
                                    return joinEntityDao.prepareQuery(tp._1).setParameters(first, tp._3).update();
                                } else {
                                    int result = 0;
                                    final SQLTransaction tran = JdbcUtil.beginTransaction(joinEntityDao.dataSource());

                                    try {
                                        result = joinEntityDao.prepareQuery(tp._1).setParameters(first, tp._3).update();
                                        result += joinEntityDao.prepareQuery(tp._2).setParameters(first, tp._3).update();

                                        tran.commit();
                                    } finally {
                                        tran.rollbackIfNotCommitted();
                                    }

                                    return result;
                                }
                            } else {
                                long result = 0;
                                final SQLTransaction tran = JdbcUtil.beginTransaction(joinEntityDao.dataSource());

                                try {
                                    final Tuple3<IntFunction<String>, IntFunction<String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                                            .getBatchDeleteSQLBuilderAndParamSetter(sbc);

                                    result = Seq.of(entities).split(JdbcUtil.DEFAULT_BATCH_SIZE).sumInt(bp -> {
                                        if (tp._2 == null) {
                                            return joinEntityDao.prepareQuery(tp._1.apply(bp.size())).setParameters(bp, tp._3).update();
                                        } else {
                                            return joinEntityDao.prepareQuery(tp._1.apply(bp.size())).setParameters(bp, tp._3).update()
                                                    + joinEntityDao.prepareQuery(tp._2.apply(bp.size())).setParameters(bp, tp._3).update();
                                        }
                                    });

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
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

                    if (!(isNonDBOperation || isUncheckedDao || throwsSQLException || isStreamReturn)) {
                        throw new UnsupportedOperationException("'throws SQLException' is not declared in method: " + fullClassMethodName
                                + ". It's required for Dao interface extends Dao. Don't want to throw SQLException? extends UncheckedDao");
                    }

                    if (isStreamReturn && throwsSQLException) {
                        throw new UnsupportedOperationException("'throws SQLException' is not allowed in method: " + fullClassMethodName
                                + " because its return type is Stream which will be lazy evaluation");
                    }

                    final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];

                    final boolean isUpdateReturnType = returnType.equals(int.class) || returnType.equals(long.class) || returnType.equals(boolean.class)
                            || returnType.equals(void.class);

                    final QueryInfo queryInfo = sqlAnnoMap.get(sqlAnno.annotationType()).apply(sqlAnno, newSQLMapper);
                    final String query = N.checkArgNotEmpty(queryInfo.sql, "sql can't be null or empty");
                    final ParsedSql parsedSql = queryInfo.parsedSql;
                    final boolean isBatch = queryInfo.isBatch;
                    final int tmpBatchSize = queryInfo.batchSize;
                    final OP op = queryInfo.op;
                    final boolean isSingleParameter = queryInfo.isSingleParameter;
                    final boolean isCall = queryInfo.isCall;

                    final boolean isQuery = sqlAnno.annotationType().equals(Select.class)
                            || (isCall && !(op == OP.update || op == OP.largeUpdate) && (op != OP.DEFAULT || !isUpdateReturnType));

                    final boolean returnGeneratedKeys = !isNoId && sqlAnno.annotationType().equals(Insert.class);

                    final boolean isNamedQuery = queryInfo.isNamedQuery;

                    if (parsedSql.getParameterCount() == 0 && !isNamedQuery) {
                        daoLogger.debug("Non-named query will be created with sql {} for method {}", queryInfo.sql, fullClassMethodName);
                    }

                    final Predicate<Class<?>> isRowMapperOrResultExtractor = it -> Jdbc.ResultExtractor.class.isAssignableFrom(it)
                            || Jdbc.BiResultExtractor.class.isAssignableFrom(it) || Jdbc.RowMapper.class.isAssignableFrom(it)
                            || Jdbc.BiRowMapper.class.isAssignableFrom(it);

                    if (isNamedQuery || isCall) {
                        // @Bind parameters are not always required for named query. It's not required if parameter is Entity/Map/EntityId/...
                        //    if (IntStreamEx.range(0, paramLen)
                        //            .noneMatch(i -> StreamEx.of(m.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(Dao.Bind.class)))) {
                        //        throw new UnsupportedOperationException(
                        //                "@Bind parameters are required for named query but none is defined in method: " + fullClassMethodName);
                        //    }

                        if (isNamedQuery) {
                            final List<String> tmp = IntStreamEx.range(0, paramLen)
                                    .mapToObj(i -> StreamEx.of(method.getParameterAnnotations()[i]).select(Bind.class).first().orElseNull())
                                    .skipNulls()
                                    .map(Bind::value)
                                    .filter(it -> !parsedSql.getNamedParameters().contains(it))
                                    .toList();

                            if (N.notEmpty(tmp)) {
                                throw new IllegalArgumentException(
                                        "Named parameters bound with names: " + tmp + " are not found the sql annotated in method: " + fullClassMethodName);
                            }
                        }
                    } else {
                        if (IntStreamEx.range(0, paramLen)
                                .anyMatch(i -> StreamEx.of(method.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(Bind.class)))) {
                            throw new UnsupportedOperationException("@Bind parameters are defined for non-named query in method: " + fullClassMethodName);
                        }
                    }

                    final int[] tmp = IntStreamEx.range(0, paramLen).filter(i -> !isRowMapperOrResultExtractor.test(paramTypes[i])).toArray();

                    if (N.notEmpty(tmp) && tmp[tmp.length - 1] != tmp.length - 1) {
                        throw new UnsupportedOperationException(
                                "RowMapper/ResultExtractor must be the last parameter but not in method: " + fullClassMethodName);
                    }

                    final Predicate<Class<?>> isRowFilter = it -> Jdbc.RowFilter.class.isAssignableFrom(it) || Jdbc.BiRowFilter.class.isAssignableFrom(it);

                    final int[] tmp2 = IntStreamEx.of(tmp).filter(i -> !isRowFilter.test(paramTypes[i])).toArray();

                    if (N.notEmpty(tmp2) && tmp2[tmp2.length - 1] != tmp2.length - 1) {
                        throw new UnsupportedOperationException(
                                "RowFilter/BiRowFilter must be the last parameter or just before RowMapper/ResultExtractor but not in method: "
                                        + fullClassMethodName);
                    }

                    final Predicate<Class<?>> isParameterSetter = it -> Jdbc.ParametersSetter.class.isAssignableFrom(it)
                            || Jdbc.BiParametersSetter.class.isAssignableFrom(it) || Jdbc.TriParametersSetter.class.isAssignableFrom(it);

                    final int[] tmp3 = IntStreamEx.of(tmp2).filter(i -> !isParameterSetter.test(paramTypes[i])).toArray();

                    if (N.notEmpty(tmp3) && tmp3[tmp3.length - 1] != tmp3.length - 1) {
                        throw new UnsupportedOperationException(
                                "ParametersSetter/BiParametersSetter/TriParametersSetter must be the last parameter or just before RowFilter/BiRowFilter/RowMapper/ResultExtractor but not in method: "
                                        + fullClassMethodName);
                    }

                    final boolean hasRowMapperOrResultExtractor = paramLen > 0 && isRowMapperOrResultExtractor.test(lastParamType);

                    final boolean hasRowFilter = paramLen >= 2 && (Jdbc.RowFilter.class.isAssignableFrom(paramTypes[paramLen - 2])
                            || Jdbc.BiRowFilter.class.isAssignableFrom(paramTypes[paramLen - 2]));

                    if (hasRowFilter && !(hasRowMapperOrResultExtractor
                            && (Jdbc.RowMapper.class.isAssignableFrom(lastParamType) || Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType)))) {
                        throw new UnsupportedOperationException(
                                "Parameter 'RowFilter/BiRowFilter' is not supported without last parameter to be 'RowMapper/BiRowMapper' in method: "
                                        + fullClassMethodName);
                    }

                    if (hasRowMapperOrResultExtractor
                            && (Jdbc.RowMapper.class.isAssignableFrom(lastParamType) || Jdbc.BiRowMapper.class.isAssignableFrom(lastParamType))
                            && !(op == OP.findFirst || op == OP.findOnlyOne || op == OP.list || op == OP.stream || op == OP.listAll || op == OP.DEFAULT)) {
                        throw new UnsupportedOperationException(
                                "Parameter 'RowMapper/BiRowMapper' is not supported by OP = " + op + " in method: " + fullClassMethodName);
                    }

                    if (hasRowMapperOrResultExtractor
                            && (Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType) || Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType))
                            && !(op == OP.query || op == OP.queryAll || op == OP.streamAll || op == OP.DEFAULT)) {
                        throw new UnsupportedOperationException(
                                "Parameter 'ResultExtractor/BiResultExtractor' is not supported by OP = " + op + " in method: " + fullClassMethodName);
                    }

                    // TODO may enable it later.
                    if (hasRowMapperOrResultExtractor) {
                        throw new UnsupportedOperationException(
                                "Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present. Can't use it in method: "
                                        + fullClassMethodName);
                    }

                    final int[] defineParamIndexes = IntStreamEx.of(tmp3)
                            .filter(i -> N.anyMatch(method.getParameterAnnotations()[i],
                                    it -> it.annotationType().equals(Define.class) || it.annotationType().equals(DefineList.class)
                                            || it.annotationType().equals(BindList.class)))
                            .toArray();

                    final int defineParamLen = N.len(defineParamIndexes);

                    if (defineParamLen > 0) {
                        //    if (defineParamIndexes[0] != 0 || defineParamIndexes[defineParamLen - 1] - defineParamIndexes[0] + 1 != defineParamLen) {
                        //        throw new UnsupportedOperationException(
                        //                "Parameters annotated with @Define must be at the head of the parameter list of method: " + fullClassMethodName);
                        //    }

                        for (int i = 0; i < defineParamLen; i++) {
                            if ((paramTypes[defineParamIndexes[i]].isArray() || Collection.class.isAssignableFrom(paramTypes[defineParamIndexes[i]]))
                                    && N.noneMatch(method.getParameterAnnotations()[defineParamIndexes[i]],
                                            it -> it.annotationType().equals(DefineList.class) || it.annotationType().equals(BindList.class))) {
                                throw new UnsupportedOperationException("Array/Collection type of parameter[" + i
                                        + "] must be annotated with @DefineList or @BindList, not @Define, in method: " + fullClassMethodName);
                            }
                        }

                        if (IntStreamEx.of(defineParamIndexes)
                                .filter(i -> N.anyMatch(method.getParameterAnnotations()[i], it -> it.annotationType().equals(DefineList.class)))
                                .anyMatch(i -> !(Collection.class.isAssignableFrom(paramTypes[i]) || paramTypes[i].isArray()))) {
                            throw new UnsupportedOperationException(
                                    "Type of parameter annotated with @DefineList(method: " + fullClassMethodName + ") must be Collection/Array.");
                        }

                        if (IntStreamEx.of(defineParamIndexes)
                                .filter(i -> N.anyMatch(method.getParameterAnnotations()[i], it -> it.annotationType().equals(BindList.class)))
                                .anyMatch(i -> !(Collection.class.isAssignableFrom(paramTypes[i]) || paramTypes[i].isArray()))) {
                            throw new UnsupportedOperationException(
                                    "Type of parameter annotated with @BindList(method: " + fullClassMethodName + ") must be Collection/Array.");
                        }

                        if ((isNamedQuery || isCall) && IntStreamEx.of(defineParamIndexes)
                                .flatMapToObj(i -> StreamEx.of(method.getParameterAnnotations()[i]))
                                .anyMatch(it -> BindList.class.isAssignableFrom(it.annotationType()))) {
                            throw new UnsupportedOperationException(
                                    "@BindList on method: " + fullClassMethodName + " is not supported for named or callable query.");
                        }
                    }

                    final BiFunction<Annotation, Object, String> defineParamMapper = (anno, param) -> N.stringOf(param);
                    final BiFunction<Annotation, Object, String> arrayDefineListParamMapper = (anno, param) -> param == null || Array.getLength(param) == 0 ? ""
                            : N.toJson(param, jsc_no_bracket);
                    final BiFunction<Annotation, Object, String> collDefineListParamMapper = (anno, param) -> param == null ? ""
                            : N.toJson(param, jsc_no_bracket);

                    final BiFunction<Annotation, Object, String> arrayBindListParamMapper = (anno, param) -> param == null || Array.getLength(param) == 0 ? ""
                            : Strings.repeat(WD.QUESTION_MARK, Array.getLength(param), WD.COMMA_SPACE, ((BindList) anno).prefixForNonEmpty(),
                                    ((BindList) anno).suffixForNonEmpty());

                    final BiFunction<Annotation, Object, String> collBindListParamMapper = (anno, param) -> param == null || ((Collection) param).size() == 0
                            ? ""
                            : Strings.repeat(WD.QUESTION_MARK, N.size((Collection) param), WD.COMMA_SPACE, ((BindList) anno).prefixForNonEmpty(),
                                    ((BindList) anno).suffixForNonEmpty());

                    final Tuple2<Annotation, String>[] defineAnnos = IntStreamEx.of(defineParamIndexes)
                            .mapToObj(i -> StreamEx.of(method.getParameterAnnotations()[i])
                                    .select(Define.class)
                                    .map(it -> Tuple2.of((Annotation) it, it.value()))
                                    .first()
                                    .orElseGet(() -> StreamEx.of(method.getParameterAnnotations()[i])
                                            .select(DefineList.class)
                                            .map(it -> Tuple2.of((Annotation) it, it.value()))
                                            .first()
                                            .orElseGet(() -> StreamEx.of(method.getParameterAnnotations()[i])
                                                    .select(BindList.class)
                                                    .map(it -> Tuple2.of((Annotation) it, it.value()))
                                                    .first()
                                                    .get())))
                            .map(tp -> Tuple.of(tp._1, tp._2.charAt(0) == '{' && tp._2.charAt(tp._2.length() - 1) == '}' ? tp._2 : "{" + tp._2 + "}"))
                            .toArray(Tuple2[]::new);

                    final BiFunction<Annotation, Object, String>[] defineMappers = IntStreamEx.of(defineParamIndexes)
                            .mapToObj(i -> StreamEx.of(method.getParameterAnnotations()[i]).map(Annotation::annotationType).map(it -> {
                                if (Define.class.isAssignableFrom(it)) {
                                    return defineParamMapper;
                                } else if (DefineList.class.isAssignableFrom(it)) {
                                    return Collection.class.isAssignableFrom(paramTypes[i]) ? collDefineListParamMapper : arrayDefineListParamMapper;
                                } else if (BindList.class.isAssignableFrom(it)) {
                                    return Collection.class.isAssignableFrom(paramTypes[i]) ? collBindListParamMapper : arrayBindListParamMapper;
                                } else {
                                    return null;
                                }
                            }).skipNulls().first())
                            .filter(u.Optional::isPresent)
                            .map(u.Optional::get)
                            .toArray(BiFunction[]::new);

                    if (N.notEmpty(defineAnnos) && N.anyMatch(defineAnnos, it -> !query.contains(it._2))) {
                        throw new IllegalArgumentException("Defines: " + N.filter(defineAnnos, it -> !query.contains(it._2))
                                + " are not found in sql annotated in method: " + fullClassMethodName);
                    }

                    final int[] stmtParamIndexes = IntStreamEx.of(tmp3)
                            .filter(i -> StreamEx.of(method.getParameterAnnotations()[i])
                                    .noneMatch(it -> it.annotationType().equals(Define.class) || it.annotationType().equals(DefineList.class)))
                            .toArray();

                    final boolean[] bindListParamFlags = IntStreamEx.of(stmtParamIndexes)
                            .mapToObj(i -> StreamEx.of(method.getParameterAnnotations()[i]).anyMatch(it -> it.annotationType().equals(BindList.class)))
                            .toListThenApply(N::toBooleanArray);

                    final int stmtParamLen = stmtParamIndexes.length;

                    if (stmtParamLen == 1 && (ClassUtil.isBeanClass(paramTypes[stmtParamIndexes[0]])
                            || Map.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]) || EntityId.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])
                            || ClassUtil.isRecordClass(paramTypes[stmtParamIndexes[0]])) && !isNamedQuery) {
                        throw new UnsupportedOperationException(
                                "Using named query: @NamedSelect/NamedUpdate/NamedInsert/NamedDelete when parameter type is Entity/Map/EntityId in method: "
                                        + fullClassMethodName);
                    }

                    if (isSingleParameter && stmtParamLen != 1) {
                        throw new UnsupportedOperationException(
                                "Don't set 'isSingleParameter' to true if the count of statement/query parameter is not one in method: " + fullClassMethodName);
                    }

                    final List<OutParameter> outParameterList = StreamEx.of(method.getAnnotations())
                            .select(OutParameter.class)
                            .append(StreamEx.of(method.getAnnotations()).select(OutParameterList.class).flattmap(OutParameterList::value))
                            .toList();

                    if (N.notEmpty(outParameterList)) {
                        if (!isCall) {
                            throw new UnsupportedOperationException(
                                    "@OutParameter annotations are only supported by method annotated by @Call, not supported in method: "
                                            + fullClassMethodName);
                        }

                        if (StreamEx.of(outParameterList).anyMatch(it -> Strings.isEmpty(it.name()) && it.position() < 0)) {
                            throw new UnsupportedOperationException(
                                    "One of the attribute: (name, position) of @OutParameter must be set in method: " + fullClassMethodName);
                        }
                    }

                    if ((op == OP.listAll || op == OP.queryAll || op == OP.streamAll || op == OP.executeAndGetOutParameters) && !isCall) {
                        throw new UnsupportedOperationException(
                                "Op.listAll/queryAll/streamAll/executeAndGetOutParameters are only supported by method annotated with @Call but method: "
                                        + fullClassMethodName + " is not annotated with @Call");
                    }

                    if (isBatch && !((stmtParamLen == 1 && Collection.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]])) || (stmtParamLen == 2
                            && Collection.class.isAssignableFrom(paramTypes[stmtParamIndexes[0]]) && int.class.equals(paramTypes[stmtParamIndexes[1]])))) {
                        throw new UnsupportedOperationException("For batch operations(" + fullClassMethodName
                                + "), the first parameter must be Collection. The second parameter is optional, it only can be int if it's set");
                    }

                    //    final boolean isUpdateReturnType = returnType.equals(int.class) || returnType.equals(Integer.class) || returnType.equals(long.class)
                    //            || returnType.equals(Long.class) || returnType.equals(boolean.class) || returnType.equals(Boolean.class)
                    //            || returnType.equals(void.class);

                    final MappedByKey mappedByKeyAnno = method.getAnnotation(MappedByKey.class);
                    final String mappedByKey = mappedByKeyAnno == null ? null
                            : Strings.isNotEmpty(mappedByKeyAnno.value()) ? mappedByKeyAnno.value()
                                    : (Strings.isNotEmpty(mappedByKeyAnno.keyName()) ? mappedByKeyAnno.keyName() : oneIdPropName);

                    if (mappedByKeyAnno != null && Strings.isEmpty(mappedByKey)) {
                        throw new IllegalArgumentException("Mapped Key name can't be null or empty in method: " + fullClassMethodName);
                    }

                    if (Strings.isNotEmpty(mappedByKey)) {
                        final Method mappedByKeyMethod = ClassUtil.getPropGetMethod(entityClass, mappedByKey);

                        if (mappedByKeyMethod == null) {
                            throw new IllegalArgumentException(
                                    "No method found by mapped key: " + mappedByKey + " in entity class: " + ClassUtil.getCanonicalClassName(entityClass));
                        }

                        if (!(op == OP.DEFAULT || op == OP.list)) {
                            throw new IllegalArgumentException("OP for method annotated by @MappedByKey can't be: " + op + " in method: " + fullClassMethodName
                                    + ". It must be OP.DEFAULT or OP.list");
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
                                final Method mergedByIdMethod = ClassUtil.getPropGetMethod(entityClass, mergedById);

                                if (mergedByIdMethod == null) {
                                    throw new IllegalArgumentException("No method found by merged id: " + mergedById + " in entity class: "
                                            + ClassUtil.getCanonicalClassName(entityClass));
                                }
                            }

                            if (!(op == OP.DEFAULT || op == OP.findFirst || op == OP.findOnlyOne || op == OP.list)) {
                                throw new IllegalArgumentException("OP for method annotated by @MergedById can't be: " + op + " in method: "
                                        + fullClassMethodName + ". It must be OP.DEFAULT, OP.findFirst, OP.findOnlyOne or OP.list");
                            }

                            final Class<?> firstReturnEleType = getFirstReturnEleType(method);

                            if (!(((returnType.isAssignableFrom(Collection.class) && (op == OP.list || op == OP.DEFAULT))
                                    || ((returnType.isAssignableFrom(u.Optional.class) || returnType.isAssignableFrom(java.util.Optional.class))
                                            && (op == OP.findFirst || op == OP.findOnlyOne || op == OP.DEFAULT)))
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
                        if (!(op == OP.DEFAULT || op == OP.findFirst || op == OP.findOnlyOne || op == OP.list || op == OP.query || op == OP.stream)) {
                            throw new IllegalArgumentException("OP for method annotated by @PrefixFieldMapping can't be: " + op + " in method: "
                                    + fullClassMethodName + ". It must be OP.DEFAULT, OP.findFirst, OP.findOnlyOne, OP.list, OP.stream and OP.query");
                        }

                        final Class<?> firstReturnEleType = getFirstReturnEleType(method);

                        if (!(Strings.isNotEmpty(mappedByKey) || N.notEmpty(mergedByIds) || returnType.isAssignableFrom(DataSet.class)
                                || returnType.isAssignableFrom(entityClass)
                                || (firstReturnEleType != null && firstReturnEleType.isAssignableFrom(entityClass)))) {
                            throw new IllegalArgumentException("The return type of method(" + fullClassMethodName
                                    + ") annotated by @PrefixFieldMapping must be: Optional/List/Collection<? super "
                                    + ClassUtil.getSimpleClassName(entityClass) + ">/DataSet/" + ClassUtil.getSimpleClassName(entityClass) + ". It can't be: "
                                    + method.getGenericReturnType());
                        }
                    }

                    final Jdbc.BiParametersSetter<AbstractQuery, Object[]> parametersSetter = createParametersSetter(queryInfo, fullClassMethodName, method,
                            paramTypes, paramLen, stmtParamLen, stmtParamIndexes, bindListParamFlags, stmtParamLen);

                    if (isQuery) {
                        final Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> queryFunc = createQueryFunctionByMethod(entityClass, method,
                                mappedByKey, mergedByIds, prefixFieldMap, fetchColumnByEntityClass, hasRowMapperOrResultExtractor, hasRowFilter, op, isCall,
                                fullClassMethodName);

                        // Getting ClassCastException. Not sure why query result is being cast Dao. It seems there is a bug in JDk compiler.
                        //   call = (proxy, args) -> queryFunc.apply(JdbcUtil.prepareQuery(proxy, ds, query, isNamedQuery, fetchSize, queryTimeout, returnGeneratedKeys, args, paramSetter), args);

                        //    if (fetchSize <= 0) {
                        //        if (mergedByIdAnno != null) {
                        //            // skip
                        //        } else if (op == OP.findOnlyOne || op == OP.queryForUnique) {
                        //            // skip.
                        //        } else if (op == OP.exists || isExistsQuery(method, op, fullClassMethodName) || op == OP.findFirst || op == OP.queryForSingle
                        //                || isSingleReturnType(returnType)) {
                        //            // skip
                        //        } else if (op == OP.list || op == OP.listAll || op == OP.query || op == OP.queryAll || op == OP.stream || op == OP.streamAll
                        //                || isListQuery(method, returnType, op, fullClassMethodName)) {
                        //            // skip.
                        //        } else if (lastParamType != null && (Jdbc.ResultExtractor.class.isAssignableFrom(lastParamType)
                        //                || Jdbc.BiResultExtractor.class.isAssignableFrom(lastParamType))) {
                        //            // skip.
                        //        } else if (Stream.class.isAssignableFrom(returnType) || DataSet.class.isAssignableFrom(returnType)) {
                        //            // skip.
                        //        } else if (isCall) {
                        //            // skip.
                        //        } else {
                        //            // skip.
                        //        }
                        //    }

                        call = (proxy, args) -> queryFunc.apply(prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames, outParameterList, parametersSetter),
                                args);
                    } else if (sqlAnno.annotationType().equals(Insert.class)) {
                        if (isNoId && !returnType.isAssignableFrom(void.class)) {
                            throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                    + ") for no id entities only can be: void. It can't be: " + returnType);
                        }

                        final TriFunction<Optional<Object>, Object, Boolean, ?> insertResultConvertor = void.class.equals(returnType)
                                ? (ret, entity, isEntity) -> null
                                : (u.Optional.class.equals(returnType) ? (ret, entity, isEntity) -> ret
                                        : (ret, entity, isEntity) -> ret.orElse(isEntity ? idGetter.apply(entity) : N.defaultValueOf(returnType))); //NOSONAR

                        if (!isBatch) {
                            if (!(returnType.isAssignableFrom(void.class) || idClass == null
                                    || ClassUtil.wrap(idClass).isAssignableFrom(ClassUtil.wrap(returnType)) || returnType.isAssignableFrom(u.Optional.class))) {
                                throw new UnsupportedOperationException("The return type of insert operations(" + fullClassMethodName
                                        + ") only can be: void or 'ID' type. It can't be: " + returnType);
                            }

                            call = (proxy, args) -> {
                                final Jdbc.BiRowMapper<Object> keyExtractor = getIdExtractor(idExtractorHolder, idExtractor, proxy);
                                final boolean isEntity = stmtParamLen == 1 && args[stmtParamIndexes[0]] != null
                                        && ClassUtil.isBeanClass(args[stmtParamIndexes[0]].getClass());
                                final Object entity = isEntity ? args[stmtParamIndexes[0]] : null;

                                final Optional<Object> id = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                        defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames, outParameterList,
                                        parametersSetter).insert(keyExtractor, isDefaultIdTester);

                                if (isEntity && id.isPresent()) {
                                    idSetter.accept(id.get(), entity);
                                }

                                return insertResultConvertor.apply(id, entity, isEntity);
                            };
                        } else {
                            if (!(returnType.equals(void.class) || returnType.isAssignableFrom(List.class))) {
                                throw new UnsupportedOperationException("The return type of batch insert operations(" + fullClassMethodName
                                        + ")  only can be: void/List<ID>/Collection<ID>. It can't be: " + returnType);
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

                                N.checkArgPositive(batchSize, "batchSize");

                                List<Object> ids = null;

                                if (N.isEmpty(batchParameters)) {
                                    ids = new ArrayList<>(0);
                                } else if (batchParameters.size() < batchSize) {
                                    AbstractQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames, outParameterList,
                                                parametersSetter).addBatchParameters(batchParameters, ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames, outParameterList,
                                                parametersSetter).addBatchParameters(batchParameters);
                                    }

                                    ids = preparedQuery.batchInsert(keyExtractor, isDefaultIdTester);
                                } else {
                                    final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                    try {
                                        try (AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method,
                                                returnType, args, defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames,
                                                outParameterList, parametersSetter).closeAfterExecution(false)) {

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
                                    } finally {
                                        tran.rollbackIfNotCommitted();
                                    }
                                }

                                final boolean isEntity = ClassUtil.isBeanClass(N.firstOrNullIfEmpty(batchParameters).getClass());

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

                                if ((N.notEmpty(ids) && ids.size() != batchParameters.size()) && daoLogger.isWarnEnabled()) {
                                    daoLogger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            batchParameters.size());
                                }

                                return void.class.equals(returnType) ? null : ids;
                            };
                        }
                    } else if (sqlAnno.annotationType().equals(Update.class) || sqlAnno.annotationType().equals(Delete.class)
                            || (sqlAnno.annotationType().equals(Call.class) && isUpdateReturnType)) {
                        if (!isUpdateReturnType) {
                            throw new UnsupportedOperationException("The return type of update/delete operations(" + fullClassMethodName
                                    + ") only can be: int/Integer/long/Long/boolean/Boolean/void. It can't be: " + returnType);
                        }

                        final LongFunction<?> updateResultConvertor = void.class.equals(returnType) ? updatedRecordCount -> null
                                : (Boolean.class.equals(ClassUtil.wrap(returnType)) ? updatedRecordCount -> updatedRecordCount > 0
                                        : (Integer.class.equals(ClassUtil.wrap(returnType)) ? Numbers::toIntExact : LongFunction.identity()));

                        final boolean isLargeUpdate = op == OP.largeUpdate
                                || (op == OP.DEFAULT && (returnType.equals(long.class) || returnType.equals(Long.class)));

                        if (!isBatch) {
                            call = (proxy, args) -> {
                                final AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType,
                                        args, defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames, outParameterList,
                                        parametersSetter);

                                final long updatedRecordCount = isLargeUpdate ? preparedQuery.largeUpdate() : preparedQuery.update();

                                return updateResultConvertor.apply(updatedRecordCount);
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

                                N.checkArgPositive(batchSize, "batchSize");

                                long updatedRecordCount = 0;

                                if (N.isEmpty(batchParameters)) {
                                    updatedRecordCount = 0;
                                } else if (batchParameters.size() < batchSize) {
                                    AbstractQuery preparedQuery = null;

                                    if (isSingleParameter) {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames, outParameterList,
                                                parametersSetter).addBatchParameters(batchParameters, ColumnOne.SET_OBJECT);
                                    } else {
                                        preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method, returnType, args,
                                                defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames, outParameterList,
                                                parametersSetter).addBatchParameters(batchParameters);
                                    }

                                    if (isLargeUpdate) {
                                        updatedRecordCount = N.sum(preparedQuery.largeBatchUpdate());
                                    } else {
                                        updatedRecordCount = N.sum(preparedQuery.batchUpdate());
                                    }
                                } else {
                                    final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                    try {
                                        try (AbstractQuery preparedQuery = prepareQuery(proxy, queryInfo, mergedByIdAnno, fullClassMethodName, method,
                                                returnType, args, defineParamIndexes, defineAnnos, defineMappers, returnGeneratedKeys, returnColumnNames,
                                                outParameterList, parametersSetter).closeAfterExecution(false)) {

                                            if (isSingleParameter) {
                                                updatedRecordCount = Seq.of(batchParameters)
                                                        .split(batchSize) //
                                                        .sumLong(bp -> isLargeUpdate
                                                                ? N.sum(preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT).largeBatchUpdate())
                                                                : N.sum(preparedQuery.addBatchParameters(bp, ColumnOne.SET_OBJECT).batchUpdate()));
                                            } else {
                                                updatedRecordCount = Seq.of((Collection<List<?>>) (Collection) batchParameters)
                                                        .split(batchSize) //
                                                        .sumLong(bp -> isLargeUpdate
                                                                //
                                                                ? N.sum(preparedQuery.addBatchParameters(bp).largeBatchUpdate())
                                                                : N.sum(preparedQuery.addBatchParameters(bp).batchUpdate()));
                                            }
                                        }

                                        tran.commit();
                                    } finally {
                                        tran.rollbackIfNotCommitted();
                                    }
                                }

                                return updateResultConvertor.apply(updatedRecordCount);
                            };
                        }
                    } else {
                        throw new UnsupportedOperationException(
                                "Unsupported sql annotation: " + sqlAnno.annotationType() + " in method: " + fullClassMethodName);
                    }
                }

                if (!throwsSQLException) {
                    final Throwables.BiFunction<Dao, Object[], ?, SQLException> tmp = (Throwables.BiFunction) call;

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
                    daoLogger.debug("Non-DB operation method: " + simpleClassMethodName);
                }

                // ignore
            } else {
                final Transactional transactionalAnno = StreamEx.of(method.getAnnotations()).select(Transactional.class).last().orElseNull();

                //    if (transactionalAnno != null && Modifier.isAbstract(m.getModifiers())) {
                //        throw new UnsupportedOperationException(
                //                "Annotation @Transactional is only supported by interface methods with default implementation: default xxx dbOperationABC(someParameters, String ... sqls), not supported by abstract method: "
                //                       + fullClassMethodName);
                //    }

                final SqlLogEnabled daoClassSqlLogAnno = StreamEx.of(allInterfaces)
                        .flattmap(Class::getAnnotations)
                        .select(SqlLogEnabled.class)
                        .filter(it -> StreamEx.of(it.filter()).anyMatch(filterByMethodNameContains))
                        .first()
                        .orElseNull();

                final PerfLog daoClassPerfLogAnno = StreamEx.of(allInterfaces)
                        .flattmap(Class::getAnnotations)
                        .select(PerfLog.class)
                        .filter(it -> StreamEx.of(it.filter()).anyMatch(filterByMethodNameContains))
                        .first()
                        .orElseNull();

                final SqlLogEnabled sqlLogAnno = StreamEx.of(method.getAnnotations()).select(SqlLogEnabled.class).last().orElse(daoClassSqlLogAnno);
                final PerfLog perfLogAnno = StreamEx.of(method.getAnnotations()).select(PerfLog.class).last().orElse(daoClassPerfLogAnno);
                final boolean hasSqlLogAnno = sqlLogAnno != null;
                final boolean hasPerfLogAnno = perfLogAnno != null;

                //    final boolean isSqlLogEnabled = hasSqlLogAnno && JdbcUtil.isSqlLogAllowed;
                //    final boolean isPerfLogEnabled = hasPerfLogAnno && (JdbcUtil.isSqlPerfLogAllowed || JdbcUtil.isDaoMethodPerfLogAllowed);
                //    final boolean isSqlPerfLogEnabled = hasPerfLogAnno && JdbcUtil.isSqlPerfLogAllowed;
                //    final boolean isDaoPerfLogEnabled = hasPerfLogAnno && JdbcUtil.isDaoMethodPerfLogAllowed;

                final Throwables.BiFunction<Dao, Object[], ?, Throwable> tmp = call;

                if (transactionalAnno == null || transactionalAnno.propagation() == Propagation.SUPPORTS) {
                    if (hasSqlLogAnno || hasPerfLogAnno) {
                        call = (proxy, args) -> {
                            final SqlLogConfig sqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
                            final boolean prevSqlLogEnabled = sqlLogConfig.isEnabled;
                            final int prevMaxSqlLogLength = sqlLogConfig.maxSqlLogLength;
                            final SqlLogConfig sqlPerfLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();
                            final long prevMinExecutionTimeForSqlPerfLog = sqlPerfLogConfig.minExecutionTimeForSqlPerfLog;
                            final int prevMaxPerfSqlLogLength = sqlPerfLogConfig.maxSqlLogLength;

                            if (hasSqlLogAnno) {
                                JdbcUtil.enableSqlLog(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                            }

                            if (hasPerfLogAnno) {
                                JdbcUtil.setMinExecutionTimeForSqlPerfLog(perfLogAnno.minExecutionTimeForSql(), perfLogAnno.maxSqlLogLength());
                            }

                            final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                            try {
                                return tmp.apply(proxy, args);
                            } finally {
                                if (hasPerfLogAnno) {
                                    logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTime);
                                }

                                if (hasPerfLogAnno) {
                                    JdbcUtil.setMinExecutionTimeForSqlPerfLog(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                }

                                if (hasSqlLogAnno) {
                                    JdbcUtil.enableSqlLog(prevSqlLogEnabled, prevMaxSqlLogLength);
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
                            final SqlLogConfig SqlPerfLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();
                            final long prevMinExecutionTimeForSqlPerfLog = SqlPerfLogConfig.minExecutionTimeForSqlPerfLog;
                            final int prevMaxPerfSqlLogLength = SqlPerfLogConfig.maxSqlLogLength;

                            if (hasSqlLogAnno) {
                                JdbcUtil.enableSqlLog(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                            }

                            if (hasPerfLogAnno) {
                                JdbcUtil.setMinExecutionTimeForSqlPerfLog(perfLogAnno.minExecutionTimeForSql(), perfLogAnno.maxSqlLogLength());
                            }

                            final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                            final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource(), transactionalAnno.isolation());
                            Object result = null;

                            try {
                                result = tmp.apply(proxy, args);

                                tran.commit();
                            } finally {
                                if (hasSqlLogAnno || hasPerfLogAnno) {
                                    try {
                                        tran.rollbackIfNotCommitted();
                                    } finally {
                                        if (hasPerfLogAnno) {
                                            logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTime);
                                        }

                                        if (hasPerfLogAnno) {
                                            JdbcUtil.setMinExecutionTimeForSqlPerfLog(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                        }

                                        if (hasSqlLogAnno) {
                                            JdbcUtil.enableSqlLog(prevSqlLogEnabled, prevMaxSqlLogLength);
                                        }
                                    }
                                } else {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            return result;
                        };
                    } else {
                        call = (proxy, args) -> {
                            final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource(), transactionalAnno.isolation());
                            Object result = null;

                            try {
                                result = tmp.apply(proxy, args);

                                tran.commit();
                            } finally {
                                tran.rollbackIfNotCommitted();
                            }

                            return result;
                        };
                    }
                } else if (transactionalAnno.propagation() == Propagation.REQUIRES_NEW) {
                    call = (proxy, args) -> {
                        final javax.sql.DataSource dataSource = proxy.dataSource();

                        return JdbcUtil.callNotInStartedTransaction(dataSource, () -> {
                            if (hasSqlLogAnno || hasPerfLogAnno) {
                                final SqlLogConfig sqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
                                final boolean prevSqlLogEnabled = sqlLogConfig.isEnabled;
                                final int prevMaxSqlLogLength = sqlLogConfig.maxSqlLogLength;
                                final SqlLogConfig SqlPerfLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();
                                final long prevMinExecutionTimeForSqlPerfLog = SqlPerfLogConfig.minExecutionTimeForSqlPerfLog;
                                final int prevMaxPerfSqlLogLength = SqlPerfLogConfig.maxSqlLogLength;

                                if (hasSqlLogAnno) {
                                    JdbcUtil.enableSqlLog(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                                }

                                if (hasPerfLogAnno) {
                                    JdbcUtil.setMinExecutionTimeForSqlPerfLog(perfLogAnno.minExecutionTimeForSql(), perfLogAnno.maxSqlLogLength());
                                }

                                final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource(), transactionalAnno.isolation());
                                Object result = null;

                                try {
                                    result = tmp.apply(proxy, args);

                                    tran.commit();
                                } finally {
                                    if (hasSqlLogAnno || hasPerfLogAnno) {
                                        try {
                                            tran.rollbackIfNotCommitted();
                                        } finally {
                                            if (hasPerfLogAnno) {
                                                logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTime);
                                            }

                                            if (hasPerfLogAnno) {
                                                JdbcUtil.setMinExecutionTimeForSqlPerfLog(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                            }

                                            if (hasSqlLogAnno) {
                                                JdbcUtil.enableSqlLog(prevSqlLogEnabled, prevMaxSqlLogLength);
                                            }
                                        }
                                    } else {
                                        tran.rollbackIfNotCommitted();
                                    }
                                }

                                return result;
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource(), transactionalAnno.isolation());
                                Object result = null;

                                try {
                                    result = tmp.apply(proxy, args);

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
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
                            return JdbcUtil.callNotInStartedTransaction(dataSource, () -> {
                                final SqlLogConfig sqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
                                final boolean prevSqlLogEnabled = sqlLogConfig.isEnabled;
                                final int prevMaxSqlLogLength = sqlLogConfig.maxSqlLogLength;
                                final SqlLogConfig SqlPerfLogConfig = JdbcUtil.minExecutionTimeForSqlPerfLog_TL.get();
                                final long prevMinExecutionTimeForSqlPerfLog = SqlPerfLogConfig.minExecutionTimeForSqlPerfLog;
                                final int prevMaxPerfSqlLogLength = SqlPerfLogConfig.maxSqlLogLength;

                                if (hasSqlLogAnno) {
                                    JdbcUtil.enableSqlLog(sqlLogAnno.value(), sqlLogAnno.maxSqlLogLength());
                                }

                                if (hasPerfLogAnno) {
                                    JdbcUtil.setMinExecutionTimeForSqlPerfLog(perfLogAnno.minExecutionTimeForSql(), perfLogAnno.maxSqlLogLength());
                                }

                                final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;

                                try {
                                    return tmp.apply(proxy, args);
                                } finally {
                                    if (hasPerfLogAnno) {
                                        logDaoMethodPerf(daoLogger, simpleClassMethodName, perfLogAnno, startTime);
                                    }

                                    if (hasPerfLogAnno) {
                                        JdbcUtil.setMinExecutionTimeForSqlPerfLog(prevMinExecutionTimeForSqlPerfLog, prevMaxPerfSqlLogLength);
                                    }

                                    if (hasSqlLogAnno) {
                                        JdbcUtil.enableSqlLog(prevSqlLogEnabled, prevMaxSqlLogLength);
                                    }
                                }
                            });
                        } else {
                            return JdbcUtil.callNotInStartedTransaction(dataSource, () -> tmp.apply(proxy, args));
                        }
                    };
                }

                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature = Tuple.of(method, ImmutableList.of(method.getParameterTypes()),
                        method.getReturnType());

                final CacheResult cacheResultAnno = StreamEx.of(method.getAnnotations())
                        .select(CacheResult.class)
                        .filter(it -> !it.disabled())
                        .last()
                        .orElse((daoClassCacheResultAnno != null //
                                && !daoClassCacheResultAnno.disabled() //
                                && !Strings.containsAnyIgnoreCase(method.getName(), "page", "paginate") //
                                && N.anyMatch(daoClassCacheResultAnno.filter(), filterByMethodNameStartsWith)) //
                                        ? daoClassCacheResultAnno
                                        : null);

                final RefreshCache refreshResultAnno = StreamEx.of(method.getAnnotations())
                        .select(RefreshCache.class)
                        .filter(it -> !it.disabled())
                        .last()
                        .orElse((daoClassRefreshCacheAnno != null // 
                                && !daoClassRefreshCacheAnno.disabled() //
                                && N.anyMatch(daoClassRefreshCacheAnno.filter(), filterByMethodNameStartsWith)) //
                                        ? daoClassRefreshCacheAnno
                                        : null);

                final long cacheLiveTime = cacheResultAnno == null ? 0 : cacheResultAnno.liveTime();
                final long cacheMaxIdleTime = cacheResultAnno == null ? 0 : cacheResultAnno.maxIdleTime();

                final boolean isQueryMethod = JdbcUtil.IS_QUERY_METHOD.test(method);
                final boolean isUpdateMethod = JdbcUtil.IS_UPDATE_METHOD.test(method);
                final boolean isAnnotatedCacheResult = cacheResultAnno != null && !cacheResultAnno.disabled();
                final boolean isAnnotatedRefreshResult = (refreshResultAnno != null && !refreshResultAnno.disabled());
                final Jdbc.DaoCache daoCacheToUseInMethod = isAnnotatedCacheResult || isAnnotatedRefreshResult ? daoCache : null;

                if (isAnnotatedCacheResult || isAnnotatedRefreshResult || (isQueryMethod || isUpdateMethod)) {
                    if (daoLogger.isDebugEnabled()) {
                        if (isAnnotatedCacheResult) {
                            daoLogger.debug("Add CacheResult method: " + method);
                        } else if (isAnnotatedRefreshResult) {
                            daoLogger.debug("Add RefreshCache method: " + method);
                        }
                    }

                    if (isAnnotatedCacheResult && Stream.of(notCacheableTypes).anyMatch(it -> it.isAssignableFrom(returnType))) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + simpleClassMethodName + " is not cacheable: " + method.getReturnType());
                    }

                    final String transferAttr = cacheResultAnno == null ? (kryoParser != null ? "kryo" : "json") : cacheResultAnno.transfer();

                    if (!(Strings.isEmpty(transferAttr) || SUPPORTED_TRANSFER_FOR_CACHE.contains(transferAttr.toLowerCase()))) {
                        throw new UnsupportedOperationException(
                                "Unsupported 'transfer' : " + transferAttr + " in annotation 'CacheResult' on method: " + simpleClassMethodName);
                    }

                    final Function<Object, Object> cloneFunc = Strings.isEmpty(transferAttr) || "none".equalsIgnoreCase(transferAttr) ? Fn.identity() : r -> {
                        final Class<?> cls = r.getClass();

                        if ((r == null) || !isValuePresentMap.getOrDefault(cls, Fn.alwaysFalse()).test(r) && isImmutableTester.test(cls)) {
                            return r;
                        } else if ("kryo".equalsIgnoreCase(transferAttr)) {
                            return kryoParser.clone(r);
                        } else {
                            return jsonParser.deserialize(jsonParser.serialize(r), r.getClass());
                        }
                    };

                    final Throwables.BiFunction<Dao, Object[], ?, Throwable> temp = call;

                    call = (proxy, args) -> {
                        final Jdbc.DaoCache localThreadCache = JdbcUtil.localThreadCache_TL.get();
                        final boolean isLocalThreadCacheEnabled = isQueryMethod && localThreadCache != null;
                        final boolean isRefreshLocalThreadCacheRequired = isUpdateMethod && localThreadCache != null;

                        final String cacheKey = isAnnotatedCacheResult || isAnnotatedRefreshResult || isLocalThreadCacheEnabled
                                || isRefreshLocalThreadCacheRequired ? JdbcUtil.createCacheKey(tableName, fullClassMethodName, args, daoLogger) : null;

                        Object result = null;

                        if (Strings.isNotEmpty(cacheKey)) {
                            if (isAnnotatedCacheResult) {
                                result = daoCacheToUseInMethod.get(cacheKey, proxy, args, methodSignature);
                            } else if (isLocalThreadCacheEnabled) {
                                result = localThreadCache.get(cacheKey, proxy, args, methodSignature);
                            }
                        }

                        if (result != null) {
                            return cloneFunc.apply(result);
                        }

                        if (isAnnotatedRefreshResult || isRefreshLocalThreadCacheRequired) {
                            try {
                                result = temp.apply(proxy, args);
                            } finally {
                                if (isAnnotatedRefreshResult) {
                                    daoCacheToUseInMethod.update(cacheKey, result, proxy, args, methodSignature);
                                }

                                if (isRefreshLocalThreadCacheRequired) {
                                    localThreadCache.update(cacheKey, result, proxy, args, methodSignature);
                                }
                            }
                        } else {
                            result = temp.apply(proxy, args);
                        }

                        if (Strings.isNotEmpty(cacheKey) && result != null) {
                            if (isAnnotatedCacheResult) {
                                if (result instanceof final DataSet dataSet) {
                                    if (dataSet.size() >= cacheResultAnno.minSize() && dataSet.size() <= cacheResultAnno.maxSize()) {
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
                            } else if (isLocalThreadCacheEnabled) {
                                localThreadCache.put(cacheKey, cloneFunc.apply(result), proxy, args, methodSignature);
                            }
                        }

                        return result;
                    };
                }

                final List<Tuple2<Jdbc.Handler, Boolean>> handlerList = StreamEx.of(method.getAnnotations())
                        .filter(anno -> anno.annotationType().equals(Handler.class) || anno.annotationType().equals(HandlerList.class))
                        .flatmap(anno -> anno.annotationType().equals(Handler.class) ? N.asList((Handler) anno) : N.asList(((HandlerList) anno).value()))
                        .prepend(StreamEx.of(daoClassHandlerList).filter(h -> StreamEx.of(h.filter()).anyMatch(filterByMethodNameContains)))
                        .map(handlerAnno -> Tuple.of((Jdbc.Handler) (Strings.isNotEmpty(handlerAnno.qualifier())
                                ? daoClassHandlerMap.getOrDefault(handlerAnno.qualifier(), HandlerFactory.get(handlerAnno.qualifier()))
                                : HandlerFactory.getOrCreate(handlerAnno.type())), handlerAnno.isForInvokeFromOutsideOfDaoOnly()))
                        .onEach(handler -> N.checkArgNotNull(handler._1,
                                "No handler found/registered with qualifier or type in class/method: " + fullClassMethodName))
                        .toList();

                if (N.notEmpty(handlerList)) {
                    final Throwables.BiFunction<Dao, Object[], ?, Throwable> temp = call;

                    call = (proxy, args) -> {
                        final boolean isInDaoMethod = isInDaoMethod_TL.get();

                        if (isInDaoMethod) {
                            for (final Tuple2<Jdbc.Handler, Boolean> tp : handlerList) {
                                if (!tp._2) {
                                    tp._1.beforeInvoke(proxy, args, methodSignature);
                                }
                            }

                            final Object result = temp.apply(proxy, args);

                            Tuple2<Jdbc.Handler, Boolean> tp = null;

                            for (int i = N.size(handlerList) - 1; i >= 0; i--) {
                                tp = handlerList.get(i);

                                if (!tp._2) {
                                    tp._1.afterInvoke(result, proxy, args, methodSignature);
                                }
                            }

                            return result;
                        } else {
                            isInDaoMethod_TL.set(true);

                            try {
                                for (final Tuple2<Jdbc.Handler, Boolean> tp : handlerList) {
                                    tp._1.beforeInvoke(proxy, args, methodSignature);
                                }

                                final Object result = temp.apply(proxy, args);

                                for (int i = N.size(handlerList) - 1; i >= 0; i--) {
                                    handlerList.get(i)._1.afterInvoke(result, proxy, args, methodSignature);
                                }

                                return result;
                            } finally {
                                isInDaoMethod_TL.set(false);
                            }
                        }
                    };
                }

                if (isAnnotatedCacheResult) {
                    hasCacheResult.setTrue();
                }

                if (isAnnotatedRefreshResult) {
                    hasRefreshCache.setTrue();
                }
            }

            // TODO maybe it's not a good idea to support Cache in general Dao which supports update/delete operations.
            if ((hasRefreshCache.isTrue() || hasCacheResult.isTrue()) && !NoUpdateDao.class.isAssignableFrom(daoInterface)) {
                throw new UnsupportedOperationException(
                        "Cache is only supported for the methods declared NoUpdateDao/UncheckedNoUpdateDao interface right now, not supported for method: "
                                + fullClassMethodName);
            }

            if (hasRefreshCache.isTrue() && hasCacheResult.isFalse()) {
                throw new UnsupportedOperationException("Class: " + daoInterface
                        + " or its super interfaces or methods are annotated by @RefreshCache, but none of them is annotated with @CacheResult. "
                        + "Please remove the unnecessary @RefreshCache annotations or Add @CacheResult annotation if it's really needed.");
            }

            methodInvokerMap.put(method, call);
        }

        final Throwables.TriFunction<Dao, Method, Object[], ?, Throwable> proxyInvoker = (proxy, method, args) -> methodInvokerMap.get(method)
                .apply(proxy, args);
        final Class<TD>[] interfaceClasses = N.asArray(daoInterface);

        final InvocationHandler h = (proxy, method, args) -> {
            if (daoLogger.isDebugEnabled() && !nonDBOperationSet.contains(method)) {
                daoLogger.debug("Invoking Dao method: {} with args: {}", method.getName(), args);
            }

            return proxyInvoker.apply((Dao) proxy, method, args);
        };

        daoInstance = N.newProxyInstance(interfaceClasses, h);

        daoPool.put(daoCacheKey, daoInstance);

        return daoInstance;
    }

    @SuppressWarnings("rawtypes")
    private static final Map<String, Dao> joinEntityDaoPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    static Dao getApplicableDaoForJoinEntity(final Class<?> referencedEntityClass, final javax.sql.DataSource ds, final Dao defaultDao) {
        final String key = ClassUtil.getCanonicalClassName(referencedEntityClass) + "_" + System.identityHashCode(ds);
        final Dao joinEntityDao = joinEntityDaoPool.get(key);

        if (joinEntityDao != null) {
            return joinEntityDao;
        } else {
            for (final Dao dao : daoPool.values()) {
                if (dao.targetEntityClass().equals(referencedEntityClass) && dao.dataSource().equals(ds)) {
                    joinEntityDaoPool.put(key, dao);
                    return dao;
                }
            }
        }

        JdbcUtil.logger.warn("No Dao interface/instance found for entity class: " + referencedEntityClass + " with data source: " + ds
                + " for join operations in Dao: " + defaultDao.getClass());

        joinEntityDaoPool.put(key, defaultDao);

        return defaultDao;
    }

    static final class QueryInfo {
        final String sql;
        final ParsedSql parsedSql;
        final int queryTimeout;
        final int fetchSize;
        final boolean isBatch;
        final int batchSize;
        final OP op;
        final boolean isSingleParameter;
        final boolean timestamped;
        final boolean isSelect;
        final boolean isCall;
        final boolean isNamedQuery;

        QueryInfo(final String sql, final ParsedSql parsedSql, final int queryTimeout, final int fetchSize, final boolean isBatch, final int batchSize,
                final OP op, final boolean isSingleParameter, final boolean timestamped, final boolean isSelect, final boolean isCall,
                final boolean hasDefineWithNamedParameter) {
            this.sql = N.checkArgNotBlank(sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql, "sql");
            this.parsedSql = parsedSql == null ? ParsedSql.parse(sql) : parsedSql;
            this.queryTimeout = queryTimeout;
            this.fetchSize = fetchSize;
            this.isBatch = isBatch;
            this.batchSize = batchSize;
            this.op = op;
            this.isSingleParameter = isSingleParameter;
            this.timestamped = timestamped;
            this.isSelect = isSelect;
            this.isCall = isCall;
            isNamedQuery = N.notEmpty(this.parsedSql.getNamedParameters()) || hasDefineWithNamedParameter;

            if (hasDefineWithNamedParameter && (this.parsedSql.getParameterCount() > 0 && N.isEmpty(this.parsedSql.getNamedParameters()))) {
                throw new IllegalArgumentException("'hasDefineWithNamedParameter' is set to true for Non-named sql: " + sql);
            }
        }
    }
}
