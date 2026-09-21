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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;

/**
 * A read-only DAO interface that provides only query operations without any write capabilities.
 * It is a pure capability composite of {@link UncheckedReadOps} (reads only) and {@link ReadOnlyDao}.
 * Save, update, delete, upsert, and batch-write operations are <b>absent from the type</b> — calling them is a
 * compile error rather than a runtime {@link UnsupportedOperationException} — so no modification of any kind is permitted.
 * It is ideal for scenarios where data should only be read, never modified.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant, meaning query methods redeclared by this interface or its
 * unchecked parents throw
 * {@link UncheckedSQLException} instead of checked {@link SQLException}.
 * Inherited methods that are not redeclared keep their checked-exception contract. Write operations
 * (save/update/delete/upsert/batch-write) are absent from the type (a compile error if called).</p>
 *
 * <p>This interface extends {@link UncheckedReadOps} and {@link ReadOnlyDao} to ensure complete
 * read-only access to the database. (The inherited raw-SQL {@code prepareQuery}/{@code prepareNamedQuery}
 * overloads reject any non-{@code SELECT} statement at runtime with an {@link UnsupportedOperationException},
 * enforced centrally by the DAO proxy.)</p>
 *
 * <p>The SQL argument is validated <i>before</i> its kind is inspected, so a {@code null} or empty
 * {@code sql}/{@code namedSql} still fails with the same {@link IllegalArgumentException} a full
 * {@link UncheckedDao} throws, never with a misleading {@link UnsupportedOperationException}.</p>
 *
 * <p>Use cases include:</p>
 * <ul>
 *   <li>Reporting databases where data should never be modified</li>
 *   <li>Read-only database replicas</li>
 *   <li>Views or materialized views that shouldn't be updated</li>
 *   <li>Historical data that must remain immutable</li>
 * </ul>
 *
 * <p>This interface is marked as {@link Beta @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>&#9888; Warning:</b> This is an API capability restriction, not a database security or
 * integrity boundary. Enforce read-only access with database permissions and an appropriate
 * read-only transaction policy as well.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface ReportDao extends UncheckedReadOnlyDao<Report, ReportDao> {
 *     // Only query methods are available
 * }
 *
 * ReportDao dao = JdbcUtil.createDao(ReportDao.class, readOnlyDataSource);
 *
 * // These operations work - note no checked exception handling needed:
 * List<Report> reports = dao.list(Filters.between("date", startDate, endDate));
 * com.landawn.abacus.util.u.Optional<Report> report = dao.findFirst(Filters.eq("id", reportId));
 * Dataset results = dao.query(Filters.eq("status", "PUBLISHED"));
 *
 * // Can be used directly in streams without checked exceptions:
 * java.util.stream.Stream.of(reportId1, reportId2, reportId3)
 *       .map(id -> dao.findFirst(Filters.eq("id", id)))
 *       .filter(result -> result.isPresent())
 *       .forEach(report -> System.out.println(report.get()));
 *
 * // All write operations are absent from the type and do not compile:
 * // dao.save(report);          // does not compile
 * // dao.update("status", "ARCHIVED", Filters.eq("id", reportId)); // does not compile
 * // dao.delete(Filters.eq("id", reportId));                        // does not compile
 * // dao.batchSave(reports);    // does not compile
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedReadOps
 * @see ReadOnlyDao
 */
@Beta
public non-sealed interface UncheckedReadOnlyDao<T, TD extends UncheckedReadOnlyDao<T, TD>> extends UncheckedReadOps<T, TD>, ReadOnlyDao<T, TD> {

}
