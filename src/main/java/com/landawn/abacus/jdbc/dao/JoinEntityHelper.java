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

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.ExceptionalStream;
import com.landawn.abacus.util.ExceptionalStream.CheckedStream;
import com.landawn.abacus.util.Fn.Fnn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 *
 * @author haiyangl
 *
 * @param <T>
 * @param <SB>
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
public interface JoinEntityHelper<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {

    /**
     *
     * @return
     * @deprecated internal only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<T> targetEntityClass();

    /**
     *
     * @return
     * @deprecated internal only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<TD> targetDaoInterface();

    /**
     *
     * @return
     * @deprecated internal only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Executor executor();

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     * @throws SQLException
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
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
     * @throws SQLException
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad, final Condition cond)
            throws SQLException {
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
     * @throws SQLException
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws SQLException {
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
     * @throws SQLException
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond)
            throws DuplicatedResultException, SQLException {
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
     * @throws SQLException
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad, final Condition cond)
            throws DuplicatedResultException, SQLException {
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
     * @throws SQLException
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond)
            throws DuplicatedResultException, SQLException {
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
     * @throws SQLException
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notNullOrEmpty(result)) {
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
     * @throws SQLException
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad, final Condition cond)
            throws SQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notNullOrEmpty(result) && N.notNullOrEmpty(joinEntitiesToLoad)) {
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
     * @throws SQLException
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (includeAllJoinEntities && N.notNullOrEmpty(result)) {
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
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     */
    @Beta
    default ExceptionalStream<T, SQLException> stream(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) {
        return DaoUtil.getDao(this)
                .stream(selectPropNames, cond)
                .splitToList(JdbcUtil.DEFAULT_BATCH_SIZE)
                .onEach(it -> loadJoinEntities(it, joinEntitiesToLoad))
                .flatmap(Fnn.identity());
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @param cond
     * @return
     */
    @Beta
    default ExceptionalStream<T, SQLException> stream(final Collection<String> selectPropNames, final Collection<? extends Class<?>> joinEntitiesToLoad,
            final Condition cond) {
        return DaoUtil.getDao(this)
                .stream(selectPropNames, cond)
                .splitToList(JdbcUtil.DEFAULT_BATCH_SIZE) //
                .onEach(it -> {
                    for (Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(it, joinEntityClass);
                    }
                })
                .flatmap(Fnn.identity());
    }

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @param cond
     * @return
     */
    @Beta
    default ExceptionalStream<T, SQLException> stream(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) {
        if (includeAllJoinEntities) {
            return DaoUtil.getDao(this)
                    .stream(selectPropNames, cond)
                    .splitToList(JdbcUtil.DEFAULT_BATCH_SIZE) //
                    .onEach(this::loadAllJoinEntities)
                    .flatmap(Fnn.identity());

        } else {
            return DaoUtil.getDao(this).stream(selectPropNames, cond);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @throws SQLException
     */
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntities(entity, joinEntityClass, null);
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
        N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @throws SQLException
     */
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntities(entities, joinEntityClass, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return;
        }

        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
        N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropName
     * @throws SQLException
     */
    default void loadJoinEntities(final T entity, final String joinEntityPropName) throws SQLException {
        loadJoinEntities(entity, joinEntityPropName, null);
    }

    /**
     *
     * @param entity
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @throws SQLException
     */
    default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
        loadJoinEntities(entities, joinEntityPropName, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @throws SQLException
     */
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isNullOrEmpty(joinEntityPropNames)) {
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
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, SQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @throws SQLException
     */
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
            return;
        }

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param inParallel
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadJoinEntities(entities, joinEntityPropNames, executor());
        } else {
            loadJoinEntities(entities, joinEntityPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param executor
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, SQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     *
     * @param entity
     * @throws SQLException
     */
    default void loadAllJoinEntities(T entity) throws SQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @throws SQLException
     */
    @Beta
    default void loadAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     */
    @Beta
    default void loadAllJoinEntities(final T entity, final Executor executor) throws SQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
    }

    /**
     *
     * @param entities
     * @throws SQLException
     */
    default void loadAllJoinEntities(final Collection<T> entities) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @throws SQLException
     */
    @Beta
    default void loadAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     */
    @Beta
    default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntitiesIfNull(entity, joinEntityClass, null);
    }

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
        N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntitiesIfNull(entities, joinEntityClass, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
            throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return;
        }

        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
        N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

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
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws SQLException {
        loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
    }

    /**
     *
     * @param entity
     * ?
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> cls = entity.getClass();
        final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);

        if (propInfo.getPropValue(entity) == null) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
        loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
            throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return;
        }

        final Class<?> cls = N.firstOrNullIfEmpty(entities).getClass();
        final PropInfo propInfo = ParserUtil.getEntityInfo(cls).getPropInfo(joinEntityPropName);
        final List<T> newEntities = N.filter(entities, entity -> propInfo.getPropValue(entity) == null);

        if (N.notNullOrEmpty(newEntities)) {
            loadJoinEntities(newEntities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isNullOrEmpty(joinEntityPropNames)) {
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
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, SQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
            return;
        }

        for (String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entities, joinEntityPropName);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param inParallel
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws SQLException {
        if (inParallel) {
            loadJoinEntitiesIfNull(entities, joinEntityPropNames, executor());
        } else {
            loadJoinEntitiesIfNull(entities, joinEntityPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param executor
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws SQLException {
        if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = CheckedStream.of(joinEntityPropNames, SQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     *
     * @param entity
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(T entity) throws SQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final T entity, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws SQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
    }

    /**
     *
     * @param entities
     * @throws SQLException
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfNull(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     */
    @Beta
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfNull(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
    }

    // TODO may or may not, should or should not? undecided.
    //    int saveWithJoinEntities(final T entity) throws SQLException;
    //
    //    default int batchSaveWithJoinEntities(final Collection<? extends T> entities) throws SQLException {
    //        return batchSaveWithJoinEntities(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    //    }
    //
    //    int batchSaveWithJoinEntities(final Collection<? extends T> entity, int batchSize) throws SQLException;

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @return the total count of updated/deleted records.
     * @throws SQLException
     */
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
        N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

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
     * @throws SQLException
     */
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, joinEntityClass);
        N.checkArgument(N.notNullOrEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        if (N.isNullOrEmpty(entities)) {
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
     * @param entity
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return the total count of updated/deleted records.
     * @throws SQLException
     */
    int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException;

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return the total count of updated/deleted records.
     * @throws SQLException
     */
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException;

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @return the total count of updated/deleted records.
     * @throws SQLException
     */
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isNullOrEmpty(joinEntityPropNames)) {
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
     * @throws SQLException
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = CheckedStream.of(joinEntityPropNames, SQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        return DaoUtil.completeSum(futures);
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @return the total count of updated/deleted records.
     * @throws SQLException
     */
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
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
     * @param entities
     * @param joinEntityPropName
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws SQLException
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
        if (inParallel) {
            return deleteJoinEntities(entities, joinEntityPropNames, executor());
        } else {
            return deleteJoinEntities(entities, joinEntityPropNames);
        }
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws SQLException
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(entities) || N.isNullOrEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = CheckedStream.of(joinEntityPropNames, SQLException.class)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        return DaoUtil.completeSum(futures);
    }

    /**
     *
     * @param entity
     * @return the total count of updated/deleted records.
     * @throws SQLException
     */
    default int deleteAllJoinEntities(T entity) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws SQLException
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
    }

    /**
     *
     * @param entities
     * @return the total count of updated/deleted records.
     * @throws SQLException
     */
    default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet());
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws SQLException
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is true.
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
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
     * @throws SQLException
     * @deprecated the operation can't be finished in one transaction if it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass()).keySet(), executor);
    }
}