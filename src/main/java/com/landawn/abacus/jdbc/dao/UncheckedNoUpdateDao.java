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
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.query.SqlBuilder;
import com.landawn.abacus.query.condition.Condition;

/**
 * Interface for an unchecked Data Access Object (DAO) that disables update and delete operations while
 * allowing read and insert operations. It supports create (insert/save) and read (select/query) operations,
 * while all update and delete methods throw {@link UnsupportedOperationException}.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant. All read and insert methods throw
 * {@link com.landawn.abacus.exception.UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * providing a more convenient API for developers who prefer unchecked exceptions. This eliminates the need
 * for try-catch blocks or throws declarations, making the code cleaner and more suitable for use in
 * functional programming contexts.</p>
 *
 * <p>This is useful for DAOs where update and delete operations should be prevented,
 * such as append-only data stores, immutable records, or when you want to ensure data is never modified
 * after creation.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EventLogDao extends UncheckedNoUpdateDao<EventLog, SqlBuilder.PSC, EventLogDao> {
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
 * // Update and delete operations throw UnsupportedOperationException:
 * // dao.update("status", "ARCHIVED", Filters.lt("timestamp", cutoffTime));   // Throws exception
 * // dao.delete(Filters.eq("id", 123));   // Throws exception
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <SB> the {@link SqlBuilder} type used to generate SQL statements; must be one of
 *             {@code SqlBuilder.PSC}, {@code SqlBuilder.PAC}, or {@code SqlBuilder.PLC}
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedDao
 * @see NoUpdateDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public interface UncheckedNoUpdateDao<T, SB extends SqlBuilder, TD extends UncheckedNoUpdateDao<T, SB, TD>>
        extends UncheckedDao<T, SB, TD>, NoUpdateDao<T, SB, TD> {

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param propName the property name to update (unused; method always throws)
     * @param propValue the new value (unused; method always throws)
     * @param cond the condition to match records (unused; method always throws)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param updateProps the properties to update (unused; method always throws)
     * @param cond the condition to match records (unused; method always throws)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity containing values to update (unused; method always throws)
     * @param cond the condition to match records (unused; method always throws)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final T entity, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity containing values to update (unused; method always throws)
     * @param propNamesToUpdate the property names to update (unused; method always throws)
     * @param cond the condition to match records (unused; method always throws)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     * Upsert operations require update capability which is not allowed in this DAO type.
     *
     * @param entity the entity to upsert (unused; method always throws)
     * @param uniquePropNamesForQuery the property names used to look up existing records (unused; method always throws)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     * Upsert operations require update capability which is not allowed in this DAO type.
     *
     * @param entity the entity to upsert (unused; method always throws)
     * @param cond the condition to verify existence (unused; method always throws)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upsert operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param cond the condition to match records to delete (unused; method always throws)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as delete operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int delete(final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
