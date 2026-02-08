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
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.u.Optional;

/**
 * Interface for CRUD operations with automatic join entity loading support.
 * This interface extends the basic CRUD functionality by providing methods that automatically
 * load related entities defined with the {@code @JoinedBy} annotation.
 * 
 * <p>The interface handles one-to-one, one-to-many, and many-to-many relationships by loading
 * related entities when retrieving records from the database. This eliminates the N+1 query problem
 * and simplifies working with entity relationships.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @Entity
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
 *     
 *     @JoinedBy({"user_roles", "userId"})
 *     private List<Role> roles;
 * }
 * 
 * public interface UserDao extends CrudJoinEntityHelper<User, Long, SQLBuilder.PSC, UserDao> {
 *     // Inherits methods for loading joined entities
 * }
 * 
 * // Usage examples:
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * 
 * // Get user with orders loaded
 * Optional<User> userWithOrders = userDao.get(userId, Order.class);
 * 
 * // Get user with multiple join entities loaded
 * Optional<User> userWithAll = userDao.get(userId, Arrays.asList("id", "name"), 
 *                                         Arrays.asList(Order.class, UserProfile.class));
 * 
 * // Batch get with join entities
 * List<User> users = userDao.batchGet(userIds, Order.class);
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <ID> the ID type of the entity
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 *
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.query.Filters
 */
