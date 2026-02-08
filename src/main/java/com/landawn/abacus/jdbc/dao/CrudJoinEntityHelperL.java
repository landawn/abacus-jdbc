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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.util.u.Optional;

/**
 * A specialized interface for CRUD operations with join entity support that uses {@code Long} as the ID type.
 * This interface extends CrudJoinEntityHelper and provides convenience methods that accept primitive {@code long} values
 * in addition to the {@code Long} object methods inherited from the parent interface.
 * 
 * <p>This interface is designed to work with entities that have relationships defined using the {@code @JoinedBy} annotation.
 * It automatically handles the loading of related entities when retrieving records from the database.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @Entity
 * public class User {
 *     @Id
 *     private long id;
 *     private String name;
 *     
 *     @JoinedBy("userId")
 *     private List<Order> orders;
 * }
 * 
 * public interface UserDao extends CrudJoinEntityHelperL<User, SQLBuilder.PSC, UserDao> {
 *     // Inherits join entity methods with Long ID type
 * }
 * 
 * // Usage
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * 
 * // Get user with orders loaded
 * Optional<User> user = userDao.get(123L, Order.class);
 * 
 * // Get user with all joined entities loaded
 * Optional<User> userWithAll = userDao.get(123L, true);
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * 
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see CrudJoinEntityHelper
 */
public interface CrudJoinEntityHelperL<T, SB extends SQLBuilder, TD extends CrudDaoL<T, SB, TD>> extends CrudJoinEntityHelper<T, Long, SB, TD> {

    /**
     * Retrieves an entity by its ID and loads the specified join entities.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with orders loaded
     * Optional<User> user = userDao.get(123L, Order.class);
     * user.ifPresent(u -> {
     *     System.out.println("User: " + u.getName());
     *     System.out.println("Orders: " + u.getOrders().size());
     * });
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID and optionally loads all join entities.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with all related entities loaded
     * Optional<User> user = userDao.get(123L, true);
     * user.ifPresent(u -> {
     *     // All @JoinedBy annotated fields are loaded
     *     System.out.println("Orders: " + u.getOrders());
     *     System.out.println("Addresses: " + u.getAddresses());
     *     System.out.println("Preferences: " + u.getPreferences());
     * });
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the retrieved entity with join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID with selected properties and loads the specified join entities.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with only id and name fields, plus orders
     * Optional<User> user = userDao.get(123L, Arrays.asList("id", "name"), Order.class);
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the retrieved entity with join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID with selected properties and loads multiple join entity types.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with orders and addresses loaded
     * Optional<User> user = userDao.get(123L, {@code null}, Arrays.asList(Order.class, Address.class));
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return an Optional containing the retrieved entity with join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID with selected properties and optionally loads all join entities.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with only essential fields and all related entities
     * Optional<User> user = userDao.get(123L, Arrays.asList("id", "name", "email"), true);
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return an Optional containing the retrieved entity with join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID and loads the specified join entities, returning {@code null} if not found.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(123L, Order.class);
     * if (user != null) {
     *     System.out.println("User: " + user.getName());
     *     System.out.println("Orders: " + user.getOrders().size());
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the retrieved entity with join entities loaded, or {@code null} if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID and optionally loads all join entities, returning {@code null} if not found.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(123L, true);
     * if (user != null) {
     *     // All @JoinedBy annotated fields are loaded
     *     processUserWithAllRelations(user);
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the retrieved entity with join entities loaded, or {@code null} if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with selected properties and loads the specified join entities, returning {@code null} if not found.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with minimal fields plus orders
     * User user = userDao.gett(123L, Arrays.asList("id", "name"), Order.class);
     * if (user != null) {
     *     displayUserSummary(user);
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the retrieved entity with join entities loaded, or {@code null} if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with selected properties and loads multiple join entity types, returning {@code null} if not found.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with orders and addresses
     * User user = userDao.gett(123L, {@code null}, Arrays.asList(Order.class, Address.class));
     * if (user != null) {
     *     processUserWithRelations(user);
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param joinEntitiesToLoad the collection of join entity classes to load
     * @return the retrieved entity with join entities loaded, or {@code null} if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
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
     * Retrieves an entity by its ID with selected properties and optionally loads all join entities, returning {@code null} if not found.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get user with essential fields and all relations
     * User user = userDao.gett(123L, Arrays.asList("id", "name", "status"), true);
     * if (user != null) {
     *     performCompleteUserAnalysis(user);
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select from the main entity, excluding join entity properties.
     *                        If {@code null}, all properties of the main entity are selected
     * @param includeAllJoinEntities if {@code true}, all join entities will be loaded;
     *                                  if {@code false}, no join entities are loaded
     * @return the retrieved entity with join entities loaded, or {@code null} if not found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }
}
