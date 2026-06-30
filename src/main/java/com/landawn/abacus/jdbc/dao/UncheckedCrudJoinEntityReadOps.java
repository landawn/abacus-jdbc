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
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.u.Optional;

/**
 * Unchecked-exception variant that combines read-side CRUD-by-ID operations with join entity loading,
 * throwing {@link UncheckedSQLException} instead of {@link java.sql.SQLException}.
 * It extends {@link UncheckedJoinEntityReadOps} and {@link CrudJoinEntityReadOps}, redeclaring
 * the read/load methods to narrow the declared exception from {@code SQLException} to {@code UncheckedSQLException}.
 *
 * <p>This interface enables efficient loading of related entities when retrieving data by ID,
 * making it ideal for entities with complex relationships that need to be fetched together.</p>
 *
 * <p>Join entities are populated <i>in place</i>: the loaded related entities are set directly onto the
 * corresponding {@code @JoinedBy} properties of the entity instance returned by each {@code get},
 * {@code gett}, and {@code batchGet} method. When a method accepts a collection of join entity classes,
 * a {@code null} or empty collection results in no join entities being loaded.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends UncheckedCrudDao<User, Long, UserDao>, UncheckedCrudJoinEntityHelper<User, Long, UserDao> {
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
 * @param <T> the entity type that this helper manages
 * @param <ID> the ID type of the entity
 * @param <TD> the concrete DAO type, bounded by {@link UncheckedCrudDao}, that owns this helper
 *             (used for fluent method chaining and access to CRUD operations)
 * @see UncheckedJoinEntityHelper
 * @see UncheckedCrudDao
 * @see CrudJoinEntityHelper
 * @see com.landawn.abacus.annotation.JoinedBy
 */
