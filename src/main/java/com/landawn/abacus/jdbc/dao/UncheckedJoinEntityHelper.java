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
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream;

/**
 * The UncheckedJoinEntityHelper interface provides advanced functionality for handling entity relationships
 * and join operations in DAOs with unchecked exceptions. It enables loading of related entities, 
 * managing one-to-one, one-to-many, and many-to-many relationships.
 * 
 * <p>This interface throws {@code UncheckedSQLException} instead of {@code SQLException}, making it
 * easier to work with in functional programming contexts and reducing boilerplate exception handling.</p>
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Lazy loading of related entities</li>
 *   <li>Batch loading for performance optimization</li>
 *   <li>Conditional loading (loadJoinEntitiesIfNull)</li>
 *   <li>Parallel loading support for multiple join properties</li>
 *   <li>Cascade delete operations for related entities</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public class User {
 *     @Id
 *     private Long id;
 *     private String name;
 *     
 *     @JoinedBy("userId")
 *     private List<Order> orders;
 *     
 *     @JoinedBy("userId")  
 *     private UserProfile profile;
 *     // getters/setters
 * }
 * 
 * UserDao userDao = ...;
 * 
 * // Load user with related entities
 * Optional<User> user = userDao.findFirst(
 *     Arrays.asList("id", "name"),
 *     Order.class,  // Load orders automatically
 *     Filters.eq("id", 1)
 * );
 * 
 * // Load join entities manually
 * User user = userDao.gett(1);
 * userDao.loadJoinEntities(user, "orders");
 * userDao.loadJoinEntities(user, UserProfile.class);
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("resource")
public interface UncheckedJoinEntityHelper<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

    /**
     * Finds the first entity matching the condition and loads the specified join entity class.
     * This is a convenience method that combines finding and join loading in one operation.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find user and load their orders
     * Optional<User> user = userDao.findFirst(
     *     {@code null},  // select all user properties
     *     Order.class,  // also load orders
     *     Filters.eq("email", "john@example.com")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of the join entities to load
     * @param cond the condition to match
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws UncheckedSQLException if a database access error occurs
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
     * Finds the first entity matching the condition and loads multiple join entity classes.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find user and load both orders and profile
     * Optional<User> user = userDao.findFirst(
     *     Arrays.asList("id", "name", "email"),
     *     Arrays.asList(Order.class, UserProfile.class),
     *     Filters.eq("status", "ACTIVE")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @param cond the condition to match
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> findFirst(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findFirst(selectPropNames, cond);

        if (result.isPresent()) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Finds the first entity matching the condition and optionally loads all join entities.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find user and load all related entities
     * Optional<User> user = userDao.findFirst(
     *     {@code null},  // select all properties
     *     {@code true},  // load all join entities
     *     Filters.eq("id", 1)
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param cond the condition to match
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws UncheckedSQLException if a database access error occurs
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
     * Finds exactly one entity matching the condition and loads the specified join entity class.
     * Throws an exception if multiple entities are found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find unique user by email and load profile
     * Optional<User> user = userDao.findOnlyOne(
     *     {@code null},
     *     UserProfile.class,
     *     Filters.eq("email", "unique@example.com")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of the join entities to load
     * @param cond the condition to match
     * @return an Optional containing the unique entity with loaded join entities, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
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
     * Finds exactly one entity matching the condition and loads multiple join entity classes.
     * Throws an exception if multiple entities are found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.findOnlyOne(
     *     Arrays.asList("id", "name"),
     *     Arrays.asList(Order.class, UserProfile.class, Address.class),
     *     Filters.eq("username", "john_doe")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @param cond the condition to match
     * @return an Optional containing the unique entity with loaded join entities, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws DuplicatedResultException, UncheckedSQLException {
        final Optional<T> result = DaoUtil.getDao(this).findOnlyOne(selectPropNames, cond);

        if (result.isPresent()) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result.get(), joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Finds exactly one entity matching the condition and optionally loads all join entities.
     * Throws an exception if multiple entities are found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.findOnlyOne(
     *     Arrays.asList("id", "name", "email"),
     *     {@code true},  // load all join entities
     *     Filters.eq("accountNumber", "ACC-12345")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param cond the condition to match
     * @return an Optional containing the unique entity with loaded join entities, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
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
     * Lists all entities matching the condition and loads the specified join entity class for each.
     * This is a beta API that provides batch loading of join entities for better performance.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get all active users with their orders loaded
     * List<User> users = userDao.list(
     *     {@code null},  // select all user properties
     *     Order.class,  // load orders for each user
     *     Filters.eq("status", "ACTIVE")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of the join entities to load
     * @param cond the condition to match
     * @return a list of entities with loaded join entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> list(final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad, final Condition cond) throws UncheckedSQLException {
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
     * Lists all entities matching the condition and loads multiple join entity classes for each.
     * This is a beta API that efficiently loads multiple relationships in batches.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get users with orders, profiles, and addresses loaded
     * List<User> users = userDao.list(
     *     Arrays.asList("id", "name", "email"),
     *     Arrays.asList(Order.class, UserProfile.class, Address.class),
     *     Filters.in("department", Arrays.asList("IT", "Sales"))
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @param cond the condition to match
     * @return a list of entities with loaded join entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> list(final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad, final Condition cond)
            throws UncheckedSQLException {
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
     * Lists all entities matching the condition and optionally loads all join entities for each.
     * This is a beta API that provides automatic loading of all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get all premium users with all relationships loaded
     * List<User> users = userDao.list(
     *     {@code null},
     *     {@code true},  // load all join entities
     *     Filters.eq("accountType", "PREMIUM")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to select from the main entity, excluding join entity properties.
     *                       If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param cond the condition to match
     * @return a list of entities with loaded join entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<T> list(final Collection<String> selectPropNames, final boolean includeAllJoinEntities, final Condition cond) throws UncheckedSQLException {
        final List<T> result = DaoUtil.getDao(this).list(selectPropNames, cond);

        if (N.notEmpty(result)) {
            if (result.size() <= JdbcUtil.DEFAULT_BATCH_SIZE) {
                loadAllJoinEntities(result);
            } else {
                N.runByBatch(result, JdbcUtil.DEFAULT_BATCH_SIZE, this::loadAllJoinEntities);
            }
        }

        return result;
    }

    /**
     * Loads join entities of the specified class for a single entity.
     * The join entities are determined by the relationship annotations in the entity class.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Load all orders for this user
     * userDao.loadJoinEntities(user, Order.class);
     * // Now user.getOrders() will contain the loaded orders
     * }</pre>
     *
     * @param entity the entity to load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntities(entity, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified class with selected properties for a single entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Load orders but only fetch id, orderDate, and total
     * userDao.loadJoinEntities(
     *     user, 
     *     Order.class, 
     *     Arrays.asList("id", "orderDate", "total")
     * );
     * }</pre>
     *
     * @param entity the entity to load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntities(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws UncheckedSQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities of the specified class for multiple entities in batch.
     * This is more efficient than loading join entities one by one.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("status", "ACTIVE"));
     * // Load orders for all users in batch
     * userDao.loadJoinEntities(users, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities to load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntities(entities, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified class with selected properties for multiple entities.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.gt("createdDate", lastMonth));
     * // Load minimal order info for all users
     * userDao.loadJoinEntities(
     *     users,
     *     Order.class,
     *     Arrays.asList("id", "total", "status")
     * );
     * }</pre>
     *
     * @param entities the collection of entities to load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
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

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities for a specific property name of a single entity.
     * This method provides fine-grained control over which join property to load.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Specifically load the "orders" property
     * userDao.loadJoinEntities(user, "orders");
     * // Load the "profile" property
     * userDao.loadJoinEntities(user, "profile");
     * }</pre>
     *
     * @param entity the entity to load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntities(entity, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a specific property name with selected properties for a single entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Load orders property with only specific fields
     * userDao.loadJoinEntities(
     *     user,
     *     "orders",
     *     Arrays.asList("id", "orderDate", "total", "status")
     * );
     * }</pre>
     *
     * @param entity the entity to load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void loadJoinEntities(final T entity, final String joinEntityPropName, final Collection<String> selectPropNames) throws UncheckedSQLException;

    /**
     * Loads join entities for a specific property name for multiple entities in batch.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("department", "Sales"));
     * // Load orders for all users
     * userDao.loadJoinEntities(users, "orders");
     * }</pre>
     *
     * @param entities the collection of entities to load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntities(entities, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a specific property name with selected properties for multiple entities.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.batchGet(userIds);
     * // Load address property with selected fields for all users
     * userDao.loadJoinEntities(
     *     users,
     *     "addresses",
     *     Arrays.asList("street", "city", "country", "isPrimary")
     * );
     * }</pre>
     *
     * @param entities the collection of entities to load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void loadJoinEntities(final Collection<T> entities, final String joinEntityPropName, final Collection<String> selectPropNames) throws UncheckedSQLException;

    /**
     * Loads join entities for multiple property names of a single entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Load specific join properties
     * userDao.loadJoinEntities(
     *     user,
     *     Arrays.asList("orders", "profile", "addresses")
     * );
     * }</pre>
     *
     * @param entity the entity to load join entities for
     * @param joinEntityPropNames the property names of join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entity, joinEntityPropName);
        }
    }

    /**
     * Loads join entities for multiple property names of a single entity, optionally in parallel.
     * This is a beta API that can improve performance for loading multiple unrelated join entities.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Load multiple properties in parallel
     * userDao.loadJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews", "wishlist"),
     *     {@code true}  // load in parallel
     * );
     * }</pre>
     *
     * @param entity the entity to load join entities for
     * @param joinEntityPropNames the property names of join entities to load
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads join entities for multiple property names using a custom executor for parallel execution.
     * This is a beta API for advanced parallel loading scenarios.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService customExecutor = Executors.newFixedThreadPool(4);
     * User user = userDao.gett(userId);
     * 
     * userDao.loadJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews", "addresses", "payments"),
     *     customExecutor
     * );
     * }</pre>
     *
     * @param entity the entity to load join entities for
     * @param joinEntityPropNames the property names of join entities to load
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default void loadJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     * Loads join entities for multiple property names for multiple entities.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("status", "ACTIVE"));
     * // Load multiple properties for all users
     * userDao.loadJoinEntities(
     *     users,
     *     Arrays.asList("orders", "addresses")
     * );
     * }</pre>
     *
     * @param entities the collection of entities to load join entities for
     * @param joinEntityPropNames the property names of join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntities(entities, joinEntityPropName);
        }
    }

    /**
     * Loads join entities for multiple property names for multiple entities, optionally in parallel.
     * This is a beta API for batch parallel loading.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.batchGet(userIds);
     * // Load multiple properties in parallel for better performance
     * userDao.loadJoinEntities(
     *     users,
     *     Arrays.asList("orders", "reviews", "addresses"),
     *     {@code true}  // load in parallel
     * );
     * }</pre>
     *
     * @param entities the collection of entities to load join entities for
     * @param joinEntityPropNames the property names of join entities to load
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads join entities for multiple property names for multiple entities using a custom executor.
     * This is a beta API for advanced batch parallel loading scenarios.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService batchExecutor = Executors.newCachedThreadPool();
     * List<User> users = userDao.list(Filters.isNotNull("premiumAccount"));
     * 
     * userDao.loadJoinEntities(
     *     users,
     *     Arrays.asList("orders", "subscriptions", "invoices"),
     *     batchExecutor
     * );
     * }</pre>
     *
     * @param entities the collection of entities to load join entities for
     * @param joinEntityPropNames the property names of join entities to load
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default void loadJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     * Loads all join entities defined in the entity class for a single entity.
     * This loads all properties annotated with relationship annotations like @JoinedBy.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Load all related entities (orders, profile, addresses, etc.)
     * userDao.loadAllJoinEntities(user);
     * }</pre>
     *
     * @param entity the entity to load all join entities for
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadAllJoinEntities(final T entity) throws UncheckedSQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities for a single entity, optionally in parallel.
     * This is a beta API for loading all relationships with parallel execution option.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Load all join entities in parallel for better performance
     * userDao.loadAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity to load all join entities for
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads all join entities for a single entity using a custom executor.
     * This is a beta API for advanced parallel loading of all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ForkJoinPool customPool = new ForkJoinPool(8);
     * User user = userDao.gett(userId);
     * 
     * // Load all join entities with custom thread pool
     * userDao.loadAllJoinEntities(user, customPool);
     * }</pre>
     *
     * @param entity the entity to load all join entities for
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
        loadJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads all join entities for multiple entities in batch.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("accountType", "PREMIUM"));
     * // Load all relationships for all users
     * userDao.loadAllJoinEntities(users);
     * }</pre>
     *
     * @param entities the collection of entities to load all join entities for
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads all join entities for multiple entities, optionally in parallel.
     * This is a beta API for batch loading all relationships with parallel execution option.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.batchGet(userIds);
     * // Load all join entities in parallel for better performance
     * userDao.loadAllJoinEntities(users, true);
     * }</pre>
     *
     * @param entities the collection of entities to load all join entities for
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads all join entities for multiple entities using a custom executor.
     * This is a beta API for advanced batch parallel loading of all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService loadingPool = Executors.newWorkStealingPool();
     * List<User> users = userDao.list(Filters.isNotNull("vipStatus"));
     * 
     * // Load all relationships with custom executor
     * userDao.loadAllJoinEntities(users, loadingPool);
     * }</pre>
     *
     * @param entities the collection of entities to load all join entities for
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads join entities of the specified class only if they are currently {@code null}.
     * This is useful for lazy loading scenarios.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Only load orders if not already loaded
     * userDao.loadJoinEntitiesIfNull(user, Order.class);
     * }</pre>
     *
     * @param entity the entity to conditionally load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified class with selected properties only if they are currently {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getPartiallyLoadedUser();
     * // Load profile with specific fields if not already loaded
     * userDao.loadJoinEntitiesIfNull(
     *     user,
     *     UserProfile.class,
     *     Arrays.asList("bio", "avatarUrl", "preferences")
     * );
     * }</pre>
     *
     * @param entity the entity to conditionally load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Class<?> joinEntityClass, final Collection<String> selectPropNames) throws UncheckedSQLException {
        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property found by type {} in class {}", joinEntityClass, targetEntityClass);

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName, selectPropNames);
        }
    }

    /**
     * Loads join entities of the specified class for multiple entities only where they are {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getCachedUsers();
     * // Only load orders for users that don't have them loaded
     * userDao.loadJoinEntitiesIfNull(users, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entities, joinEntityClass, null);
    }

    /**
     * Loads join entities of the specified class with selected properties for multiple entities only where they are {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyCachedUsers();
     * // Load minimal address info only for users without addresses
     * userDao.loadJoinEntitiesIfNull(
     *     users,
     *     Address.class,
     *     Arrays.asList("city", "country")
     * );
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load join entities for
     * @param joinEntityClass the class of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
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
            for (final String joinEntityPropName : joinEntityPropNames) {
                loadJoinEntitiesIfNull(entities, joinEntityPropName, selectPropNames);
            }
        }
    }

    /**
     * Loads join entities for a specific property only if it is currently {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getUser();
     * // Only load profile if user.getProfile() is null
     * userDao.loadJoinEntitiesIfNull(user, "profile");
     * }</pre>
     *
     * @param entity the entity to conditionally load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a specific property with selected fields only if the property is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getUser();
     * // Load addresses with specific fields if not already loaded
     * userDao.loadJoinEntitiesIfNull(
     *     user,
     *     "addresses",
     *     Arrays.asList("street", "city", "postalCode")
     * );
     * }</pre>
     *
     * @param entity the entity to conditionally load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads join entities for a specific property for multiple entities only where the property is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getMixedUsers();
     * // Only load orders for users that don't have them
     * userDao.loadJoinEntitiesIfNull(users, "orders");
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entities, joinEntityPropName, null);
    }

    /**
     * Loads join entities for a specific property with selected fields for multiple entities only where {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getCachedUsers();
     * // Load payment methods with minimal info for users without them
     * userDao.loadJoinEntitiesIfNull(
     *     users,
     *     "paymentMethods",
     *     Arrays.asList("type", "lastFourDigits", "expiryDate")
     * );
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load join entities for
     * @param joinEntityPropName the property name of the join entities to load
     * @param selectPropNames the properties (columns) to be selected from the join entities.
     *                       If {@code null}, all properties of the join entities are selected
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads multiple join properties only if they are {@code null} for a single entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getPartialUser();
     * // Load multiple properties if not already loaded
     * userDao.loadJoinEntitiesIfNull(
     *     user,
     *     Arrays.asList("orders", "profile", "preferences")
     * );
     * }</pre>
     *
     * @param entity the entity to conditionally load join entities for
     * @param joinEntityPropNames the property names of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entity, joinEntityPropName);
        }
    }

    /**
     * Loads multiple join properties only if they are {@code null}, optionally in parallel.
     * This is a beta API for conditional parallel loading.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Load missing properties in parallel
     * userDao.loadJoinEntitiesIfNull(
     *     user,
     *     Arrays.asList("orders", "reviews", "wishlist"),
     *     {@code true}  // parallel loading
     * );
     * }</pre>
     *
     * @param entity the entity to conditionally load join entities for
     * @param joinEntityPropNames the property names of the join entities to load
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads multiple join properties only if they are {@code null} using a custom executor.
     * This is a beta API for advanced conditional parallel loading.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService lazyLoader = Executors.newCachedThreadPool();
     * User user = getUser();
     * 
     * userDao.loadJoinEntitiesIfNull(
     *     user,
     *     Arrays.asList("heavyData1", "heavyData2", "heavyData3"),
     *     lazyLoader
     * );
     * }</pre>
     *
     * @param entity the entity to conditionally load join entities for
     * @param joinEntityPropNames the property names of the join entities to load
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .filter(joinEntityPropName -> Beans.getPropValue(entity, joinEntityPropName) == null)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entity, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     * Loads multiple join properties for multiple entities only where they are {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getMixedCacheUsers();
     * // Load missing properties for all users
     * userDao.loadJoinEntitiesIfNull(
     *     users,
     *     Arrays.asList("orders", "addresses")
     * );
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load join entities for
     * @param joinEntityPropNames the property names of the join entities to load
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        for (final String joinEntityPropName : joinEntityPropNames) {
            loadJoinEntitiesIfNull(entities, joinEntityPropName);
        }
    }

    /**
     * Loads multiple join properties for multiple entities only where {@code null}, optionally in parallel.
     * This is a beta API for batch conditional parallel loading.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * // Efficiently load missing data in parallel
     * userDao.loadJoinEntitiesIfNull(
     *     users,
     *     Arrays.asList("orders", "subscriptions", "activities"),
     *     true
     * );
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load join entities for
     * @param joinEntityPropNames the property names of the join entities to load
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads multiple join properties for multiple entities only where {@code null} using a custom executor.
     * This is a beta API for advanced batch conditional parallel loading.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ForkJoinPool fjPool = new ForkJoinPool(16);
     * List<User> users = getThousandsOfUsers();
     * 
     * userDao.loadJoinEntitiesIfNull(
     *     users,
     *     Arrays.asList("transactions", "analytics", "recommendations"),
     *     fjPool
     * );
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load join entities for
     * @param joinEntityPropNames the property names of the join entities to load
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return;
        }

        final List<ContinuableFuture<Void>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.run(() -> loadJoinEntitiesIfNull(entities, joinEntityPropName), executor))
                .toList();

        DaoUtil.uncheckedComplete(futures);
    }

    /**
     * Loads all join entities only if they are {@code null} for a single entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Load all missing relationships
     * userDao.loadJoinEntitiesIfNull(user);
     * }</pre>
     *
     * @param entity the entity to conditionally load all join entities for
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Override
    default void loadJoinEntitiesIfNull(final T entity) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Loads all join entities only if they are {@code null}, optionally in parallel.
     * This is a beta API for conditional loading of all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getPartialUser();
     * // Load all missing relationships in parallel
     * userDao.loadJoinEntitiesIfNull(user, true);
     * }</pre>
     *
     * @param entity the entity to conditionally load all join entities for
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads all join entities only if they are {@code null} using a custom executor.
     * This is a beta API for advanced conditional loading of all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
     * User user = getUser();
     * 
     * userDao.loadJoinEntitiesIfNull(user, scheduler);
     * }</pre>
     *
     * @param entity the entity to conditionally load all join entities for
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void loadJoinEntitiesIfNull(final T entity, final Executor executor) throws UncheckedSQLException {
        loadJoinEntitiesIfNull(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Loads all join entities only if they are {@code null} for multiple entities.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getCachedUsers();
     * // Load all missing relationships for all users
     * userDao.loadJoinEntitiesIfNull(users);
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load all join entities for
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads all join entities only if they are {@code null} for multiple entities, optionally in parallel.
     * This is a beta API for batch conditional loading of all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getPartiallyLoadedUsers();
     * // Load all missing relationships in parallel
     * userDao.loadJoinEntitiesIfNull(users, true);
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load all join entities for
     * @param inParallel if {@code true}, entities are loaded in parallel; if {@code false}, loaded sequentially
     * @throws UncheckedSQLException if a database access error occurs
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
     * Loads all join entities only if they are {@code null} for multiple entities using a custom executor.
     * This is a beta API for advanced batch conditional loading of all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService batchLoader = Executors.newWorkStealingPool();
     * List<User> users = getLargeUserCollection();
     * 
     * userDao.loadJoinEntitiesIfNull(users, batchLoader);
     * }</pre>
     *
     * @param entities the collection of entities to conditionally load all join entities for
     * @param executor the {@code Executor} to use for parallel execution
     * @throws UncheckedSQLException if a database access error occurs
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
     * Deletes all join entities of the specified class related to the given entity.
     * This performs a cascade delete operation for the specified relationship type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all orders for this user
     * int deletedCount = userDao.deleteJoinEntities(user, Order.class);
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityClass the class of join entities to delete
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
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
     * Deletes all join entities of the specified class for multiple entities.
     * This performs a batch cascade delete operation.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToClean = getInactiveUsers();
     * // Delete all orders for these users
     * int totalDeleted = userDao.deleteJoinEntities(usersToClean, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityClass the class of join entities to delete
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
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
     * Deletes join entities for a specific property name of a single entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all addresses for this user
     * int deleted = userDao.deleteJoinEntities(user, "addresses");
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropName the property name of the join entities to delete
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException;

    /**
     * Deletes join entities for a specific property name for multiple entities.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getDeactivatedUsers();
     * // Delete all payment methods for these users
     * int totalDeleted = userDao.deleteJoinEntities(users, "paymentMethods");
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropName the property name of the join entities to delete
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException;

    /**     
     * Deletes join entities for multiple property names of a single entity.
     * The deletions are performed in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all orders and reviews for this user
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews")
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
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
     * Deletes join entities for multiple property names of a single entity.
     * Note: Operations executed in multiple threads won't be completed in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete multiple related entities
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews", "wishlistItems")
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction when it's executed in multiple threads
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        return DaoUtil.uncheckedCompleteSum(futures);
    }

    /**     
     * Deletes join entities for multiple property names of a single entity in parallel (deprecated).
     * Note: Parallel deletion may not complete in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete multiple relationships in parallel
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews", "notifications"),
     *     {@code true}  // parallel deletion
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param inParallel if {@code true}, entities are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction if {@code inParallel} is {@code true}
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
     * Deletes join entities for multiple property names for multiple entities.
     * The deletions are performed in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getDeletedUsers();
     * // Clean up multiple relationships
     * int deleted = userDao.deleteJoinEntities(
     *     users,
     *     Arrays.asList("orders", "addresses", "preferences")
     * );
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
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
     * Deletes join entities for multiple property names for multiple entities in parallel (deprecated).
     * Note: Parallel deletion may not complete in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getObsoleteUsers();
     * int deleted = userDao.deleteJoinEntities(
     *     users,
     *     Arrays.asList("orders", "transactions"),
     *     {@code true}  // parallel deletion
     * );
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param inParallel if {@code true}, entities are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction if {@code inParallel} is {@code true}
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
     * Deletes join entities for multiple property names for multiple entities using a custom executor (deprecated).
     * Note: Operations executed in multiple threads won't be completed in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ForkJoinPool cleanupPool = new ForkJoinPool(8);
     * List<User> users = getUsersToCleanup();
     * 
     * int deleted = userDao.deleteJoinEntities(
     *     users,
     *     Arrays.asList("logs", "sessions", "tempData"),
     *     cleanupPool
     * );
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction when it's executed in multiple threads
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        return DaoUtil.uncheckedCompleteSum(futures);
    }

    /**
     * Deletes all join entities for a single entity.
     * This performs a cascade delete for all relationships defined in the entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all related entities (orders, addresses, profile, etc.)
     * int totalDeleted = userDao.deleteAllJoinEntities(user);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteAllJoinEntities(final T entity) throws UncheckedSQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for a single entity, optionally in parallel (deprecated).
     * Note: Parallel deletion may not complete in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all relationships in parallel
     * int deleted = userDao.deleteAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @param inParallel if {@code true}, entities are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction if {@code inParallel} is {@code true}
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
     * Deletes all join entities for a single entity using a custom executor (deprecated).
     * Note: Operations executed in multiple threads won't be completed in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService cleanupService = Executors.newCachedThreadPool();
     * User user = userDao.gett(userId);
     * 
     * int deleted = userDao.deleteAllJoinEntities(user, cleanupService);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction when it's executed in multiple threads
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Deletes all join entities for multiple entities.
     * This performs a batch cascade delete for all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToDelete = getTerminatedUsers();
     * // Clean up all related data
     * int totalDeleted = userDao.deleteAllJoinEntities(usersToDelete);
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
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
     * Deletes all join entities for multiple entities, optionally in parallel (deprecated).
     * Note: Parallel deletion may not complete in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getExpiredUsers();
     * // Delete all relationships in parallel
     * int deleted = userDao.deleteAllJoinEntities(users, true);
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted
     * @param inParallel if {@code true}, entities are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction if {@code inParallel} is {@code true}
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
     * Deletes all join entities for multiple entities using a custom executor (deprecated).
     * Note: Operations executed in multiple threads won't be completed in a single transaction.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ThreadPoolExecutor massDeleteExecutor = new ThreadPoolExecutor(...);
     * List<User> users = getUsersForMassCleanup();
     * 
     * int deleted = userDao.deleteAllJoinEntities(users, massDeleteExecutor);
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated This operation may not complete in a single transaction when it's executed in multiple threads
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
