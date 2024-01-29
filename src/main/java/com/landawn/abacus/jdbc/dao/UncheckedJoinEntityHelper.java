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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.CheckedStream;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 *
 * @author haiyangl
 *
 * @param <T>
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
public interface UncheckedJoinEntityHelper<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond)
            throws UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (result.isPresent()) {
            loadJoinEntities(result.get(), joinEntitiesToLoad);
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (result.isPresent()) {
            for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default Optional<T> findFirst(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond)
            throws UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (includeAllJoinEntities && result.isPresent()) {
            loadAllJoinEntities(result.get());
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond)
            throws DuplicatedResultException, UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

        if (result.isPresent()) {
            loadJoinEntities(result.get(), joinEntitiesToLoad);
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws DuplicatedResultException, UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

        if (result.isPresent()) {
            for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond)
            throws DuplicatedResultException, UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

        if (includeAllJoinEntities && result.isPresent()) {
            loadAllJoinEntities(result.get());
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws UncheckedSQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notEmpty(result)) {
            if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> loadJoinEntities(it, joinEntitiesToLoad));
            } else {
                loadJoinEntities(result, joinEntitiesToLoad);
            }
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> list(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws UncheckedSQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notEmpty(result) && N.notEmpty(joinEntitiesToLoad)) {
            if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(it -> {
                    for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(it, joinEntityClass);
                    }
                });
            } else {
                for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result, joinEntityClass);
                }
            }
        }

        return result;
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> list(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws UncheckedSQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notEmpty(result)) {
            if (result.size() > JdbcUtil.DEFAULT_BATCH_SIZE) {
                StreamEx.of(result).splitToList(JdbcUtil.DEFAULT_BATCH_SIZE).forEach(this::loadAllJoinEntities);
            } else {
                loadAllJoinEntities(result);
            }
        }

        return result;
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntities(entity, joinEntityClass, null);
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws UncheckedSQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntities(entities, joinEntityClass, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
            throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropName
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntities(entity, joinEntityPropName, null);
    }

    /**
     *
     * @param entity
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws UncheckedSQLException;

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntities(entities, joinEntityPropName, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws UncheckedSQLException;

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            loadJoinEntities(entity, joinEntityPropNames, executor());
        } else {
            loadJoinEntities(entity, joinEntityPropNames);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, UncheckedSQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName);
        }
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException {
        if (inParallel) {
            loadJoinEntities(entities, joinEntityPropNames, executor());
        } else {
            loadJoinEntities(entities, joinEntityPropNames);
        }
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, UncheckedSQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     *
     * @param entity
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadAllJoinEntities(T entity) throws UncheckedSQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            loadAllJoinEntities(entity, executor());
        } else {
            loadAllJoinEntities(entity);
        }
    }

    /**
     *
     * @param entity
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     *
     * @param entities
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            loadAllJoinEntities(entities, executor());
        } else {
            loadAllJoinEntities(entities);
        }
    }

    /**
     *
     * @param entities
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, joinEntityClass, null);
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws UncheckedSQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entities, joinEntityClass, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
            throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        if (joinEntityPropNames.size() == 1) {
            loadJoinEntitiesIfNull(entities, joinEntityPropNames.get(0), selectPropNames);
        } else {
            for (String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
            }
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropName
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
    }

    /**
     *
     * @param entity
     * ?
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames)
            throws UncheckedSQLException {
        final Class<?> cls = entity.getClass();
        final PropInfo propInfo = ParserUtil.getBeanInfo(cls).getPropInfo(joinEntityPropName);

        if (propInfo.getPropValue(entity) == null) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
            throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        final Class<?> cls = N.firstOrNullIfEmpty(entities).getClass();
        final PropInfo propInfo = ParserUtil.getBeanInfo(cls).getPropInfo(joinEntityPropName);
        final List<T> newEntities = N.filter(entities, entity -> propInfo.getPropValue(entity) == null);

        if (N.notEmpty(newEntities)) {
            loadJoinEntities(newEntities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            loadJoinEntitiesIfNull(entity, joinEntityPropNames, executor());
        } else {
            loadJoinEntitiesIfNull(entity, joinEntityPropNames);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, UncheckedSQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entities, joinEntityPropName);
        }
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException {
        if (inParallel) {
            loadJoinEntitiesIfNull(entities, joinEntityPropNames, executor());
        } else {
            loadJoinEntitiesIfNull(entities, joinEntityPropNames);
        }
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, UncheckedSQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     *
     * @param entity
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntitiesIfNull(T entity) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            loadJoinEntitiesIfNull(entity, executor());
        } else {
            loadJoinEntitiesIfNull(entity);
        }
    }

    /**
     *
     * @param entity
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     *
     * @param entities
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfNull(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            loadJoinEntitiesIfNull(entities, executor());
        } else {
            loadJoinEntitiesIfNull(entities);
        }
    }

    /**
     *
     * @param entities
     * @param executor
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfNull(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, joinEntityPropNames.get(0));
        } else {
            int result = 0;
            final DataSource dataSource = DaoUtil.getDao(this).dataSource();
            final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                for (String joinEntityPropName : joinEntityPropNames) {
                    result += deleteJoinEntities(entity, joinEntityPropName);
                }
                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        if (N.isEmpty(entities)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entities, joinEntityPropNames.get(0));
        } else {
            int result = 0;
            final DataSource dataSource = DaoUtil.getDao(this).dataSource();
            final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                for (String joinEntityPropName : joinEntityPropNames) {
                    result += deleteJoinEntities(entities, joinEntityPropName);
                }
                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     *
     *
     * @param entity
     * @param joinEntityPropName
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException;

    /**
     *
     *
     * @param entities
     * @param joinEntityPropName
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException;

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource dataSource = DaoUtil.getDao(this).dataSource();
            final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                for (String joinEntityPropName : joinEntityPropNames) {
                    result += deleteJoinEntities(entity, joinEntityPropName);
                }
                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            return deleteJoinEntities(entity, joinEntityPropNames, executor());
        } else {
            return deleteJoinEntities(entity, joinEntityPropNames);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = CheckedStream.of(joinEntityPropNames, UncheckedSQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        return DaoUtil.uncheckedCompleteSum(futures);
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entities, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource dataSource = DaoUtil.getDao(this).dataSource();
            final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                for (String joinEntityPropName : joinEntityPropNames) {
                    result += deleteJoinEntities(entities, joinEntityPropName);
                }
                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException {
        if (inParallel) {
            return deleteJoinEntities(entities, joinEntityPropNames, executor());
        } else {
            return deleteJoinEntities(entities, joinEntityPropNames);
        }
    }

    /**
     *
     *
     * @param entities
     * @param joinEntityPropNames
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = CheckedStream.of(joinEntityPropNames, UncheckedSQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        return DaoUtil.uncheckedCompleteSum(futures);
    }

    /**
     *
     * @param entity
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteAllJoinEntities(T entity) throws UncheckedSQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            return deleteAllJoinEntities(entity, executor());
        } else {
            return deleteAllJoinEntities(entity);
        }
    }

    /**
     *
     * @param entity
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     *
     * @param entities
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
        if (inParallel) {
            return deleteAllJoinEntities(entities, executor());
        } else {
            return deleteAllJoinEntities(entities);
        }
    }

    /**
     *
     * @param entities
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }
}