sealed interface UncheckedCrudJoinEntityReadOps<T, ID, TD extends UncheckedCrudDao<T, ID, TD>> extends UncheckedJoinEntityReadOps<T, TD>,
        CrudJoinEntityReadOps<T, ID, TD> permits UncheckedCrudJoinEntityHelper, UncheckedReadOnlyCrudJoinEntityHelper {

    /**
     * Retrieves an entity by ID and loads the specified join entity class.
     * This is a beta API that combines entity retrieval with automatic join loading.
     * The loaded related entities are populated in place on the returned entity instance.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @return an {@link Optional} containing the entity with the specified join entities loaded, or an empty {@code Optional} if no entity is found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicateResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by ID and optionally loads all join entities.
     * This is a beta API for convenient loading of all relationships. When {@code includeAllJoinEntities}
     * is {@code true}, all {@code @JoinedBy} properties are populated in place on the returned entity;
     * when {@code false}, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with all relationships loaded
     * Optional<User> user = userDao.get(userId, true);
     * // User will have orders, profile, addresses, etc. all loaded
     * }</pre>
     *
     * @param id the entity ID
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an {@link Optional} containing the entity with its join entities loaded (when requested), or an empty {@code Optional} if no entity is found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws DuplicateResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by ID with selected properties and loads the specified join entity class.
     * This is a beta API for efficient partial loading of entities with relationships.
     * The loaded related entities are populated in place on the returned entity instance.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an {@link Optional} containing the entity with the selected properties and the specified join entities loaded, or an empty {@code Optional} if no entity is found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicateResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by ID with selected properties and loads multiple join entity classes.
     * This is a beta API for flexible entity loading with multiple relationships.
     * The loaded related entities are populated in place on the returned entity; if {@code joinEntitiesToLoad}
     * is {@code null} or empty, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return an {@link Optional} containing the entity with the selected properties and the specified join entities loaded, or an empty {@code Optional} if no entity is found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property is found for one of the specified types in the entity class
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicateResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by ID with selected properties and optionally loads all join entities.
     * This is a beta API for flexible entity retrieval with automatic relationship loading. When
     * {@code includeAllJoinEntities} is {@code true}, the loaded entities are populated in place on the
     * returned entity; when {@code false}, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an {@link Optional} containing the entity with the selected properties and its join entities loaded (when requested), or an empty {@code Optional} if no entity is found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicateResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by ID and loads the specified join entity class, returning the entity directly.
     * This is a beta API that returns {@code null} if the entity is not found.
     * The loaded related entities are populated in place on the returned entity instance.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @return the entity with loaded join entities, or {@code null} if not found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    @Override
    default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicateResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by ID and optionally loads all join entities, returning the entity directly.
     * This is a beta API that returns {@code null} if the entity is not found. When {@code includeAllJoinEntities}
     * is {@code true}, the loaded entities are populated in place on the returned entity; when {@code false},
     * no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @return the entity with its join entities loaded (when requested), or {@code null} if no entity is found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default T gett(final ID id, final boolean includeAllJoinEntities) throws DuplicateResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves an entity by ID with selected properties and loads the specified join entity class, returning {@code null} if not found.
     * This is a beta API for efficient partial entity loading with relationships.
     * The loaded related entities are populated in place on the returned entity instance.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the entity with selected properties and loaded join entities, or {@code null} if not found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicateResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by ID with selected properties and loads multiple join entity classes, returning {@code null} if not found.
     * This is a beta API for complex entity loading scenarios.
     * The loaded related entities are populated in place on the returned entity; if {@code joinEntitiesToLoad}
     * is {@code null} or empty, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return the entity with selected properties and loaded join entities, or {@code null} if not found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property is found for one of the specified types in the entity class
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicateResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && N.notEmpty(joinEntitiesToLoad)) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result, joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Retrieves an entity by ID with selected properties and optionally loads all join entities, returning {@code null} if not found.
     * This is a beta API that combines partial loading with automatic relationship loading. When
     * {@code includeAllJoinEntities} is {@code true}, the loaded entities are populated in place on the
     * returned entity; when {@code false}, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the entity with the selected properties and its join entities loaded (when requested), or {@code null} if no entity is found
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicateResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Batch gets entities by IDs and loads the specified join entity class for each.
     * This is a beta API for efficient batch loading with relationships.
     * The loaded related entities are populated in place on each returned entity.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @return a list of the found entities, each with the specified join entities loaded; empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntitiesToLoad) throws DuplicateResultException, UncheckedSQLException {
        return batchGet(ids, null, joinEntitiesToLoad, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch gets entities by IDs and optionally loads all join entities for each.
     * This is a beta API for batch loading with automatic relationship loading. When
     * {@code includeAllJoinEntities} is {@code true}, the loaded entities are populated in place on each
     * returned entity; when {@code false}, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @return a list of the found entities, each with its join entities loaded (when requested); empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities) throws DuplicateResultException, UncheckedSQLException {
        return batchGet(ids, null, includeAllJoinEntities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch gets entities with selected properties and loads the specified join entity class.
     * This is a beta API for efficient partial batch loading with relationships.
     * The loaded related entities are populated in place on each returned entity.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties to select from the main entities, excluding join entity properties.
     *                       If {@code null}, all properties of the main entities are selected
     * @param joinEntitiesToLoad the class of the join entities to load for each entity
     * @return a list of the found entities, each with the selected properties and the specified join entities loaded; empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicateResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, joinEntitiesToLoad, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch gets entities with selected properties and loads multiple join entity classes.
     * This is a beta API for complex batch loading scenarios.
     * The loaded related entities are populated in place on each returned entity; if {@code joinEntitiesToLoad}
     * is {@code null} or empty, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties to select from the main entities, excluding join entity properties.
     *                       If {@code null}, all properties of the main entities are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return a list of the found entities, each with the selected properties and the specified join entities loaded; empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property is found for one of the specified types in the entity class
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicateResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, joinEntitiesToLoad, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch gets entities with selected properties and optionally loads all join entities.
     * This is a beta API for flexible batch loading with automatic relationship loading. When
     * {@code includeAllJoinEntities} is {@code true}, the loaded entities are populated in place on each
     * returned entity; when {@code false}, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
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
     * @param selectPropNames the properties to select from the main entities, excluding join entity properties.
     *                       If {@code null}, all properties of the main entities are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of the found entities, each with the selected properties and its join entities loaded (when requested); empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicateResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, includeAllJoinEntities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch gets entities with selected properties using a specific batch size and loads the specified join entity class.
     * This is a beta API for efficient large-scale batch loading with relationships.
     * The loaded related entities are populated in place on each returned entity.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get thousands of users in batches of 500 with their orders
     * List<User> users = userDao.batchGet(
     *     largeUserIdSet,
     *     Arrays.asList("id", "name", "email"),
     *     Order.class,
     *     500   // batch size
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, excluding join entity properties.
     *                       If {@code null}, all properties of the main entities are selected
     * @param joinEntitiesToLoad the class of the join entities to load for each entity
     * @param batchSize the size of each batch for processing
     * @return a list of the found entities, each with the selected properties and the specified join entities loaded; empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code batchSize} is not positive, or if no join property of the specified type is found in the entity class
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad,
            final int batchSize) throws DuplicateResultException, UncheckedSQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

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
     * The loaded related entities are populated in place on each returned entity; if {@code joinEntitiesToLoad}
     * is {@code null} or empty, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get large number of users with multiple relationships
     * List<User> users = userDao.batchGet(
     *     thousandsOfIds,
     *     Arrays.asList("id", "name", "status"),
     *     Arrays.asList(Order.class, UserProfile.class, Address.class),
     *     1000   // batch size
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, excluding join entity properties.
     *                       If {@code null}, all properties of the main entities are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @param batchSize the size of each batch for processing
     * @return a list of the found entities, each with the selected properties and the specified join entities loaded; empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code batchSize} is not positive, or if no join property is found for one of the specified types in the entity class
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad,
            final int batchSize) throws DuplicateResultException, UncheckedSQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

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
     * This is a beta API for maximum flexibility in batch loading operations. When {@code includeAllJoinEntities}
     * is {@code true}, the loaded entities are populated in place on each returned entity; when {@code false},
     * no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get large dataset with all relationships in optimized batches
     * List<User> users = userDao.batchGet(
     *     veryLargeIdCollection,
     *     Arrays.asList("id", "name", "email", "createdDate"),
     *     true,  // load all join entities
     *     2000   // batch size
     * );
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select from the main entities, excluding join entity properties.
     *                       If {@code null}, all properties of the main entities are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param batchSize the size of each batch for processing
     * @return a list of the found entities, each with the selected properties and its join entities loaded (when requested); empty if none are found
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities,
            final int batchSize) throws DuplicateResultException, UncheckedSQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

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
