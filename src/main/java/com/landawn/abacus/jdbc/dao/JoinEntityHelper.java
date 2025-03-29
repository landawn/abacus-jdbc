/*
 * Copyright (c) 2021, Haiyang Li.
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
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcContext;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream;

/**
 * Interface for handling join entities for specified entities
 *
 * @param <T>
 * @param <SB>
 * @param <TD>
 *
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public interface JoinEntityHelper<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {
    /**
     * Retrieves the class type of the target DAO interface.
     *
     * @return the class type of the target DAO interface
     * @deprecated internal only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<TD> targetDaoInterface();

    /**
     * Retrieves the class type of the target entity.
     *
     * @return the class type of the target entity
     * @deprecated internal only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<T> targetEntityClass();

    /**
     * Retrieves the name of the target table.
     *
     * @return the name of the target table
     * @deprecated internal only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    String targetTableName();

    /**
     * Retrieves the executor for executing tasks in parallel.
     *
     * @return the executor for executing tasks
     * @deprecated internal only
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Executor executor();

    /**
     * Finds the first entity that matches the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the entities to be joined and loaded
     * @param cond the condition to match
     * @return an {@code Optional} containing the first entity that matches the condition, or an empty {@code Optional} if no entity matches
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (result.isPresent()) {
            loadJoinEntities(result.get(), joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Finds the first entity that matches the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the classes of the entities to be joined and loaded
     * @param cond the condition to match
     * @return an {@code Optional} containing the first entity that matches the condition, or an empty {@code Optional} if no entity matches
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws SQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (result.isPresent()) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Finds the first entity that matches the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities whether to include all join entities in the result
     * @param cond the condition to match
     * @return an {@code Optional} containing the first entity that matches the condition, or an empty {@code Optional} if no entity matches
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws SQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (includeAllJoinEntities && result.isPresent()) {
            loadAllJoinEntities(result.get());
        }

        return result;
    }

    /**
     * Finds the only entity that matches the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the entities to be joined and loaded
     * @param cond the condition to match
     * @return an {@code Optional} containing the only entity that matches the condition, or an empty {@code Optional} if no entity matches
     * @throws DuplicatedResultException if more than one record found by the specified {@code condition}.
     * @throws SQLException if a database access error occurs
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
     * Finds the only entity that matches the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the classes of the entities to be joined and loaded
     * @param cond the condition to match
     * @return an {@code Optional} containing the only entity that matches the condition, or an empty {@code Optional} if no entity matches
     * @throws DuplicatedResultException if more than one record found by the specified {@code condition}.
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws DuplicatedResultException, SQLException {
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

        if (result.isPresent()) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Finds the only entity that matches the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities whether to include all join entities in the result
     * @param cond the condition to match
     * @return an {@code Optional} containing the only entity that matches the condition, or an empty {@code Optional} if no entity matches
     * @throws DuplicatedResultException if more than one record found by the specified {@code condition}.
     * @throws SQLException if a database access error occurs
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
     * Retrieves a list of entities that match the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the entities to be joined and loaded
     * @param cond the condition to match
     * @return a list of entities that match the specified condition
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notEmpty(result)) {
            if (result.size() <= JdbcContext.DEFAULT_BATCH_SIZE) {
                loadJoinEntities(result, joinEntitiesToLoad);
            } else {
                N.runByBatch(result, JdbcContext.DEFAULT_BATCH_SIZE, batchEntities -> loadJoinEntities(batchEntities, joinEntitiesToLoad));
            }
        }

        return result;
    }

    /**
     * Retrieves a list of entities that match the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the classes of the entities to be joined and loaded
     * @param cond the condition to match
     * @return a list of entities that match the specified condition
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notEmpty(result) && N.notEmpty(joinEntitiesToLoad)) {
            if (result.size() <= JdbcContext.DEFAULT_BATCH_SIZE) {
                for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result, joinEntityClass);
                }
            } else {
                N.runByBatch(result, JdbcContext.DEFAULT_BATCH_SIZE, batchEntities -> {
                    for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(batchEntities, joinEntityClass);
                    }
                });
            }
        }

        return result;
    }

    /**
     * Retrieves a list of entities that match the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities whether to include all join entities in the result
     * @param cond the condition to match
     * @return a list of entities that match the specified condition
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (includeAllJoinEntities && N.notEmpty(result)) {
            if (result.size() <= JdbcContext.DEFAULT_BATCH_SIZE) {
                loadAllJoinEntities(result);
            } else {
                N.runByBatch(result, JdbcContext.DEFAULT_BATCH_SIZE, this::loadAllJoinEntities);
            }
        }

        return result;
    }

    /**
     * Streams entities that match the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the entities to be joined and loaded
     * @param cond the condition to match
     * @return a {@code CheckedStream} of entities that match the specified condition
     */
    @Beta
    default Stream<T> stream(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) {
        return DaoUtil.getDao(this).stream(selectPropNames, cond).split(JdbcContext.DEFAULT_BATCH_SIZE).onEach(batchEntities -> {
            try {
                loadJoinEntities(batchEntities, joinEntitiesToLoad);
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }).flatmap(Fn.identity());
    }

    /**
     * Streams entities that match the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the classes of the entities to be joined and loaded
     * @param cond the condition to match
     * @return a {@code CheckedStream} of entities that match the specified condition
     */
    @Beta
    default Stream<T> stream(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond) {
        return DaoUtil.getDao(this)
                .stream(selectPropNames, cond)
                .split(JdbcContext.DEFAULT_BATCH_SIZE) //
                .onEach(batchEntities -> {
                    try {
                        for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                            loadJoinEntities(batchEntities, joinEntityClass);
                        }
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .flatmap(Fn.identity());
    }

    /**
     * Streams entities that match the specified condition.
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities whether to include all join entities in the result
     * @param cond the condition to match
     * @return a {@code CheckedStream} of entities that match the specified condition
     */
    @Beta
    default Stream<T> stream(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) {
        if (includeAllJoinEntities) {
            return DaoUtil.getDao(this)
                    .stream(selectPropNames, cond)
                    .split(JdbcContext.DEFAULT_BATCH_SIZE) //
                    .onEach(t -> {
                        try {
                            loadAllJoinEntities(t);
                        } catch (final SQLException e) {
                            throw new UncheckedSQLException(e);
                        }
                    })
                    .flatmap(Fn.identity());

        } else {
            return DaoUtil.getDao(this).stream(selectPropNames, cond);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entity.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntities(entity, joinEntityClass, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entity.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entities.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntities(entities, joinEntityClass, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entities.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        if (N.isEmpty(entities)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entity.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final T entity, final String joinEntityPropName) throws SQLException {
        loadJoinEntities(entity, joinEntityPropName, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entity.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entities.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
        loadJoinEntities(entities, joinEntityPropName, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entities.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

    /**
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entity.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entity.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entity by multiple threads in parallel using the provided executor.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entities.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entities.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entities by multiple threads in parallel using the provided executor.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     * Loads all join entities for the specified entity.
     *
     * @param entity the entity for which to load all join entities
     * @throws SQLException if a database access error occurs
     */
    default void loadAllJoinEntities(final T entity) throws SQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities for the specified entity.
     *
     * @param entity the entity for which to load all join entities
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads all join entities for the specified entity by multiple threads in parallel using the provided executor.
     *
     * @param entity the entity for which to load all join entities
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadAllJoinEntities(final T entity, final Executor executor) throws SQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads all join entities for the specified collection of entities.
     *
     * @param entities the collection of entities for which to load all join entities
     * @throws SQLException if a database access error occurs
     */
    default void loadAllJoinEntities(final Collection<T> entities) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities for the specified collection of entities.
     *
     * @param entities the collection of entities for which to load all join entities
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads all join entities for the specified collection of entities by multiple threads in parallel using the provided executor.
     *
     * @param entities the collection of entities for which to load all join entities
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntitiesIfNull(entity, joinEntityClass, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntitiesIfNull(entities, joinEntityClass, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityClass} for the specified entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityClass the class of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
            throws SQLException {
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
            for (final String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
            }
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws SQLException {
        loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> cls = entity.getClass();
        final PropInfo propInfo = ParserUtil.getBeanInfo(cls).getPropInfo(joinEntityPropName);

        if (propInfo.getPropValue(entity) == null) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
        loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropName} for the specified entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropName the property name of the join entities to be loaded
     * @param selectPropNames the properties (columns) to be selected from joining entities. All properties(columns) will be selected from the joining entities if the specified {@code selectPropNames} is {@code null}.
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
            throws SQLException {
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
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entity if they(the join entities) are {@code null}, using the provided executor to run the loading tasks in parallel.
     * using the provided executor to run the loading tasks in parallel.
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .filter(joinEntityPropName -> N.getPropValue(entity, joinEntityPropName) == null)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entities, joinEntityPropName);
        }
    }

    /**
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads the join entities specified by {@code joinEntityPropNames} for the specified entities if they(the join entities) are {@code null}, using the provided executor to run the loading tasks in parallel.
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to be loaded
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     * Loads the join entities for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final T entity) throws SQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads the join entities for the specified entity if they(the join entities) are {@code null}.
     *
     * @param entity the entity for which to load join entities
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads the join entities for the specified entity if they(the join entities) are {@code null}, using the provided executor to run the loading tasks in parallel.
     *
     * @param entity the entity for which to load join entities
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws SQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads the join entities for the specified collection of entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @throws SQLException if a database access error occurs
     */
    default void loadJoinEntitiesIfNull(final Collection<T> entities) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfNull(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads the join entities for the specified collection of entities if they(the join entities) are {@code null}.
     *
     * @param entities the collection of entities for which to load join entities
     * @param inParallel whether to load the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
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
     * Loads the join entities for the specified collection of entities if they(the join entities) are {@code null}, using the provided executor to run the loading tasks in parallel.
     *
     * @param entities the collection of entities for which to load join entities
     * @param executor the executor to use for loading the join entities by multiple threads in parallel
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfNull(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    // TODO may or may not, should or should not? undecided.
    //    int saveWithJoinEntities(final T entity) throws SQLException;
    //
    //    default int batchSaveWithJoinEntities(final Collection<? extends T> entities) throws SQLException {
    //        return batchSaveWithJoinEntities(entities, JdbcContext.DEFAULT_BATCH_SIZE);
    //    }
    //
    //    int batchSaveWithJoinEntities(final Collection<? extends T> entity, int batchSize) throws SQLException;

    /**
     * Deletes the join entities specified by {@code joinEntityClass} for the specified entity.
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityClass the class of the join entities to be deleted
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, joinEntityPropNames.get(0));
        } else {
            int result = 0;
            final DataSource dataSource = DaoUtil.getDao(this).dataSource();
            final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
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
     * Deletes the join entities specified by {@code joinEntityClass} for the specified collection of entities.
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityClass the class of the join entities to be deleted
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
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
            final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
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
     * Deletes the join entities specified by {@code joinEntityPropName} for the specified entity.
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropName the property name of the join entities to be deleted
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException;

    /**
     * Deletes the join entities specified by {@code joinEntityPropName} for the specified collection of entities.
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityPropName the property name of the join entities to be deleted
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException;

    /**
     * Deletes the join entities specified by {@code joinEntityPropNames} for the specified entity.
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to be deleted
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource dataSource = DaoUtil.getDao(this).dataSource();
            final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
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
     * Deletes the join entities specified by {@code joinEntityPropNames} for the specified entity.
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to be deleted
     * @param inParallel whether to delete the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is {@code true}.
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
     * Deletes the join entities specified by {@code joinEntityPropNames} for the specified entity by multiple threads in parallel using the provided executor.
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to be deleted
     * @param executor the executor to use for deleting the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation can't be finished in one transaction when it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        return DaoUtil.completeSum(futures);
    }

    /**
     * Deletes the join entities specified by {@code joinEntityPropNames} for the specified collection of entities.
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to be deleted
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entities, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource dataSource = DaoUtil.getDao(this).dataSource();
            final SQLTransaction tran = JdbcContext.beginTransaction(dataSource);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
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
     * Deletes the join entities specified by {@code joinEntityPropNames} for the specified collection of entities.
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to be deleted
     * @param inParallel whether to delete the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is {@code true}.
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
     * Deletes the join entities specified by {@code joinEntityPropNames} for the specified collection of entities by multiple threads in parallel using the provided executor.
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to be deleted
     * @param executor the executor to use for deleting the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation can't be finished in one transaction when it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        return DaoUtil.completeSum(futures);
    }

    /**
     * Deletes all join entities for the specified entity.
     *
     * @param entity the entity for which to delete all join entities
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    default int deleteAllJoinEntities(final T entity) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for the specified entity.
     *
     * @param entity the entity for which to delete all join entities
     * @param inParallel whether to delete the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is {@code true}.
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
     * Deletes all join entities for the specified entity by multiple threads in parallel using the provided executor.
     *
     * @param entity the entity for which to delete all join entities
     * @param executor the executor to use for deleting the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation can't be finished in one transaction when it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Deletes all join entities for the specified collection of entities.
     *
     * @param entities the collection of entities for which to delete all join entities
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     */
    default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for the specified collection of entities.
     *
     * @param entities the collection of entities for which to delete all join entities
     * @param inParallel whether to delete the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation maybe can't be finished in one transaction if {@code isParallel} is {@code true}.
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
     * Deletes all join entities for the specified collection of entities by multiple threads in parallel using the provided executor.
     *
     * @param entities the collection of entities for which to delete all join entities
     * @param executor the executor to use for deleting the join entities by multiple threads in parallel
     * @return the total count of updated/deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated the operation can't be finished in one transaction when it's executed in multiple threads.
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }
}
