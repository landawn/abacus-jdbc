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
 * Completely read-only CRUD DAO that prevents all data modification operations.
 * This interface extends {@link ReadOnlyDao} and {@link CrudReadOps}, providing the most
 * restrictive CRUD DAO implementation where only read operations are permitted.
 *
 * <p>Insert, update, delete, and upsert operations are <b>absent from the type</b> — calling them is a
 * compile error rather than a runtime {@link UnsupportedOperationException}. (The inherited
 * {@code prepareQuery}/{@code prepareNamedQuery} overloads that take raw SQL reject any non-{@code SELECT}
 * statement at runtime; this SQL-kind gate is enforced centrally by the DAO proxy.)
 * This interface is ideal for:</p>
 * <ul>
 *   <li>Read-only database connections</li>
 *   <li>Reporting or analytics systems</li>
 *   <li>Public APIs that should never modify data</li>
 *   <li>Enforcing read-only access at the application level</li>
 * </ul>
 *
 * <p><b>Supported Read Operations:</b></p>
 * <ul>
 *   <li>{@code get(ID)} / {@code gett(ID)} - Retrieve entity by ID (returns {@code Optional} or {@code null})</li>
 *   <li>{@code list(Condition)} - Query multiple records matching a condition</li>
 *   <li>{@code findFirst(Condition)} - Find the first record matching a condition</li>
 *   <li>{@code findOnlyOne(Condition)} - Find exactly one record (throws if multiple found)</li>
 *   <li>{@code count(Condition)} - Count records matching a condition</li>
 *   <li>{@code exists(Condition)} - Check if any records match a condition</li>
 *   <li>{@code queryForBoolean/Int/Long/String(propName, ID)} - Query single column value by ID</li>
 *   <li>{@code queryForSingleValue(propName, ID, Class)} - Query single property value by ID</li>
 *   <li>{@code prepareQuery(String)} - Prepare {@code SELECT} queries for execution</li>
 * </ul>
 *
 * <p>This interface is marked as {@link Beta @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO for reporting
 * public interface ReportDao extends ReadOnlyCrudDao<Report, Long, ReportDao> {
 *     // Custom query methods can be added
 * }
 *
 * ReportDao reportDao = JdbcUtil.createDao(ReportDao.class, dataSource);
 *
 * // Supported operations - all work fine:
 *
 * // Get by ID (returns Optional)
 * Optional<Report> report = reportDao.get(123L);
 *
 * // Get by ID (returns null if not found)
 * Report report2 = reportDao.gett(456L);
 *
 * // List reports by condition
 * List<Report> activeReports = reportDao.list(Filters.eq("status", "ACTIVE"));
 *
 * // Find first report
 * Optional<Report> firstReport = reportDao.findFirst(Filters.gt("createdDate", someDate));
 *
 * // Count reports
 * int count = reportDao.count(Filters.eq("type", "MONTHLY"));
 *
 * // Check if report exists
 * boolean exists = reportDao.exists(Filters.eq("id", 789L));
 *
 * // Query single property by ID
 * Nullable<String> title = reportDao.queryForString("title", 123L);
 * OptionalInt year = reportDao.queryForInt("year", 123L);
 *
 * // Prepare custom SELECT queries
 * List<Report> results = reportDao.prepareQuery("SELECT * FROM reports WHERE year = ?")
 *                                 .setInt(1, 2023)
 *                                 .list(Report.class);
 *
 * // Unsupported operations - these are absent from the type and do not compile:
 * // reportDao.insert(new Report());     // does not compile
 * // reportDao.update(report2);          // does not compile
 * // reportDao.deleteById(123L);         // does not compile
 * // reportDao.batchInsert(reports);     // does not compile
 * // reportDao.upsert(report2);          // does not compile
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the type of the entity's primary key
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see ReadOnlyDao
 * @see CrudReadOps
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("RedundantThrows")
@Beta
public non-sealed interface ReadOnlyCrudDao<T, ID, TD extends ReadOnlyCrudDao<T, ID, TD>> extends ReadOnlyDao<T, TD>, CrudReadOps<T, ID, TD> {

}
