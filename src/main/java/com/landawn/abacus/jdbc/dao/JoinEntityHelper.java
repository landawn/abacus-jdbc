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
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
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
 * Interface for handling join entities in database operations. This helper provides methods to load and delete
 * related entities that are joined to a main entity through foreign key relationships.
 *
 * <p>The interface supports both eager and lazy loading of join entities, allowing for efficient data retrieval
 * strategies. It also provides parallel execution capabilities for performance optimization when dealing with
 * multiple join operations.</p>
 *
 * <p>Join entities are typically defined using the {@code @JoinedBy} annotation on entity properties.</p>
 *
 * <p>A <i>join entity property</i> is a property of the main entity, annotated with {@code @JoinedBy}, whose value
 * holds the related entity (for a single-valued property) or related entities (for a {@code Collection}-valued
 * property). The {@code loadXxx} methods <b>populate these properties in place</b> on the entity (or entities)
 * passed to them; they do not create or return new main-entity objects. The {@code findXxx}/{@code list}/
 * {@code stream} methods first fetch the matching main entities and then populate the requested join properties on
 * those returned objects. The {@code deleteXxx} methods delete the related rows from the database but leave the
 * in-memory join properties of the passed entity (or entities) unchanged.</p>
 *
 * <p>Methods that accept a {@code selectPropNames} argument select only the listed columns: from the main entity for
 * the {@code findXxx}/{@code list}/{@code stream} methods, or from the join entity for the {@code loadXxx} methods.
 * A {@code null} {@code selectPropNames} selects all columns. Most operations declare the checked
 * {@link SQLException}; the {@code stream} methods instead wrap any {@link SQLException} raised while loading join
 * entities during stream consumption in an {@link UncheckedSQLException}. Methods throw
 * {@link IllegalArgumentException} when a referenced join property (by type or by name) cannot be resolved.</p>
 *
 * <p><b>Example usage:</b></p>
 * <pre>{@code
 * // Define entities with join relationships
 * public class User {
 *     private Long id;
 *     private String name;
 *
 *     @JoinedBy("id=Order.userId")
 *     private List<Order> orders;
 * }
 *
 * // Find the first matching user and eagerly load their orders in one call
 * Optional<User> user = userDao.findFirst(null, Order.class, Filters.eq("id", 1L));
 *
 * // List users without join entities, then load orders for all of them separately
 * List<User> users = userDao.list(Filters.gt("id", 0));
 * userDao.loadJoinEntities(users, "orders");
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 *
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public interface JoinEntityHelper<T, TD extends Dao<T, TD>> {
    /**
     * Retrieves the class type of the target DAO interface.
     * Internal use only.
     *
     * @return the class type of the target DAO interface
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<TD> targetDaoInterface();

    /**
     * Retrieves the class type of the target entity.
     * Internal use only.
     *
     * @return the class type of the target entity
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<T> targetEntityClass();

    /**
     * Retrieves the name of the target table.
     * Internal use only.
     *
     * @return the name of the target table
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    String targetTableName();

    /**
     * Retrieves the executor for executing tasks in parallel.
     * Internal use only.
     *
     * @return the executor for executing parallel tasks
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Executor executor();

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
     * @param joinEntitiesToLoad the class of the join entities to load
     * @param cond the condition to match
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (result.isPresent()) {
            loadJoinEntities(result.get(), joinEntitiesToLoad);
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
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

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
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

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
     * @param joinEntitiesToLoad the class of the join entities to load
     * @param cond the condition to match
     * @return an {@code Optional} containing the only matching entity with join entities loaded, or empty if no match
     * @throws DuplicateResultException if more than one record is found by the specified condition
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond)
            throws DuplicateResultException, SQLException {
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

        if (result.isPresent()) {
            loadJoinEntities(result.get(), joinEntitiesToLoad);
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
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

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
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

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
     * @param joinEntitiesToLoad the class of the join entities to load
     * @param cond the condition to match
     * @return a list of entities matching the condition with the specified join entities loaded
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @Beta
    default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws SQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notEmpty(result)) {
            if (result.size() <= JdbcUtil.DEFAULT_BATCH_SIZE) {
                loadJoinEntities(result, joinEntitiesToLoad);
            } else {
                N.runByBatch(result, JdbcUtil.DEFAULT_BATCH_SIZE, batchEntities -> loadJoinEntities(batchEntities, joinEntitiesToLoad));
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
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

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
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

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
     * @param joinEntitiesToLoad the class of the join entities to load
     * @param cond the condition to match
     * @return a {@code Stream} of entities matching the condition with join entities loaded
     */
    @Beta
    default Stream<T> stream(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) {
        return DaoUtil.getDao(this)
                .stream(selectPropNames, cond) //
                .split(JdbcUtil.DEFAULT_BATCH_SIZE)
                .onEach(batchEntities -> {
                    try {
                        loadJoinEntities(batchEntities, joinEntitiesToLoad);
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
            return DaoUtil.getDao(this).stream(selectPropNames, cond);
        }

        return DaoUtil.getDao(this)
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
            return DaoUtil.getDao(this)
                    .stream(selectPropNames, cond)
                    .split(JdbcUtil.DEFAULT_BATCH_SIZE) //
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
     * @param entities the collection of entities for which to load join entities
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
     * @param entities the collection of entities for which to load join entities. Can be empty
     *                 but not {@code null}. If empty, this method returns immediately
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param entities the collection of entities for which to load all join entities
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

        if (joinEntityPropNames.size() == 1) {
            loadJoinEntitiesIfAbsent(entities, joinEntityPropNames.get(0), selectPropNames);
        } else {
            for (final String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfAbsent(entities, joinEntityPropName, selectPropNames);
            }
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
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropName the property name of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the specified {@code joinEntityPropName} does not exist in the entity class
     */
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames)
            throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        final Class<?> cls = N.firstOrNullIfEmpty(entities).getClass();
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
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
     * @param entities the collection of entities for which to load join entities
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * @param joinEntityPropNames the property names of the join entities to load
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
     * userDao.loadJoinEntitiesIfAbsent(user);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default void loadJoinEntitiesIfAbsent(final T entity) throws SQLException {
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
     * userDao.loadJoinEntitiesIfAbsent(user, true);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntitiesIfAbsent(final T entity, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadJoinEntitiesIfAbsent(entity, executor());
        } else {
            loadJoinEntitiesIfAbsent(entity);
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
     * userDao.loadJoinEntitiesIfAbsent(user, customExecutor);
     * }</pre>
     *
     * @param entity the entity for which to load join entities
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntitiesIfAbsent(final T entity, final Executor executor) throws SQLException {
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
     * userDao.loadJoinEntitiesIfAbsent(users);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities) throws SQLException {
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
     * userDao.loadJoinEntitiesIfAbsent(users, true);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param inParallel if {@code true}, join entities will be loaded in parallel
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final boolean inParallel) throws SQLException {
        if (inParallel) {
            loadJoinEntitiesIfAbsent(entities, executor());
        } else {
            loadJoinEntitiesIfAbsent(entities);
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
     * userDao.loadJoinEntitiesIfAbsent(users, batchExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to load join entities
     * @param executor the executor to use for parallel loading
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void loadJoinEntitiesIfAbsent(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isEmpty(entities)) {
            return;
        }

        loadJoinEntitiesIfAbsent(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Deletes all join entities of the specified type for a single entity.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of {@code entity} are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all orders associated with the user
     * int deletedCount = userDao.deleteJoinEntities(user, Order.class);
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityClass the class of the join entities to delete
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, joinEntityPropNames.get(0));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getDao(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entity, joinEntityPropName));
                }

                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     * Deletes all join entities of the specified type for a collection of entities.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of the entities are left unchanged.
     * If {@code entities} is {@code null} or empty, this method returns 0 immediately.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("status", "inactive"));
     * // Delete all orders for inactive users
     * int deletedCount = userDao.deleteJoinEntities(users, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities.
     *                 If {@code null} or empty, this method returns 0 immediately
     * @param joinEntityClass the class of the join entities to delete
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entities, joinEntityPropNames.get(0));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getDao(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entities, joinEntityPropName));
                }

                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     * Deletes join entities for a single entity by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     * This is an abstract method whose implementation is provided by the generated DAO.
     *
     * <p>This method deletes all related entities for the specified join property. The deletion
     * is based on the foreign key relationship defined in the {@code @JoinedBy} annotation. The
     * method constructs and executes a DELETE statement targeting the join entity table with a
     * WHERE clause matching the foreign key value(s) from the parent entity.</p>
     *
     * <p>Important notes:</p>
     * <ul>
     *   <li>This operation does NOT modify the in-memory join property of the entity</li>
     *   <li>The deletion is permanent and cannot be rolled back unless within a transaction</li>
     *   <li>Cascade deletion of further nested entities depends on database constraints</li>
     *   <li>For transactional deletion of multiple properties, use {@link #deleteJoinEntities(Object, Collection)}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all addresses associated with the user
     * int deletedCount = userDao.deleteJoinEntities(user, "addresses");
     * System.out.println("Deleted " + deletedCount + " addresses");
     * }</pre>
     *
     * @param entity the entity for which to delete join entities. Must not be {@code null}
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total number of deleted records. Returns 0 if no matching records were found
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException;

    /**
     * Deletes join entities for a collection of entities by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     * This is an abstract method whose implementation is provided by the generated DAO.
     *
     * <p>This method efficiently deletes all related entities for multiple parent entities in a batch operation.
     * The implementation typically uses an IN clause to delete all related records in one or more SQL statements,
     * avoiding the N+1 delete problem. For large collections, the deletion may be automatically batched to
     * prevent SQL statement size limits from being exceeded.</p>
     *
     * <p>Performance characteristics:</p>
     * <ul>
     *   <li>For N parent entities, executes O(1) or O(N/batch_size) DELETE statements instead of O(N)</li>
     *   <li>Much more efficient than deleting join entities one parent at a time</li>
     *   <li>The actual number of deleted records may be less than or greater than the number of parent entities</li>
     * </ul>
     *
     * <p>Important notes:</p>
     * <ul>
     *   <li>This operation does NOT modify the in-memory join properties of the entities</li>
     *   <li>All deletions are permanent unless executed within a transaction</li>
     *   <li>For transactional deletion of multiple properties, use {@link #deleteJoinEntities(Collection, Collection)}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.in("id", userIdsToClean));
     * // Delete all reviews for these users
     * int deletedCount = userDao.deleteJoinEntities(users, "reviews");
     * System.out.println("Deleted " + deletedCount + " reviews for " + users.size() + " users");
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities. Can be empty
     *                 but not {@code null}. If empty, this method returns 0 immediately
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total number of deleted records across all parent entities. Returns 0 if no
     *         matching records were found or if {@code entities} is empty
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException;

    /**
     * Deletes multiple join entities for a single entity by property names.
     * This operation is performed within a transaction when multiple properties are specified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete orders and addresses in a single transaction
     * int deletedCount = userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses"));
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     */
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getDao(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entity, joinEntityPropName));
                }

                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     * Deletes multiple join entities for a single entity with optional parallel execution.
     * Note: when {@code inParallel} is {@code true}, the deletions are dispatched to the default executor
     * and therefore are not executed within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete multiple join entity types in parallel (not transactional)
     * int deletedCount = userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses", "reviews"), true);
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated when {@code inParallel} is {@code true} the deletions are not performed within a single
     *             transaction; prefer {@link #deleteJoinEntities(Object, Collection)} for transactional behavior
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
     * Deletes multiple join entities for a single entity using a custom executor for parallel execution.
     * Note: this operation cannot be completed within a single transaction when executed across multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService deleteExecutor = Executors.newFixedThreadPool(3);
     * User user = userDao.get(1L).orElseThrow();
     * int deletedCount = userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses"), deleteExecutor);
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteJoinEntities(Object, Collection)} for transactional behavior
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
     * Deletes multiple join entities for a collection of entities by property names.
     * This operation is performed within a transaction when multiple properties are specified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("accountStatus", "terminated"));
     * // Delete all related data for terminated accounts
     * int deletedCount = userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses", "paymentMethods"));
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     */
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entities, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getDao(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entities, joinEntityPropName));
                }

                tran.commit();
            } finally {
                tran.rollbackIfNotCommitted();
            }

            return result;
        }
    }

    /**
     * Deletes multiple join entities for a collection of entities with optional parallel execution.
     * Note: Parallel execution may not complete within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getBulkUsersForDeletion();
     * // Delete join entities in parallel for performance (not transactional)
     * int deletedCount = userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses"), true);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated when {@code inParallel} is {@code true} the deletions are not performed within a single
     *             transaction; prefer {@link #deleteJoinEntities(Collection, Collection)} for transactional behavior
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
     * Deletes multiple join entities for a collection of entities using a custom executor for parallel execution.
     * Note: This operation cannot be completed within a single transaction when executed in multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService bulkDeleteExecutor = Executors.newWorkStealingPool();
     * List<User> users = getBulkUsersForDeletion();
     * int deletedCount = userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses"), bulkDeleteExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteJoinEntities(Collection, Collection)} for transactional behavior
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
     * Deletes all join entities for a single entity.
     * This deletes the rows referenced by every property annotated with {@code @JoinedBy};
     * the in-memory join properties of {@code entity} are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all related data (orders, addresses, reviews, etc.)
     * int deletedCount = userDao.deleteAllJoinEntities(user);
     * }</pre>
     *
     * @param entity the entity for which to delete all join entities
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default int deleteAllJoinEntities(final T entity) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for a single entity with optional parallel execution.
     * Note: Parallel execution may not complete within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all join entities in parallel (not transactional)
     * int deletedCount = userDao.deleteAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity for which to delete all join entities
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Object)} for transactional behavior
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
     * Deletes all join entities for a single entity using a custom executor for parallel execution.
     * Note: This operation cannot be completed within a single transaction when executed in multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService cleanupExecutor = Executors.newCachedThreadPool();
     * User user = userDao.get(1L).orElseThrow();
     * int deletedCount = userDao.deleteAllJoinEntities(user, cleanupExecutor);
     * }</pre>
     *
     * @param entity the entity for which to delete all join entities
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Object)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Deletes all join entities for a collection of entities.
     * This deletes the rows referenced by every property annotated with {@code @JoinedBy} for each entity;
     * the in-memory join properties of the entities are left unchanged.
     * If {@code entities} is {@code null} or empty, this method returns 0 immediately.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("markedForDeletion", true));
     * // Delete all related data for users marked for deletion
     * int deletedCount = userDao.deleteAllJoinEntities(users);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete all join entities.
     *                 If {@code null} or empty, this method returns 0 immediately
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for a collection of entities with optional parallel execution.
     * Note: Parallel execution may not complete within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getInactiveUsers();
     * // Delete all join entities in parallel for performance (not transactional)
     * int deletedCount = userDao.deleteAllJoinEntities(users, true);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete all join entities
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Collection)} for transactional behavior
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
     * Deletes all join entities for a collection of entities using a custom executor for parallel execution.
     * Note: This operation cannot be completed within a single transaction when executed in multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService massCleanupExecutor = Executors.newWorkStealingPool(8);
     * List<User> users = getAllUsersForPurge();
     * int deletedCount = userDao.deleteAllJoinEntities(users, massCleanupExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete all join entities
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Collection)} for transactional behavior
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
