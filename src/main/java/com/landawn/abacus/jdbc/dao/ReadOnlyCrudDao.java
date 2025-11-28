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
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.query.SQLBuilder;

/**
 * A completely read-only CRUD Data Access Object interface that prevents all data modification operations.
 * This interface extends both {@link ReadOnlyDao} and {@link NoUpdateCrudDao}, providing the most
 * restrictive DAO implementation where only read operations are permitted.
 *
 * <p>All insert, update, delete, and upsert operations will throw {@link UnsupportedOperationException}.
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
 *   <li>{@code get(ID)} / {@code gett(ID)} - Retrieve entity by ID (returns Optional or null)</li>
 *   <li>{@code list(Condition)} - Query multiple records matching a condition</li>
 *   <li>{@code findFirst(Condition)} - Find the first record matching a condition</li>
 *   <li>{@code findOnlyOne(Condition)} - Find exactly one record (throws exception if multiple found)</li>
 *   <li>{@code count(Condition)} - Count records matching a condition</li>
 *   <li>{@code exists(Condition)} - Check if any records match a condition</li>
 *   <li>{@code queryForBoolean/Int/Long/String(propName, ID)} - Query single column value by ID</li>
 *   <li>{@code queryForSingleResult(propName, ID, Class)} - Query single property value by ID</li>
 *   <li>{@code prepareQuery(String)} - Prepare SELECT queries for execution</li>
 * </ul>
 *
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO for reporting
 * public interface ReportDao extends ReadOnlyCrudDao<Report, Long, SQLBuilder.PSC, ReportDao> {
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
 * // Unsupported operations - all throw UnsupportedOperationException:
 * reportDao.insert(new Report());  // Throws exception
 * reportDao.update(report2);  // Throws exception
 * reportDao.deleteById(123L);  // Throws exception
 * reportDao.batchInsert(reports);  // Throws exception
 * reportDao.upsert(report2);  // Throws exception
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity
 * @param <SB> the SQLBuilder type used for query construction
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see ReadOnlyDao
 * @see NoUpdateCrudDao
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface ReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends ReadOnlyCrudDao<T, ID, SB, TD>>
        extends ReadOnlyDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD> {

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToInsert the entity to insert (must not be null)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToInsert the entity to insert (must not be null)
     * @param propNamesToInsert the property names to include in the INSERT statement.
     *                          If {@code null} or empty, all properties will be inserted
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entityToSave the entity whose properties will be bound to the named parameters
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default ID insert(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to insert
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to insert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as insert operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
