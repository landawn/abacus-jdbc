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
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Unchecked-exception delete capability of {@link UncheckedCrudDao}: the {@link CrudDeleteOps} operations
 * re-declared to throw {@link UncheckedSQLException}.
 * 
 * @param <T> entity type
 * @param <ID> id type
 * @param <TD> self DAO type
 * @see CrudDeleteOps
 * @see UncheckedCrudDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
@Beta
sealed interface UncheckedCrudDeleteOps<T, ID, TD extends UncheckedDaoBase<T, TD>> extends CrudDeleteOps<T, ID, TD>, UncheckedDeleteOps<T, TD>
        permits UncheckedCrudDao {
    /**
     * Deletes an entity from the database, identifying it by its ID property(ies).
     * The entity must have its ID field(s) populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * int deletedRows = userDao.delete(user);
     * if (deletedRows > 0) {
     *     System.out.println("User deleted successfully");
     * }
     * }</pre>
     *
     * @param entity the entity to delete (must have its ID populated)
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int delete(final T entity) throws UncheckedSQLException;

    /**
     * Deletes an entity by its ID.
     * This is more efficient than loading the entity first and then deleting it.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deletedRows = userDao.deleteById(userId);
     * if (deletedRows == 0) {
     *     System.out.println("User not found");
     * }
     * }</pre>
     *
     * @param id the ID of the entity to delete
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws IllegalArgumentException if {@code id} is {@code null}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int deleteById(final ID id) throws UncheckedSQLException;

    /**
     * Performs batch delete of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * Each entity must have its ID field(s) populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToDelete = getInactiveUsers();
     * int totalDeleted = userDao.batchDelete(usersToDelete);
     * System.out.println("Deleted " + totalDeleted + " users");
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @return the total number of rows deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchDelete(final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch delete of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> thousandsToDelete = getObsoleteUsers();
     * // Delete in batches of 500
     * int totalDeleted = userDao.batchDelete(thousandsToDelete, 500);
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows deleted
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

    /**
     * Deletes multiple entities by their IDs using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * This is more efficient than deleting entities one by one.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIdsToDelete = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * int totalDeleted = userDao.batchDeleteByIds(userIdsToDelete);
     * }</pre>
     *
     * @param ids the collection of IDs to delete
     * @return the total number of rows deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UncheckedSQLException {
        return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Deletes multiple entities by their IDs with a specified batch size.
     * Large ID collections will be processed in batches to avoid database query size limits.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<Long> thousandsOfIds = getExpiredUserIds();
     * // Delete in batches of 1000 to avoid query size limits
     * int totalDeleted = userDao.batchDeleteByIds(thousandsOfIds, 1000);
     * }</pre>
     *
     * @param ids the collection of IDs to delete
     * @param batchSize the number of IDs to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows deleted
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UncheckedSQLException;

}
