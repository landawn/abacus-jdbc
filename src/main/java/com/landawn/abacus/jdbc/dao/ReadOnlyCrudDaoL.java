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
import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only CRUD (Create, Read, Update, Delete) Data Access Object interface specifically
 * designed for entities with {@code Long} type primary keys. This interface combines
 * {@link ReadOnlyCrudDao} and {@link NoUpdateCrudDaoL} to provide a complete read-only
 * DAO implementation for entities using Long identifiers.
 *
 * <p>This interface provides convenience methods that accept primitive {@code long} values
 * for ID parameters, making it more convenient than using {@code Long} objects while
 * enforcing complete read-only access to the database.</p>
 *
 * <p>All mutation operations (insert, update, delete) inherited from the parent interfaces
 * will throw {@link UnsupportedOperationException}, while read operations remain functional.
 * This is ideal for:</p>
 * <ul>
 *   <li>Read-only database connections with Long ID entities</li>
 *   <li>Reporting systems that query numeric ID-based tables</li>
 *   <li>Data warehouses where modification is strictly prohibited</li>
 *   <li>Public APIs that provide read-only access to Long ID entities</li>
 * </ul>
 *
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO for reports with Long IDs
 * public interface ReportDao extends ReadOnlyCrudDaoL<Report, SQLBuilder.PSC, ReportDao> {
 *     // Inherits all read-only operations with Long ID type
 *     // Can use both primitive long and Long object IDs
 *
 *     @Query("SELECT * FROM reports WHERE created_date >= ?")
 *     List<Report> findRecentReports(Date since);
 * }
 *
 * // Usage with primitive long IDs:
 * Report report = reportDao.gett(123L); // Works - returns null if not found
 * Optional<Report> optReport = reportDao.get(456L); // Works - returns Optional
 * boolean exists = reportDao.exists(789L); // Works
 * List<Report> reports = reportDao.batchGet(Arrays.asList(1L, 2L, 3L)); // Works
 *
 * // Query operations work normally:
 * List<Report> activeReports = reportDao.list(CF.eq("status", "ACTIVE"));
 * long count = reportDao.count(CF.ge("created_date", startDate));
 *
 * // All modification operations throw UnsupportedOperationException:
 * reportDao.insert(new Report()); // Throws UnsupportedOperationException
 * reportDao.update("status", "ARCHIVED", 123L); // Throws UnsupportedOperationException
 * reportDao.deleteById(123L); // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used for query construction (must be SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see ReadOnlyCrudDao
 * @see NoUpdateCrudDaoL
 * @see CrudDaoL
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@Beta
public interface ReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends ReadOnlyCrudDaoL<T, SB, TD>>
        extends ReadOnlyCrudDao<T, Long, SB, TD>, NoUpdateCrudDaoL<T, SB, TD> {
}