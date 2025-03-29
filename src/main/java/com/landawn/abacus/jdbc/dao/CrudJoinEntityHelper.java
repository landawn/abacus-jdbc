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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.jdbc.JdbcContext;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Optional;

/**
 * Interface for CRUD operations with join entity support.
 *
 * @param <T> the type of the entity
 * @param <ID> the type of the entity ID
 * @param <SB> the type of the SQL builder
 * @param <TD> the type of the CRUD DAO
 *
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
public interface CrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

    /**
     * Retrieves an entity by its ID and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID and optionally loads all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param includeAllJoinEntities whether to include all join entities in the retrieval
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loading the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loading the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the classes of the join entities to load
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and optionally loading all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities whether to include all join entities in the retrieval
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID and optionally loads all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param includeAllJoinEntities whether to include all join entities in the retrieval
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final ID id, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loading the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loading the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the classes of the join entities to load
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result, joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and optionally loading all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities whether to include all join entities in the retrieval
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves a list of entities by their IDs and loads the specified join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        return batchGet(ids, null, JdbcContext.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     * Retrieves a list of entities by their IDs and optionally loads all join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param includeAllJoinEntities whether to include all join entities in the retrieval
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        return batchGet(ids, null, JdbcContext.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
    }

    /**
     * Retrieves a list of entities by their IDs, selecting specified properties and loading the specified join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, JdbcContext.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     * Retrieves a list of entities by their IDs, selecting specified properties and loading the specified join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, JdbcContext.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     * Retrieves a list of entities by their IDs, selecting specified properties and optionally loading all join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities whether to include all join entities in the retrieval
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, JdbcContext.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
    }

    /**
     * Retrieves a list of entities by their IDs, selecting specified properties and loading the specified join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param batchSize the number of entities to retrieve in each batch
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final List<T> result = DaoUtil.getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

        if (N.notEmpty(result)) {
            if (result.size() <= batchSize) {
                loadJoinEntities(result, joinEntitiesToLoad);
            } else {
                N.runByBatch(result, batchSize, batchEntities -> loadJoinEntities(batchEntities, joinEntitiesToLoad));
            }
        }

        return result;
    }

    /**
     * Retrieves a list of entities by their IDs, selecting specified properties and loading the specified join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param batchSize the number of entities to retrieve in each batch
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final Collection<Class<?>> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final List<T> result = DaoUtil.getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

        if (N.notEmpty(result) && N.notEmpty(joinEntitiesToLoad)) {
            if (result.size() <= batchSize) {
                for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result, joinEntityClass);
                }
            } else {
                N.runByBatch(result, batchSize, batchEntities -> {
                    for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(batchEntities, joinEntityClass);
                    }
                });
            }
        }

        return result;
    }

    /**
     * Retrieves a list of entities by their IDs, selecting specified properties and loading the specified join entities.
     *
     * @param ids the collection of IDs of the entities to retrieve
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param batchSize the number of entities to retrieve in each batch
     * @param includeAllJoinEntities whether to include all join entities in the retrieval
     * @return a list of retrieved entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        final List<T> result = DaoUtil.getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

        if (includeAllJoinEntities && N.notEmpty(result)) {
            if (result.size() <= batchSize) {
                loadAllJoinEntities(result);
            } else {
                N.runByBatch(result, batchSize, this::loadAllJoinEntities);
            }
        }

        return result;
    }
}
