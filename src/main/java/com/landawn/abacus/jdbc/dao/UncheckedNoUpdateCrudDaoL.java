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
import com.landawn.abacus.query.SqlBuilder;

/**
 * A no-update CRUD DAO interface specialized for entities whose primary key type is {@link Long},
 * with unchecked exception handling. This interface provides convenience method overrides that accept
 * primitive {@code long} values in addition to the {@code Long} object methods inherited from
 * {@link UncheckedNoUpdateCrudDao}.
 *
 * <p>This interface combines the restrictions of a no-update DAO (no update or delete operations allowed)
 * with the convenience of primitive {@code long} ID methods. It's useful for append-only data stores
 * where records can be added and read, but never modified.</p>
 *
 * <p>Read and insert methods throw {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * making it easier to work with in functional programming contexts. All update and delete operations
 * (including the primitive {@code long} ID variants declared on this interface) throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EventLogDao extends UncheckedNoUpdateCrudDaoL<EventLog, SqlBuilder.PSC, EventLogDao> {
 *     // Can insert and query, but not update/delete
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
 * @param <SB> the {@link SqlBuilder} type used to generate SQL statements; must be one of
 *             {@code SqlBuilder.PSC}, {@code SqlBuilder.PAC}, {@code SqlBuilder.PLC}, or {@code SqlBuilder.PSB}
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedNoUpdateCrudDao
 * @see UncheckedCrudDaoL
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public interface UncheckedNoUpdateCrudDaoL<T, SB extends SqlBuilder, TD extends UncheckedNoUpdateCrudDaoL<T, SB, TD>>
        extends UncheckedNoUpdateCrudDao<T, Long, SB, TD>, UncheckedCrudDaoL<T, SB, TD> {

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * This {@code long}-keyed single-property update overload is disallowed by
     * {@code UncheckedNoUpdateCrudDaoL} because it would mutate an existing record,
     * violating the read/insert-only contract.
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the primitive {@code long} ID of the entity to update
     * @return never returns normally
     * @throws UnsupportedOperationException always, since updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDaoL}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * This {@code long}-keyed multi-property update overload is disallowed by
     * {@code UncheckedNoUpdateCrudDaoL} because it would mutate an existing record,
     * violating the read/insert-only contract.
     *
     * @param updateProps a map of property names to their new values
     * @param id the primitive {@code long} ID of the entity to update
     * @return never returns normally
     * @throws UnsupportedOperationException always, since updates are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDaoL}. Updates are not allowed.
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     * This {@code long}-keyed delete-by-id overload is disallowed by
     * {@code UncheckedNoUpdateCrudDaoL} because it would remove an existing record,
     * violating the read/insert-only contract.
     *
     * @param id the primitive {@code long} ID of the entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletes are not permitted
     * @deprecated Unsupported in {@code UncheckedNoUpdateCrudDaoL}. Deletes are not allowed.
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
