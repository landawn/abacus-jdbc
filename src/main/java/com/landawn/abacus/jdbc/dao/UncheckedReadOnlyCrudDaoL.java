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
import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only CRUD DAO interface that uses {@code Long} as the ID type with unchecked exception handling.
 * This interface provides convenience methods that accept primitive {@code long} values
 * in addition to the {@code Long} object methods inherited from {@link UncheckedReadOnlyCrudDao}.
 *
 * <p>This interface is particularly useful for entities that use numeric long IDs,
 * which is a common pattern in many database schemas. All methods delegate to their
 * corresponding UncheckedReadOnlyCrudDao methods after boxing the primitive long to Long.</p>
 *
 * <p>This interface throws {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * making it easier to work with in functional programming contexts.</p>
 *
 * <p>All mutation operations (insert, update, delete) inherited from parent interfaces will throw
 * {@link UnsupportedOperationException}, while read operations (get, exists, list, count) remain fully functional.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends UncheckedReadOnlyCrudDaoL<User, SQLBuilder.PSC, UserDao> {
 *     // Additional query methods can be added here
 * }
 *
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 *
 * // Read operations work normally with primitive long
 * Optional<User> user = userDao.get(123L);
 * boolean exists = userDao.exists(123L);
 * List<User> users = userDao.list(CF.eq("status", "ACTIVE"));
 *
 * // Write operations will throw UnsupportedOperationException
 * // userDao.insert(newUser); // This will fail
 * // userDao.deleteById(123L); // This will fail
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedReadOnlyCrudDao
 * @see UncheckedNoUpdateCrudDaoL
 * @see UncheckedSQLException
 */
@Beta
public interface UncheckedReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedReadOnlyCrudDaoL<T, SB, TD>>
        extends UncheckedReadOnlyCrudDao<T, Long, SB, TD>, UncheckedNoUpdateCrudDaoL<T, SB, TD> {
}