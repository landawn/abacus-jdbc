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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.util.u.Optional;

/**
 * A specialized interface for CRUD operations with join entity support, specifically designed for entities
 * with {@link Long} type primary keys. This interface extends the generic join entity helper functionality
 * with convenience methods that accept primitive {@code long} values for IDs.
 * 
 * <p>This interface provides all the standard join entity operations but with overloaded methods that
 * accept primitive {@code long} parameters instead of {@link Long} objects. This can lead to cleaner code
 * and better performance by avoiding auto-boxing in high-frequency operations.</p>
 * 
 * <p>The interface supports loading related entities through various join strategies, allowing you to
 * fetch an entity along with its associated entities in a single operation or through lazy loading.</p>
 * 
 * <p>Example usage:
 * <pre>{@code
 * UncheckedCrudJoinEntityHelperL<User, SQLBuilder, ?> userDao = daoFactory.createJoinDaoL(User.class);
 * 
 * // Fetch user with primitive long ID and load associated orders
 * Optional<User> user = userDao.get(123L, Order.class);
 * 
 * // Fetch user with specific properties and multiple join entities
 * User userWithDetails = userDao.gett(123L, 
 *     Arrays.asList("id", "name", "email"),
 *     Arrays.asList(Order.class, Address.class));
 * 
 * // Load all join entities for a user
 * Optional<User> fullUser = userDao.get(123L, true); // includeAllJoinEntities = true
 * }</pre></p>
 * 
 * <p>All methods in this interface throw {@link UncheckedSQLException} instead of checked exceptions,
 * allowing for cleaner code without explicit exception handling. Methods may also throw
 * {@link DuplicatedResultException} when multiple records are found for a unique query.</p>
 *
 * @param <T> The entity type that this helper manages
 * @param <SB> The type of {@link SQLBuilder} used to generate SQL scripts
 * @param <TD> The self-referential type parameter for the DAO, extending {@link UncheckedCrudDaoL}
 * @see UncheckedCrudJoinEntityHelper
 * @see CrudJoinEntityHelperL
 * @see UncheckedCrudDaoL
 */
