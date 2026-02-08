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
 * A no-update CRUD DAO interface that uses {@code Long} as the ID type with unchecked exception handling.
 * This interface provides convenience methods that accept primitive {@code long} values
 * in addition to the {@code Long} object methods inherited from {@link UncheckedNoUpdateCrudDao}.
 *
 * <p>This interface combines the restrictions of no-update DAO (no update operations allowed)
 * with the convenience of primitive long ID methods. It's useful for append-only data stores
 * where records can be added and read, but never modified.</p>
 *
 * <p>This interface throws {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * making it easier to work with in functional programming contexts.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EventLogDao extends UncheckedNoUpdateCrudDaoL<EventLog, SQLBuilder.PSC, EventLogDao> {
 *     // Can insert and query, but not update
 * }
 *
 * EventLogDao dao = JdbcUtil.createDao(EventLogDao.class, dataSource);
 *
 * // These operations work with primitive long:
 * Long id = dao.insert(new EventLog("User logged in"));
 * Optional<EventLog> log = dao.get(123L);
 * boolean exists = dao.exists(123L);
 * List<EventLog> logs = dao.list(Filters.eq("severity", "ERROR"));
 *
 * // These operations throw UnsupportedOperationException:
 * // dao.update("status", "PROCESSED", 123L);   // not allowed
 * // dao.deleteById(123L);   // not allowed
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedNoUpdateCrudDao
 * @see UncheckedCrudDaoL
 */
@Beta
public interface UncheckedNoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDaoL<T, SB, TD>>
        extends UncheckedNoUpdateCrudDao<T, Long, SB, TD>, UncheckedCrudDaoL<T, SB, TD> {

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * <p>Even though this method accepts a convenient primitive long ID,
     * update operations are not allowed in no-update DAOs.</p>
     *
     * @param propName the property name to update (operation will fail)
     * @param propValue the new value (operation will fail)
     * @param id the entity ID as primitive long (operation will fail)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * <p>Even though this method accepts a convenient primitive long ID,
     * update operations are not allowed in no-update DAOs.</p>
     *
     * @param updateProps the properties to update (operation will fail)
     * @param id the entity ID as primitive long (operation will fail)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in a no-update DAO.
     * Always throws {@code UnsupportedOperationException}.
     *
     * <p>Even though this method accepts a convenient primitive long ID,
     * delete operations are not allowed in this no-update DAO variant.</p>
     *
     * @param id the entity ID as primitive long (operation will fail)
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as delete operations are not supported
     * @deprecated This operation is not supported and will always throw an exception
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
