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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream;

/**
 * Read-side view of {@link JoinEntityHelper}: declares the join-entity <i>load</i> operations
 * ({@code findFirst}/{@code findOnlyOne}/{@code list}/{@code stream} with join loading, and the
 * {@code loadJoinEntities}/{@code loadAllJoinEntities}/{@code loadJoinEntitiesIfAbsent} families)
 * together with the internal accessor methods (inherited from {@link JoinEntityBase}) they rely on.
 *
 * <p>This interface contains no operation that modifies the database, so it can be mixed into
 * read-only DAOs (see {@link ReadOnlyJoinEntityHelper}) without exposing any delete capability.</p>
 *
 * <p><b>&#9888; Warning:</b> When selecting only some source properties, include every property used
 * as a join key. Streams are caller-owned and must be closed. Parallel loaders do not propagate the
 * caller's thread-bound transaction and may partially populate entities before a task fails.</p>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 *
 * @see JoinEntityHelper
 * @see JoinEntityDeleteOps
 * @see com.landawn.abacus.annotation.JoinedBy
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface JoinEntityReadOps<T, TD extends DaoBase<T, TD>> extends JoinEntityBase<T, TD>
        permits CrudJoinEntityReadOps, UncheckedJoinEntityReadOps, JoinEntityHelper, ReadOnlyJoinEntityHelper {
    /**
     * Finds the first entity that matches the specified condition and loads the specified type of join entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find first user with their orders loaded
     * Optional<User> user = userDao.findFirst(Arrays.asList("id", "name"), Order.class, Filters.eq("email", "john@example.com"));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClass the class of the join entities to load
     * @param cond the condition to match
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Class<?> joinEntityClass, final Condition cond) throws SQLException {
        final Optional<T> result = DaoUtil.getReadOps(this).findFirst(selectPropNames, cond);

        if (result.isPresent()) {
            loadJoinEntities(result.get(), joinEntityClass);
        }

        return result;
    }

    /**
     * Finds the first entity that matches the specified condition and loads multiple types of join entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find first user with both orders and addresses loaded
     * Optional<User> user = userDao.findFirst(null, Arrays.asList(Order.class, Address.class), Filters.eq("id", 1L));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load.
     *                          If {@code null} or empty, no join entities are loaded and the matched entity is returned as-is
     * @param cond the condition to match
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property is found for one of the specified types in the entity class
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws SQLException {
        final Optional<T> result = DaoUtil.getReadOps(this).findFirst(selectPropNames, cond);

        if (result.isPresent() && N.notEmpty(joinEntitiesToLoad)) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Finds the first entity that matches the specified condition, optionally loading all join entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find first user with all join entities loaded
     * Optional<User> user = userDao.findFirst(null, true, Filters.eq("status", "active"));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param cond the condition to match
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws SQLException {
        final Optional<T> result = DaoUtil.getReadOps(this).findFirst(selectPropNames, cond);

        if (includeAllJoinEntities && result.isPresent()) {
            loadAllJoinEntities(result.get());
        }

        return result;
    }

    /**
     * Finds the only entity that matches the specified condition and loads the specified type of join entities.
     * Throws an exception if more than one entity matches the condition.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find the only user with specific email and load their orders
     * Optional<User> user = userDao.findOnlyOne(null, Order.class, Filters.eq("email", "unique@example.com"));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClass the class of the join entities to load
     * @param cond the condition to match
     * @return an {@code Optional} containing the only matching entity with join entities loaded, or empty if no match
     * @throws DuplicateResultException if more than one record is found by the specified condition
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Class<?> joinEntityClass, final Condition cond)
            throws DuplicateResultException, SQLException {
        final Optional<T> result = DaoUtil.getReadOps(this).findOnlyOne(selectPropNames, cond);

        if (result.isPresent()) {
            loadJoinEntities(result.get(), joinEntityClass);
        }

        return result;
    }

    /**
     * Finds the only entity that matches the specified condition and loads multiple types of join entities.
     * Throws an exception if more than one entity matches the condition.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find the only user with specific ID and load multiple join entities
     * Optional<User> user = userDao.findOnlyOne(null, Arrays.asList(Order.class, Address.class), Filters.eq("id", 1L));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load.
     *                          If {@code null} or empty, no join entities are loaded and the matched entity is returned as-is
     * @param cond the condition to match
     * @return an {@code Optional} containing the only matching entity with join entities loaded, or empty if no match
     * @throws DuplicateResultException if more than one record is found by the specified condition
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property is found for one of the specified types in the entity class
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws DuplicateResultException, SQLException {
        final Optional<T> result = DaoUtil.getReadOps(this).findOnlyOne(selectPropNames, cond);

        if (result.isPresent() && N.notEmpty(joinEntitiesToLoad)) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Finds the only entity that matches the specified condition, optionally loading all join entities.
     * Throws an exception if more than one entity matches the condition.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find the only active user and load all their join entities
     * Optional<User> user = userDao.findOnlyOne(Arrays.asList("id", "name", "email"), true, Filters.eq("status", "active"));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param cond the condition to match
     * @return an {@code Optional} containing the only matching entity with join entities loaded, or empty if no match
     * @throws DuplicateResultException if more than one record is found by the specified condition
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond)
            throws DuplicateResultException, SQLException {
        final Optional<T> result = DaoUtil.getReadOps(this).findOnlyOne(selectPropNames, cond);

        if (includeAllJoinEntities && result.isPresent()) {
            loadAllJoinEntities(result.get());
        }

        return result;
    }

    /**
     * Retrieves a list of entities that match the specified condition and loads the specified type of join entities for each.
     * For result sets larger than {@link JdbcUtil#DEFAULT_BATCH_SIZE}, the join-entity loading is performed in batches.
     * This is a beta API and may change in a future release.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get all active users with their orders loaded
     * List<User> users = userDao.list(null, Order.class, Filters.eq("status", "active"));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClass the class of the join entities to load
     * @param cond the condition to match
     * @return a list of entities matching the condition with the specified join entities loaded
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntityClass, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getReadOps(this).list(selectPropNames, cond);

        if (N.notEmpty(result)) {
            if (result.size() <= JdbcUtil.DEFAULT_BATCH_SIZE) {
                loadJoinEntities(result, joinEntityClass);
            } else {
                N.runByBatch(result, JdbcUtil.DEFAULT_BATCH_SIZE, batchEntities -> loadJoinEntities(batchEntities, joinEntityClass));
            }
        }

        return result;
    }

    /**
     * Retrieves a list of entities that match the specified condition and loads multiple types of join entities for each.
     * For result sets larger than {@link JdbcUtil#DEFAULT_BATCH_SIZE}, the join-entity loading is performed in batches.
     * This is a beta API and may change in a future release.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get all users with both orders and addresses loaded
     * List<User> users = userDao.list(null, Arrays.asList(Order.class, Address.class), Filters.gt("createdDate", lastWeek));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load.
     *                          If {@code null} or empty, no join entities are loaded and the matched entities are returned as-is
     * @param cond the condition to match
     * @return a list of entities matching the condition with the specified join entities loaded
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property is found for one of the specified types in the entity class
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getReadOps(this).list(selectPropNames, cond);

        if (N.notEmpty(result) && N.notEmpty(joinEntitiesToLoad)) {
            if (result.size() <= JdbcUtil.DEFAULT_BATCH_SIZE) {
                for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result, joinEntityClass);
                }
            } else {
                N.runByBatch(result, JdbcUtil.DEFAULT_BATCH_SIZE, batchEntities -> {
                    for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(batchEntities, joinEntityClass);
                    }
                });
            }
        }

        return result;
    }

    /**
     * Retrieves a list of entities that match the specified condition, optionally loading all join entities.
     * For result sets larger than {@link JdbcUtil#DEFAULT_BATCH_SIZE}, the join-entity loading is performed in batches.
     * This is a beta API and may change in a future release.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get all premium users with all their related data loaded
     * List<User> users = userDao.list(Arrays.asList("id", "name", "email"), true, Filters.eq("membershipType", "premium"));
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param cond the condition to match
     * @return a list of entities matching the condition with join entities loaded as specified
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getReadOps(this).list(selectPropNames, cond);

        if (includeAllJoinEntities && N.notEmpty(result)) {
            if (result.size() <= JdbcUtil.DEFAULT_BATCH_SIZE) {
                loadAllJoinEntities(result);
            } else {
                N.runByBatch(result, JdbcUtil.DEFAULT_BATCH_SIZE, this::loadAllJoinEntities);
            }
        }

        return result;
    }

    /**
     * Streams entities that match the specified condition and loads the specified type of join entities for each.
     * The stream processes entities in batches of {@link JdbcUtil#DEFAULT_BATCH_SIZE} for efficient memory usage.
     * Join entities are populated in place on the streamed entities. Any {@link SQLException} thrown while loading
     * join entities during stream consumption is wrapped as an {@link UncheckedSQLException}; if no join property of
     * the specified type is found, an {@link IllegalArgumentException} is thrown during stream consumption.
     * This is a beta API and may change in a future release.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream all users and load their orders, processing in batches
     * try (Stream<User> users = userDao.stream(null, Order.class, Filters.alwaysTrue())) {
     *     users.filter(user -> user.getOrders().size() > 5)
     *          .forEach(user -> processUserWithManyOrders(user));
     * }
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntityClass the class of the join entities to load
     * @param cond the condition to match
     * @return a {@code Stream} of entities matching the condition with join entities loaded
     */
    @Beta
    default Stream<T> stream(final Collection<String> selectPropNames, final Class<?> joinEntityClass, final Condition cond) {
        return DaoUtil.getReadOps(this)
                .stream(selectPropNames, cond) //
                .split(JdbcUtil.DEFAULT_BATCH_SIZE)
                .onEach(batchEntities -> {
                    try {
                        loadJoinEntities(batchEntities, joinEntityClass);
                    } catch (final SQLException e) {
                        throw new UncheckedSQLException(e);
                    }
                })
                .flatmap(Fn.identity());
    }

    /**
     * Streams entities that match the specified condition and loads multiple types of join entities for each.
     * The stream processes entities in batches of {@link JdbcUtil#DEFAULT_BATCH_SIZE} for efficient memory usage.
     * Join entities are populated in place on the streamed entities. Any {@link SQLException} thrown while loading
     * join entities during stream consumption is wrapped as an {@link UncheckedSQLException}; if no join property is
     * found for one of the specified types, an {@link IllegalArgumentException} is thrown during stream consumption.
     * If {@code joinEntitiesToLoad} is {@code null} or empty, the underlying entity stream is returned unmodified.
     * This is a beta API and may change in a future release.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream users with multiple join entities loaded
     * try (Stream<User> users = userDao.stream(null, Arrays.asList(Order.class, Address.class, PaymentMethod.class), Filters.eq("country", "US"))) {
     *     users.forEach(user -> analyzeUserProfile(user));
     * }
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load.
     *                          If {@code null} or empty, no join entities are loaded
     * @param cond the condition to match
     * @return a {@code Stream} of entities matching the condition with join entities loaded
     */
    @Beta
    default Stream<T> stream(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond) {
        if (N.isEmpty(joinEntitiesToLoad)) {
            return DaoUtil.getReadOps(this).stream(selectPropNames, cond);
        }

        return DaoUtil.getReadOps(this)
                .stream(selectPropNames, cond)
                .split(JdbcUtil.DEFAULT_BATCH_SIZE) //
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
     * Streams entities that match the specified condition, optionally loading all join entities.
     * When {@code includeAllJoinEntities} is {@code true}, the stream processes entities in batches of
     * {@link JdbcUtil#DEFAULT_BATCH_SIZE} for efficient memory usage; any {@link SQLException} thrown
     * while loading join entities during stream consumption is wrapped as an {@link UncheckedSQLException}.
     * When {@code includeAllJoinEntities} is {@code false}, the underlying entity stream is returned without modification.
     * This is a beta API and may change in a future release.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Stream all entities with complete data
     * try (Stream<User> users = userDao.stream(null, true, Filters.alwaysTrue())) {
     *     users.limit(100)
     *          .forEach(user -> exportUserData(user));
     * }
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param cond the condition to match
     * @return a {@code Stream} of entities matching the condition with join entities loaded as specified
     */
    @Beta
    default Stream<T> stream(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) {
        if (includeAllJoinEntities) {
            return DaoUtil.getReadOps(this)
                    .stream(selectPropNames, cond)
                    .split(JdbcUtil.DEFAULT_BATCH_SIZE) //
                    .onEach(batchEntities -> {
                        try {
                            loadAllJoinEntities(batchEntities);
                        } catch (final SQLException e) {
                            throw new UncheckedSQLException(e);
                        }
                    })
                    .flatmap(Fn.identity());

        } else {
            return DaoUtil.getReadOps(this).stream(selectPropNames, cond);
        }
    }

    /**
     * Loads join entities of the specified type for a single entity.
     * If multiple properties in the entity class are joined to the specified type, all of them will be loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * userDao.loadJoinEntities(user, Order.class);
     * // Now user.getOrders() contains the loaded orders
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntities(entity, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified type for a single entity with specific property selection.
     * If multiple properties in the entity class are joined to the specified type, all of them will be loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Load only specific fields from orders
     * userDao.loadJoinEntities(user, Order.class, Arrays.asList("id", "totalAmount", "orderDate"));
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities of the specified type for a collection of entities.
     * If multiple properties in the entity class are joined to the specified type, all of them will be loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.in("id", Arrays.asList(1L, 2L, 3L)));
     * userDao.loadJoinEntities(users, Order.class);
     * // All users now have their orders loaded
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities.
     *                 If {@code null} or empty, this method returns immediately
     * @param joinEntityClass the class of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntities(entities, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified type for a collection of entities with specific property selection.
     * If multiple properties in the entity class are joined to the specified type, all of them will be loaded.
     * The loaded join entities are populated in place on each entity in the collection.
     * If {@code entities} is {@code null} or empty, this method returns immediately without performing any query.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("status", "active"));
     * // Load only essential order information
     * userDao.loadJoinEntities(users, Order.class, Arrays.asList("id", "totalAmount"));
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities.
     *                 If {@code null} or empty, this method returns immediately
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities for a single entity by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * userDao.loadJoinEntities(user, "orders");
     * // The 'orders' property is now populated
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropName the property name of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    default void loadJoinEntities(final T entity, final String joinEntityPropName) throws SQLException {
        loadJoinEntities(entity, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a single entity by property name with specific property selection.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     * This is an abstract method whose implementation is provided by the generated DAO.
     *
     * <p>This method is the core implementation for loading join entities. It queries the database
     * for related entities based on the join relationship defined in the {@code @JoinedBy} annotation
     * and populates the specified property in the entity.</p>
     *
     * <p>The implementation should handle both collection-type properties (List, Set, etc.) and
     * single-entity properties. For collection types, all matching join entities are loaded into
     * the collection. For single entities, only one matching entity is loaded.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Load only specific fields from the 'addresses' join entity
     * userDao.loadJoinEntities(user, "addresses", Arrays.asList("street", "city", "zipCode"));
     *
     * // Load all fields from 'orders' join entity
     * userDao.loadJoinEntities(user, "orders", null);
     * }</pre>
     *
     * @param entity the entity for which to load join entities. Must not be {@code null}
     * @param joinEntityPropName the property name of the join entities to load. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected.
     *                       This parameter is useful for performance optimization when only
     *                       specific fields are needed
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

    /**
     * Loads join entities for a collection of entities by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("country", "US"));
     * userDao.loadJoinEntities(users, "paymentMethods");
     * // All users now have their payment methods loaded
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropName the property name of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
        loadJoinEntities(entities, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a collection of entities by property name with specific property selection.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     * This is an abstract method whose implementation is provided by the generated DAO.
     *
     * <p>This method is the core batch implementation for loading join entities. It efficiently loads
     * related entities for multiple parent entities in a single operation, avoiding the N+1 query problem.
     * The implementation typically uses an IN clause to fetch all related entities in one query, then
     * distributes them to the appropriate parent entities based on the foreign key relationship.</p>
     *
     * <p>Performance characteristics:</p>
     * <ul>
     *   <li>For N parent entities, this method executes O(1) queries instead of O(N)</li>
     *   <li>Large collections may be automatically batched to prevent excessive memory usage</li>
     *   <li>Selecting fewer properties via {@code selectPropNames} can significantly improve performance</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.between("createdDate", startDate, endDate));
     * // Load only essential fields from addresses
     * userDao.loadJoinEntities(users, "addresses", Arrays.asList("city", "country"));
     *
     * // Load all fields from orders for multiple users
     * userDao.loadJoinEntities(users, "orders", null);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities.
     *                 If {@code null} or empty, no join entities are loaded
     * @param joinEntityPropName the property name of the join entities to load. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected.
     *                       Specifying only needed properties can significantly improve query
     *                       performance and reduce memory usage
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException;

    /**
     * Loads multiple join entities for a single entity by property names.
     * Each property name must correspond to a field annotated with {@code @JoinedBy}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * userDao.loadJoinEntities(user, Arrays.asList("orders", "addresses", "paymentMethods"));
     * // Multiple join entities are now loaded
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load.
     *                            If {@code null} or empty, this method returns immediately
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
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
     * Loads multiple join entities for a single entity with optional parallel execution.
     * When parallel execution is enabled, join entities are loaded concurrently for better performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Load multiple join entities in parallel
     * userDao.loadJoinEntities(user, Arrays.asList("orders", "addresses", "reviews"), true);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadJoinEntities(entity, joinEntityPropNames, executor());
        } else {
            loadJoinEntities(entity, joinEntityPropNames);
        }
    }

    /**
     * Loads multiple join entities for a single entity using a custom executor for parallel execution.
     * This method provides fine-grained control over the threading behavior.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService customExecutor = Executors.newFixedThreadPool(4);
     * User user = userDao.get(1L).orElseThrow();
     * userDao.loadJoinEntities(user, Arrays.asList("orders", "addresses"), customExecutor);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
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
     * Loads multiple join entities for a collection of entities by property names.
     * Each property name must correspond to a field annotated with {@code @JoinedBy}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("accountType", "premium"));
     * userDao.loadJoinEntities(users, Arrays.asList("orders", "subscriptions"));
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities.
     *                 If {@code null} or empty, this method returns immediately
     * @param joinEntityPropNames the property names of the join entities to load.
     *                            If {@code null} or empty, this method returns immediately
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
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
     * Loads multiple join entities for a collection of entities with optional parallel execution.
     * When parallel execution is enabled, different join entity types are loaded concurrently.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.in("id", userIds));
     * // Load multiple join entity types in parallel for better performance
     * userDao.loadJoinEntities(users, Arrays.asList("orders", "addresses", "reviews"), true);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadJoinEntities(entities, joinEntityPropNames, executor());
        } else {
            loadJoinEntities(entities, joinEntityPropNames);
        }
    }

    /**
     * Loads multiple join entities for a collection of entities using a custom executor for parallel execution.
     * This method provides fine-grained control over the threading behavior when loading multiple join entity types.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService customExecutor = Executors.newCachedThreadPool();
     * List<User> users = userDao.list(Filters.alwaysTrue());
     * userDao.loadJoinEntities(users, Arrays.asList("orders", "addresses", "reviews"), customExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
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
     * Loads all join entities for a single entity.
     * This method loads all properties annotated with {@code @JoinedBy} in the entity class, populating them in place.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * userDao.loadAllJoinEntities(user);
     * // All join entities (orders, addresses, etc.) are now loaded
     * }</pre>
     *
     * @param entity the entity for which to load all join entities
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default void loadAllJoinEntities(final T entity) throws SQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities for a single entity with optional parallel execution.
     * When parallel execution is enabled, all join entities are loaded concurrently for better performance.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Load all join entities in parallel
     * userDao.loadAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity for which to load all join entities
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadAllJoinEntities(entity, executor());
        } else {
            loadAllJoinEntities(entity);
        }
    }

    /**
     * Loads all join entities for a single entity using a custom executor for parallel execution.
     * This method provides fine-grained control over the threading behavior when loading all join entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ForkJoinPool customPool = new ForkJoinPool(8);
     * User user = userDao.get(1L).orElseThrow();
     * userDao.loadAllJoinEntities(user, customPool);
     * }</pre>
     *
     * @param entity the entity for which to load all join entities
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntities(final T entity, final Executor executor) throws SQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads all join entities for a collection of entities.
     * This method loads all properties annotated with {@code @JoinedBy} in the entity class for each entity,
     * populating them in place. If {@code entities} is {@code null} or empty, this method returns immediately.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("status", "active"));
     * userDao.loadAllJoinEntities(users);
     * // All join entities are loaded for all users
     * }</pre>
     *
     * @param entities the collection of entities for which to load all join entities.
     *                 If {@code null} or empty, this method returns immediately
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default void loadAllJoinEntities(final Collection<T> entities) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities for a collection of entities with optional parallel execution.
     * When parallel execution is enabled, different join entity types are loaded concurrently.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.in("id", largeUserIdList));
     * // Load all join entities in parallel for better performance
     * userDao.loadAllJoinEntities(users, true);
     * }</pre>
     *
     * @param entities the collection of entities for which to load all join entities
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadAllJoinEntities(entities, executor());
        } else {
            loadAllJoinEntities(entities);
        }
    }

    /**
     * Loads all join entities for a collection of entities using a custom executor for parallel execution.
     * This method provides fine-grained control over the threading behavior when loading all join entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService batchExecutor = Executors.newWorkStealingPool();
     * List<User> users = userDao.list(Filters.alwaysTrue());
     * userDao.loadAllJoinEntities(users, batchExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to load all join entities.
     *                 If {@code null} or empty, this method returns immediately
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads join entities of the specified type for a single entity only if the corresponding join properties are currently {@code null}.
     * If multiple properties in the entity class are joined to the specified type, only those whose value is {@code null} are loaded.
     * This method is useful for lazy loading scenarios.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Load orders only if not already loaded
     * userDao.loadJoinEntitiesIfAbsent(user, Order.class);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final T entity, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntitiesIfAbsent(entity, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified type for a single entity only if the corresponding join properties are currently {@code null},
     * with specific property selection.
     * If multiple properties in the entity class are joined to the specified type, only those whose value is {@code null} are loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Load addresses with specific fields only if not already loaded
     * userDao.loadJoinEntitiesIfAbsent(user, Address.class, Arrays.asList("street", "city"));
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws SQLException {
        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfAbsent(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities of the specified type for a collection of entities only if the corresponding join properties are currently {@code null}.
     * For each join property in the entity class joined to the specified type, only entities whose value for that property is {@code null}
     * will have their join entities loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load orders only for users who don't have them loaded yet
     * userDao.loadJoinEntitiesIfAbsent(users, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityClass the class of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        loadJoinEntitiesIfAbsent(entities, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified type for a collection of entities only if the corresponding join properties are currently {@code null},
     * with specific property selection.
     * For each join property in the entity class joined to the specified type, only entities whose value for that property is {@code null}
     * will have their join entities loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load payment methods with limited fields for users who don't have them loaded
     * userDao.loadJoinEntitiesIfAbsent(users, PaymentMethod.class, Arrays.asList("type", "lastFourDigits"));
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final Class<?> joinEntityClass, final Collection<String> selectPropNames)
            throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfAbsent(entities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities for a single entity by property name only if the property is currently {@code null}.
     * This method is useful for lazy loading specific join properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Load orders only if not already loaded
     * userDao.loadJoinEntitiesIfAbsent(user, "orders");
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropName the property name of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the specified {@code joinEntityPropName} does not exist in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final T entity, final String joinEntityPropName) throws SQLException {
        loadJoinEntitiesIfAbsent(entity, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a single entity by property name only if the property is currently {@code null},
     * with specific property selection.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Load addresses with specific fields only if not already loaded
     * userDao.loadJoinEntitiesIfAbsent(user, "addresses", Arrays.asList("city", "country"));
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropName the property name of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the specified {@code joinEntityPropName} does not exist in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws SQLException {
        final Class<?> cls = entity.getClass();
        final PropInfo propInfo = ParserUtil.getBeanInfo(cls).getPropInfo(joinEntityPropName);

        if (propInfo == null) {
            throw new IllegalArgumentException("No property found by name: \"" + joinEntityPropName + "\" in class: " + ClassUtil.getCanonicalClassName(cls));
        }

        if (propInfo.getPropValue(entity) == null) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities for a collection of entities by property name only if the property is currently {@code null}.
     * Only entities with {@code null} values for the specified property will have their join entities loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load reviews only for users who don't have them loaded yet
     * userDao.loadJoinEntitiesIfAbsent(users, "reviews");
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropName the property name of the join entities to load
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the specified {@code joinEntityPropName} does not exist in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final String joinEntityPropName) throws SQLException {
        loadJoinEntitiesIfAbsent(entities, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a collection of entities by property name only if the property is currently {@code null},
     * with specific property selection.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load subscriptions with limited fields for users who don't have them loaded
     * userDao.loadJoinEntitiesIfAbsent(users, "subscriptions", Arrays.asList("planType", "expiryDate"));
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities.
     *                 If {@code null} or empty, this method returns immediately
     * @param joinEntityPropName the property name of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the specified {@code joinEntityPropName} does not exist in the entity class,
     *                                  or if the first element of {@code entities} is {@code null}
     */
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
            throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        final T first = N.firstOrNullIfEmpty(entities);
        N.checkArgNotNull(first, "The first element in the specified collection 'entities' cannot be null");

        final Class<?> cls = first.getClass();
        final PropInfo propInfo = ParserUtil.getBeanInfo(cls).getPropInfo(joinEntityPropName);

        if (propInfo == null) {
            throw new IllegalArgumentException("No property found by name: \"" + joinEntityPropName + "\" in class: " + ClassUtil.getCanonicalClassName(cls));
        }

        final List<T> newEntities = N.filter(entities, entity -> propInfo.getPropValue(entity) == null);

        if (N.notEmpty(newEntities)) {
            loadJoinEntities(newEntities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads multiple join entities for a single entity by property names only if they are currently {@code null}.
     * Only properties with {@code null} values will have their join entities loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getPartiallyLoadedUser();
     * // Load only the join entities that haven't been loaded yet
     * userDao.loadJoinEntitiesIfAbsent(user, Arrays.asList("orders", "addresses", "reviews"));
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load.
     *                            If {@code null} or empty, this method returns immediately
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    default void loadJoinEntitiesIfAbsent(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfAbsent(entity, joinEntityPropName);
        }
    }

    /**
     * Loads multiple join entities for a single entity only if they are currently {@code null},
     * with optional parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getPartiallyLoadedUser();
     * // Load missing join entities in parallel
     * userDao.loadJoinEntitiesIfAbsent(user, Arrays.asList("orders", "addresses", "reviews"), true);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntitiesIfAbsent(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadJoinEntitiesIfAbsent(entity, joinEntityPropNames, executor());
        } else {
            loadJoinEntitiesIfAbsent(entity, joinEntityPropNames);
        }
    }

    /**
     * Loads multiple join entities for a single entity only if they are currently {@code null},
     * using a custom executor for parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService lazyLoadExecutor = Executors.newFixedThreadPool(3);
     * User user = getPartiallyLoadedUser();
     * userDao.loadJoinEntitiesIfAbsent(user, Arrays.asList("orders", "addresses"), lazyLoadExecutor);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    @Beta
    default void loadJoinEntitiesIfAbsent(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .filter(joinEntityPropName -> Beans.getPropValue(entity, joinEntityPropName) == null)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfAbsent(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     * Loads multiple join entities for a collection of entities by property names only if they are currently {@code null}.
     * Only properties with {@code null} values will have their join entities loaded.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load only missing join entities for all users
     * userDao.loadJoinEntitiesIfAbsent(users, Arrays.asList("orders", "addresses"));
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities.
     *                 If {@code null} or empty, this method returns immediately
     * @param joinEntityPropNames the property names of the join entities to load.
     *                            If {@code null} or empty, this method returns immediately
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfAbsent(entities, joinEntityPropName);
        }
    }

    /**
     * Loads multiple join entities for a collection of entities only if they are currently {@code null},
     * with optional parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load missing join entities in parallel for better performance
     * userDao.loadJoinEntitiesIfAbsent(users, Arrays.asList("orders", "addresses", "reviews"), true);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws SQLException {
        if (inParallel) {
            loadJoinEntitiesIfAbsent(entities, joinEntityPropNames, executor());
        } else {
            loadJoinEntitiesIfAbsent(entities, joinEntityPropNames);
        }
    }

    /**
     * Loads multiple join entities for a collection of entities only if they are currently {@code null},
     * using a custom executor for parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService batchLazyLoader = Executors.newWorkStealingPool();
     * List<User> users = getPartiallyLoadedUsers();
     * userDao.loadJoinEntitiesIfAbsent(users, Arrays.asList("orders", "addresses"), batchLazyLoader);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load. If {@code null} or empty, this method returns immediately
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any of the {@code joinEntityPropNames} does not exist or is not properly annotated with {@code @JoinedBy}
     */
    @Beta
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfAbsent(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.complete(futures);
    }

    /**
     * Loads all join entities for a single entity only if they are currently {@code null}.
     * This method checks all properties annotated with {@code @JoinedBy} and loads only those that are {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getPartiallyLoadedUser();
     * // Load all missing join entities
     * userDao.loadAllJoinEntitiesIfAbsent(user);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default void loadAllJoinEntitiesIfAbsent(final T entity) throws SQLException {
        loadJoinEntitiesIfAbsent(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities for a single entity only if they are currently {@code null},
     * with optional parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getPartiallyLoadedUser();
     * // Load all missing join entities in parallel
     * userDao.loadAllJoinEntitiesIfAbsent(user, true);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntitiesIfAbsent(final T entity, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadAllJoinEntitiesIfAbsent(entity, executor());
        } else {
            loadAllJoinEntitiesIfAbsent(entity);
        }
    }

    /**
     * Loads all join entities for a single entity only if they are currently {@code null},
     * using a custom executor for parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService customExecutor = Executors.newCachedThreadPool();
     * User user = getPartiallyLoadedUser();
     * userDao.loadAllJoinEntitiesIfAbsent(user, customExecutor);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntitiesIfAbsent(final T entity, final Executor executor) throws SQLException {
        loadJoinEntitiesIfAbsent(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads all join entities for a collection of entities only if they are currently {@code null}.
     * This method checks all properties annotated with {@code @JoinedBy} and loads only those that are {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load all missing join entities for all users
     * userDao.loadAllJoinEntitiesIfAbsent(users);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default void loadAllJoinEntitiesIfAbsent(final Collection<T> entities) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfAbsent(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities for a collection of entities only if they are currently {@code null},
     * with optional parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load all missing join entities in parallel for better performance
     * userDao.loadAllJoinEntitiesIfAbsent(users, true);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntitiesIfAbsent(final Collection<T> entities, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadAllJoinEntitiesIfAbsent(entities, executor());
        } else {
            loadAllJoinEntitiesIfAbsent(entities);
        }
    }

    /**
     * Loads all join entities for a collection of entities only if they are currently {@code null},
     * using a custom executor for parallel execution.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService batchExecutor = Executors.newWorkStealingPool();
     * List<User> users = getPartiallyLoadedUsers();
     * userDao.loadAllJoinEntitiesIfAbsent(users, batchExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadAllJoinEntitiesIfAbsent(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfAbsent(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

}
