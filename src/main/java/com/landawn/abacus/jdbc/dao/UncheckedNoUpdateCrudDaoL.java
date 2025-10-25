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

import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SQLBuilder;

/**
 * A no-update CRUD DAO interface that uses primitive {@code long} for ID operations.
 * This interface combines the restrictions of no-update DAO (no update operations allowed)
 * with the convenience of primitive long ID methods.
 * 
 * <p>This is a beta API that supports insert and read operations with primitive long IDs,
 * but disables all update and delete operations. It's useful for append-only data stores
 * where records can be added but never modified or removed.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EventLogDao extends UncheckedNoUpdateCrudDaoL<EventLog, SQLBuilder.PSC, EventLogDao> {
 *     // Can insert and query, but not update or delete
 * }
 * 
 * EventLogDao dao = JdbcUtil.createDao(EventLogDao.class, dataSource);
 * 
 * // These operations work with primitive long:
 * Long id = dao.insert(new EventLog("User logged in"));
 * Optional<EventLog> log = dao.get(123L);
 * boolean exists = dao.exists(123L);
 * List<EventLog> logs = dao.list(CF.eq("severity", "ERROR"));
 * 
 * // These operations throw UnsupportedOperationException:
 * // dao.update("status", "PROCESSED", 123L);  // not allowed
 * // dao.deleteById(123L);                      // not allowed
 * // dao.batchUpdate(logs);                     // not allowed
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedNoUpdateCrudDao
 * @see UncheckedCrudDaoL
 */
@Beta
public interface UncheckedNoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDaoL<T, SB, TD>>
        extends UncheckedNoUpdateCrudDao<T, Long, SB, TD>, UncheckedCrudDaoL<T, SB, TD> {

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     * 
     * <p>Even though this method accepts a convenient primitive long ID,
     * update operations are not allowed in no-update DAOs.</p>
     *
     * @param propName the property name to update (ignored)
     * @param propValue the new value (ignored)
     * @param id the entity ID as primitive long (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     * 
     * <p>Even though this method accepts a convenient primitive long ID,
     * update operations are not allowed in no-update DAOs.</p>
     *
     * @param updateProps the properties to update (ignored)
     * @param id the entity ID as primitive long (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as update operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     * 
     * <p>Even though this method accepts a convenient primitive long ID,
     * delete operations are not allowed in no-update DAOs.</p>
     *
     * @param id the entity ID as primitive long (ignored)
     * @return never returns, always throws exception
     * @throws UncheckedSQLException never thrown
     * @throws UnsupportedOperationException always thrown as delete operations are not allowed
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}