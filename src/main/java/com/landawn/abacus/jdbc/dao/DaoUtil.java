/*
 * Copyright (c) 2021, Haiyang Li.
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
package com.landawn.abacus.jdbc.dao;

import java.lang.reflect.ParameterizedType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.JoinInfo;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Result;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLBuilder.NAC;
import com.landawn.abacus.util.SQLBuilder.NLC;
import com.landawn.abacus.util.SQLBuilder.NSB;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.PAC;
import com.landawn.abacus.util.SQLBuilder.PLC;
import com.landawn.abacus.util.SQLBuilder.PSB;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.function.Function;

@Internal
final class DaoUtil {
    private DaoUtil() {
        // singleton.
    }

    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
        stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    };

    @SuppressWarnings("deprecation")
    static <T, ID> ID extractId(final T entity, final List<String> idPropNameList, final BeanInfo entityInfo) {
        if (idPropNameList.size() == 1) {
            return entityInfo.getPropInfo(idPropNameList.get(0)).getPropValue(entity);
        } else {
            final Seid entityId = Seid.of(entityInfo.simpleClassName);

            for (final String idPropName : idPropNameList) {
                entityId.set(idPropName, entityInfo.getPropInfo(idPropName).getPropValue(entity));
            }

            return (ID) entityId;
        }
    }

    @SuppressWarnings("deprecation")
    static <T, ID> Function<T, ID> createIdExtractor(final List<String> idPropNameList, final BeanInfo entityInfo) {
        if (idPropNameList.size() == 1) {
            final PropInfo idPropInfo = entityInfo.getPropInfo(idPropNameList.get(0));

            return idPropInfo::getPropValue;
        } else {
            final List<PropInfo> idPropInfos = N.map(idPropNameList, entityInfo::getPropInfo);

            return it -> {
                final Seid entityId = Seid.of(entityInfo.simpleClassName);

                for (final PropInfo propInfo : idPropInfos) {
                    entityId.set(propInfo.name, propInfo.getPropValue(it));
                }

                return (ID) entityId;
            };
        }
    }

    static Collection<String> getRefreshSelectPropNames(final Collection<String> propNamesToRefresh, final List<String> idPropNameList) {
        if (propNamesToRefresh.containsAll(idPropNameList)) {
            return propNamesToRefresh;
        } else {
            final Collection<String> selectPropNames = new HashSet<>(propNamesToRefresh);
            selectPropNames.addAll(idPropNameList);
            return selectPropNames;
        }
    }

    static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> TD getCrudDao(final CrudJoinEntityHelper<T, ID, SB, TD> dao) {
        if (dao instanceof CrudDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extend interface JoinEntityHelper"); //NOSONAR
        }
    }

    static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD getDao(final JoinEntityHelper<T, SB, TD> dao) {
        if (dao instanceof Dao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extend interface JoinEntityHelper");
        }
    }

    static <T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> TD getDao(final UncheckedJoinEntityHelper<T, SB, TD> dao) {
        if (dao instanceof UncheckedDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extend interface JoinEntityHelper");
        }
    }

    static <T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>> TD getCrudDao(final UncheckedCrudJoinEntityHelper<T, ID, SB, TD> dao) {
        if (dao instanceof UncheckedCrudDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " doesn't extend interface JoinEntityHelper");
        }
    }

    static final Throwables.Consumer<? super Exception, UncheckedSQLException> throwUncheckedSQLException = e -> {
        if (e instanceof SQLException) {
            throw new UncheckedSQLException((SQLException) e);
        } else if (e.getCause() instanceof SQLException) {
            throw new UncheckedSQLException((SQLException) e.getCause());
        } else {
            throw N.toRuntimeException(e);
        }
    };

    static final Throwables.Consumer<? super Exception, SQLException> throwSQLExceptionAction = e -> {
        if (e instanceof SQLException) {
            throw (SQLException) e;
        } else if (e.getCause() instanceof SQLException) {
            throw (SQLException) e.getCause();
        } else {
            throw N.toRuntimeException(e);
        }
    };

    static void uncheckedComplete(final List<ContinuableFuture<Void>> futures) throws UncheckedSQLException {
        for (final ContinuableFuture<Void> f : futures) {
            f.gett().ifFailure(throwUncheckedSQLException);
        }
    }

    static int uncheckedCompleteSum(final List<ContinuableFuture<Integer>> futures) throws UncheckedSQLException {
        int result = 0;
        Result<Integer, Exception> ret = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.gett();

            if (ret.isFailure()) {
                throwUncheckedSQLException.accept(ret.getException());
            }

            result += ret.orElseIfFailure(0);
        }

        return result;
    }

    static void complete(final List<ContinuableFuture<Void>> futures) throws SQLException {
        for (final ContinuableFuture<Void> f : futures) {
            f.gett().ifFailure(throwSQLExceptionAction);
        }
    }

    static int completeSum(final List<ContinuableFuture<Integer>> futures) throws SQLException {
        int result = 0;
        Result<Integer, Exception> ret = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.gett();

            if (ret.isFailure()) {
                throwSQLExceptionAction.accept(ret.getException());
            }

            result += ret.orElseIfFailure(0);
        }

        return result;
    }

    @SuppressWarnings("rawtypes")
    static final Map<Class<? extends Dao>, Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>>> daoPrepareQueryFuncPool = new ConcurrentHashMap<>();

    @SuppressWarnings("rawtypes")
    static Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>> getDaoPreparedQueryFunc(
            final Dao dao) {
        final Class<? extends Dao> daoInterface = dao.getClass();

        Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>> tp = daoPrepareQueryFuncPool
                .get(daoInterface);

        if (tp == null) {
            java.lang.reflect.Type[] typeArguments = null;

            if (N.notEmpty(daoInterface.getGenericInterfaces()) && daoInterface.getGenericInterfaces()[0] instanceof ParameterizedType parameterizedType) {
                typeArguments = parameterizedType.getActualTypeArguments();
            }

            final Class<? extends SQLBuilder> sbc = N.isEmpty(typeArguments) ? PSC.class
                    : (typeArguments.length >= 2 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[1]) ? (Class) typeArguments[1]
                            : (typeArguments.length >= 3 && SQLBuilder.class.isAssignableFrom((Class) typeArguments[2]) ? (Class) typeArguments[2]
                                    : PSC.class));

            @SuppressWarnings("deprecation")
            final Class<?> targetEntityClass = dao.targetEntityClass();

            final Throwables.BiFunction<javax.sql.DataSource, SP, PreparedQuery, SQLException> prepareQueryFunc = (dataSource, sp) -> {
                final PreparedQuery query = JdbcUtil.prepareQuery(dataSource, sp.sql);

                if (N.notEmpty(sp.parameters)) {
                    boolean noException = false;

                    try {
                        query.setParameters(sp.parameters);

                        noException = true;
                    } finally {
                        if (!noException) {
                            query.close();
                        }
                    }
                }

                return query;
            };

            final Throwables.BiFunction<javax.sql.DataSource, SP, NamedQuery, SQLException> prepareNamedQueryFunc = (dataSource, sp) -> {
                final NamedQuery query = JdbcUtil.prepareNamedQuery(dataSource, sp.sql);

                if (N.notEmpty(sp.parameters)) {
                    boolean noException = false;

                    try {
                        query.setParameters(sp.parameters);

                        noException = true;
                    } finally {
                        if (!noException) {
                            query.close();
                        }
                    }
                }

                return query;
            };

            if (PSC.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PSC.selectFrom(targetEntityClass) : PSC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NSC.selectFrom(targetEntityClass) : NSC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else if (PAC.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PAC.selectFrom(targetEntityClass) : PAC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NAC.selectFrom(targetEntityClass) : NAC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else if (PLC.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PLC.selectFrom(targetEntityClass) : PLC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NLC.selectFrom(targetEntityClass) : NLC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else if (PSB.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PSB.selectFrom(targetEntityClass) : PSB.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NSB.selectFrom(targetEntityClass) : NSB.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .pair();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else {
                throw new IllegalArgumentException("SQLBuilder Type parameter must be: SQLBuilder.PSC/PAC/PLC/PSB. Can't be: " + sbc);
            }

            daoPrepareQueryFuncPool.put(daoInterface, tp);
        }

        return tp;
    }

    static boolean isSelectQuery(final String sql) throws UnsupportedOperationException {
        return sql.startsWith("select ") || sql.startsWith("SELECT ") || Strings.startsWithIgnoreCase(Strings.trim(sql), "select ");
    }

    static boolean isInsertQuery(final String sql) throws UnsupportedOperationException {
        return sql.startsWith("insert ") || sql.startsWith("INSERT ") || Strings.startsWithIgnoreCase(Strings.trim(sql), "insert ");
    }

    static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName) {
        return JoinInfo.getEntityJoinInfo(targetDaoInterface, targetEntityClass, targetTableName);
    }

    static List<String> getJoinEntityPropNamesByType(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName,
            final Class<?> joinEntityClass) {
        return JoinInfo.getJoinEntityPropNamesByType(targetDaoInterface, targetEntityClass, targetTableName, joinEntityClass);
    }
}
