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
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Define a read-only DAO for reporting
 * public interface ReportDao extends ReadOnlyCrudDao<Report, Long, SQLBuilder, ReportDao> {
 *     // Only read operations are available
 *     @Query("SELECT * FROM reports WHERE created_date >= ?")
 *     List<Report> findRecentReports(Date since);
 * }
 * 
 * // Usage:
 * Report report = reportDao.findById(123L); // Works
 * List<Report> reports = reportDao.list(); // Works
 * reportDao.insert(new Report()); // Throws UnsupportedOperationException
 * reportDao.update(report); // Throws UnsupportedOperationException
 * reportDao.deleteById(123L); // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the type of the entity
 * @param <ID> the type of the entity's identifier
 * @param <SB> the type of SQLBuilder used for query construction
 * @param <TD> the type of the DAO implementation (self-referencing type parameter)
 * @see ReadOnlyDao
 * @see NoUpdateCrudDao
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface ReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends ReadOnlyCrudDao<T, ID, SB, TD>>
        extends ReadOnlyDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD> {

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToInsert the entity to insert
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToInsert the entity to insert
     * @param propNamesToInsert collection of property names to insert
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entityToSave the entity to save
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default ID insert(final String namedInsertSQL, final T entityToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to insert
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to insert
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to insert
     * @param propNamesToInsert collection of property names to insert
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities collection of entities to insert
     * @param propNamesToInsert collection of property names to insert
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entities collection of entities to insert
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entities collection of entities to insert
     * @param batchSize the batch size for batch execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only DAO
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}