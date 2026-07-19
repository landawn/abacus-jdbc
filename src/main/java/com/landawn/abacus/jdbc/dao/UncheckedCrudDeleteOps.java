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
 * Unchecked-exception delete capability of {@link UncheckedCrudDao}.
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
     * Deletes the specified entity from the database. The entity must have its ID set.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * int deletedRows = userDao.delete(user);
     * if (deletedRows > 0) {
     *     System.out.println("User deleted successfully");
     * }
     * }</pre>
     *
     * @param entity the entity to delete (must have ID set)
     * @return the number of rows deleted (typically 1 or 0)
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int delete(final T entity) throws UncheckedSQLException;

    /**
     * Deletes the entity with the specified ID from the database.
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
     * @return the number of rows deleted (typically 1 or 0)
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int deleteById(final ID id) throws UncheckedSQLException;

    /**
     * Batch deletes multiple entities from the database using the default batch size.
     * Each entity must have its ID set.
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
     * Batch deletes multiple entities from the database using the specified batch size.
     * Each entity must have its ID set.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> thousandsToDelete = getObsoleteUsers();
     * // Delete in batches of 500
     * int totalDeleted = userDao.batchDelete(thousandsToDelete, 500);
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @param batchSize the size of each batch
     * @return the total number of rows deleted
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

    /**
     * Batch deletes entities by their IDs using the default batch size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIdsToDelete = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * int totalDeleted = userDao.batchDeleteByIds(userIdsToDelete);
     * }</pre>
     *
     * @param ids the collection of entity IDs to delete
     * @return the total number of rows deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UncheckedSQLException {
        return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch deletes entities by their IDs using the specified batch size.
     * This is more efficient than deleting entities one by one, especially for large collections.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<Long> thousandsOfIds = getExpiredUserIds();
     * // Delete in batches of 1000 to avoid query size limits
     * int totalDeleted = userDao.batchDeleteByIds(thousandsOfIds, 1000);
     * }</pre>
     *
     * @param ids the collection of entity IDs to delete
     * @param batchSize the size of each batch
     * @return the total number of rows deleted
     * @throws IllegalArgumentException if {@code batchSize} is not positive
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UncheckedSQLException;

}
