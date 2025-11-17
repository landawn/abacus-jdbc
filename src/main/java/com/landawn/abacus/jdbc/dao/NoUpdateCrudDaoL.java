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
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.query.SQLBuilder;

/**
 * A CRUD Data Access Object interface for entities with {@code Long} type primary keys
 * that disables update and delete operations while allowing read and insert operations. This interface extends both
 * {@link NoUpdateCrudDao} and {@link CrudDaoL}, providing read and insert operations
 * while blocking update and delete functionality.
 *
 * <p>This interface combines the convenience of primitive {@code long} ID support from
 * {@link CrudDaoL} with the safety guarantees of {@link NoUpdateCrudDao}, making it ideal
 * for append-only data stores with numeric identifiers.</p>
 *
 * <p>This interface is useful when you want to create a DAO that can read and insert
 * records but cannot modify or delete existing records. All update and delete methods
 * throw {@link UnsupportedOperationException}. This is particularly beneficial for:</p>
 * <ul>
 *   <li>Audit trail systems where historical records must be immutable</li>
 *   <li>Event sourcing patterns with Long ID events</li>
 *   <li>Log aggregation systems that only append new entries</li>
 *   <li>Transaction records that should never be modified once created</li>
 *   <li>Time-series data where updates would compromise data integrity</li>
 * </ul>
 *
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define an append-only DAO for audit logs with Long IDs
 * public interface AuditLogDao extends NoUpdateCrudDaoL<AuditLog, SQLBuilder.PSC, AuditLogDao> {
 *     // Can insert new audit logs and read existing ones
 *     // Cannot update or delete audit logs (immutable records)
 *
 *     @Query("SELECT * FROM audit_log WHERE user_id = ? ORDER BY created_at DESC")
 *     List<AuditLog> findByUserId(long userId);
 * }
 *
 * // Usage - Insert and read operations work:
 * AuditLog log = new AuditLog();
 * log.setUserId(123L);
 * log.setAction("LOGIN");
 * log.setTimestamp(new Date());
 * Long id = auditLogDao.insert(log); // Works - returns generated ID
 *
 * AuditLog retrieved = auditLogDao.gett(id); // Works (returns {@code null} if not found)
 * Optional<AuditLog> optional = auditLogDao.get(id); // Works (returns Optional)
 * boolean exists = auditLogDao.exists(id); // Works
 *
 * // Batch operations work for insert:
 * List<AuditLog> logs = generateAuditLogs();
 * List<Long> ids = auditLogDao.batchInsert(logs); // Works
 *
 * // Query operations work:
 * List<AuditLog> userLogs = auditLogDao.findByUserId(123L);
 * int logCount = auditLogDao.count(CF.eq("action", "LOGIN"));
 *
 * // All update and delete operations throw UnsupportedOperationException:
 * auditLogDao.update("status", "processed", id); // Throws UnsupportedOperationException
 * auditLogDao.deleteById(123L); // Throws UnsupportedOperationException
 * auditLogDao.batchDelete(logs); // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used for query construction (must be SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see NoUpdateCrudDao
 * @see CrudDaoL
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface NoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends NoUpdateCrudDaoL<T, SB, TD>>
        extends NoUpdateCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the primitive long ID of the entity to update
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param updateProps a map of property names to their new values
     * @param id the primitive long ID of the entity to update
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as updates are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param id the primitive long ID of the entity to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as deletes are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
