/*
 * Copyright (c) 2019, Haiyang Li.
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

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.DataSourceManager;
import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.EntityId;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.core.DirtyMarkerUtil;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.ExceptionalStream.ExceptionalIterator;
import com.landawn.abacus.util.Fn.IntFunctions;
import com.landawn.abacus.util.JdbcUtil.AbstractPreparedQuery;
import com.landawn.abacus.util.JdbcUtil.BiResultExtractor;
import com.landawn.abacus.util.JdbcUtil.BiRowFilter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.Dao;
import com.landawn.abacus.util.JdbcUtil.Dao.OutParameter;
import com.landawn.abacus.util.JdbcUtil.NamedQuery;
import com.landawn.abacus.util.JdbcUtil.PreparedCallableQuery;
import com.landawn.abacus.util.JdbcUtil.ResultExtractor;
import com.landawn.abacus.util.JdbcUtil.RowFilter;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
import com.landawn.abacus.util.SQLBuilder.NAC;
import com.landawn.abacus.util.SQLBuilder.NLC;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.PAC;
import com.landawn.abacus.util.SQLBuilder.PLC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.Tuple.Tuple4;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.LongFunction;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.stream.IntStream.IntStreamEx;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

@SuppressWarnings("deprecation")
final class DaoUtil {

    private DaoUtil() {
        // singleton for utility class.
    }

    /** The Constant daoPool. */
    @SuppressWarnings("rawtypes")
    private static final Map<String, JdbcUtil.Dao> daoPool = new ConcurrentHashMap<>();

    /** The Constant sqlAnnoMap. */
    private static final Map<Class<? extends Annotation>, Function<Annotation, String>> sqlAnnoMap = new HashMap<>();

    static {
        sqlAnnoMap.put(Dao.Select.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.Select) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.Select) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.Insert.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.Insert) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.Insert) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.Update.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.Update) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.Update) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.Delete.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.Delete) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.Delete) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.NamedSelect.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.NamedSelect) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.NamedSelect) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.NamedInsert.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.NamedInsert) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.NamedInsert) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.NamedUpdate.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.NamedUpdate) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.NamedUpdate) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.NamedDelete.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.NamedDelete) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.NamedDelete) anno).value());
            }

            return sql;
        });
        sqlAnnoMap.put(Dao.Call.class, (Annotation anno) -> {
            String sql = StringUtil.trim(((Dao.Call) anno).sql());

            if (N.isNullOrEmpty(sql)) {
                sql = StringUtil.trim(((Dao.Call) anno).value());
            }

            return sql;
        });
    }

    /** The Constant noLogMethods. */
    private static final Set<String> noLogMethods = N.newHashSet();

    static {
        noLogMethods.add("dataSource");
        noLogMethods.add("sqlMapper");
        noLogMethods.add("executor");
        noLogMethods.add("prepareQuery");
        noLogMethods.add("prepareNamedQuery");
        noLogMethods.add("prepareCallableQuery");
        noLogMethods.add("targetEntityClass");
    }

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
        } catch (Exception e) {
            try {
                final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class);
                ClassUtil.setAccessible(constructor, true);

                return constructor.newInstance(declaringClass).in(declaringClass).unreflectSpecial(method, declaringClass);
            } catch (Exception ex) {
                try {
                    return MethodHandles.lookup()
                            .findSpecial(declaringClass, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                                    declaringClass);
                } catch (Exception exx) {
                    throw new UnsupportedOperationException(exx);
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static <R> Throwables.BiFunction<AbstractPreparedQuery, Object[], R, Exception> createQueryFunctionByMethod(final Method method,
            final boolean hasRowMapperOrExtractor, final boolean hasRowFilter) {
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final int paramLen = paramTypes.length;
        final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];
        final boolean isListQuery = isListQuery(method);

        if (hasRowMapperOrExtractor) {
            if (RowMapper.class.isAssignableFrom(lastParamType)) {
                if (isListQuery) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.list((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.list((RowMapper) args[paramLen - 1]);
                    }
                } else if (Optional.class.isAssignableFrom(returnType)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst((RowMapper) args[paramLen - 1]);
                } else if (ExceptionalStream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowMapper) args[paramLen - 1]);
                    }
                } else if (Stream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowFilter) args[paramLen - 2], (RowMapper) args[paramLen - 1]).unchecked();
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((RowMapper) args[paramLen - 1]).unchecked();
                    }
                } else {
                    if (Nullable.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + method.getName() + " can't be: " + returnType + " when RowMapper/BiRowMapper is specified");
                    }

                    return (preparedQuery, args) -> (R) preparedQuery.findFirst((RowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                }
            } else if (BiRowMapper.class.isAssignableFrom(lastParamType)) {
                if (isListQuery) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.list((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.list((BiRowMapper) args[paramLen - 1]);
                    }
                } else if (Optional.class.isAssignableFrom(returnType)) {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst((BiRowMapper) args[paramLen - 1]);
                } else if (ExceptionalStream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1]);
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((BiRowMapper) args[paramLen - 1]);
                    }
                } else if (Stream.class.isAssignableFrom(returnType)) {
                    if (hasRowFilter) {
                        return (preparedQuery,
                                args) -> (R) preparedQuery.stream((BiRowFilter) args[paramLen - 2], (BiRowMapper) args[paramLen - 1]).unchecked();
                    } else {
                        return (preparedQuery, args) -> (R) preparedQuery.stream((BiRowMapper) args[paramLen - 1]).unchecked();
                    }
                } else {
                    if (Nullable.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException(
                                "The return type of method: " + method.getName() + " can't be: " + returnType + " when RowMapper/BiRowMapper is specified");
                    }

                    return (preparedQuery, args) -> (R) preparedQuery.findFirst((BiRowMapper) args[paramLen - 1]).orElse(N.defaultValueOf(returnType));
                }
            } else {
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

                if (ResultExtractor.class.isAssignableFrom(lastParamType)) {
                    return (preparedQuery, args) -> (R) preparedQuery.query((ResultExtractor) args[paramLen - 1]);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.query((BiResultExtractor) args[paramLen - 1]);
                }
            }
        } else if (DataSet.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.query();
        } else if (ClassUtil.isEntity(returnType) || Map.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(returnType)).orNull();
        } else if (isListQuery) {
            final ParameterizedType parameterizedReturnType = (ParameterizedType) method.getGenericReturnType();
            final Class<?> eleType = parameterizedReturnType.getActualTypeArguments()[0] instanceof Class
                    ? (Class<?>) parameterizedReturnType.getActualTypeArguments()[0]
                    : (Class<?>) ((ParameterizedType) parameterizedReturnType.getActualTypeArguments()[0]).getRawType();

            return (preparedQuery, args) -> (R) preparedQuery.list(eleType);
        } else if (Optional.class.isAssignableFrom(returnType) || Nullable.class.isAssignableFrom(returnType)) {
            final ParameterizedType parameterizedReturnType = (ParameterizedType) method.getGenericReturnType();

            final Class<?> eleType = parameterizedReturnType.getActualTypeArguments()[0] instanceof Class
                    ? (Class<?>) parameterizedReturnType.getActualTypeArguments()[0]
                    : (Class<?>) ((ParameterizedType) parameterizedReturnType.getActualTypeArguments()[0]).getRawType();

            if (ClassUtil.isEntity(eleType) || Map.class.isAssignableFrom(eleType) || List.class.isAssignableFrom(eleType)
                    || Object[].class.isAssignableFrom(eleType)) {
                if (Nullable.class.isAssignableFrom(returnType)) {
                    return (preparedQuery, args) -> (R) Nullable.from(preparedQuery.findFirst(BiRowMapper.to(eleType)));
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.findFirst(BiRowMapper.to(eleType));
                }
            } else {
                if (Nullable.class.isAssignableFrom(returnType)) {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(eleType);
                } else {
                    return (preparedQuery, args) -> (R) preparedQuery.queryForSingleNonNull(eleType);
                }
            }
        } else if (OptionalBoolean.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForBoolean();
        } else if (OptionalChar.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForChar();
        } else if (OptionalByte.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForByte();
        } else if (OptionalShort.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForShort();
        } else if (OptionalInt.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForInt();
        } else if (OptionalLong.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForLong();
        } else if (OptionalFloat.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForFloat();
        } else if (OptionalDouble.class.isAssignableFrom(returnType)) {
            return (preparedQuery, args) -> (R) preparedQuery.queryForDouble();
        } else if (ExceptionalStream.class.isAssignableFrom(returnType) || Stream.class.isAssignableFrom(returnType)) {
            final ParameterizedType parameterizedReturnType = (ParameterizedType) method.getGenericReturnType();

            final Class<?> eleType = parameterizedReturnType.getActualTypeArguments()[0] instanceof Class
                    ? (Class<?>) parameterizedReturnType.getActualTypeArguments()[0]
                    : (Class<?>) ((ParameterizedType) parameterizedReturnType.getActualTypeArguments()[0]).getRawType();

            if (ExceptionalStream.class.isAssignableFrom(returnType)) {
                return (preparedQuery, args) -> (R) preparedQuery.stream(eleType);
            } else {
                return (preparedQuery, args) -> (R) preparedQuery.stream(eleType).unchecked();
            }
        } else {
            return (preparedQuery, args) -> (R) preparedQuery.queryForSingleResult(returnType).orElse(N.defaultValueOf(returnType));
        }
    }

    @SuppressWarnings("rawtypes")
    private static AbstractPreparedQuery prepareQuery(final Dao proxy, final String query, final boolean isNamedQuery, final NamedSQL namedSQL,
            final int fetchSize, final boolean isBatch, final int batchSize, final int queryTimeout, final boolean returnGeneratedKeys,
            final String[] returnColumnNames, final boolean isCall, final List<OutParameter> outParameterList) throws SQLException, Exception {

        final AbstractPreparedQuery preparedQuery = isCall ? proxy.prepareCallableQuery(query)
                : (isNamedQuery ? (returnGeneratedKeys ? proxy.prepareNamedQuery(namedSQL, returnColumnNames) : proxy.prepareNamedQuery(namedSQL))
                        : (returnGeneratedKeys ? proxy.prepareQuery(query, returnColumnNames) : proxy.prepareQuery(query)));

        if (isCall && N.notNullOrEmpty(outParameterList)) {
            final PreparedCallableQuery callableQuery = ((PreparedCallableQuery) preparedQuery);

            for (OutParameter op : outParameterList) {
                if (N.isNullOrEmpty(op.name())) {
                    callableQuery.registerOutParameter(op.position(), op.sqlType());
                } else {
                    callableQuery.registerOutParameter(op.name(), op.sqlType());
                }
            }
        }

        if (fetchSize > 0) {
            preparedQuery.setFetchSize(fetchSize);
        }

        if (queryTimeout >= 0) {
            preparedQuery.setQueryTimeout(queryTimeout);
        }

        return preparedQuery;
    }

    /**
     * Checks if is list query.
     *
     * @param method
     * @return true, if is list query
     */
    private static boolean isListQuery(final Method method) {
        final Class<?>[] paramTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();
        final int paramLen = paramTypes.length;

        if (List.class.isAssignableFrom(returnType)) {

            // Check if return type is generic List type.
            if (method.getGenericReturnType() instanceof ParameterizedType) {
                final ParameterizedType parameterizedReturnType = (ParameterizedType) method.getGenericReturnType();

                if (paramLen > 0 && (RowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]) || BiRowMapper.class.isAssignableFrom(paramTypes[paramLen - 1]))
                        && method.getGenericParameterTypes()[paramLen - 1] instanceof ParameterizedType) {

                    // if the return type of the method is same as the return type of RowMapper/BiRowMapper parameter, return false;
                    return !parameterizedReturnType.equals(((ParameterizedType) method.getGenericParameterTypes()[paramLen - 1]).getActualTypeArguments()[0]);
                }
            }

            return !(method.getName().startsWith("get") || method.getName().startsWith("findFirst") || method.getName().startsWith("findOne"));
        } else {
            return false;
        }
    }

    @SuppressWarnings({ "rawtypes" })
    static <T, SB extends SQLBuilder, TD extends JdbcUtil.Dao<T, SB, TD>> TD createDao(final Class<TD> daoInterface, final javax.sql.DataSource ds,
            final DataSourceManager dsm, final SQLMapper sqlMapper, final Executor executor) {
        N.checkArgNotNull(daoInterface, "daoInterface");
        N.checkArgNotNull(ds, "dataSource");

        final String key = ClassUtil.getCanonicalClassName(daoInterface) + "_" + System.identityHashCode(ds) + "_"
                + (sqlMapper == null ? "null" : System.identityHashCode(sqlMapper)) + "_" + (executor == null ? "null" : System.identityHashCode(executor));

        TD daoInstance = (TD) daoPool.get(key);

        if (daoInstance != null) {
            return daoInstance;
        }

        final Logger logger = LoggerFactory.getLogger(daoInterface);

        final javax.sql.DataSource primaryDataSource = ds != null ? ds : dsm.getPrimaryDataSource();
        final SQLMapper nonNullSQLMapper = sqlMapper == null ? new SQLMapper() : sqlMapper;
        final Executor nonNullExecutor = executor == null ? JdbcUtil.asyncExecutor.getExecutor() : executor;
        final AsyncExecutor nonNullAsyncExecutor = new AsyncExecutor(nonNullExecutor);
        final SQLExecutor sqlExecutor = ds != null ? new SQLExecutor(ds, null, nonNullSQLMapper, null, nonNullAsyncExecutor)
                : new SQLExecutor(dsm, null, nonNullSQLMapper, null, nonNullAsyncExecutor);

        java.lang.reflect.Type[] typeArguments = null;

        if (N.notNullOrEmpty(daoInterface.getGenericInterfaces()) && daoInterface.getGenericInterfaces()[0] instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) daoInterface.getGenericInterfaces()[0];
            typeArguments = parameterizedType.getActualTypeArguments();

            if (typeArguments.length >= 1 && typeArguments[0] instanceof Class) {
                if (!ClassUtil.isEntity((Class) typeArguments[0])) {
                    throw new IllegalArgumentException(
                            "Entity Type parameter must be: Object.class or entity class with getter/setter methods. Can't be: " + typeArguments[0]);
                }
            }

            if (typeArguments.length >= 2 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[1])) {
                if (!(typeArguments[1].equals(PSC.class) || typeArguments[1].equals(PAC.class) || typeArguments[1].equals(PLC.class))) {
                    throw new IllegalArgumentException("SQLBuilder Type parameter must be: SQLBuilder.PSC/PAC/PLC. Can't be: " + typeArguments[1]);
                }
            } else if (typeArguments.length >= 3 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[2])) {
                if (!(typeArguments[2].equals(PSC.class) || typeArguments[2].equals(PAC.class) || typeArguments[2].equals(PLC.class))) {
                    throw new IllegalArgumentException("SQLBuilder Type parameter must be: SQLBuilder.PSC/PAC/PLC. Can't be: " + typeArguments[2]);
                }
            }

            if (JdbcUtil.CrudDao.class.isAssignableFrom(daoInterface)) {
                final List<String> idFieldNames = ClassUtil.getIdFieldNames((Class) typeArguments[0]);

                if (idFieldNames.size() == 0) {
                    throw new IllegalArgumentException("To support CRUD operations by extending CrudDao interface, the entity class: " + typeArguments[0]
                            + " must have at least one field annotated with @Id");
                } else if (idFieldNames.size() == 1) {
                    if (!(Primitives.wrap((Class) typeArguments[1]))
                            .isAssignableFrom(Primitives.wrap(ClassUtil.getPropGetMethod((Class) typeArguments[0], idFieldNames.get(0)).getReturnType()))) {
                        throw new IllegalArgumentException("The 'ID' type declared in Dao type parameters: " + typeArguments[1]
                                + " is not assignable from the id property type in the entity class: "
                                + ClassUtil.getPropGetMethod((Class) typeArguments[0], idFieldNames.get(0)).getReturnType());
                    }
                } else if (!EntityId.class.equals(typeArguments[1])) {
                    throw new IllegalArgumentException("To support multiple ids, the 'ID' type type must be EntityId. It can't be: " + typeArguments[1]);
                }
            }
        }

        final Map<Method, Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable>> methodInvokerMap = new HashMap<>();

        final List<Method> sqlMethods = StreamEx.of(ClassUtil.getAllInterfaces(daoInterface))
                .append(daoInterface)
                .distinct()
                .flatMapp(clazz -> clazz.getDeclaredMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .toList();

        final Class<T> entityClass = N.isNullOrEmpty(typeArguments) ? null : (Class) typeArguments[0];
        final Class<?> idClass = JdbcUtil.CrudDao.class.isAssignableFrom(daoInterface) ? (Class) typeArguments[1] : null;

        final boolean isDirtyMarker = entityClass == null ? false : ClassUtil.isDirtyMarker(entityClass);

        final Class<? extends SQLBuilder> sbc = N.isNullOrEmpty(typeArguments) ? PSC.class
                : (typeArguments.length >= 2 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[1]) ? (Class) typeArguments[1]
                        : (typeArguments.length >= 3 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[2]) ? (Class) typeArguments[2] : PSC.class));

        final NamingPolicy namingPolicy = sbc.equals(PSC.class) ? NamingPolicy.LOWER_CASE_WITH_UNDERSCORE
                : (sbc.equals(PAC.class) ? NamingPolicy.UPPER_CASE_WITH_UNDERSCORE : NamingPolicy.LOWER_CAMEL_CASE);

        final Function<Condition, SQLBuilder.SP> selectFromSQLBuilderFunc = sbc.equals(PSC.class) ? cond -> PSC.selectFrom(entityClass).where(cond).pair()
                : (sbc.equals(PAC.class) ? cond -> PAC.selectFrom(entityClass).where(cond).pair() : cond -> PLC.selectFrom(entityClass).where(cond).pair());

        final BiFunction<String, Condition, SQLBuilder.SP> singleQuerySQLBuilderFunc = sbc.equals(PSC.class)
                ? (selectPropName, cond) -> PSC.select(selectPropName).from(entityClass).where(cond).pair()
                : (sbc.equals(PAC.class) ? (selectPropName, cond) -> PAC.select(selectPropName).from(entityClass).where(cond).pair()
                        : (selectPropName, cond) -> PLC.select(selectPropName).from(entityClass).where(cond).pair());

        final BiFunction<Collection<String>, Condition, SQLBuilder> selectSQLBuilderFunc = sbc.equals(PSC.class)
                ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? PSC.selectFrom(entityClass).where(cond)
                        : PSC.select(selectPropNames).from(entityClass).where(cond))
                : (sbc.equals(PAC.class)
                        ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? PAC.selectFrom(entityClass).where(cond)
                                : PAC.select(selectPropNames).from(entityClass).where(cond))
                        : (selectPropNames, cond) -> (N.isNullOrEmpty(selectPropNames) ? PLC.selectFrom(entityClass).where(cond)
                                : PLC.select(selectPropNames).from(entityClass).where(cond)));

        final BiFunction<Collection<String>, Condition, SQLBuilder> namedSelectSQLBuilderFunc = sbc.equals(PSC.class)
                ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? NSC.selectFrom(entityClass).where(cond)
                        : NSC.select(selectPropNames).from(entityClass).where(cond))
                : (sbc.equals(PAC.class)
                        ? ((selectPropNames, cond) -> N.isNullOrEmpty(selectPropNames) ? NAC.selectFrom(entityClass).where(cond)
                                : NAC.select(selectPropNames).from(entityClass).where(cond))
                        : (selectPropNames, cond) -> (N.isNullOrEmpty(selectPropNames) ? NLC.selectFrom(entityClass).where(cond)
                                : NLC.select(selectPropNames).from(entityClass).where(cond)));

        final Function<Collection<String>, SQLBuilder> namedInsertSQLBuilderFunc = sbc.equals(PSC.class)
                ? (propNamesToInsert -> N.isNullOrEmpty(propNamesToInsert) ? NSC.insertInto(entityClass) : NSC.insert(propNamesToInsert).into(entityClass))
                : (sbc.equals(PAC.class)
                        ? (propNamesToInsert -> N.isNullOrEmpty(propNamesToInsert) ? NAC.insertInto(entityClass)
                                : NAC.insert(propNamesToInsert).into(entityClass))
                        : (propNamesToInsert -> N.isNullOrEmpty(propNamesToInsert) ? NLC.insertInto(entityClass)
                                : NLC.insert(propNamesToInsert).into(entityClass)));

        final Function<Class<?>, SQLBuilder> parameterizedUpdateFunc = sbc.equals(PSC.class) ? clazz -> PSC.update(clazz)
                : (sbc.equals(PAC.class) ? clazz -> PAC.update(clazz) : clazz -> PLC.update(clazz));

        final Function<Class<?>, SQLBuilder> parameterizedDeleteFromFunc = sbc.equals(PSC.class) ? clazz -> PSC.deleteFrom(clazz)
                : (sbc.equals(PAC.class) ? clazz -> PAC.deleteFrom(clazz) : clazz -> PLC.deleteFrom(clazz));

        final Function<Class<?>, SQLBuilder> namedUpdateFunc = sbc.equals(PSC.class) ? clazz -> NSC.update(clazz)
                : (sbc.equals(PAC.class) ? clazz -> NAC.update(clazz) : clazz -> NLC.update(clazz));

        final List<String> idPropNameList = entityClass == null ? N.emptyList() : ClassUtil.getIdFieldNames(entityClass);
        final boolean isNoId = entityClass == null || N.isNullOrEmpty(idPropNameList) || ClassUtil.isFakeId(idPropNameList);
        final Set<String> idPropNameSet = N.newHashSet(idPropNameList);
        final String oneIdPropName = isNoId ? null : idPropNameList.get(0);
        final EntityInfo entityInfo = entityClass == null ? null : ParserUtil.getEntityInfo(entityClass);
        final PropInfo idPropInfo = isNoId ? null : entityInfo.getPropInfo(oneIdPropName);
        final boolean isOneId = isNoId ? false : idPropNameList.size() == 1;
        final Condition idCond = isNoId ? null : isOneId ? CF.eq(oneIdPropName) : CF.and(StreamEx.of(idPropNameList).map(CF::eq).toList());

        String sql_getById = null;
        String sql_existsById = null;
        String sql_insertWithId = null;
        String sql_insertWithoutId = null;
        String sql_updateById = null;
        String sql_deleteById = null;

        if (sbc.equals(PSC.class)) {
            sql_getById = isNoId ? null : NSC.selectFrom(entityClass).where(idCond).sql();
            sql_existsById = isNoId ? null : NSC.select(SQLBuilder._1).from(entityClass).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NSC.insertInto(entityClass).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (idPropNameSet.containsAll(ClassUtil.getPropNameList(entityClass)) ? sql_insertWithId : NSC.insertInto(entityClass, idPropNameSet).sql());
            sql_updateById = isNoId ? null : NSC.update(entityClass, idPropNameSet).where(idCond).sql();
            sql_deleteById = isNoId ? null : NSC.deleteFrom(entityClass).where(idCond).sql();
        } else if (sbc.equals(PAC.class)) {
            sql_getById = isNoId ? null : NAC.selectFrom(entityClass).where(idCond).sql();
            sql_existsById = isNoId ? null : NAC.select(SQLBuilder._1).from(entityClass).where(idCond).sql();
            sql_updateById = isNoId ? null : NAC.update(entityClass, idPropNameSet).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NAC.insertInto(entityClass).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (idPropNameSet.containsAll(ClassUtil.getPropNameList(entityClass)) ? sql_insertWithId : NAC.insertInto(entityClass, idPropNameSet).sql());
            sql_deleteById = isNoId ? null : NAC.deleteFrom(entityClass).where(idCond).sql();
        } else {
            sql_getById = isNoId ? null : NLC.selectFrom(entityClass).where(idCond).sql();
            sql_existsById = isNoId ? null : NLC.select(SQLBuilder._1).from(entityClass).where(idCond).sql();
            sql_insertWithId = entityClass == null ? null : NLC.insertInto(entityClass).sql();
            sql_insertWithoutId = entityClass == null ? null
                    : (idPropNameSet.containsAll(ClassUtil.getPropNameList(entityClass)) ? sql_insertWithId : NLC.insertInto(entityClass, idPropNameSet).sql());
            sql_updateById = isNoId ? null : NLC.update(entityClass, idPropNameSet).where(idCond).sql();
            sql_deleteById = isNoId ? null : NLC.deleteFrom(entityClass).where(idCond).sql();
        }

        final NamedSQL namedGetByIdSQL = N.isNullOrEmpty(sql_getById) ? null : NamedSQL.parse(sql_getById);
        final NamedSQL namedExistsByIdSQL = N.isNullOrEmpty(sql_existsById) ? null : NamedSQL.parse(sql_existsById);
        final NamedSQL namedInsertWithIdSQL = N.isNullOrEmpty(sql_insertWithId) ? null : NamedSQL.parse(sql_insertWithId);
        final NamedSQL namedInsertWithoutIdSQL = N.isNullOrEmpty(sql_insertWithoutId) ? null : NamedSQL.parse(sql_insertWithoutId);
        final NamedSQL namedUpdateByIdSQL = N.isNullOrEmpty(sql_updateById) ? null : NamedSQL.parse(sql_updateById);
        final NamedSQL namedDeleteByIdSQL = N.isNullOrEmpty(sql_deleteById) ? null : NamedSQL.parse(sql_deleteById);

        final ImmutableMap<String, String> propColumnNameMap = SQLBuilder.getPropColumnNameMap(entityClass, namingPolicy);

        final String[] returnColumnNames = isNoId ? N.EMPTY_STRING_ARRAY
                : (isOneId ? Array.of(propColumnNameMap.get(oneIdPropName))
                        : Stream.of(idPropNameList).map(idName -> propColumnNameMap.get(idName)).toArray(IntFunctions.ofStringArray()));

        final Tuple3<JdbcUtil.BiRowMapper<Object>, Function<Object, Object>, BiConsumer<Object, Object>> tp3 = JdbcUtil.getIdGeneratorGetterSetter(entityClass,
                namingPolicy);

        final JdbcUtil.BiRowMapper<Object> keyExtractor = tp3._1;
        final Function<Object, Object> idGetter = tp3._2;
        final BiConsumer<Object, Object> idSetter = tp3._3;

        final Predicate<Object> isDefaultIdTester = isNoId ? id -> true
                : (isOneId ? id -> JdbcUtil.isDefaultIdPropValue(id)
                        : id -> Stream.of(((EntityId) id).entrySet()).allMatch(e -> JdbcUtil.isDefaultIdPropValue(e.getValue())));

        final JdbcUtil.BiParametersSetter<NamedQuery, Object> idParamSetter = isOneId ? (pq, id) -> pq.setObject(oneIdPropName, id, idPropInfo.dbType)
                : (pq, id) -> {
                    final EntityId entityId = (EntityId) id;
                    PropInfo propInfo = null;

                    for (String idName : entityId.keySet()) {
                        propInfo = entityInfo.getPropInfo(idName);

                        if (propInfo == null) {
                            pq.setObject(idName, entityId.get(idName));
                        } else {
                            pq.setObject(idName, entityId.get(idName), propInfo.dbType);
                        }
                    }
                };

        final JdbcUtil.BiParametersSetter<NamedQuery, Object> idParamSetterByEntity = isOneId
                ? (pq, entity) -> pq.setObject(oneIdPropName, idPropInfo.getPropValue(entity), idPropInfo.dbType)
                : (pq, entity) -> pq.setParameters(entity);

        final Dao.PerfLog daoClassPerfLogAnno = StreamEx.of(ClassUtil.getAllInterfaces(daoInterface))
                .append(daoInterface)
                .flatMapp(cls -> cls.getAnnotations())
                .select(Dao.PerfLog.class)
                .last()
                .orNull();

        for (Method m : sqlMethods) {
            final Class<?> declaringClass = m.getDeclaringClass();
            final String methodName = m.getName();
            final Class<?>[] paramTypes = m.getParameterTypes();
            final Class<?> returnType = m.getReturnType();
            final int paramLen = paramTypes.length;

            final Dao.Sqls sqlsAnno = StreamEx.of(m.getAnnotations()).select(Dao.Sqls.class).onlyOne().orNull();

            if (sqlsAnno != null) {
                if (Modifier.isAbstract(m.getModifiers())) {
                    throw new UnsupportedOperationException(
                            "Annotation @Sqls is only supported by interface methods with default implementation: default xxx dbOperationABC(someParameters, String ... sqls), not supported by abstract method: "
                                    + m.getName());
                }

                if (paramLen == 0 || !paramTypes[paramLen - 1].equals(String[].class)) {
                    throw new UnsupportedOperationException(
                            "To support sqls binding by @Sqls, the type of last parameter must be: String... sqls. It can't be : " + paramTypes[paramLen - 1]
                                    + " on method: " + m.getName());
                }
            }

            final Dao.Transactional transactionalAnno = StreamEx.of(m.getAnnotations()).select(Dao.Transactional.class).last().orNull();

            //    if (transactionalAnno != null && Modifier.isAbstract(m.getModifiers())) {
            //        throw new UnsupportedOperationException(
            //                "Annotation @Transactional is only supported by interface methods with default implementation: default xxx dbOperationABC(someParameters, String ... sqls), not supported by abstract method: "
            //                        + m.getName());
            //    }

            final Dao.PerfLog perfLogAnno = StreamEx.of(m.getAnnotations()).select(Dao.PerfLog.class).last().orElse(daoClassPerfLogAnno);

            Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> call = null;

            if (!Modifier.isAbstract(m.getModifiers())) {
                final MethodHandle methodHandle = createMethodHandle(m);

                call = (proxy, args) -> {
                    if (sqlsAnno != null) {
                        if (N.notNullOrEmpty((String[]) args[paramLen - 1])) {
                            throw new IllegalArgumentException(
                                    "The last parameter(String[]) of method annotated by @Sqls must be null, don't specify it. It will auto-filled by sqls from annotation @Sqls on the method: "
                                            + m.getName());
                        }

                        args[paramLen - 1] = sqlsAnno.value();
                    }

                    return methodHandle.bindTo(proxy).invokeWithArguments(args);
                };

            } else if (m.getName().equals("targetEntityClass") && Class.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> entityClass;
            } else if (methodName.equals("dataSource") && javax.sql.DataSource.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> primaryDataSource;
            } else if (methodName.equals("sqlExecutor") && SQLExecutor.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> sqlExecutor;
            } else if (methodName.equals("sqlMapper") && SQLMapper.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> nonNullSQLMapper;
            } else if (methodName.equals("executor") && Executor.class.isAssignableFrom(returnType) && paramLen == 0) {
                call = (proxy, args) -> nonNullExecutor;
            } else {
                final Annotation sqlAnno = StreamEx.of(m.getAnnotations()).filter(anno -> sqlAnnoMap.containsKey(anno.annotationType())).first().orNull();

                if (declaringClass.equals(Dao.class)) {
                    if (methodName.equals("save") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];

                            if (entity instanceof DirtyMarker) {
                                final Collection<String> propNamesToSave = SQLBuilder.getInsertPropNames(entity, null);

                                N.checkArgNotNullOrEmpty(propNamesToSave, "propNamesToSave");

                                final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                                proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();

                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            } else {
                                final NamedSQL namedInsertSQL = isNoId || isDefaultIdTester.test(idGetter.apply(entity)) ? namedInsertWithoutIdSQL
                                        : namedInsertWithIdSQL;

                                proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();
                            }

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToSave = (Collection<String>) args[1];

                            N.checkArgNotNullOrEmpty(propNamesToSave, "propNamesToSave");

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                            proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();

                            if (entity instanceof DirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToSave, false);
                            }

                            return null;
                        };
                    } else if (methodName.equals("save") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Object entity = args[1];

                            proxy.prepareNamedQuery(namedInsertSQL).setParameters(entity).update();

                            if (entity instanceof DirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return null;
                        };
                    } else if (methodName.equals("saveAll") && paramLen == 2 && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final NamedSQL namedInsertSQL = isNoId || isDefaultIdTester.test(idGetter.apply(N.firstOrNullIfEmpty(entities)))
                                    ? namedInsertWithoutIdSQL
                                    : namedInsertWithIdSQL;

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("saveAll") && paramLen == 3 && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            if (entities.size() <= batchSize) {
                                proxy.prepareNamedQuery(namedInsertSQL).addBatchParameters(entities).batchUpdate();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL).closeAfterExecution(false)) {
                                        ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .forEach(bp -> nameQuery.addBatchParameters(bp).batchUpdate());
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            return null;
                        };
                    } else if (methodName.equals("exists") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply(SQLBuilder._1, (Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).exists();
                        };
                    } else if (methodName.equals("count") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply(SQLBuilder.COUNT_ALL, (Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForInt().orZero();
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).findFirst((JdbcUtil.RowMapper) args[1]);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).findFirst((JdbcUtil.BiRowMapper) args[1]);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).findFirst(entityClass);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).findFirst((JdbcUtil.RowMapper) args[2]);
                        };
                    } else if (methodName.equals("findFirst") && paramLen == 3 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class) && paramTypes[2].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).findFirst((JdbcUtil.BiRowMapper) args[2]);
                        };
                    } else if (methodName.equals("queryForBoolean") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForBoolean();
                        };
                    } else if (methodName.equals("queryForChar") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForChar();
                        };
                    } else if (methodName.equals("queryForByte") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForByte();
                        };
                    } else if (methodName.equals("queryForShort") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForShort();
                        };
                    } else if (methodName.equals("queryForInt") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForInt();
                        };
                    } else if (methodName.equals("queryForLong") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForLong();
                        };
                    } else if (methodName.equals("queryForFloat") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForFloat();
                        };
                    } else if (methodName.equals("queryForDouble") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForDouble();
                        };
                    } else if (methodName.equals("queryForString") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForString();
                        };
                    } else if (methodName.equals("queryForDate") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForDate();
                        };
                    } else if (methodName.equals("queryForTime") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForTime();
                        };
                    } else if (methodName.equals("queryForTimestamp") && paramLen == 2 && paramTypes[0].equals(String.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[0], (Condition) args[1]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForTimestamp();
                        };
                    } else if (methodName.equals("queryForSingleResult") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], (Condition) args[2]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForSingleResult((Class) args[0]);
                        };
                    } else if (methodName.equals("queryForSingleNonNull") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], (Condition) args[2]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForSingleNonNull((Class) args[0]);
                        };
                    } else if (methodName.equals("queryForUniqueResult") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], (Condition) args[2]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForUniqueResult((Class) args[0]);
                        };
                    } else if (methodName.equals("queryForUniqueNonNull") && paramLen == 3 && paramTypes[0].equals(Class.class)
                            && paramTypes[1].equals(String.class) && paramTypes[2].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = singleQuerySQLBuilderFunc.apply((String) args[1], (Condition) args[2]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).queryForUniqueNonNull((Class) args[0]);
                        };
                    } else if (methodName.equals("query") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).query();
                        };
                    } else if (methodName.equals("query") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).query();
                        };
                    } else if (methodName.equals("list") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.RowMapper) args[1]);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.BiRowMapper) args[1]);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.RowFilter.class) && paramTypes[2].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.RowFilter) args[1], (JdbcUtil.RowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.BiRowFilter.class) && paramTypes[2].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.BiRowFilter) args[1], (JdbcUtil.BiRowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 2 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list(entityClass);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.RowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.BiRowMapper) args[2]);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.RowFilter.class) && paramTypes[3].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.RowFilter) args[2], (JdbcUtil.RowMapper) args[3]);
                        };
                    } else if (methodName.equals("list") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.BiRowFilter.class) && paramTypes[3].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).list((JdbcUtil.BiRowFilter) args[2], (JdbcUtil.BiRowMapper) args[3]);
                        };
                    } else if (methodName.equals("stream") && paramLen == 1 && paramTypes[0].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                                        private ExceptionalIterator<T, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<T, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql).setParameters(sp.parameters).stream(entityClass).iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.RowMapper) args[1])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.BiRowMapper) args[1])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.RowFilter.class) && paramTypes[2].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.RowFilter) args[1], (JdbcUtil.RowMapper) args[2])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Condition.class)
                            && paramTypes[1].equals(JdbcUtil.BiRowFilter.class) && paramTypes[2].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectFromSQLBuilderFunc.apply((Condition) args[0]);

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.BiRowFilter) args[1], (JdbcUtil.BiRowMapper) args[2])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 2 && paramTypes[0].equals(Collection.class)
                            && paramTypes[1].equals(Condition.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final ExceptionalIterator<T, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<T, SQLException>, SQLException>() {
                                        private ExceptionalIterator<T, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<T, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql).setParameters(sp.parameters).stream(entityClass).iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.RowMapper) args[2])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 3 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.BiRowMapper) args[2])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.RowFilter.class) && paramTypes[3].equals(JdbcUtil.RowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.RowFilter) args[2], (JdbcUtil.RowMapper) args[3])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("stream") && paramLen == 4 && paramTypes[0].equals(Collection.class) && paramTypes[1].equals(Condition.class)
                            && paramTypes[2].equals(JdbcUtil.BiRowFilter.class) && paramTypes[3].equals(JdbcUtil.BiRowMapper.class)) {
                        call = (proxy, args) -> {
                            final SP sp = selectSQLBuilderFunc.apply((Collection<String>) args[0], (Condition) args[1]).pair();

                            final ExceptionalIterator<Object, SQLException> lazyIter = ExceptionalIterator
                                    .of(new Throwables.Supplier<ExceptionalIterator<Object, SQLException>, SQLException>() {
                                        private ExceptionalIterator<Object, SQLException> internalIter;

                                        @Override
                                        public ExceptionalIterator<Object, SQLException> get() throws SQLException {
                                            if (internalIter == null) {
                                                internalIter = proxy.prepareQuery(sp.sql)
                                                        .setParameters(sp.parameters)
                                                        .stream((JdbcUtil.BiRowFilter) args[2], (JdbcUtil.BiRowMapper) args[3])
                                                        .iterator();
                                            }

                                            return internalIter;
                                        }
                                    });

                            return ExceptionalStream.newStream(lazyIter).onClose(new Throwables.Runnable<SQLException>() {
                                @Override
                                public void run() throws SQLException {
                                    lazyIter.close();
                                }
                            });
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])
                            && Condition.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> props = (Map<String, Object>) args[0];
                            final Condition cond = (Condition) args[1];
                            N.checkArgNotNullOrEmpty(props, "updateProps");

                            final SP sp = parameterizedUpdateFunc.apply(entityClass).set(props).where(cond).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).update();
                        };
                    } else if (methodName.equals("delete") && paramLen == 1 && Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final SP sp = parameterizedDeleteFromFunc.apply(entityClass).where((Condition) args[0]).pair();
                            return proxy.prepareQuery(sp.sql).setParameters(sp.parameters).update();
                        };
                    } else if (methodName.equals("loadJoinEntities") && paramLen == 3 && !Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1]) && Collection.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final String joinEntityPropName = (String) args[1];
                            final Collection<String> selectPropNames = (Collection<String>) args[2];
                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(entityClass, joinEntityPropName);
                            final Tuple2<Function<Collection<String>, String>, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                    .getSelectSQLBuilderAndParamSetter(sbc);

                            final JdbcUtil.PreparedQuery preparedQuery = proxy.prepareQuery(tp._1.apply(selectPropNames)).setParameters(entity, tp._2);

                            if (propJoinInfo.joinPropInfo.type.isCollection()) {
                                final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                if (propJoinInfo.joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                                    propJoinInfo.joinPropInfo.setPropValue(entity, propEntities);
                                } else {
                                    final Collection<Object> c = (Collection) N.newInstance(propJoinInfo.joinPropInfo.clazz);
                                    c.addAll(propEntities);
                                    propJoinInfo.joinPropInfo.setPropValue(entity, c);
                                }
                            } else {
                                propJoinInfo.joinPropInfo.setPropValue(entity, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orNull());
                            }

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propJoinInfo.joinPropInfo.name, false);
                            }

                            return null;
                        };
                    } else if (methodName.equals("loadJoinEntities") && paramLen == 3 && Collection.class.isAssignableFrom(paramTypes[0])
                            && String.class.isAssignableFrom(paramTypes[1]) && Collection.class.isAssignableFrom(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection) args[0];
                            final String joinEntityPropName = (String) args[1];
                            final Collection<String> selectPropNames = (Collection<String>) args[2];
                            final JoinInfo propJoinInfo = JoinInfo.getPropJoinInfo(entityClass, joinEntityPropName);

                            if (N.isNullOrEmpty(entities)) {
                                // Do nothing.
                            } else if (entities.size() == 1) {
                                final Object entity = N.firstOrNullIfEmpty(entities);
                                final Tuple2<Function<Collection<String>, String>, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = propJoinInfo
                                        .getSelectSQLBuilderAndParamSetter(sbc);

                                final JdbcUtil.PreparedQuery preparedQuery = proxy.prepareQuery(tp._1.apply(selectPropNames)).setParameters(entity, tp._2);

                                if (propJoinInfo.joinPropInfo.type.isCollection()) {
                                    final List<?> propEntities = preparedQuery.list(propJoinInfo.referencedEntityClass);

                                    if (propJoinInfo.joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                                        propJoinInfo.joinPropInfo.setPropValue(entity, propEntities);
                                    } else {
                                        final Collection<Object> c = (Collection) N.newInstance(propJoinInfo.joinPropInfo.clazz);
                                        c.addAll(propEntities);
                                        propJoinInfo.joinPropInfo.setPropValue(entity, c);
                                    }
                                } else {
                                    propJoinInfo.joinPropInfo.setPropValue(entity, preparedQuery.findFirst(propJoinInfo.referencedEntityClass).orNull());
                                }

                                if (isDirtyMarker) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, propJoinInfo.joinPropInfo.name, false);
                                }
                            } else {
                                final Tuple2<BiFunction<Collection<String>, Integer, String>, JdbcUtil.BiParametersSetter<PreparedStatement, Collection<?>>> tp = propJoinInfo
                                        .getSelectSQLBuilderAndParamSetterForBatch(sbc);

                                final List<?> joinPropEntities = proxy.prepareQuery(tp._1.apply(selectPropNames, entities.size()))
                                        .setParameters(entities, tp._2)
                                        .list(propJoinInfo.referencedEntityClass);

                                propJoinInfo.setJoinPropEntities(entities, joinPropEntities);
                            }

                            return null;
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + m);
                        };
                    }
                } else if (m.getDeclaringClass().equals(JdbcUtil.CrudDao.class)) {
                    if (methodName.equals("insert") && paramLen == 1) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];

                            NamedSQL namedInsertSQL = null;

                            if (isDirtyMarker) {
                                final Collection<String> propNamesToSave = SQLBuilder.getInsertPropNames(entity, null);

                                N.checkArgNotNullOrEmpty(propNamesToSave, "propNamesToSave");

                                namedInsertSQL = NamedSQL.parse(namedInsertSQLBuilderFunc.apply(propNamesToSave).sql());
                            } else {
                                if (isDefaultIdTester.test(idGetter.apply(entity))) {
                                    namedInsertSQL = namedInsertWithoutIdSQL;
                                } else {
                                    namedInsertSQL = namedInsertWithIdSQL;
                                }
                            }

                            Object newId = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                    .setParameters(entity)
                                    .insert(keyExtractor)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return newId;
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = args[0];
                            final Collection<String> propNamesToSave = (Collection<String>) args[1];

                            N.checkArgNotNullOrEmpty(propNamesToSave, "propNamesToSave");

                            final String namedInsertSQL = namedInsertSQLBuilderFunc.apply(propNamesToSave).sql();

                            final Object id = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                    .setParameters(entity)
                                    .insert(keyExtractor)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, propNamesToSave, false);
                            }

                            return id;
                        };
                    } else if (methodName.equals("insert") && paramLen == 2 && String.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Object entity = args[1];

                            final Object id = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames)
                                    .setParameters(entity)
                                    .insert(keyExtractor)
                                    .ifPresent(ret -> idSetter.accept(ret, entity))
                                    .orElse(idGetter.apply(entity));

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                            }

                            return id;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 2 && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<?> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final boolean isDefaultIdPropValue = isDefaultIdTester.test(idGetter.apply(N.firstOrNullIfEmpty(entities)));
                            final NamedSQL namedInsertSQL = isDefaultIdPropValue ? namedInsertWithoutIdSQL : namedInsertWithIdSQL;
                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).addBatchParameters(entities).batchInsert(keyExtractor);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = ExceptionalStream.of(entities)
                                                .splitToList(batchSize)
                                                .flattMap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                                ids = new ArrayList<>();
                            }

                            if (N.notNullOrEmpty(ids) && N.notNullOrEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            if (N.notNullOrEmpty(ids) && ids.size() != entities.size()) {
                                if (logger.isWarnEnabled()) {
                                    logger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            entities.size());
                                }
                            }

                            if (N.isNullOrEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("batchInsert") && paramLen == 3 && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final String namedInsertSQL = (String) args[0];
                            final Collection<?> entities = (Collection<Object>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            List<Object> ids = null;

                            if (entities.size() <= batchSize) {
                                ids = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).addBatchParameters(entities).batchInsert(keyExtractor);
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedInsertSQL, returnColumnNames).closeAfterExecution(false)) {
                                        ids = ExceptionalStream.of(entities)
                                                .splitToList(batchSize)
                                                .flattMap(bp -> nameQuery.addBatchParameters(bp).batchInsert(keyExtractor))
                                                .toList();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                                ids = new ArrayList<>();
                            }

                            if (N.notNullOrEmpty(ids) && N.notNullOrEmpty(entities) && ids.size() == N.size(entities)) {
                                int idx = 0;

                                for (Object e : entities) {
                                    idSetter.accept(ids.get(idx++), e);
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            if (N.notNullOrEmpty(ids) && ids.size() != entities.size()) {
                                if (logger.isWarnEnabled()) {
                                    logger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                            entities.size());
                                }
                            }

                            if (N.isNullOrEmpty(ids)) {
                                ids = Stream.of(entities).map(idGetter).toList();
                            }

                            return ids;
                        };
                    } else if (methodName.equals("get")) {
                        if (paramLen == 1) {
                            call = (proxy, args) -> proxy.prepareNamedQuery(namedGetByIdSQL).settParameters(args[0], idParamSetter).get(entityClass);
                        } else {
                            call = (proxy, args) -> proxy.prepareNamedQuery(namedSelectSQLBuilderFunc.apply((Collection<String>) args[0], idCond).sql())
                                    .settParameters(args[1], idParamSetter)
                                    .get(entityClass);
                        }
                    } else if (methodName.equals("batchGet") && paramLen == 3 && Collection.class.equals(paramTypes[0])
                            && Collection.class.equals(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> ids = (Collection<Object>) args[0];
                            final Collection<String> selectPropNames = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];

                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(ids)) {
                                return new ArrayList<>();
                            }

                            final Object firstId = N.first(ids).get();
                            final boolean isMap = firstId instanceof Map;
                            final boolean isEntity = firstId != null && ClassUtil.isEntity(firstId.getClass());
                            final boolean isEntityId = firstId instanceof EntityId;

                            N.checkArgument(idPropNameList.size() > 1 || !(isEntity || isMap || isEntityId),
                                    "Input 'ids' can not be EntityIds/Maps or entities for single id ");

                            final List idList = ids instanceof List ? (List) ids : new ArrayList(ids);
                            final List<T> resultList = new ArrayList<>(idList.size());

                            if (idPropNameList.size() == 1) {
                                String sql_selectPart = selectSQLBuilderFunc.apply(selectPropNames, idCond).sql();
                                sql_selectPart = sql_selectPart.substring(0, sql_selectPart.lastIndexOf('=')) + "IN ";

                                if (ids.size() >= batchSize) {
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer(true);

                                    for (int i = 0; i < batchSize; i++) {
                                        joiner.append('?');
                                    }

                                    final String qery = sql_selectPart + joiner.toString();

                                    try (JdbcUtil.PreparedQuery preparedQuery = proxy.prepareQuery(qery).closeAfterExecution(false)) {
                                        for (int i = 0, to = ids.size() - batchSize; i <= to; i += batchSize) {
                                            resultList.addAll(preparedQuery.setParameters(idList.subList(i, i + batchSize)).list(entityClass));
                                        }
                                    }
                                }

                                if (ids.size() % batchSize != 0) {
                                    final int remaining = ids.size() % batchSize;
                                    final Joiner joiner = Joiner.with(", ", "(", ")").reuseCachedBuffer(true);

                                    for (int i = 0; i < remaining; i++) {
                                        joiner.append('?');
                                    }

                                    final String qery = sql_selectPart + joiner.toString();
                                    resultList.addAll(
                                            proxy.prepareQuery(qery).setParameters(idList.subList(ids.size() - remaining, ids.size())).list(entityClass));
                                }
                            } else {
                                if (ids.size() >= batchSize) {
                                    for (int i = 0, to = ids.size() - batchSize; i <= to; i += batchSize) {
                                        if (isEntityId) {
                                            resultList.addAll(proxy.list(CF.id2Cond(idList.subList(i, i + batchSize))));
                                        } else if (isMap) {
                                            resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(i, i + batchSize))));
                                        } else {
                                            resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(i, i + batchSize), idPropNameList)));
                                        }
                                    }
                                }

                                if (ids.size() % batchSize != 0) {
                                    final int remaining = ids.size() % batchSize;

                                    if (isEntityId) {
                                        resultList.addAll(proxy.list(CF.id2Cond(idList.subList(idList.size() - remaining, ids.size()))));
                                    } else if (isMap) {
                                        resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(ids.size() - remaining, ids.size()))));
                                    } else {
                                        resultList.addAll(proxy.list(CF.eqAndOr(idList.subList(ids.size() - remaining, ids.size()), idPropNameList)));
                                    }
                                }
                            }

                            if (resultList.size() > ids.size()) {
                                throw new DuplicatedResultException(
                                        "The size of result: " + resultList.size() + " is bigger than the size of input ids: " + ids.size());
                            }

                            return resultList;
                        };
                    } else if (methodName.equals("exists") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> proxy.prepareNamedQuery(namedExistsByIdSQL).settParameters(args[0], idParamSetter).exists();
                    } else if (methodName.equals("update") && paramLen == 1) {
                        if (isDirtyMarker) {
                            call = (proxy, args) -> {
                                final DirtyMarker entity = (DirtyMarker) args[0];
                                final String query = namedUpdateFunc.apply(entityClass).set(entity, idPropNameSet).where(idCond).sql();
                                final int result = proxy.prepareNamedQuery(query).setParameters(entity).update();

                                DirtyMarkerUtil.markDirty(entity, false);

                                return result;
                            };
                        } else {
                            call = (proxy, args) -> {
                                return proxy.prepareNamedQuery(namedUpdateByIdSQL).setParameters(args[0]).update();
                            };
                        }
                    } else if (methodName.equals("update") && paramLen == 2 && !Map.class.equals(paramTypes[0]) && Collection.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            N.checkArgNotNullOrEmpty(propNamesToUpdate, "propNamesToUpdate");

                            final String query = namedUpdateFunc.apply(entityClass).set(propNamesToUpdate).where(idCond).sql();

                            final int result = proxy.prepareNamedQuery(query).setParameters(args[0]).update();

                            if (isDirtyMarker) {
                                DirtyMarkerUtil.markDirty((DirtyMarker) args[0], propNamesToUpdate, false);
                            }

                            return result;
                        };
                    } else if (methodName.equals("update") && paramLen == 2 && Map.class.equals(paramTypes[0])) {
                        call = (proxy, args) -> {
                            final Map<String, Object> props = (Map<String, Object>) args[0];
                            final Object id = args[1];
                            N.checkArgNotNullOrEmpty(props, "updateProps");

                            final String query = namedUpdateFunc.apply(entityClass).set(props.keySet()).where(idCond).sql();

                            return proxy.prepareNamedQuery(query).setParameters(props).settParameters(id, idParamSetter).update();
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 2 && int.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            long result = 0;

                            if (entities.size() <= batchSize) {
                                result = N.sum(proxy.prepareNamedQuery(namedUpdateByIdSQL).addBatchParameters(entities).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedUpdateByIdSQL).closeAfterExecution(false)) {
                                        result = ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp).batchUpdate()))
                                                .orZero();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                }
                            }

                            return N.toIntExact(result);
                        };
                    } else if (methodName.equals("batchUpdate") && paramLen == 3 && int.class.equals(paramTypes[2])) {
                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection<Object>) args[0];
                            final Collection<String> propNamesToUpdate = (Collection<String>) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            N.checkArgNotNullOrEmpty(propNamesToUpdate, "propNamesToUpdate");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            }

                            final String query = namedUpdateFunc.apply(entityClass).set(propNamesToUpdate).where(idCond).sql();
                            long result = 0;

                            if (entities.size() <= batchSize) {
                                result = N.sum(proxy.prepareNamedQuery(query).addBatchParameters(entities).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(query).closeAfterExecution(false)) {
                                        result = ExceptionalStream.of(entities)
                                                .splitToList(batchSize) //
                                                .sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp).batchUpdate()))
                                                .orZero();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }
                            }

                            if (isDirtyMarker) {
                                for (Object e : entities) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) e, propNamesToUpdate, false);
                                }
                            }

                            return N.toIntExact(result);
                        };
                    } else if (methodName.equals("deleteById")) {
                        call = (proxy, args) -> proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(args[0], idParamSetter).update();
                    } else if (methodName.equals("delete") && paramLen == 1 && !Condition.class.isAssignableFrom(paramTypes[0])) {
                        call = (proxy, args) -> proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(args[0], idParamSetterByEntity).update();
                    } else if (methodName.equals("delete") && paramLen == 2 && !Condition.class.isAssignableFrom(paramTypes[0])
                            && boolean.class.equals(paramTypes[1])) {
                        call = (proxy, args) -> {
                            final Object entity = (args[0]);
                            final boolean deleteAllJoinEntities = (Boolean) args[1];

                            if (deleteAllJoinEntities == false) {
                                return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                            }

                            final Map<String, JoinInfo> entityJoinInfo = JoinInfo.getEntityJoinInfo(entityClass);

                            if (N.isNullOrEmpty(entityJoinInfo)) {
                                return proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                long result = 0;

                                try {
                                    Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = null;

                                    for (JoinInfo propJoinInfo : entityJoinInfo.values()) {
                                        tp = propJoinInfo.getDeleteSqlAndParamSetter(sbc);

                                        result += proxy.prepareQuery(tp._1).setParameters(entity, tp._2).update();
                                    }

                                    result += proxy.prepareNamedQuery(namedDeleteByIdSQL).settParameters(entity, idParamSetterByEntity).update();

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }

                                return N.toIntExact(result);
                            }
                        };
                    } else if ((methodName.equals("batchDelete") || methodName.equals("batchDeleteByIds")) && paramLen == 2
                            && int.class.equals(paramTypes[1])) {

                        final JdbcUtil.BiParametersSetter<NamedQuery, Object> paramSetter = methodName.equals("batchDeleteByIds") ? idParamSetter
                                : idParamSetterByEntity;

                        call = (proxy, args) -> {
                            final Collection<Object> idsOrEntities = (Collection) args[0];
                            final int batchSize = (Integer) args[1];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(idsOrEntities)) {
                                return 0;
                            }

                            if (idsOrEntities.size() <= batchSize) {
                                return N.sum(proxy.prepareNamedQuery(namedDeleteByIdSQL).addBatchParameters(idsOrEntities, paramSetter).batchUpdate());
                            } else {
                                final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                                long result = 0;

                                try {
                                    try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedDeleteByIdSQL).closeAfterExecution(false)) {
                                        result = ExceptionalStream.of(idsOrEntities)
                                                .splitToList(batchSize)
                                                .sumInt(bp -> N.sum(nameQuery.addBatchParameters(bp, paramSetter).batchUpdate()))
                                                .orZero();
                                    }

                                    tran.commit();
                                } finally {
                                    tran.rollbackIfNotCommitted();
                                }

                                return N.toIntExact(result);
                            }
                        };
                    } else if (methodName.equals("batchDelete") && paramLen == 3 && boolean.class.equals(paramTypes[1]) && int.class.equals(paramTypes[2])) {
                        final JdbcUtil.BiParametersSetter<NamedQuery, Object> paramSetter = idParamSetterByEntity;

                        call = (proxy, args) -> {
                            final Collection<Object> entities = (Collection) args[0];
                            final boolean deleteAllJoinEntities = (Boolean) args[1];
                            final int batchSize = (Integer) args[2];
                            N.checkArgPositive(batchSize, "batchSize");

                            if (N.isNullOrEmpty(entities)) {
                                return 0;
                            } else if (deleteAllJoinEntities == false) {
                                return ((JdbcUtil.CrudDao) proxy).batchDelete(entities, batchSize);
                            }

                            final Map<String, JoinInfo> entityJoinInfo = JoinInfo.getEntityJoinInfo(entityClass);

                            if (N.isNullOrEmpty(entityJoinInfo)) {
                                return ((JdbcUtil.CrudDao) proxy).batchDelete(entities, batchSize);
                            }

                            final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                            long result = 0;

                            try {
                                try (NamedQuery nameQuery = proxy.prepareNamedQuery(namedDeleteByIdSQL).closeAfterExecution(false)) {
                                    result = ExceptionalStream.of(entities) //
                                            .splitToList(batchSize) //
                                            .sumLong(bp -> {
                                                long tmpResult = 0;

                                                Tuple2<String, JdbcUtil.BiParametersSetter<PreparedStatement, Object>> tp = null;

                                                for (JoinInfo propJoinInfo : entityJoinInfo.values()) {
                                                    tp = propJoinInfo.getDeleteSqlAndParamSetter(sbc);

                                                    tmpResult += N.sum(proxy.prepareQuery(tp._1).addBatchParameters2(bp, tp._2).batchUpdate());
                                                }

                                                tmpResult += N.sum(nameQuery.addBatchParameters(bp, paramSetter).batchUpdate());

                                                return tmpResult;
                                            })
                                            .orZero();
                                }

                                tran.commit();
                            } finally {
                                tran.rollbackIfNotCommitted();
                            }

                            return N.toIntExact(result);
                        };
                    } else {
                        call = (proxy, args) -> {
                            throw new UnsupportedOperationException("Unsupported operation: " + m);
                        };
                    }
                } else {
                    final Class<?> lastParamType = paramLen == 0 ? null : paramTypes[paramLen - 1];
                    final String query = N.checkArgNotNullOrEmpty(sqlAnnoMap.get(sqlAnno.annotationType()).apply(sqlAnno), "sql can't be null or empty");

                    final boolean returnGeneratedKeys = isNoId == false
                            && (sqlAnno.annotationType().equals(Dao.Insert.class) || sqlAnno.annotationType().equals(Dao.NamedInsert.class));

                    final boolean isCall = sqlAnno.annotationType().getSimpleName().endsWith("Call");
                    final boolean isNamedQuery = sqlAnno.annotationType().getSimpleName().startsWith("Named");
                    final NamedSQL namedSQL = isNamedQuery ? NamedSQL.parse(query) : null;
                    final List<Dao.OutParameter> outParameterList = StreamEx.of(m.getAnnotations())
                            .select(Dao.OutParameter.class)
                            .append(StreamEx.of(m.getAnnotations()).select(DaoUtil.OutParameterList.class).flatMapp(e -> e.value()))
                            .toList();

                    if (N.notNullOrEmpty(outParameterList)) {
                        if (!isCall) {
                            throw new UnsupportedOperationException(
                                    "@OutParameter annotations are only supported by method annotated by @Call, not supported in method: " + m.getName());
                        }

                        if (StreamEx.of(outParameterList).anyMatch(op -> N.isNullOrEmpty(op.name()) && op.position() < 0)) {
                            throw new UnsupportedOperationException(
                                    "One of the attribute: (name, position) of @OutParameter must be set in method: " + m.getName());
                        }
                    }

                    final boolean hasParameterSetter = (paramLen > 0 && JdbcUtil.ParametersSetter.class.isAssignableFrom(paramTypes[0]))
                            || (paramLen > 1 && JdbcUtil.BiParametersSetter.class.isAssignableFrom(paramTypes[1]))
                            || (paramLen > 1 && JdbcUtil.TriParametersSetter.class.isAssignableFrom(paramTypes[1]));

                    final boolean hasRowMapperOrExtractor = paramLen > 0 && (JdbcUtil.ResultExtractor.class.isAssignableFrom(lastParamType)
                            || JdbcUtil.BiResultExtractor.class.isAssignableFrom(lastParamType) || JdbcUtil.RowMapper.class.isAssignableFrom(lastParamType)
                            || JdbcUtil.BiRowMapper.class.isAssignableFrom(lastParamType));

                    final boolean hasRowFilter = paramLen >= 2 && (JdbcUtil.RowFilter.class.isAssignableFrom(paramTypes[paramLen - 2])
                            || JdbcUtil.BiRowFilter.class.isAssignableFrom(paramTypes[paramLen - 2]));

                    if (hasRowFilter && !hasRowMapperOrExtractor) {
                        throw new UnsupportedOperationException(
                                "Parameter 'RowFilter/BiRowFilter' is not supported without last paramere to be 'RowMapper/BiRowMapper' in method: "
                                        + m.getName());
                    }

                    if (hasParameterSetter) {
                        throw new UnsupportedOperationException(
                                "Setting parameters by 'ParametersSetter/BiParametersSetter/TriParametersSetter' is not enabled at present. Can't use it in method: "
                                        + m.getName());
                    }

                    if (hasRowMapperOrExtractor) {
                        throw new UnsupportedOperationException(
                                "Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present. Can't use it in method: "
                                        + m.getName());
                    }

                    if (java.util.Optional.class.isAssignableFrom(returnType) || java.util.OptionalInt.class.isAssignableFrom(returnType)
                            || java.util.OptionalLong.class.isAssignableFrom(returnType) || java.util.OptionalDouble.class.isAssignableFrom(returnType)) {
                        throw new UnsupportedOperationException("the return type of the method: " + m.getName() + " can't be: " + returnType
                                + ". Please use the OptionalXXX classes defined in com.landawn.abacus.util.u");
                    }

                    if (StreamEx.of(m.getExceptionTypes()).noneMatch(e -> SQLException.class.equals(e))) {
                        throw new UnsupportedOperationException("'throws SQLException' is not declared in method: " + m.getName());
                    }

                    if (paramLen > 0 && (ClassUtil.isEntity(paramTypes[0]) || Map.class.isAssignableFrom(paramTypes[0])
                            || EntityId.class.isAssignableFrom(paramTypes[0])) && isNamedQuery == false) {
                        throw new IllegalArgumentException(
                                "Using named query: @NamedSelect/NamedUpdate/NamedInsert/NamedDelete when parameter type is Entity/Map/EntityId in method: "
                                        + m.getName());
                    }

                    Tuple4<Integer, Integer, Boolean, Integer> tp = null;

                    if (sqlAnno instanceof Dao.Select) {
                        final Dao.Select tmp = (Dao.Select) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), tmp.fetchSize(), false, -1);
                    } else if (sqlAnno instanceof Dao.NamedSelect) {
                        final Dao.NamedSelect tmp = (Dao.NamedSelect) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), tmp.fetchSize(), false, -1);
                    } else if (sqlAnno instanceof Dao.Insert) {
                        final Dao.Insert tmp = (Dao.Insert) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), -1, false, -1);
                    } else if (sqlAnno instanceof Dao.NamedInsert) {
                        final Dao.NamedInsert tmp = (Dao.NamedInsert) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), -1, tmp.isBatch(), tmp.batchSize());
                    } else if (sqlAnno instanceof Dao.Update) {
                        final Dao.Update tmp = (Dao.Update) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), -1, false, -1);
                    } else if (sqlAnno instanceof Dao.NamedUpdate) {
                        final Dao.NamedUpdate tmp = (Dao.NamedUpdate) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), -1, tmp.isBatch(), tmp.batchSize());
                    } else if (sqlAnno instanceof Dao.Delete) {
                        final Dao.Delete tmp = (Dao.Delete) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), -1, false, -1);
                    } else if (sqlAnno instanceof Dao.NamedDelete) {
                        final Dao.NamedDelete tmp = (Dao.NamedDelete) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), -1, tmp.isBatch(), tmp.batchSize());
                    } else if (sqlAnno instanceof Dao.Call) {
                        final Dao.Call tmp = (Dao.Call) sqlAnno;
                        tp = Tuple.of(tmp.queryTimeout(), -1, false, -1);
                    } else {
                        tp = Tuple.of(-1, -1, false, -1);
                    }

                    final int queryTimeout = tp._1;
                    final int fetchSize = tp._2;
                    final boolean isBatch = tp._3;
                    final int tmpBatchSize = tp._4;

                    if (isBatch) {
                        if (!((paramLen == 1 && Collection.class.isAssignableFrom(paramTypes[0]))
                                || (paramLen == 2 && Collection.class.isAssignableFrom(paramTypes[0]) && int.class.equals(paramTypes[1])))) {
                            throw new UnsupportedOperationException("For batch operations(" + m.getName()
                                    + "), the first parameter must be Collection. The second parameter is optional, it only can be int if it's set");
                        }

                        if (isNamedQuery == false) {
                            throw new UnsupportedOperationException("Only named query/sql is supported for batch operations(" + m.getName() + ") at present");
                        }
                    }

                    JdbcUtil.BiParametersSetter<AbstractPreparedQuery, Object[]> parametersSetter = null;

                    if (paramLen == 0) {
                        parametersSetter = null;
                    } else if (JdbcUtil.ParametersSetter.class.isAssignableFrom(paramTypes[0])) {
                        parametersSetter = (preparedQuery, args) -> preparedQuery.settParameters((JdbcUtil.ParametersSetter) args[0]);
                    } else if (paramLen > 1 && JdbcUtil.BiParametersSetter.class.isAssignableFrom(paramTypes[1])) {
                        parametersSetter = (preparedQuery, args) -> preparedQuery.settParameters(args[0], (JdbcUtil.BiParametersSetter) args[1]);
                    } else if (paramLen > 1 && JdbcUtil.TriParametersSetter.class.isAssignableFrom(paramTypes[1])) {
                        parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters(args[0], args[1]);
                    } else if (paramLen == 1 && Collection.class.isAssignableFrom(paramTypes[0])) {
                        parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Collection) args[0]);
                    } else if (paramLen == 1 && Object[].class.isAssignableFrom(paramTypes[0])) {
                        parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Object[]) args[0]);
                    } else {
                        final int stmtParamLen = hasRowFilter ? (paramLen - 2) : (hasRowMapperOrExtractor ? paramLen - 1 : paramLen);

                        if (stmtParamLen == 0) {
                            // ignore
                        } else if (stmtParamLen == 1) {
                            if (isCall) {
                                final String paramName = StreamEx.of(m.getParameterAnnotations())
                                        .limit(1)
                                        .flatMapp(e -> e)
                                        .select(Dao.Bind.class)
                                        .map(b -> b.value())
                                        .first()
                                        .orNull();

                                if (N.notNullOrEmpty(paramName)) {
                                    parametersSetter = (preparedQuery, args) -> ((PreparedCallableQuery) preparedQuery).setObject(paramName, args[0]);
                                } else if (Map.class.isAssignableFrom(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> ((PreparedCallableQuery) preparedQuery).setParameters((Map<String, ?>) args[0]);
                                } else if (ClassUtil.isEntity(paramTypes[0]) || EntityId.class.isAssignableFrom(paramTypes[0])) {
                                    throw new UnsupportedOperationException("In method: " + ClassUtil.getSimpleClassName(daoInterface) + "." + m.getName()
                                            + ", parameters for call(procedure) have to be binded with names through annotation @Bind, or Map. Entity/EntityId type parameter are not supported");
                                } else if (Collection.class.isAssignableFrom(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Collection) args[0]);
                                } else if (Object[].class.isAssignableFrom(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Object[]) args[0]);
                                } else {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[0]);
                                }
                            } else if (isNamedQuery) {
                                final String paramName = StreamEx.of(m.getParameterAnnotations())
                                        .limit(1)
                                        .flatMapp(e -> e)
                                        .select(Dao.Bind.class)
                                        .map(b -> b.value())
                                        .first()
                                        .orNull();

                                if (N.notNullOrEmpty(paramName)) {
                                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setObject(paramName, args[0]);
                                } else if (ClassUtil.isEntity(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters(args[0]);
                                } else if (Map.class.isAssignableFrom(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters((Map<String, ?>) args[0]);
                                } else if (EntityId.class.isAssignableFrom(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> ((NamedQuery) preparedQuery).setParameters(args[0]);
                                } else {
                                    throw new UnsupportedOperationException("In method: " + ClassUtil.getSimpleClassName(daoInterface) + "." + m.getName()
                                            + ", parameters for named query have to be binded with names through annotation @Bind, or Map/Entity with getter/setter methods. Can not be: "
                                            + ClassUtil.getSimpleClassName(paramTypes[0]));
                                }
                            } else {
                                if (Collection.class.isAssignableFrom(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Collection) args[0]);
                                } else if (Object[].class.isAssignableFrom(paramTypes[0])) {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters((Object[]) args[0]);
                                } else {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setObject(1, args[0]);
                                }
                            }
                        } else {
                            if (isCall) {
                                final String[] paramNames = IntStreamEx.range(0, stmtParamLen)
                                        .mapToObj(i -> StreamEx.of(m.getParameterAnnotations()[i]).select(Dao.Bind.class).first().orElseThrow(null))
                                        .skipNull()
                                        .map(b -> b.value())
                                        .toArray(len -> new String[len]);

                                if (N.notNullOrEmpty(paramNames)) {
                                    parametersSetter = (preparedQuery, args) -> {
                                        final PreparedCallableQuery namedQuery = ((PreparedCallableQuery) preparedQuery);

                                        for (int i = 0, count = paramNames.length; i < count; i++) {
                                            namedQuery.setObject(paramNames[i], args[i]);
                                        }
                                    };
                                } else {
                                    if (stmtParamLen == paramLen) {
                                        parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters(args);
                                    } else {
                                        parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters(N.copyOfRange(args, 0, stmtParamLen));
                                    }
                                }
                            } else if (isNamedQuery) {
                                final String[] paramNames = IntStreamEx.range(0, stmtParamLen)
                                        .mapToObj(i -> StreamEx.of(m.getParameterAnnotations()[i])
                                                .select(Dao.Bind.class)
                                                .first()
                                                .orElseThrow(() -> new UnsupportedOperationException(
                                                        "In method: " + ClassUtil.getSimpleClassName(daoInterface) + "." + m.getName() + ", parameters[" + i
                                                                + "]: " + ClassUtil.getSimpleClassName(m.getParameterTypes()[i])
                                                                + " is not binded with parameter named through annotation @Bind")))
                                        .map(b -> b.value())
                                        .toArray(len -> new String[len]);

                                parametersSetter = (preparedQuery, args) -> {
                                    final NamedQuery namedQuery = ((NamedQuery) preparedQuery);

                                    for (int i = 0, count = paramNames.length; i < count; i++) {
                                        namedQuery.setObject(paramNames[i], args[i]);
                                    }
                                };
                            } else {
                                if (stmtParamLen == paramLen) {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters(args);
                                } else {
                                    parametersSetter = (preparedQuery, args) -> preparedQuery.setParameters(N.copyOfRange(args, 0, stmtParamLen));
                                }
                            }
                        }
                    }

                    final JdbcUtil.BiParametersSetter<AbstractPreparedQuery, Object[]> finalParametersSetter = parametersSetter == null
                            ? JdbcUtil.BiParametersSetter.DO_NOTHING
                            : parametersSetter;

                    final boolean isUpdateReturnType = returnType.equals(int.class) || returnType.equals(Integer.class) || returnType.equals(long.class)
                            || returnType.equals(Long.class) || returnType.equals(boolean.class) || returnType.equals(Boolean.class)
                            || returnType.equals(void.class);

                    final boolean idDirtyMarkerReturnType = ClassUtil.isEntity(returnType) && DirtyMarker.class.isAssignableFrom(returnType);

                    if (sqlAnno.annotationType().equals(Dao.Select.class) || sqlAnno.annotationType().equals(Dao.NamedSelect.class)
                            || (isCall && !isUpdateReturnType)) {
                        final Throwables.BiFunction<AbstractPreparedQuery, Object[], T, Exception> queryFunc = createQueryFunctionByMethod(m,
                                hasRowMapperOrExtractor, hasRowFilter);

                        // Getting ClassCastException. Not sure why query result is being casted Dao. It seems there is a bug in JDk compiler.
                        //   call = (proxy, args) -> queryFunc.apply(JdbcUtil.prepareQuery(proxy, ds, query, isNamedQuery, fetchSize, queryTimeout, returnGeneratedKeys, args, paramSetter), args);

                        call = (proxy, args) -> {
                            Object result = queryFunc.apply(prepareQuery(proxy, query, isNamedQuery, namedSQL, fetchSize, isBatch, -1, queryTimeout,
                                    returnGeneratedKeys, returnColumnNames, isCall, outParameterList).settParameters(args, finalParametersSetter), args);

                            if (idDirtyMarkerReturnType) {
                                ((DirtyMarker) result).markDirty(false);
                            }

                            return result;
                        };
                    } else if (sqlAnno.annotationType().equals(Dao.Insert.class) || sqlAnno.annotationType().equals(Dao.NamedInsert.class)) {
                        if (isNoId) {
                            if (!returnType.isAssignableFrom(void.class)) {
                                throw new UnsupportedOperationException("The return type of insert operations(" + m.getName()
                                        + ") for no id entities only can be: void. It can't be: " + returnType);
                            }
                        }

                        if (isBatch == false) {
                            if (!(returnType.isAssignableFrom(void.class) || idClass == null
                                    || Primitives.wrap(idClass).isAssignableFrom(Primitives.wrap(returnType)) || returnType.isAssignableFrom(Optional.class))) {
                                throw new UnsupportedOperationException(
                                        "The return type of insert operations(" + m.getName() + ") only can be: void or 'ID' type. It can't be: " + returnType);
                            }

                            call = (proxy, args) -> {
                                final boolean isEntity = paramLen == 1 && args[0] != null && ClassUtil.isEntity(args[0].getClass());
                                final Object entity = isEntity ? args[0] : null;

                                final Optional<Object> id = prepareQuery(proxy, query, isNamedQuery, namedSQL, fetchSize, isBatch, -1, queryTimeout,
                                        returnGeneratedKeys, returnColumnNames, isCall, outParameterList).settParameters(args, finalParametersSetter)
                                                .insert(keyExtractor);

                                if (isEntity && id.isPresent()) {
                                    id.ifPresent(ret -> idSetter.accept(ret, entity));
                                }

                                if (isEntity && entity instanceof DirtyMarker) {
                                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, false);
                                }

                                return void.class.equals(returnType) ? null
                                        : Optional.class.equals(returnType) ? id : id.orElse(isEntity ? idGetter.apply(entity) : N.defaultValueOf(returnType));
                            };
                        } else {
                            if (!(returnType.equals(void.class) || returnType.isAssignableFrom(List.class))) {
                                throw new UnsupportedOperationException("The return type of batch insert operations(" + m.getName()
                                        + ")  only can be: void/List<ID>/Collection<ID>. It can't be: " + returnType);
                            }

                            call = (proxy, args) -> {
                                final Collection<Object> batchParameters = (Collection) args[0];
                                int batchSize = tmpBatchSize;

                                if (paramLen == 2) {
                                    batchSize = (Integer) args[1];
                                }

                                if (batchSize == 0) {
                                    batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                                }

                                N.checkArgPositive(batchSize, "batchSize");

                                List<Object> ids = null;

                                if (N.isNullOrEmpty(batchParameters)) {
                                    ids = new ArrayList<>(0);
                                } else if (batchParameters.size() < batchSize) {
                                    ids = ((NamedQuery) prepareQuery(proxy, query, isNamedQuery, namedSQL, fetchSize, isBatch, -1, queryTimeout,
                                            returnGeneratedKeys, returnColumnNames, isCall, outParameterList)).addBatchParameters(batchParameters)
                                                    .batchInsert();
                                } else {
                                    final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                    try {
                                        try (NamedQuery nameQuery = (NamedQuery) prepareQuery(proxy, query, isNamedQuery, namedSQL, fetchSize, isBatch,
                                                batchSize, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                        .closeAfterExecution(false)) {

                                            ids = ExceptionalStream.of(batchParameters)
                                                    .splitToList(batchSize) //
                                                    .flattMap(bp -> nameQuery.addBatchParameters(bp).batchInsert())
                                                    .toList();
                                        }

                                        tran.commit();
                                    } finally {
                                        tran.rollbackIfNotCommitted();
                                    }
                                }

                                final boolean isEntity = ClassUtil.isEntity(N.firstOrNullIfEmpty(batchParameters).getClass());

                                if (N.notNullOrEmpty(ids) && Stream.of(ids).allMatch(Fn.isNull())) {
                                    ids = new ArrayList<>();
                                }

                                if (isEntity) {
                                    final Collection<Object> entities = batchParameters;

                                    if (N.notNullOrEmpty(ids) && N.notNullOrEmpty(entities) && ids.size() == N.size(entities)) {
                                        int idx = 0;

                                        for (Object e : entities) {
                                            idSetter.accept(ids.get(idx++), e);
                                        }
                                    }

                                    if (N.firstOrNullIfEmpty(entities) instanceof DirtyMarker) {
                                        for (Object e : entities) {
                                            DirtyMarkerUtil.markDirty((DirtyMarker) e, false);
                                        }
                                    }

                                    if (N.isNullOrEmpty(ids)) {
                                        ids = Stream.of(entities).map(idGetter).toList();
                                    }
                                }

                                if (N.notNullOrEmpty(ids) && ids.size() != batchParameters.size()) {
                                    if (logger.isWarnEnabled()) {
                                        logger.warn("The size of returned id list: {} is different from the size of input entity list: {}", ids.size(),
                                                batchParameters.size());
                                    }
                                }

                                return void.class.equals(returnType) ? null : ids;
                            };
                        }
                    } else if (sqlAnno.annotationType().equals(Dao.Update.class) || sqlAnno.annotationType().equals(Dao.Delete.class)
                            || sqlAnno.annotationType().equals(Dao.NamedUpdate.class) || sqlAnno.annotationType().equals(Dao.NamedDelete.class)
                            || (sqlAnno.annotationType().equals(Dao.Call.class) && isUpdateReturnType)) {
                        if (!isUpdateReturnType) {
                            throw new UnsupportedOperationException("The return type of update/delete operations(" + m.getName()
                                    + ") only can be: int/Integer/long/Long/boolean/Boolean/void. It can't be: " + returnType);
                        }

                        final LongFunction<?> updateResultConvertor = void.class.equals(returnType) ? updatedRecordCount -> null
                                : (Boolean.class.equals(Primitives.wrap(returnType)) ? updatedRecordCount -> updatedRecordCount > 0
                                        : (Integer.class.equals(Primitives.wrap(returnType)) ? updatedRecordCount -> N.toIntExact(updatedRecordCount)
                                                : LongFunction.identity()));

                        final boolean isLargeUpdate = returnType.equals(long.class) || returnType.equals(Long.class);

                        if (isBatch == false) {
                            final boolean idDirtyMarker = paramTypes.length == 1 && ClassUtil.isEntity(paramTypes[0])
                                    && DirtyMarker.class.isAssignableFrom(paramTypes[0]);

                            call = (proxy, args) -> {
                                final AbstractPreparedQuery preparedQuery = prepareQuery(proxy, query, isNamedQuery, namedSQL, fetchSize, isBatch, -1,
                                        queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList).settParameters(args,
                                                finalParametersSetter);

                                final long updatedRecordCount = isLargeUpdate ? preparedQuery.largeUpate() : preparedQuery.update();

                                if (idDirtyMarker) {
                                    if (isNamedQuery
                                            && (sqlAnno.annotationType().equals(Dao.Update.class) || sqlAnno.annotationType().equals(Dao.NamedUpdate.class))) {
                                        ((DirtyMarker) args[0]).markDirty(namedSQL.getNamedParameters(), false);
                                    } else {
                                        ((DirtyMarker) args[0]).markDirty(false);
                                    }
                                }

                                return updateResultConvertor.apply(updatedRecordCount);
                            };

                        } else {
                            call = (proxy, args) -> {
                                final Collection<Object> batchParameters = (Collection) args[0];
                                int batchSize = tmpBatchSize;

                                if (paramLen == 2) {
                                    batchSize = (Integer) args[1];
                                }

                                if (batchSize == 0) {
                                    batchSize = JdbcUtil.DEFAULT_BATCH_SIZE;
                                }

                                N.checkArgPositive(batchSize, "batchSize");

                                long updatedRecordCount = 0;

                                if (N.isNullOrEmpty(batchParameters)) {
                                    updatedRecordCount = 0;
                                } else if (batchParameters.size() < batchSize) {
                                    final NamedQuery preparedQuery = ((NamedQuery) prepareQuery(proxy, query, isNamedQuery, namedSQL, fetchSize, isBatch,
                                            batchSize, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList))
                                                    .addBatchParameters(batchParameters);

                                    if (isLargeUpdate) {
                                        updatedRecordCount = N.sum(preparedQuery.largeBatchUpdate());
                                    } else {
                                        updatedRecordCount = N.sum(preparedQuery.batchUpdate());
                                    }
                                } else {
                                    final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());

                                    try {
                                        try (NamedQuery nameQuery = (NamedQuery) prepareQuery(proxy, query, isNamedQuery, namedSQL, fetchSize, isBatch,
                                                batchSize, queryTimeout, returnGeneratedKeys, returnColumnNames, isCall, outParameterList)
                                                        .closeAfterExecution(false)) {

                                            updatedRecordCount = ExceptionalStream.of(batchParameters)
                                                    .splitToList(batchSize) //
                                                    .sumLong(bp -> isLargeUpdate ? N.sum(nameQuery.addBatchParameters(bp).largeBatchUpdate())
                                                            : N.sum(nameQuery.addBatchParameters(bp).batchUpdate()))
                                                    .orZero();
                                        }

                                        tran.commit();
                                    } finally {
                                        tran.rollbackIfNotCommitted();
                                    }
                                }

                                if (N.firstOrNullIfEmpty(batchParameters) instanceof DirtyMarker) {
                                    if (isNamedQuery
                                            && (sqlAnno.annotationType().equals(Dao.Update.class) || sqlAnno.annotationType().equals(Dao.NamedUpdate.class))) {

                                        for (Object e : batchParameters) {
                                            ((DirtyMarker) e).markDirty(namedSQL.getNamedParameters(), false);
                                        }
                                    } else {
                                        for (Object e : batchParameters) {
                                            ((DirtyMarker) e).markDirty(false);
                                        }
                                    }
                                }

                                return updateResultConvertor.apply(updatedRecordCount);
                            };
                        }
                    } else {
                        throw new UnsupportedOperationException("Unsupported sql annotation: " + sqlAnno.annotationType() + " in method: " + m.getName());
                    }
                }
            }

            final Throwables.BiFunction<JdbcUtil.Dao, Object[], ?, Throwable> tmp = call;
            final String fullMethodName = daoInterface.getSimpleName() + "." + m.getName();
            final boolean hasPerfLogAnno = perfLogAnno != null;

            if (transactionalAnno == null || transactionalAnno.propagation() == Propagation.SUPPORTS) {
                if (hasPerfLogAnno) {
                    call = (proxy, args) -> {
                        final long startTime = System.currentTimeMillis();
                        final long prevMinExecutionTimeForSQLPerfLog = JdbcUtil.getMinExecutionTimeForSQLPerfLog();

                        try {
                            JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());

                            return tmp.apply(proxy, args);
                        } finally {
                            JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);

                            if (perfLogAnno.minExecutionTimeForOperation() >= 0 && logger.isInfoEnabled()) {
                                final long elapsedTime = System.currentTimeMillis() - startTime;

                                if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                    logger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + fullMethodName);
                                }
                            }
                        }
                    };

                } else {
                    // Do not need to do anything.
                }
            } else if (transactionalAnno.propagation() == Propagation.REQUIRED) {
                call = (proxy, args) -> {
                    final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;
                    final long prevMinExecutionTimeForSQLPerfLog = hasPerfLogAnno ? JdbcUtil.getMinExecutionTimeForSQLPerfLog() : -1;

                    final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                    Object result = null;

                    try {
                        if (hasPerfLogAnno) {
                            JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());
                        }

                        result = tmp.apply(proxy, args);

                        tran.commit();
                    } finally {
                        if (hasPerfLogAnno) {
                            try {
                                tran.rollbackIfNotCommitted();
                            } finally {
                                JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);

                                if (perfLogAnno.minExecutionTimeForOperation() >= 0 && logger.isInfoEnabled()) {
                                    final long elapsedTime = System.currentTimeMillis() - startTime;

                                    if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                        logger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + fullMethodName);
                                    }
                                }
                            }
                        } else {
                            tran.rollbackIfNotCommitted();
                        }
                    }

                    return result;
                };
            } else if (transactionalAnno.propagation() == Propagation.REQUIRES_NEW) {
                call = (proxy, args) -> {
                    final javax.sql.DataSource dataSource = proxy.dataSource();

                    return JdbcUtil.callNotInStartedTransaction(dataSource, () -> {
                        final long startTime = hasPerfLogAnno ? System.currentTimeMillis() : -1;
                        final long prevMinExecutionTimeForSQLPerfLog = hasPerfLogAnno ? JdbcUtil.getMinExecutionTimeForSQLPerfLog() : -1;

                        final SQLTransaction tran = JdbcUtil.beginTransaction(proxy.dataSource());
                        Object result = null;

                        try {
                            if (hasPerfLogAnno) {
                                JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());
                            }

                            result = tmp.apply(proxy, args);

                            tran.commit();
                        } finally {
                            if (hasPerfLogAnno) {
                                try {
                                    tran.rollbackIfNotCommitted();
                                } finally {
                                    JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);

                                    if (perfLogAnno.minExecutionTimeForOperation() >= 0 && logger.isInfoEnabled()) {
                                        final long elapsedTime = System.currentTimeMillis() - startTime;

                                        if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                            logger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + fullMethodName);
                                        }
                                    }
                                }
                            } else {
                                tran.rollbackIfNotCommitted();
                            }
                        }

                        return result;
                    });
                };
            } else if (transactionalAnno.propagation() == Propagation.NOT_SUPPORTED) {
                call = (proxy, args) -> {
                    final javax.sql.DataSource dataSource = proxy.dataSource();

                    if (hasPerfLogAnno) {
                        return JdbcUtil.callNotInStartedTransaction(dataSource, () -> {
                            final long startTime = System.currentTimeMillis();
                            final long prevMinExecutionTimeForSQLPerfLog = JdbcUtil.getMinExecutionTimeForSQLPerfLog();

                            try {
                                JdbcUtil.setMinExecutionTimeForSQLPerfLog(perfLogAnno.minExecutionTimeForSql());

                                return tmp.apply(proxy, args);
                            } finally {
                                JdbcUtil.setMinExecutionTimeForSQLPerfLog(prevMinExecutionTimeForSQLPerfLog);

                                if (perfLogAnno.minExecutionTimeForOperation() >= 0 && logger.isInfoEnabled()) {
                                    final long elapsedTime = System.currentTimeMillis() - startTime;

                                    if (elapsedTime >= perfLogAnno.minExecutionTimeForOperation()) {
                                        logger.info("[DAO-OP-PERF]: " + elapsedTime + ", " + fullMethodName);
                                    }
                                }
                            }
                        });
                    } else {
                        return JdbcUtil.callNotInStartedTransaction(dataSource, () -> tmp.apply(proxy, args));
                    }
                };
            }

            methodInvokerMap.put(m, call);
        }

        final Throwables.TriFunction<JdbcUtil.Dao, Method, Object[], ?, Throwable> proxyInvoker = (proxy, method, args) -> methodInvokerMap.get(method)
                .apply(proxy, args);
        final Class<TD>[] interfaceClasses = N.asArray(daoInterface);

        final InvocationHandler h = (proxy, method, args) -> {
            if (!noLogMethods.contains(method.getName())) {
                logger.debug("Invoking Dao method: {} with args: {}", method.getName(), args);
            }

            return proxyInvoker.apply((JdbcUtil.Dao) proxy, method, args);
        };

        daoInstance = N.newProxyInstance(interfaceClasses, h);

        daoPool.put(key, daoInstance);

        return daoInstance;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    static @interface OutParameterList {
        Dao.OutParameter[] value();
    }
}