public interface CrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

    /**
     * Retrieves an entity by its ID and loads the specified type of join entities.
     * Only the join entities of the specified class will be loaded.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // User has @JoinedBy orders, profile, and roles
     * // This will only load orders
     * Optional<User> user = userDao.get(userId, Order.class);
     * user.ifPresent(u -> {
     *     assert u.getOrders() != null;  // Orders are loaded
     *     assert u.getProfile() == null;  // Profile is not loaded
     *     assert u.getRoles() == null;  // Roles are not loaded
     * });
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the entity with join entities loaded, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID and optionally loads all join entities.
     * When includeAllJoinEntities is {@code true}, all fields annotated with @JoinedBy will be loaded.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load user with all related entities
     * Optional<User> user = userDao.get(userId, true);
     * user.ifPresent(u -> {
     *     // All @JoinedBy fields are populated
     *     System.out.println("Orders: " + u.getOrders().size());
     *     System.out.println("Profile: " + u.getProfile());
     *     System.out.println("Roles: " + u.getRoles().size());
     * });
     * 
     * // Load user without join entities
     * Optional<User> userOnly = userDao.get(userId, false);
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the entity with join entities loaded as specified, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID with only selected properties and loads the specified join entities.
     * This method allows for optimized queries by selecting only needed columns from the main entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load only id, name, and email from user, plus all orders
     * Optional<User> user = userDao.get(userId, 
     *                                  Arrays.asList("id", "name", "email"), 
     *                                  Order.class);
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of join entities to load
     * @return an Optional containing the entity with selected properties and join entities loaded, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID with only selected properties and loads multiple types of join entities.
     * This method provides fine-grained control over what data is loaded from the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load user with minimal fields and specific relations
     * Optional<User> user = userDao.get(userId,
     *     Arrays.asList("id", "name", "status"),
     *     Arrays.asList(Order.class, UserProfile.class));
     * // Orders and UserProfile are loaded, but Roles are not
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return an Optional containing the entity with selected properties and specified join entities loaded, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID with only selected properties and optionally loads all join entities.
     * Combines property selection with the option to load all relationships.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load user with only essential fields but all relationships
     * Optional<User> user = userDao.get(userId,
     *     Arrays.asList("id", "name", "email", "status"),
     *     true);   // Load all @JoinedBy fields
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the entity with selected properties and join entities as specified, or empty if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID and loads the specified type of join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Class)}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId, Order.class);
     * if (user != null) {
     *     // Process user with orders loaded
     *     user.getOrders().forEach(order -> processOrder(order));
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param joinEntitiesToLoad the class of join entities to load
     * @return the entity with specified join entities loaded, or {@code null} if not found
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
     * Retrieves an entity by its ID and optionally loads all join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, boolean)}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId, true);
     * if (user != null) {
     *     // All @JoinedBy fields are populated
     *     performCompleteUserAnalysis(user);
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the entity with join entities loaded as specified, or {@code null} if not found
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
     * Retrieves an entity by its ID with only selected properties and loads the specified join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Collection, Class)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with specific fields and orders
     * User user = userDao.gett(userId, Arrays.asList("id", "name", "email"), Order.class);
     * if (user != null) {
     *     displayUserWithOrders(user);
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of join entities to load
     * @return the entity with selected properties and join entities loaded, or {@code null} if not found
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
     * Retrieves an entity by its ID with only selected properties and loads multiple types of join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Collection, Collection)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with selected properties and multiple relations
     * User user = userDao.gett(userId,
     *                         Arrays.asList("id", "name", "status"),
     *                         Arrays.asList(Order.class, UserProfile.class));
     * if (user != null) {
     *     processUserWithOrdersAndProfile(user);
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return the entity with selected properties and specified join entities loaded, or {@code null} if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && N.notEmpty(joinEntitiesToLoad)) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result, joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with only selected properties and optionally loads all join entities, returning {@code null} if not found.
     * This is the null-returning variant of {@link #get(Object, Collection, boolean)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with minimal fields and all relations
     * User user = userDao.gett(userId,
     *                         Arrays.asList("id", "name", "email"),
     *                         true);   // Load all @JoinedBy fields
     * if (user != null) {
     *     performFullUserAnalysis(user);
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the entity with selected properties and join entities as specified, or {@code null} if not found
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
     * Retrieves multiple entities by their IDs and loads the specified type of join entities.
     * Uses the default batch size for processing.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
     * List<User> users = userDao.batchGet(userIds, Order.class);
     * // Each user has their orders loaded
     * users.forEach(user -> {
     *     System.out.println(user.getName() + " has " + user.getOrders().size() + " orders");
     * });
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load for each entity
     * @return a list of entities with the specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        return batchGet(ids, null, joinEntitiesToLoad, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs and optionally loads all join entities.
     * Uses the default batch size for processing.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = getUserIdsToProcess();
     * // Get all users with all their relationships loaded
     * List<User> users = userDao.batchGet(userIds, true);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of entities with join entities loaded as specified
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        return batchGet(ids, null, includeAllJoinEntities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and loads the specified join entities.
     * Uses the default batch size for processing.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get users with only essential fields and their orders
     * List<User> users = userDao.batchGet(userIds, 
     *                                     Arrays.asList("id", "name", "email"),
     *                                     Order.class);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntitiesToLoad the class of join entities to load for each entity
     * @return a list of entities with selected properties and join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, joinEntitiesToLoad, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and loads multiple types of join entities.
     * Uses the default batch size for processing.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get users with orders and profiles loaded
     * List<User> users = userDao.batchGet(userIds,
     *                                     {@code null},  // Select all user properties
     *                                     Arrays.asList(Order.class, UserProfile.class));
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load for each entity
     * @return a list of entities with selected properties and specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, joinEntitiesToLoad, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and optionally loads all join entities.
     * Uses the default batch size for processing.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get users with selected properties and all relations
     * List<User> users = userDao.batchGet(userIds,
     *                                     Arrays.asList("id", "name", "email"),
     *                                     true);   // Load all relationships
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return a list of entities with selected properties and join entities as specified
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, includeAllJoinEntities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with selected properties and loads the specified join entities.
     * Processes the retrieval in batches of the specified size to handle large ID collections efficiently.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Process large number of users in batches of 100
     * List<User> users = userDao.batchGet(thousandsOfUserIds,
     *                                     Arrays.asList("id", "name", "status"),
     *                                     100,  // batch size
     *                                     Order.class);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntitiesToLoad the class of join entities to load for each entity
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of entities with selected properties and join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad,
            final int batchSize) throws DuplicatedResultException, SQLException {
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
     * Retrieves multiple entities by their IDs with selected properties and loads multiple types of join entities.
     * Processes the retrieval in batches of the specified size to handle large ID collections efficiently.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load users with multiple relationships in batches
     * List<User> users = userDao.batchGet(userIds,
     *                                     {@code null},  // all properties
     *                                     50,    // batch size
     *                                     Arrays.asList(Order.class, UserProfile.class, Role.class));
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load for each entity
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of entities with selected properties and specified join entities loaded
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad,
            final int batchSize) throws DuplicatedResultException, SQLException {
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
     * Retrieves multiple entities by their IDs with selected properties and optionally loads all join entities.
     * Processes the retrieval in batches of the specified size to handle large ID collections efficiently.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get all users with complete data in optimized batches
     * List<User> users = userDao.batchGet(userIds,
     *                                     Arrays.asList("id", "name", "email", "status"),
     *                                     200,   // larger batch size
     *                                     true);   // load all relationships
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select from each entity, excluding join entity properties.
     *                       If {@code null}, all properties of the entities are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of entities with selected properties and join entities as specified
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities,
            final int batchSize) throws DuplicatedResultException, SQLException {
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
