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
import java.util.HashMap;
import java.util.Map;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.util.N;

/**
 * Update capability of {@link CrudDao}: id/entity-based {@code update}/{@code batchUpdate}.
 * Extends {@link UpdateOps}.
 *
 * @param <T> entity type
 * @param <ID> id type
 * @param <TD> self DAO type
 * @see CrudDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface CrudUpdateOps<T, ID, TD extends DaoBase<T, TD>> extends UpdateOps<T, TD> permits CrudDao, UncheckedCrudUpdateOps {
    /**
     * Updates an existing entity in the database, locating the row by its ID property(ies).
     * All updatable properties of the entity will be written; the entity's ID must be populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new java.util.Date());
     * int updatedRows = userDao.update(user);
     * }</pre>
     *
     * @param entity the entity with updated values (must have its ID populated)
     * @return the number of rows updated (typically 1 if successful, 0 if not found); also 0, without executing any
     *         statement, if the entity class has no updatable non-ID property
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws UncheckedSQLException if acquiring a required database connection fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     */
    int update(final T entity) throws IllegalArgumentException, UncheckedSQLException, SQLException;

    /**
     * Updates only the specified properties of an existing entity.
     * Properties not included in {@code propNamesToUpdate} will not be modified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new java.util.Date());
     * // Only update email and lastModified fields
     * int rows = userDao.update(user, Arrays.asList("email", "lastModified"));
     * }</pre>
     *
     * @param entity the entity containing the values to update
     * @param propNamesToUpdate the property names to update (must not be {@code null} or empty)
     * @return the number of rows updated
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or if {@code propNamesToUpdate} is {@code null} or empty
     * @throws UncheckedSQLException if acquiring a required database connection fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     */
    int update(final T entity, final Collection<String> propNamesToUpdate) throws IllegalArgumentException, UncheckedSQLException, SQLException;

    /**
     * Updates a single property of the entity identified by ID.
     * Convenience method for updating one property without building a map of properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * userDao.update("lastLoginTime", new java.util.Date(), userId);
     * userDao.update("failedLoginAttempts", 0, userId);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the ID of the entity to update
     * @return the number of rows updated
     * @throws IllegalArgumentException if {@code propName} is {@code null} or empty, or if {@code id} is {@code null}
     * @throws UncheckedSQLException if acquiring a required database connection fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     */
    default int update(final String propName, final Object propValue, final ID id) throws IllegalArgumentException, UncheckedSQLException, SQLException {
        N.checkArgNotEmpty(propName, cs.propName);
        N.checkArgNotNull(id, cs.id);

        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, id);
    }

    /**
     * Updates multiple properties of an entity identified by ID without loading the entire entity.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("status", "ACTIVE");
     * updates.put("lastModified", new java.util.Date());
     * updates.put("modifiedBy", currentUserId);
     * userDao.update(updates, userId);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the ID of the entity to update
     * @return the number of rows updated
     * @throws IllegalArgumentException if {@code updateProps} is {@code null} or empty, or if {@code id} is {@code null}
     * @throws UncheckedSQLException if acquiring a required database connection fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     */
    int update(final Map<String, Object> updateProps, final ID id) throws IllegalArgumentException, UncheckedSQLException, SQLException;

    /**
     * Performs batch update of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * All updatable properties of each entity will be updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsersToUpdate();
     * users.forEach(u -> u.setLastModified(new java.util.Date()));
     * int totalUpdated = userDao.batchUpdate(users);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @return the total number of rows updated
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a required database connection fails, or starting or completing an internally required transaction fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     */
    default int batchUpdate(final Collection<? extends T> entities) throws IllegalStateException, UncheckedSQLException, SQLException, ArithmeticException {
        return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch update of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = loadUsers();   // 5000 users
     * largeUserList.forEach(u -> u.setLastModified(new java.util.Date()));
     * // Process in batches of 500
     * int totalUpdated = userDao.batchUpdate(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows updated; 0, without executing any statement, if {@code entities} is {@code null}
     *         or empty or the entity class has no updatable non-ID property
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a required database connection fails, or starting or completing an internally required transaction fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     */
    int batchUpdate(final Collection<? extends T> entities, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, SQLException, ArithmeticException;

    /**
     * Performs batch update of multiple entities updating only the specified properties.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsers();
     * users.forEach(u -> {
     *     u.setStatus("INACTIVE");
     *     u.setDeactivatedDate(new java.util.Date());
     * });
     * int rows = userDao.batchUpdate(users, Arrays.asList("status", "deactivatedDate"));
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities (must not be {@code null} or empty)
     * @return the total number of rows updated
     * @throws IllegalArgumentException if {@code propNamesToUpdate} is {@code null} or empty
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a required database connection fails, or starting or completing an internally required transaction fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     */
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, SQLException, ArithmeticException {
        return batchUpdate(entities, propNamesToUpdate, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch update of multiple entities updating only specified properties with custom batch size.
     * This provides the most control over batch update operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadLargeUserList();   // 10000 users
     * users.forEach(u -> {
     *     u.setStatus("VERIFIED");
     *     u.setVerifiedDate(new java.util.Date());
     * });
     * // Update only status and verifiedDate in batches of 500
     * int rows = userDao.batchUpdate(users, Arrays.asList("status", "verifiedDate"), 500);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities (must not be {@code null} or empty)
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows updated
     * @throws IllegalArgumentException if {@code propNamesToUpdate} is {@code null} or empty, or if {@code batchSize} is not positive
     * @throws IllegalStateException if an existing transaction on the current thread is no longer active and cannot accept
     *         the internally required transaction scope
     * @throws UncheckedSQLException if acquiring a required database connection fails, or starting or completing an internally required transaction fails
     * @throws SQLException if preparing, binding, or executing an UPDATE statement fails
     * @throws ArithmeticException if the total affected-row count overflows an {@code int}
     */
    int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws IllegalArgumentException, IllegalStateException, UncheckedSQLException, SQLException, ArithmeticException;

}
