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

import com.landawn.abacus.jdbc.JdbcUtil;

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
     * User user = userDao.gett(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new Date());
     * int updatedRows = userDao.update(user);
     * }</pre>
     *
     * @param entity the entity with updated values (must have its ID populated)
     * @return the number of rows updated (typically 1 if successful, 0 if not found)
     * @throws SQLException if a database access error occurs
     */
    int update(final T entity) throws SQLException;

    /**
     * Updates only specified properties of an existing entity.
     * This is useful when you want to update only certain fields.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new Date());
     * // Only update email and lastModified fields
     * int rows = userDao.update(user, Arrays.asList("email", "lastModified"));
     * }</pre>
     *
     * @param entity the entity containing the values to update
     * @param propNamesToUpdate the property names to update (must not be {@code null} or empty)
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code propNamesToUpdate} is {@code null} or empty
     */
    int update(final T entity, final Collection<String> propNamesToUpdate) throws SQLException;

    /**
     * Updates a single property of an entity identified by ID.
     * This is a convenience default method for updating one field; it builds a single-entry
     * map and delegates to {@link #update(Map, Object)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * userDao.update("lastLoginTime", new Date(), userId);
     * userDao.update("failedLoginAttempts", 0, userId);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the ID of the entity to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final String propName, final Object propValue, final ID id) throws SQLException {
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
     * updates.put("lastModified", new Date());
     * updates.put("modifiedBy", currentUserId);
     * userDao.update(updates, userId);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the ID of the entity to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    int update(final Map<String, Object> updateProps, final ID id) throws SQLException;

    /**
     * Performs batch update of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * All updatable properties of each entity will be updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsersToUpdate();
     * users.forEach(u -> u.setLastModified(new Date()));
     * int totalUpdated = userDao.batchUpdate(users);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @return the total number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int batchUpdate(final Collection<? extends T> entities) throws SQLException {
        return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch update of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = loadUsers();   // 5000 users
     * largeUserList.forEach(u -> u.setLastModified(new Date()));
     * // Process in batches of 500
     * int totalUpdated = userDao.batchUpdate(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows updated
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws SQLException if a database access error occurs
     */
    int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     * Performs batch update of multiple entities updating only the specified properties.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsers();
     * users.forEach(u -> {
     *     u.setStatus("INACTIVE");
     *     u.setDeactivatedDate(new Date());
     * });
     * int rows = userDao.batchUpdate(users, Arrays.asList("status", "deactivatedDate"));
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities (must not be {@code null} or empty)
     * @return the total number of rows updated
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code propNamesToUpdate} is {@code null} or empty
     */
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws SQLException {
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
     *     u.setVerifiedDate(new Date());
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
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code propNamesToUpdate} is {@code null} or empty, or if {@code batchSize} is not positive
     */
    int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws SQLException;

}
