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
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.u.Optional;

/**
 * Unchecked-exception variant that combines read-side CRUD-by-ID operations with join entity loading,
 * throwing {@link UncheckedSQLException} instead of {@link SQLException}.
 * It extends {@link UncheckedJoinEntityReadOps} and {@link CrudJoinEntityReadOps}, redeclaring
 * the read/load methods to narrow the declared exception from {@code SQLException} to {@code UncheckedSQLException}.
 *
 * <p>This interface enables efficient loading of related entities when retrieving data by ID,
 * making it ideal for entities with complex relationships that need to be fetched together.
 * A null or empty source-property selection loads all default source properties; a restricted selection
 * also includes the required source join keys.</p>
 *
 * <p>Join entities are populated <i>in place</i>: the loaded related entities are set directly onto the
 * corresponding {@code @JoinedBy} properties of the entity instance returned by each {@code get},
 * {@code getOrNull}, and {@code batchGet} method. When a method accepts a collection of join entity classes,
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
 * User userWithOrders = userDao.getOrNull(userId, Order.class);
 *
 * // Batch get users with their profiles
 * List<User> users = userDao.batchGet(
 *     Arrays.asList(1L, 2L, 3L),
 *     UserProfile.class
 * );
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity
 * @param <TD> the concrete DAO type, bounded by {@link UncheckedDaoBase}, that owns this helper;
 *             the DAO must also implement {@link UncheckedCrudReadOps} (read-only CRUD DAOs qualify)
 * @see UncheckedJoinEntityHelper
 * @see UncheckedCrudDao
 * @see CrudJoinEntityHelper
 * @see com.landawn.abacus.annotation.JoinedBy
 */
