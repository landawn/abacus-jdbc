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

/**
 * Interface for an unchecked Data Access Object (DAO) that disables update and delete operations while
 * allowing read and insert operations. It supports create (insert/save) and read (select/query) operations,
 * while all update, delete, upsert, and stored-procedure (callable) methods are <b>absent from the type</b> —
 * calling them is a compile error rather than a runtime {@link UnsupportedOperationException}.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant. Read and insert methods redeclared by this interface or its
 * unchecked parents throw
 * {@link com.landawn.abacus.exception.UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * providing a more convenient API for developers who prefer unchecked exceptions. Inherited methods that are
 * not redeclared keep their checked-exception contract.</p>
 *
 * <p>The inherited raw-SQL {@code prepareQuery}/{@code prepareNamedQuery} overloads accept only
 * {@code SELECT} and {@code INSERT} statements at runtime (any other SQL kind fails with
 * {@link UnsupportedOperationException}), enforced centrally by the DAO proxy.</p>
 *
 * <p>This is useful for DAOs where update and delete operations should be prevented,
 * such as append-only data stores, immutable records, or when you want to ensure data is never modified
 * after creation.</p>
 *
 * <p><b>&#9888; Warning:</b> This is an API capability restriction, not a database security or
 * integrity boundary. Enforce append-only rules with database permissions, constraints, and an
 * appropriate transaction policy as well.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EventLogDao extends UncheckedNoUpdateDao<EventLog, EventLogDao> {
 *     // Can insert new logs and query existing logs
 *     // But cannot update or delete any logs
 * }
 *
 * EventLogDao dao = JdbcUtil.createDao(EventLogDao.class, dataSource);
 *
 * // Insert and query operations work without checked exception handling:
 * dao.save(new EventLog("System startup"));
 * List<EventLog> logs = dao.list(Filters.between("timestamp", startTime, endTime));
 * boolean hasErrors = dao.exists(Filters.eq("severity", "ERROR"));
 *
 * // Batch inserts are also supported:
 * List<EventLog> newLogs = Arrays.asList(
 *     new EventLog("User login"),
 *     new EventLog("Data export")
 * );
 * dao.batchSave(newLogs);
 *
 * // Can be used in functional contexts without try-catch:
 * Stream.of("INFO", "WARN", "ERROR")
 *       .forEach(level -> dao.save(new EventLog("Log level: " + level)));
 *
 * // Update and delete operations are absent from the type and do not compile:
 * // dao.update("status", "ARCHIVED", Filters.lt("timestamp", cutoffTime));   // does not compile
 * // dao.delete(Filters.eq("id", 123));   // does not compile
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedDao
 * @see NoUpdateDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface UncheckedNoUpdateDao<T, TD extends UncheckedNoUpdateDao<T, TD>>
        extends UncheckedReadOps<T, TD>, UncheckedInsertOps<T, TD>, NoUpdateDao<T, TD> {

}
