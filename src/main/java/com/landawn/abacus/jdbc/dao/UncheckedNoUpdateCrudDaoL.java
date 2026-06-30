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
import com.landawn.abacus.exception.UncheckedSQLException;

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
 * <p>Read and insert methods redeclared by this interface or its unchecked parents throw
 * {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException}. Inherited methods that
 * are not redeclared keep their checked-exception contract. All update and delete operations
 * (including any primitive {@code long} ID variants) are <b>absent from the type</b> — calling them is a
 * compile error rather than a runtime {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface EventLogDao extends UncheckedNoUpdateCrudDaoL<EventLog, EventLogDao> {
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
 * // These operations are absent from the type and do not compile:
 * // dao.update("status", "PROCESSED", 123L);   // does not compile
 * // dao.deleteById(123L);                      // does not compile
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedNoUpdateCrudDao
 * @see UncheckedReadableCrudDaoL
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface UncheckedNoUpdateCrudDaoL<T, TD extends UncheckedNoUpdateCrudDaoL<T, TD>>
        extends UncheckedNoUpdateCrudDao<T, Long, TD>, UncheckedReadableLongIdCrudDao<T, TD> {

}