sealed interface UncheckedCrudJoinEntityReadOps<T, ID, TD extends UncheckedDaoBase<T, TD>> extends UncheckedJoinEntityReadOps<T, TD>,
        CrudJoinEntityReadOps<T, ID, TD> permits UncheckedCrudJoinEntityHelper, UncheckedReadOnlyCrudJoinEntityHelper {

    /**
     * Retrieves an entity by its ID and loads the specified type of join entities.
     * Only the join properties of the specified class will be loaded; if multiple properties in the entity
     * class are joined to that type, all of them are loaded. The loaded related entities are populated in
     * place on the returned entity instance.
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
     * @param id the entity ID to retrieve
     * @param joinEntityClass the class of the join entities to load
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws IllegalArgumentException if {@code id} or {@code joinEntityClass} is {@code null}, or, when a matching record is found,
     *                                  if no join property of the specified type is found in the entity class,
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return Optional.ofNullable(getOrNull(id, joinEntityClass));
    }

    /**
     * Retrieves an entity by its ID and optionally loads all join entities.
     * When {@code includeAllJoinEntities} is {@code true}, all fields annotated with {@code @JoinedBy} are
     * populated in place on the returned entity; when {@code false}, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with all relationships loaded
     * Optional<User> user = userDao.get(userId, true);
     * // User will have orders, profile, addresses, etc. all loaded
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the entity with join entities loaded as specified, or empty if not found
     * @throws IllegalArgumentException if {@code id} is {@code null},
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final boolean includeAllJoinEntities)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return Optional.ofNullable(getOrNull(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID with only selected properties and loads the specified type of join entities.
     * This method allows for optimized queries by selecting only needed columns from the main entity.
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
     * @param id the entity ID to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClass the class of the join entities to load
     * @return an Optional containing the entity with selected properties and join entities loaded, or empty if not found
     * @throws IllegalArgumentException if {@code id} or {@code joinEntityClass} is {@code null}, or, when a matching record is found,
     *                                  if no join property of the specified type is found in the entity class,
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> sourceSelectPropNames, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return Optional.ofNullable(getOrNull(id, sourceSelectPropNames, joinEntityClass));
    }

    /**
     * Retrieves an entity by its ID with only selected properties and loads multiple types of join entities.
     * This method provides fine-grained control over what data is loaded from the database.
     * The loaded related entities are populated in place on the returned entity instance; if
     * {@code joinEntityClasses} is {@code null} or empty, no join entities are loaded.
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
     * @param id the entity ID to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClasses the collection of join entity classes to load
     * @return an Optional containing the entity with selected properties and specified join entities loaded, or empty if not found
     * @throws IllegalArgumentException if {@code id} is {@code null}, or if {@code joinEntityClasses} contains a {@code null} element,
     *                                  or, when a matching record is found, if no join property is found for one of the specified types
     *                                  in the entity class, or a join being loaded has a disallowed null/default key or multiple rows for a
     *                                  map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> sourceSelectPropNames, final Collection<Class<?>> joinEntityClasses)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return Optional.ofNullable(getOrNull(id, sourceSelectPropNames, joinEntityClasses));
    }

    /**
     * Retrieves an entity by its ID with only selected properties and optionally loads all join entities.
     * Combines property selection with the option to load all relationships. When
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
     * @param id the entity ID to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the entity with selected properties and join entities as specified, or empty if not found
     * @throws IllegalArgumentException if {@code id} is {@code null},
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> sourceSelectPropNames, final boolean includeAllJoinEntities)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return Optional.ofNullable(getOrNull(id, sourceSelectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID and loads the specified type of join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Class)}. The loaded related entities are
     * populated in place on the returned entity instance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with orders, returns null if not found
     * User user = userDao.getOrNull(userId, Order.class);
     * if (user != null) {
     *     // Process user with loaded orders
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param joinEntityClass the class of the join entities to load
     * @return the entity with specified join entities loaded, or {@code null} if not found
     * @throws IllegalArgumentException if {@code id} or {@code joinEntityClass} is {@code null}, or, when a matching record is found,
     *                                  if no join property of the specified type is found in the entity class,
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default T getOrNull(final ID id, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(id, cs.id);
        N.checkArgNotNull(joinEntityClass, cs.joinEntityClass);

        final T result = DaoUtil.getCrudReadOps(this).getOrNull(id);

        if (result != null) {
            loadJoinEntities(result, joinEntityClass);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID and optionally loads all join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, boolean)}. When {@code includeAllJoinEntities}
     * is {@code true}, the loaded entities are populated in place on the returned entity; when {@code false},
     * no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with all relationships
     * User user = userDao.getOrNull(userId, true);
     * if (user != null) {
     *     // All relationships are loaded
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the entity with join entities loaded as specified, or {@code null} if not found
     * @throws IllegalArgumentException if {@code id} is {@code null},
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default T getOrNull(final ID id, final boolean includeAllJoinEntities)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(id, cs.id);

        final T result = DaoUtil.getCrudReadOps(this).getOrNull(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with only selected properties and loads the specified type of join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Collection, Class)}. The loaded related
     * entities are populated in place on the returned entity instance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with minimal data and profile
     * User user = userDao.getOrNull(
     *     userId,
     *     Arrays.asList("id", "name"),
     *     UserProfile.class
     * );
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClass the class of the join entities to load
     * @return the entity with selected properties and join entities loaded, or {@code null} if not found
     * @throws IllegalArgumentException if {@code id} or {@code joinEntityClass} is {@code null}, or, when a matching record is found,
     *                                  if no join property of the specified type is found in the entity class,
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default T getOrNull(final ID id, final Collection<String> sourceSelectPropNames, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(id, cs.id);
        N.checkArgNotNull(joinEntityClass, cs.joinEntityClass);

        final T result = DaoUtil.getCrudReadOps(this).getOrNull(id, DaoUtil.includeSourceJoinPropNames(this, sourceSelectPropNames, joinEntityClass));

        if (result != null) {
            loadJoinEntities(result, joinEntityClass);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with only selected properties and loads multiple types of join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Collection, Collection)}. The loaded related
     * entities are populated in place on the returned entity; if {@code joinEntityClasses} is {@code null} or
     * empty, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with specific fields and multiple relationships
     * User user = userDao.getOrNull(
     *     userId,
     *     Arrays.asList("id", "name", "email"),
     *     Arrays.asList(Order.class, Payment.class, Review.class)
     * );
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClasses the collection of join entity classes to load
     * @return the entity with selected properties and specified join entities loaded, or {@code null} if not found
     * @throws IllegalArgumentException if {@code id} is {@code null}, or if {@code joinEntityClasses} contains a {@code null} element,
     *                                  or, when a matching record is found, if no join property is found for one of the specified types
     *                                  in the entity class, or a join being loaded has a disallowed null/default key or multiple rows for a
     *                                  map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default T getOrNull(final ID id, final Collection<String> sourceSelectPropNames, final Collection<Class<?>> joinEntityClasses)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(id, cs.id);
        N.checkElementNotNull(joinEntityClasses, cs.joinEntityClasses);

        final T result = DaoUtil.getCrudReadOps(this).getOrNull(id, DaoUtil.includeSourceJoinPropNames(this, sourceSelectPropNames, joinEntityClasses));

        if (result != null && N.notEmpty(joinEntityClasses)) {
            for (final Class<?> joinEntityClass : joinEntityClasses) {
                loadJoinEntities(result, joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with only selected properties and optionally loads all join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Collection, boolean)}. When
     * {@code includeAllJoinEntities} is {@code true}, the loaded entities are populated in place on the
     * returned entity; when {@code false}, no join entities are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with core fields and all relationships
     * User user = userDao.getOrNull(
     *     userId,
     *     Arrays.asList("id", "name", "email", "verified"),
     *     true  // load all join entities
     * );
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the entity with selected properties and join entities as specified, or {@code null} if not found
     * @throws IllegalArgumentException if {@code id} is {@code null},
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if more than one record matches the given {@code id}
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default T getOrNull(final ID id, final Collection<String> sourceSelectPropNames, final boolean includeAllJoinEntities)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(id, cs.id);

        final T result = DaoUtil.getCrudReadOps(this)
                .getOrNull(id, includeAllJoinEntities ? DaoUtil.includeAllSourceJoinPropNames(this, sourceSelectPropNames) : sourceSelectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves multiple entities by their IDs and loads the specified type of join entities.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}) for processing. The loaded related entities are populated in place on
     * each returned entity.
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
     * @param ids the collection of IDs to retrieve
     * @param joinEntityClass the class of the join entities to load for each entity
     * @return a list of entities with the specified join entities loaded
     * @throws IllegalArgumentException if {@code joinEntityClass} is {@code null},
     *                                  or if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or, when matching records are found, if no join property of the specified type is found in the
     *                                  entity class, or a join being loaded has a disallowed null/default key or multiple rows for a
     *                                  map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return batchGet(ids, null, joinEntityClass, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs and optionally loads all join entities.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}) for processing. When {@code includeAllJoinEntities} is {@code true}, the
     * loaded entities are populated in place on each returned entity; when {@code false}, no join entities
     * are loaded.
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
     * @param ids the collection of IDs to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of entities with join entities loaded as specified
     * @throws IllegalArgumentException if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return batchGet(ids, null, includeAllJoinEntities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and loads the specified join entities.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}) for processing. The loaded related entities are populated in place on
     * each returned entity.
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
     * @param ids the collection of IDs to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntityClass the class of the join entities to load for each entity
     * @return a list of entities with selected properties and join entities loaded
     * @throws IllegalArgumentException if {@code joinEntityClass} is {@code null},
     *                                  or if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or, when matching records are found, if no join property of the specified type is found in the
     *                                  entity class, or a join being loaded has a disallowed null/default key or multiple rows for a
     *                                  map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> sourceSelectPropNames, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return batchGet(ids, sourceSelectPropNames, joinEntityClass, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and loads multiple types of join entities.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}) for processing. The loaded related entities are populated in place on each
     * returned entity; if {@code joinEntityClasses} is {@code null} or empty, no join entities are loaded.
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
     * @param ids the collection of IDs to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntityClasses the collection of join entity classes to load for each entity
     * @return a list of entities with selected properties and specified join entities loaded
     * @throws IllegalArgumentException if {@code joinEntityClasses} contains a {@code null} element,
     *                                  or if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or, when matching records are found, if no join property is found for one of the specified types
     *                                  in the entity class, or a join being loaded has a disallowed null/default key or multiple rows for
     *                                  a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> sourceSelectPropNames, final Collection<Class<?>> joinEntityClasses)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return batchGet(ids, sourceSelectPropNames, joinEntityClasses, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and optionally loads all join entities.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}) for processing. When {@code includeAllJoinEntities} is {@code true}, the
     * loaded entities are populated in place on each returned entity; when {@code false}, no join entities
     * are loaded.
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
     * @param ids the collection of IDs to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of entities with selected properties and join entities as specified
     * @throws IllegalArgumentException if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> sourceSelectPropNames, final boolean includeAllJoinEntities)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        return batchGet(ids, sourceSelectPropNames, includeAllJoinEntities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and loads the specified join entities.
     * Processes the retrieval in batches of the specified size to handle large ID collections efficiently.
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
     * @param ids the collection of IDs to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntityClass the class of the join entities to load for each entity
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of entities with selected properties and join entities loaded
     * @throws IllegalArgumentException if {@code joinEntityClass} is {@code null}, or {@code batchSize} is not positive,
     *                                  or if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or, when matching records are found, if no join property of the specified type is found in the
     *                                  entity class, or a join being loaded has a disallowed null/default key or multiple rows for a
     *                                  map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> sourceSelectPropNames, final Class<?> joinEntityClass,
            final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(joinEntityClass, cs.joinEntityClass);
        N.checkArgPositive(batchSize, cs.batchSize);

        final List<T> result = DaoUtil.getCrudReadOps(this)
                .batchGet(ids, DaoUtil.includeSourceJoinPropNames(this, sourceSelectPropNames, joinEntityClass), batchSize);

        if (N.notEmpty(result)) {
            if (result.size() <= batchSize) {
                loadJoinEntities(result, joinEntityClass);
            } else {
                N.runByBatch(result, batchSize, batchEntities -> loadJoinEntities(batchEntities, joinEntityClass));
            }
        }

        return result;
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and loads multiple types of join entities.
     * Processes the retrieval in batches of the specified size to handle large ID collections efficiently.
     * The loaded related entities are populated in place on each returned entity; if {@code joinEntityClasses}
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
     * @param ids the collection of IDs to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntityClasses the collection of join entity classes to load for each entity
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of entities with selected properties and specified join entities loaded
     * @throws IllegalArgumentException if {@code joinEntityClasses} contains a {@code null} element,
     *                                  or if {@code batchSize} is not positive,
     *                                  or if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or, when matching records are found, if no join property is found for one of the specified types
     *                                  in the entity class, or a join being loaded has a disallowed null/default key or multiple rows for
     *                                  a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> sourceSelectPropNames, final Collection<Class<?>> joinEntityClasses,
            final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkElementNotNull(joinEntityClasses, cs.joinEntityClasses);
        N.checkArgPositive(batchSize, cs.batchSize);

        final List<T> result = DaoUtil.getCrudReadOps(this)
                .batchGet(ids, DaoUtil.includeSourceJoinPropNames(this, sourceSelectPropNames, joinEntityClasses), batchSize);

        if (N.notEmpty(result) && N.notEmpty(joinEntityClasses)) {
            if (result.size() <= batchSize) {
                for (final Class<?> joinEntityClass : joinEntityClasses) {
                    loadJoinEntities(result, joinEntityClass);
                }
            } else {
                N.runByBatch(result, batchSize, batchEntities -> {
                    for (final Class<?> joinEntityClass : joinEntityClasses) {
                        loadJoinEntities(batchEntities, joinEntityClass);
                    }
                });
            }
        }

        return result;
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and optionally loads all join entities.
     * Processes the retrieval in batches of the specified size to handle large ID collections efficiently.
     * When {@code includeAllJoinEntities} is {@code true}, the loaded entities are populated in place on each
     * returned entity; when {@code false}, no join entities are loaded.
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
     * @param ids the collection of IDs to retrieve
     * @param sourceSelectPropNames the properties (columns) to be selected from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of entities with selected properties and join entities as specified
     * @throws IllegalArgumentException if {@code batchSize} is not positive,
     *                                  or if {@code ids} are {@code EntityId}s/{@code Map}s or entities for a single-id entity,
     *                                  or, for a composite-id entity, if an {@code EntityId} element is {@code null} or has no keys,
     *                                  if {@code Map} and entity elements are mixed, or if every element of {@code ids} is {@code null},
     *                                  or a join being loaded has a disallowed null/default key or multiple rows for a map-valued property
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or executing the SELECT statement, binding its parameters, or reading
     *         its result fails
     * @throws DuplicateResultException if a query batch returns more rows than its number of distinct IDs
     * @throws UnsupportedOperationException if a join property being loaded is read-only
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> sourceSelectPropNames, final boolean includeAllJoinEntities,
            final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgPositive(batchSize, cs.batchSize);

        final List<T> result = DaoUtil.getCrudReadOps(this)
                .batchGet(ids, includeAllJoinEntities ? DaoUtil.includeAllSourceJoinPropNames(this, sourceSelectPropNames) : sourceSelectPropNames, batchSize);

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
