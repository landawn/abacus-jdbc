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

import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Delete capability of {@link CrudDao}: {@code delete}/{@code deleteById}/{@code batchDelete}/{@code batchDeleteByIds}.
 * Extends {@link DeleteOps}.
 * 
 * @param <T> entity type
 * @param <ID> id type
 * @param <TD> self DAO type
 * @see CrudDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface CrudDeleteOps<T, ID, TD extends DaoBase<T, TD>> extends DeleteOps<T, TD> permits CrudDao, UncheckedCrudDeleteOps {
    /**
     * Deletes an entity from the database, identifying it by its ID property(ies).
     * The entity must have its ID field(s) populated.
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
     * @param entity the entity to delete (must have its ID populated)
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws SQLException if a database access error occurs
     */
    int delete(final T entity) throws SQLException;

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
     * @throws SQLException if a database access error occurs
     */
    int deleteById(final ID id) throws SQLException;

    /**
     * Performs batch delete of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * Each entity must have its ID field(s) populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToDelete = getInactiveUsers();
     * int totalDeleted = userDao.batchDelete(usersToDelete);
     * System.out.println(totalDeleted + " users deleted");
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    default int batchDelete(final Collection<? extends T> entities) throws SQLException {
        return batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch delete of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeDeleteList = getInactiveUsers();   // 20000 inactive users
     * int totalDeleted = userDao.batchDelete(largeDeleteList, 1000);
     * System.out.println(totalDeleted + " users deleted");
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    int batchDelete(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     * Deletes multiple entities by their IDs using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * This is more efficient than deleting entities one by one.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * int deletedCount = userDao.batchDeleteByIds(userIds);
     * System.out.println(deletedCount + " users deleted");
     * }</pre>
     *
     * @param ids the collection of IDs to delete
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws SQLException {
        return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Deletes multiple entities by their IDs with a specified batch size.
     * Large ID collections will be processed in batches to avoid database query size limits.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> largeIdList = getExpiredUserIds();   // 40000 IDs
     * // Delete in batches of 2000 to avoid SQL query size limits
     * int deletedCount = userDao.batchDeleteByIds(largeIdList, 2000);
     * }</pre>
     *
     * @param ids the collection of IDs to delete
     * @param batchSize the number of IDs to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws SQLException;

}
