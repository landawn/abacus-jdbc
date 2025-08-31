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
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.condition.Condition;

/**
 * Interface for an unchecked Data Access Object (DAO) that disables update and delete operations while allowing read and insert operations.
 * This interface provides all basic DAO operations except updates and deletes. It supports create (insert/save)
 * and read (select/query) operations, but all update and delete methods will throw {@code UnsupportedOperationException}.
 * 
 * <p>This is useful for DAOs where update and delete operations should be prevented,
 * such as append-only data stores, immutable records, or when you want to ensure data is never modified
 * after creation.</p>
 * 
 * <p>Its methods throw {@code UncheckedSQLException} instead of {@code SQLException}, providing a more
 * convenient API for developers who prefer unchecked exceptions.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface EventLogDao extends UncheckedNoUpdateDao<EventLog, SQLBuilder.PSC, EventLogDao> {
 *     // Can insert new logs and query existing logs
 *     // But cannot update or delete any logs
 * }
 * 
 * EventLogDao dao = JdbcUtil.createDao(EventLogDao.class, dataSource);
 * 
 * // These operations work:
 * dao.save(new EventLog("System startup"));
 * List<EventLog> logs = dao.list(CF.between("timestamp", startTime, endTime));
 * boolean hasErrors = dao.exists(CF.eq("severity", "ERROR"));
 * 
 * // These operations throw UnsupportedOperationException:
 * // dao.update("status", "ARCHIVED", CF.lt("timestamp", cutoffTime));
 * // dao.delete(CF.eq("id", 123));
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@Beta
public interface UncheckedNoUpdateDao<T, SB extends SQLBuilder, TD extends UncheckedNoUpdateDao<T, SB, TD>>
        extends UncheckedDao<T, SB, TD>, NoUpdateDao<T, SB, TD> {

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param propName the property name to update (ignored)
     * @param propValue the new value (ignored)
     * @param cond the condition to match records (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final Condition cond) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param updateProps the properties to update (ignored)
     * @param cond the condition to match records (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final Condition cond) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entity the entity containing values to update (ignored)
     * @param cond the condition to match records (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entity, final Condition cond) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entity the entity containing values to update (ignored)
     * @param propNamesToUpdate the properties to update (ignored)
     * @param cond the condition to match records (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     * Upsert operations require update capability which is not allowed in this DAO type.
     *
     * @param entity the entity to upsert (ignored)
     * @param uniquePropNamesForQuery the unique properties for query (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     * Upsert operations require update capability which is not allowed in this DAO type.
     *
     * @param entity the entity to upsert (ignored)
     * @param cond the condition to verify existence (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param cond the condition to match records to delete (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as delete operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int delete(final Condition cond) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}