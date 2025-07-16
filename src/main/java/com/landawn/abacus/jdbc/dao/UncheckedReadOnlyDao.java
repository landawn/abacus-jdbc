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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only DAO interface that provides only query operations without any write capabilities.
 * This interface disables all insert, update, and delete operations by throwing {@code UnsupportedOperationException}.
 * It's ideal for scenarios where data should only be read, never modified.
 * 
 * <p>This is a beta API that extends {@code UncheckedNoUpdateDao} and further restricts save/insert operations,
 * ensuring complete read-only access to the database.</p>
 * 
 * <p>Use cases include:</p>
 * <ul>
 *   <li>Reporting databases where data should never be modified</li>
 *   <li>Read-only database replicas</li>
 *   <li>Views or materialized views that shouldn't be updated</li>
 *   <li>Historical data that must remain immutable</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface ReportDao extends UncheckedReadOnlyDao<Report, SQLBuilder.PSC, ReportDao> {
 *     // Only query methods are available
 * }
 * 
 * ReportDao dao = JdbcUtil.createDao(ReportDao.class, readOnlyDataSource);
 * 
 * // These operations work:
 * List<Report> reports = dao.list(CF.between("date", startDate, endDate));
 * Optional<Report> report = dao.findFirst(CF.eq("id", reportId));
 * DataSet results = dao.query(CF.eq("status", "PUBLISHED"));
 * 
 * // All write operations throw UnsupportedOperationException:
 * // dao.save(report);         // throws exception
 * // dao.update(...);          // throws exception  
 * // dao.delete(...);          // throws exception
 * // dao.batchSave(reports);   // throws exception
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedNoUpdateDao
 * @see ReadOnlyDao
 */
@Beta
public interface UncheckedReadOnlyDao<T, SB extends SQLBuilder, TD extends UncheckedReadOnlyDao<T, SB, TD>>
        extends UncheckedNoUpdateDao<T, SB, TD>, ReadOnlyDao<T, SB, TD> {

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entityToSave the entity to save (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entityToSave the entity to save (ignored)
     * @param propNamesToSave the properties to save (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (ignored)
     * @param entityToSave the entity to save (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final String namedInsertSQL, final T entityToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave the entities to save (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as batch save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave the entities to save (ignored)
     * @param batchSize the batch size (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as batch save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave the entities to save (ignored)
     * @param propNamesToSave the properties to save (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as batch save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave the entities to save (ignored)
     * @param propNamesToSave the properties to save (ignored)
     * @param batchSize the batch size (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as batch save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (ignored)
     * @param entitiesToSave the entities to save (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as batch save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named insert SQL (ignored)
     * @param entitiesToSave the entities to save (ignored)
     * @param batchSize the batch size (ignored)
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as batch save operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}