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
 * Read-only CRUD DAO for entities with {@code Long} primary keys.
 * This interface combines {@link ReadOnlyCrudDao} and {@link CrudLReadOps} to provide
 * a complete read-only DAO with convenient primitive {@code long} ID support.
 *
 * <p>All mutation operations (insert, update, delete, upsert) are <b>absent from the type</b> — calling
 * them is a compile error rather than a runtime {@link UnsupportedOperationException} — while read
 * operations remain fully functional. This is ideal for:</p>
 * <ul>
 *   <li>Read-only database connections with {@code Long} ID entities</li>
 *   <li>Reporting systems that query numeric ID-based tables</li>
 *   <li>Data warehouses where modification is strictly prohibited</li>
 *   <li>Public APIs that provide read-only access to {@code Long} ID entities</li>
 * </ul>
 *
 * <p>This interface is marked as {@link Beta @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO for reports with Long IDs
 * public interface ReportDao extends ReadOnlyCrudLDao<Report, ReportDao> {
 *     // Inherits all read-only operations with Long ID type
 *     // Can use both primitive long and Long object IDs
 *
 *     @Query("SELECT * FROM reports WHERE created_date >= ?")
 *     List<Report> findRecentReports(Date since);
 * }
 *
 * // Usage with primitive long IDs:
 * Report report = reportDao.gett(123L);   // Works - returns null if not found
 * Optional<Report> optReport = reportDao.get(456L);   // Works - returns Optional
 * boolean exists = reportDao.exists(789L);   // Works
 * List<Report> reports = reportDao.batchGet(Arrays.asList(1L, 2L, 3L));   // Works
 *
 * // Query operations work normally:
 * List<Report> activeReports = reportDao.list(Filters.eq("status", "ACTIVE"));
 * int count = reportDao.count(Filters.ge("created_date", startDate));
 *
 * // All modification operations are absent from the type and do not compile:
 * // reportDao.insert(new Report());                  // does not compile
 * // reportDao.update("status", "ARCHIVED", 123L);    // does not compile
 * // reportDao.deleteById(123L);                      // does not compile
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see ReadOnlyCrudDao
 * @see CrudLReadOps
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface ReadOnlyCrudLDao<T, TD extends ReadOnlyCrudLDao<T, TD>> extends ReadOnlyCrudDao<T, Long, TD>, LongIdCrudReadOps<T, TD> {
}
