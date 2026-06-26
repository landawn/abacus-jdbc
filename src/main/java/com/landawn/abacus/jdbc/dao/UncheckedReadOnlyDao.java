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

/**
 * A read-only DAO interface that provides only query operations without any write capabilities.
 * This interface disables all save and batch-save operations by overriding them to throw {@link UnsupportedOperationException},
 * and it inherits the disabling of update and delete operations from {@link UncheckedNoUpdateDao}, so no modification of any kind is permitted.
 * It is ideal for scenarios where data should only be read, never modified.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant, meaning query methods throw {@link com.landawn.abacus.exception.UncheckedSQLException}
 * instead of checked {@link java.sql.SQLException}. This eliminates the need for explicit try-catch blocks or
 * throws declarations, making the API more convenient for use in functional programming contexts and lambda expressions.
 * Write operations (save/batchSave) are disabled and throw {@link UnsupportedOperationException}.</p>
 *
 * <p>This interface extends both {@link UncheckedNoUpdateDao} and {@link ReadOnlyDao}, further
 * restricting save and batch-save operations to ensure complete read-only access to the database.</p>
 *
 * <p>Use cases include:</p>
 * <ul>
 *   <li>Reporting databases where data should never be modified</li>
 *   <li>Read-only database replicas</li>
 *   <li>Views or materialized views that shouldn't be updated</li>
 *   <li>Historical data that must remain immutable</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface ReportDao extends UncheckedReadOnlyDao<Report, ReportDao> {
 *     // Only query methods are available
 * }
 *
 * ReportDao dao = JdbcUtil.createDao(ReportDao.class, readOnlyDataSource, Dsl.PSC);
 *
 * // These operations work - note no checked exception handling needed:
 * List<Report> reports = dao.list(Filters.between("date", startDate, endDate));
 * Optional<Report> report = dao.findFirst(Filters.eq("id", reportId));
 * Dataset results = dao.query(Filters.eq("status", "PUBLISHED"));
 *
 * // Can be used directly in streams without checked exceptions:
 * Stream.of(reportId1, reportId2, reportId3)
 *       .map(id -> dao.findFirst(Filters.eq("id", id)))
 *       .filter(Optional::isPresent)
 *       .forEach(report -> System.out.println(report.get()));
 *
 * // All write operations throw UnsupportedOperationException:
 * // dao.save(report);   // throws exception
 * // dao.update(...);   // throws exception
 * // dao.delete(...);   // throws exception
 * // dao.batchSave(reports);   // throws exception
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedNoUpdateDao
 * @see ReadOnlyDao
 */
@Beta
public interface UncheckedReadOnlyDao<T, TD extends UncheckedReadOnlyDao<T, TD>> extends UncheckedNoUpdateDao<T, TD>, ReadOnlyDao<T, TD> {

    /**
     * Unsupported save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entityToSave the entity that would be saved
     * @throws UnsupportedOperationException always, since saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entityToSave the entity that would be saved
     * @param propNamesToSave the collection of property names that would be saved
     * @throws UnsupportedOperationException always, since saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSql the named SQL insert statement that would be executed
     * @param entityToSave the entity that would be saved
     * @throws UnsupportedOperationException always, since saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void save(final String namedInsertSql, final T entityToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported batch-save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave the collection of entities that would be saved
     * @throws UnsupportedOperationException always, since batch saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported batch-save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave the collection of entities that would be saved
     * @param batchSize the number of entities that would be processed per batch
     * @throws UnsupportedOperationException always, since batch saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported batch-save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave the collection of entities that would be saved
     * @param propNamesToSave the collection of property names that would be saved
     * @throws UnsupportedOperationException always, since batch saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported batch-save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave the collection of entities that would be saved
     * @param propNamesToSave the collection of property names that would be saved
     * @param batchSize the number of entities that would be processed per batch
     * @throws UnsupportedOperationException always, since batch saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported batch-save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSql the named SQL insert statement that would be executed
     * @param entitiesToSave the collection of entities that would be saved
     * @throws UnsupportedOperationException always, since batch saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSql, final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }

    /**
     * Unsupported batch-save operation that always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSql the named SQL insert statement that would be executed
     * @param entitiesToSave the collection of entities that would be saved
     * @param batchSize the number of entities that would be processed per batch
     * @throws UnsupportedOperationException always, since batch saves are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyDao}. All modifications are prohibited.
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSql, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException("This operation is not supported in a read-only DAO.");
    }
}