public interface UncheckedCrudJoinEntityHelperL<T, SB extends SQLBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
        extends UncheckedCrudJoinEntityHelper<T, Long, SB, TD>, CrudJoinEntityHelperL<T, SB, TD> {

    /**
     * Retrieves an entity by its ID and loads the specified join entity class.
     * 
     * <p>This method fetches the main entity and automatically loads the related entities
     * of the specified type. The join is performed based on the relationship mappings
     * defined in the entity classes.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Fetch a user and load their orders
     * Optional<User> userWithOrders = userDao.get(123L, Order.class);
     * 
     * if (userWithOrders.isPresent()) {
     *     User user = userWithOrders.get();
     *     List<Order> orders = user.getOrders(); // Orders are already loaded
     * }
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param joinEntitiesToLoad The class of the join entities to load (e.g., Order.class for loading orders)
     * @return An {@link Optional} containing the entity with loaded join entities, or empty if not found
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID with the option to load all associated join entities.
     * 
     * <p>When {@code includeAllJoinEntities} is true, all mapped relationships will be loaded.
     * This is useful when you need the complete object graph but should be used carefully
     * to avoid performance issues with large datasets.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Fetch a user with all related entities (orders, addresses, preferences, etc.)
     * Optional<User> fullUser = userDao.get(123L, true);
     * 
     * // Fetch only the user without any joins
     * Optional<User> userOnly = userDao.get(123L, false);
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param includeAllJoinEntities If true, loads all mapped join entities; if false, loads only the main entity
     * @return An {@link Optional} containing the entity, or empty if not found
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID with specific properties and loads the specified join entity.
     * 
     * <p>This method allows you to optimize queries by selecting only the required properties
     * of the main entity while still loading the complete join entities. This is particularly
     * useful for large entities where you only need a subset of fields.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Fetch only id, name, and email from User, but load complete Order entities
     * Optional<User> user = userDao.get(123L, 
     *     Arrays.asList("id", "name", "email"), 
     *     Order.class);
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param selectPropNames The properties (columns) to select from the main entity. 
     *                        If null, all properties are selected
     * @param joinEntitiesToLoad The class of the join entities to load
     * @return An {@link Optional} containing the entity with selected properties and loaded join entities
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID with specific properties and loads multiple join entity types.
     * 
     * <p>This method provides fine-grained control over what data is fetched, allowing you to
     * specify exactly which properties of the main entity and which related entity types to load.
     * This can significantly improve performance in complex object graphs.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Fetch user with limited properties and multiple join entities
     * Optional<User> user = userDao.get(123L,
     *     Arrays.asList("id", "name", "email", "status"),
     *     Arrays.asList(Order.class, Address.class, PaymentMethod.class));
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param selectPropNames The properties (columns) to select from the main entity.
     *                        If null, all properties are selected
     * @param joinEntitiesToLoad Collection of join entity classes to load
     * @return An {@link Optional} containing the entity with selected properties and loaded join entities
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID with specific properties and optionally loads all join entities.
     * 
     * <p>This method combines property selection with the option to load all relationships,
     * providing maximum flexibility in controlling what data is fetched from the database.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Fetch minimal user data with all relationships
     * Optional<User> user = userDao.get(123L,
     *     Arrays.asList("id", "name"),
     *     true); // Load all join entities
     * 
     * // Fetch complete user data without relationships  
     * Optional<User> userOnly = userDao.get(123L,
     *     null, // Select all properties
     *     false); // Don't load join entities
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param selectPropNames The properties (columns) to select from the main entity.
     *                        If null, all properties are selected
     * @param includeAllJoinEntities If true, loads all mapped join entities; if false, loads only the main entity
     * @return An {@link Optional} containing the entity with selected properties
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID and loads the specified join entity class, throwing an exception if not found.
     * 
     * <p>This method is similar to {@link #get(long, Class)} but returns the entity directly
     * instead of an Optional. It throws an exception if the entity is not found, making it
     * suitable for cases where the entity is expected to exist.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Get a user that must exist, with orders loaded
     * User user = userDao.gett(123L, Order.class);
     * List<Order> orders = user.getOrders(); // Orders are already loaded
     * 
     * // This will throw an exception if user 999 doesn't exist
     * User missingUser = userDao.gett(999L, Order.class); // Throws exception
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param joinEntitiesToLoad The class of the join entities to load
     * @return The entity with loaded join entities, never null
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation or if the entity is not found
     */
    @Override
    @Beta
    default T gett(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with the option to load all join entities, throwing an exception if not found.
     * 
     * <p>This method provides a non-Optional alternative to {@link #get(long, boolean)},
     * suitable for cases where the entity is expected to exist.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Get a complete user object graph
     * User fullUser = userDao.gett(123L, true);
     * 
     * // Get just the user without relationships
     * User userOnly = userDao.gett(123L, false);
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param includeAllJoinEntities If true, loads all mapped join entities
     * @return The entity, never null
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation or if the entity is not found
     */
    @Override
    @Beta
    default T gett(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with specific properties and loads the specified join entity, throwing an exception if not found.
     * 
     * <p>This method combines property selection with join loading, providing optimized
     * data fetching while ensuring the entity exists.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Get essential user data with orders
     * User user = userDao.gett(123L,
     *     Arrays.asList("id", "name", "email"),
     *     Order.class);
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param selectPropNames The properties to select from the main entity. If null, all properties are selected
     * @param joinEntitiesToLoad The class of the join entities to load
     * @return The entity with selected properties and loaded join entities, never null
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation or if the entity is not found
     */
    @Override
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID with specific properties and loads multiple join entity types, throwing an exception if not found.
     * 
     * <p>This method provides maximum control over data fetching, allowing precise specification
     * of what data to retrieve while ensuring the entity exists.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Get user with specific fields and multiple relationships
     * User user = userDao.gett(123L,
     *     Arrays.asList("id", "name", "email", "createdDate"),
     *     Arrays.asList(Order.class, Address.class, Preference.class));
     * 
     * // Now user has limited fields but all specified relationships are loaded
     * List<Order> orders = user.getOrders();
     * List<Address> addresses = user.getAddresses();
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param selectPropNames The properties to select from the main entity. If null, all properties are selected
     * @param joinEntitiesToLoad Collection of join entity classes to load
     * @return The entity with selected properties and loaded join entities, never null
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation or if the entity is not found
     */
    @Override
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
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
     * Retrieves an entity by its ID with specific properties and optionally loads all join entities, throwing an exception if not found.
     * 
     * <p>This method provides complete flexibility in controlling what data is fetched,
     * combining property selection with optional loading of all relationships.</p>
     * 
     * <p>Example usage:
     * <pre>{@code
     * // Get minimal user data but with all relationships
     * User user = userDao.gett(123L,
     *     Arrays.asList("id", "name"), // Only these fields
     *     true); // But load all join entities
     * 
     * // Get complete user data without any relationships
     * User userOnly = userDao.gett(123L,
     *     null, // All fields
     *     false); // No join entities
     * }</pre></p>
     *
     * @param id The primary key value of the entity to retrieve
     * @param selectPropNames The properties to select from the main entity. If null, all properties are selected
     * @param includeAllJoinEntities If true, loads all mapped join entities
     * @return The entity with selected properties, never null
     * @throws DuplicatedResultException If more than one record is found for the specified ID
     * @throws UncheckedSQLException If any SQL error occurs during the operation or if the entity is not found
     */
    @Override
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }
}