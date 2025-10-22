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

import java.util.Collection;
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.u.Optional;

/**
 * The UncheckedCrudJoinEntityHelper interface combines CRUD operations with join entity management capabilities,
 * providing a comprehensive solution for handling entities with relationships. It extends both CRUD DAO
 * and join entity helper interfaces with unchecked exceptions.
 * 
 * <p>This interface enables efficient loading of related entities when retrieving data by ID,
 * making it ideal for entities with complex relationships that need to be fetched together.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends UncheckedCrudJoinEntityHelper<User, Long, SQLBuilder.PSC, UserDao> {
 *     // Inherits both CRUD and join entity operations
 * }
 * 
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * 
 * // Get user with all related entities
 * Optional<User> user = userDao.get(userId, true);
 * 
 * // Get user with specific related entities
 * User user = userDao.gett(userId, Order.class);
 * 
 * // Batch get users with their profiles
 * List<User> users = userDao.batchGet(
 *     Arrays.asList(1L, 2L, 3L),
 *     UserProfile.class
 * );
 * }</pre>
 *
 * @param <T> the entity type
 * @param <ID> the ID type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedJoinEntityHelper
 * @see UncheckedCrudDao
 */
public interface UncheckedCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>>
        extends UncheckedJoinEntityHelper<T, SB, TD>, CrudJoinEntityHelper<T, ID, SB, TD> {

    /**
     * Retrieves an entity by ID and loads the specified join entity class.
     * This is a beta API that combines entity retrieval with automatic join loading.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with their orders loaded
     * Optional<User> user = userDao.get(userId, Order.class);
     * if (user.isPresent()) {
     *     List<Order> orders = user.get().getOrders();
     *     // Orders are already loaded
     * }
     * }</pre>
     *
     * @param id the entity ID
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by ID and optionally loads all join entities.
     * This is a beta API for convenient loading of all relationships.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with all relationships loaded
     * Optional<User> user = userDao.get(userId, true);
     * // User will have orders, profile, addresses, etc. all loaded
     * }</pre>
     *
     * @param id the entity ID
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by ID with selected properties and loads the specified join entity class.
     * This is a beta API for efficient partial loading of entities with relationships.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with minimal fields and their profile
     * Optional<User> user = userDao.get(
     *     userId,
     *     Arrays.asList("id", "name", "email"),
     *     UserProfile.class
     * );
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties (columns) to select from the main entity,
     *                       excluding join entity properties.
     *                       All properties will be selected if {@code null}
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the entity with selected properties and loaded join entities
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by ID with selected properties and loads multiple join entity classes.
     * This is a beta API for flexible entity loading with multiple relationships.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with specific fields and multiple relationships
     * Optional<User> user = userDao.get(
     *     userId,
     *     Arrays.asList("id", "name", "status"),
     *     Arrays.asList(Order.class, UserProfile.class, Address.class)
     * );
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties (columns) to select from the main entity,
     *                       excluding join entity properties.
     *                       All properties will be selected if {@code null}
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return an Optional containing the entity with selected properties and loaded join entities
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by ID with selected properties and optionally loads all join entities.
     * This is a beta API for flexible entity retrieval with automatic relationship loading.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with essential fields and all relationships
     * Optional<User> user = userDao.get(
     *     userId,
     *     Arrays.asList("id", "name", "email", "status"),
     *     true  // load all join entities
     * );
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties (columns) to select from the main entity,
     *                       excluding join entity properties.
     *                       All properties will be selected if {@code null}
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the entity with selected properties and loaded join entities
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by ID and loads the specified join entity class, returning the entity directly.
     * This is a beta API that returns null if the entity is not found.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with orders, returns null if not found
     * User user = userDao.gett(userId, Order.class);
     * if (user != null) {
     *     // Process user with loaded orders
     * }
     * }</pre>
     *
     * @param id the entity ID
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the entity with loaded join entities, or null if not found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by ID and optionally loads all join entities, returning the entity directly.
     * This is a beta API that returns null if the entity is not found.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with all relationships
     * User user = userDao.gett(userId, true);
     * if (user != null) {
     *     // All relationships are loaded
     * }
     * }</pre>
     *
     * @param id the entity ID
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the entity with loaded join entities, or null if not found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default T gett(final ID id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves an entity by ID with selected properties and loads the specified join entity class.
     * This is a beta API for efficient partial entity loading with relationships.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with minimal data and profile
     * User user = userDao.gett(
     *     userId,
     *     Arrays.asList("id", "name"),
     *     UserProfile.class
     * );
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties (columns) to select from the main entity,
     *                       excluding join entity properties.
     *                       All properties will be selected if {@code null}
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the entity with selected properties and loaded join entities, or null if not found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by ID with selected properties and loads multiple join entity classes.
     * This is a beta API for complex entity loading scenarios.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with specific fields and multiple relationships
     * User user = userDao.gett(
     *     userId,
     *     Arrays.asList("id", "name", "email"),
     *     Arrays.asList(Order.class, Payment.class, Review.class)
     * );
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties (columns) to select from the main entity,
     *                       excluding join entity properties.
     *                       All properties will be selected if {@code null}
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return the entity with selected properties and loaded join entities, or null if not found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result, joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Retrieves an entity by ID with selected properties and optionally loads all join entities.
     * This is a beta API that combines partial loading with automatic relationship loading.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get user with core fields and all relationships
     * User user = userDao.gett(
     *     userId,
     *     Arrays.asList("id", "name", "email", "verified"),
     *     true  // load all join entities
     * );
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties (columns) to select from the main entity,
     *                       excluding join entity properties.
     *                       All properties will be selected if {@code null}
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the entity with selected properties and loaded join entities, or null if not found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Batch gets entities by IDs and loads the specified join entity class for each.
     * This is a beta API for efficient batch loading with relationships.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get multiple users with their orders
     * List<User> users = userDao.batchGet(
     *     Arrays.asList(1L, 2L, 3L, 4L, 5L),
     *     Order.class
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param joinEntitiesToLoad the class of the join entities to load for each entity
     * @return a list of entities with loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     * Batch gets entities by IDs and optionally loads all join entities for each.
     * This is a beta API for batch loading with automatic relationship loading.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get multiple users with all their relationships
     * List<User> users = userDao.batchGet(
     *     userIds,
     *     true  // load all join entities
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of entities with loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
    }

    /**
     * Batch gets entities with selected properties and loads the specified join entity class.
     * This is a beta API for efficient partial batch loading with relationships.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get users with minimal fields and their profiles
     * List<User> users = userDao.batchGet(
     *     userIds,
     *     Arrays.asList("id", "name", "email"),
     *     UserProfile.class
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, or null for all
     * @param joinEntitiesToLoad the class of the join entities to load for each entity
     * @return a list of entities with selected properties and loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     * Batch gets entities with selected properties and loads multiple join entity classes.
     * This is a beta API for complex batch loading scenarios.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get users with specific fields and multiple relationships
     * List<User> users = userDao.batchGet(
     *     userIds,
     *     Arrays.asList("id", "name", "status"),
     *     Arrays.asList(Order.class, Address.class, Payment.class)
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, or null for all
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return a list of entities with selected properties and loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     * Batch gets entities with selected properties and optionally loads all join entities.
     * This is a beta API for flexible batch loading with automatic relationship loading.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get users with essential fields and all relationships
     * List<User> users = userDao.batchGet(
     *     userIds,
     *     Arrays.asList("id", "name", "email", "active"),
     *     true  // load all join entities
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, or null for all
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of entities with selected properties and loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
    }

    /**
     * Batch gets entities with selected properties using a specific batch size and loads the specified join entity class.
     * This is a beta API for efficient large-scale batch loading with relationships.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get thousands of users in batches of 500 with their orders
     * List<User> users = userDao.batchGet(
     *     largeUserIdSet,
     *     Arrays.asList("id", "name", "email"),
     *     500,  // batch size
     *     Order.class
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, or null for all
     * @param batchSize the size of each batch for processing
     * @param joinEntitiesToLoad the class of the join entities to load for each entity
     * @return a list of entities with selected properties and loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
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
     * Batch gets entities with selected properties using a specific batch size and loads multiple join entity classes.
     * This is a beta API for complex large-scale batch loading scenarios.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get large number of users with multiple relationships
     * List<User> users = userDao.batchGet(
     *     thousandsOfIds,
     *     Arrays.asList("id", "name", "status"),
     *     1000,  // batch size
     *     Arrays.asList(Order.class, UserProfile.class, Address.class)
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, or null for all
     * @param batchSize the size of each batch for processing
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return a list of entities with selected properties and loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final Collection<Class<?>> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
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
     * Batch gets entities with selected properties using a specific batch size and optionally loads all join entities.
     * This is a beta API for maximum flexibility in batch loading operations.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Get large dataset with all relationships in optimized batches
     * List<User> users = userDao.batchGet(
     *     veryLargeIdCollection,
     *     Arrays.asList("id", "name", "email", "createdDate"),
     *     2000,  // large batch size
     *     true   // load all join entities
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, or null for all
     * @param batchSize the size of each batch for processing
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of entities with selected properties and loaded join entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
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