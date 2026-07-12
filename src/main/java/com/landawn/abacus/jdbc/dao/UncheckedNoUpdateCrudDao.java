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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;

/**
 * A specialized CRUD DAO interface that disables update and delete operations while allowing read and insert operations.
 * This interface is designed for use cases where stored records must remain immutable after insertion.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant. Read and insert methods redeclared by this interface or its
 * unchecked parents throw {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException}.
 * Inherited methods that are not redeclared keep their checked-exception contract.</p>
 *
 * <p>This interface extends {@link UncheckedNoUpdateDao}, {@link NoUpdateCrudDao}, {@link UncheckedCrudReadOps}
 * and {@link UncheckedCrudInsertOps} to provide comprehensive read/insert functionality while blocking update
 * and delete operations. It's particularly useful in audit systems, append-only data stores, or scenarios where
 * historical data must remain immutable.</p>
 *
 * <p>All update-related methods (including {@code update}, {@code batchUpdate}, and {@code upsert}) and all
 * delete-related methods are <b>absent from the type</b> — calling them is a compile error rather than a runtime
 * {@link UnsupportedOperationException}.</p>
 *
 * <p>The inherited raw-SQL {@code prepareQuery}/{@code prepareNamedQuery} overloads accept only
 * {@code SELECT} and {@code INSERT} statements at runtime (any other SQL kind fails with
 * {@link UnsupportedOperationException}), enforced centrally by the DAO proxy.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface AuditLogDao extends UncheckedNoUpdateCrudDao<AuditLog, Long, AuditLogDao> {
 *     // Only read and insert operations available
 * }
 *
 * AuditLogDao auditDao = JdbcUtil.createDao(AuditLogDao.class, dataSource);
 *
 * // Insert operations work without checked exception handling:
 * AuditLog log = new AuditLog("User login", userId);
 * Long id = auditDao.insert(log);
 *
 * // Batch inserts are also supported:
 * List<AuditLog> logs = Arrays.asList(
 *     new AuditLog("Action 1", userId),
 *     new AuditLog("Action 2", userId)
 * );
 * List<Long> ids = auditDao.batchInsert(logs);
 *
 * // Read operations work without checked exception handling:
 * Optional<AuditLog> retrieved = auditDao.get(id);
 * List<AuditLog> userLogs = auditDao.list(Filters.eq("userId", userId));
 * int count = auditDao.count(Filters.between("timestamp", startDate, endDate));
 *
 * // Can be used in functional contexts without try-catch:
 * List<Long> logIds = Arrays.asList(1L, 2L, 3L);
 * logIds.forEach(logId -> auditDao.get(logId).ifPresent(System.out::println));
 *
 * // Update operations are absent from the type and do not compile:
 * // auditDao.update(log);   // does not compile
 * // auditDao.upsert(log);   // does not compile
 *
 * // Delete operations are also absent from the type and do not compile:
 * // auditDao.deleteById(id);   // does not compile
 * // auditDao.delete(Filters.lt("timestamp", cutoffDate));   // does not compile
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the type of the entity's primary key
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedNoUpdateDao
 * @see NoUpdateCrudDao
 * @see UncheckedCrudReadOps
 * @see UncheckedCrudInsertOps
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface UncheckedNoUpdateCrudDao<T, ID, TD extends UncheckedNoUpdateCrudDao<T, ID, TD>>
        extends UncheckedCrudReadOps<T, ID, TD>, UncheckedCrudInsertOps<T, ID, TD>, UncheckedNoUpdateDao<T, TD>, NoUpdateCrudDao<T, ID, TD> {

}
