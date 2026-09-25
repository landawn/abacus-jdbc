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
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.CannotGetJdbcConnectionException;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.util.N;

/**
 * Unchecked-exception update capability of {@link UncheckedCrudDao}: the {@link CrudUpdateOps}
 * operations re-declared to throw {@link UncheckedSQLException}.
 *
 * @param <T> entity type
 * @param <ID> id type
 * @param <TD> self DAO type
 * @see CrudUpdateOps
 * @see UncheckedCrudDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
@Beta
sealed interface UncheckedCrudUpdateOps<T, ID, TD extends UncheckedDaoBase<T, TD>> extends CrudUpdateOps<T, ID, TD>, UncheckedUpdateOps<T, TD>
        permits UncheckedCrudDao {
    /**
     * Updates the specified entity in the database. The entity must have its ID set.
     * All updatable properties of the entity will be updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new java.util.Date());
     * int updatedRows = userDao.update(user);
     * }</pre>
     *
     * @param entity the entity containing the values to update
     * @return the number of rows updated (typically 1 if successful, 0 if not found); also 0, without executing any
     *         statement, if the entity class has no updatable non-ID property
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing, binding, or executing an UPDATE statement fails
     */
    @Override
    int update(final T entity) throws IllegalArgumentException, CannotGetJdbcConnectionException, UncheckedSQLException;

    /**
     * Updates only the specified properties of the entity in the database.
     * Properties not included in {@code propNamesToUpdate} will not be modified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(userId);
     * user.setEmail("newemail@example.com");
     * user.setPhone("555-1234");
     * user.setAddress("123 Main St");   // This won't be updated
     *
     * // Only update email and phone
     * int updated = userDao.update(user, Arrays.asList("email", "phone"));
     * }</pre>
     *
     * @param entity the entity containing the values to update
     * @param propNamesToUpdate the property names to update (must not be {@code null} or empty)
     * @return the number of rows updated
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or if {@code propNamesToUpdate} is {@code null} or empty
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing, binding, or executing an UPDATE statement fails
     */
    @Override
    int update(final T entity, final Collection<String> propNamesToUpdate)
            throws IllegalArgumentException, CannotGetJdbcConnectionException, UncheckedSQLException;

    /**
     * Updates a single property of the entity identified by ID.
     * Convenience method for updating one property without building a map of properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Update user's last login time
     * userDao.update("lastLoginTime", new java.util.Date(), userId);
     *
     * // Deactivate user
     * userDao.update("status", "INACTIVE", userId);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the ID of the entity to update
     * @return the number of rows updated
     * @throws IllegalArgumentException if {@code propName} is {@code null} or empty, or if {@code id} is {@code null}
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing, binding, or executing an UPDATE statement fails
     */
    @Override
    default int update(final String propName, final Object propValue, final ID id)
            throws IllegalArgumentException, CannotGetJdbcConnectionException, UncheckedSQLException {
        N.checkArgNotEmpty(propName, cs.propName);
        N.checkArgNotNull(id, cs.id);

        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, id);
    }

    /**
     * Updates multiple properties for the entity with the specified ID.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("email", "newemail@example.com");
     * updates.put("phone", "555-9999");
     * updates.put("lastModified", new java.util.Date());
     *
     * int updated = userDao.update(updates, userId);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the ID of the entity to update
     * @return the number of rows updated
     * @throws IllegalArgumentException if {@code updateProps} is {@code null} or empty, or if {@code id} is {@code null}
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing, binding, or executing an UPDATE statement fails
     */
    @Override
    int update(final Map<String, Object> updateProps, final ID id) throws IllegalArgumentException, CannotGetJdbcConnectionException, UncheckedSQLException;

    /**
     * Batch updates multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * All updatable properties of each entity will be included in the UPDATE statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("needsUpdate", true));
     * users.forEach(user -> {
     *     user.setProcessed(true);
     *     user.setProcessedDate(new java.util.Date());
     * });
     * int totalUpdated = userDao.batchUpdate(users);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @return the total number of rows updated
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     * @throws IllegalArgumentException if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     */
    @Override
    default int batchUpdate(final Collection<? extends T> entities)
            throws IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException, IllegalArgumentException {
        return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch updates multiple entities using the specified batch size.
     * All updatable properties of each entity will be included in the UPDATE statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = getThousandsOfUsers();
     * // Update in batches of 500
     * int totalUpdated = userDao.batchUpdate(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param batchSize the number of entities to process in each batch
     * @return the total number of rows updated; 0, without executing any statement, if {@code entities} is {@code null}
     *         or empty or the entity class has no updatable non-ID property
     * @throws IllegalArgumentException if {@code batchSize} is not positive,
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     */
    @Override
    int batchUpdate(final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException;

    /**
     * Batch updates only the specified properties of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsersToProcess();
     * users.forEach(user -> {
     *     user.setStatus("PROCESSED");
     *     user.setScore(calculateScore(user));
     * });
     * // Only update status and score fields
     * int updated = userDao.batchUpdate(users, Arrays.asList("status", "score"));
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for each entity (must not be {@code null} or empty)
     * @return the total number of rows updated
     * @throws IllegalArgumentException if {@code propNamesToUpdate} is {@code null} or empty,
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     */
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        return batchUpdate(entities, propNamesToUpdate, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch updates only the specified properties of multiple entities using the specified batch size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * // Prepare updates
     * users.forEach(u -> u.setMigrated(true));
     *
     * // Update only the 'migrated' field in batches of 1000
     * int updated = userDao.batchUpdate(users, Arrays.asList("migrated"), 1000);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for each entity (must not be {@code null} or empty)
     * @param batchSize the number of entities to process in each batch
     * @return the total number of rows updated
     * @throws IllegalArgumentException if {@code propNamesToUpdate} is {@code null} or empty, or if {@code batchSize} is not positive,
     *         if a batch row is {@code null} and the SQL has other than one parameter,
     *         or a batch entity lacks a property required by the named SQL parameters
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing,
     *         binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     */
    @Override
    int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException;

}
