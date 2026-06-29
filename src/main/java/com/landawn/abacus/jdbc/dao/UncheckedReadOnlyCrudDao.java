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
 * A read-only CRUD DAO interface that provides only query operations without any insert, update, or delete capabilities.
 * This interface is useful for creating DAOs that should only have read access to the database,
 * ensuring data safety by throwing {@link UnsupportedOperationException} on any modification attempt.
 *
 * <p><b>Unchecked Exception Handling:</b></p>
 * <p>This is an "unchecked" DAO variant. Query methods redeclared by this interface or its unchecked
 * parents throw {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException}. Inherited
 * methods that are not redeclared keep their checked-exception contract.</p>
 *
 * <p>All write operations (insert, update, delete, and their batch variants) are disabled and throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserReadOnlyDao extends UncheckedReadOnlyCrudDao<User, Long, UserReadOnlyDao> {
 *     // Only query methods available, no insert/update/delete
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, readOnlyDataSource);
 *
 * // Query operations work without checked exception handling:
 * Optional<User> user = userDao.get(userId);
 * List<User> users = userDao.list(Filters.eq("status", "ACTIVE"));
 * boolean exists = userDao.exists(Filters.eq("email", "test@example.com"));
 * long count = userDao.count(Filters.gt("age", 18));
 *
 * // Can be used in functional contexts without try-catch:
 * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
 * List<User> usersFound = userIds.stream()
 *     .map(id -> userDao.get(id))
 *     .filter(Optional::isPresent)
 *     .map(Optional::get)
 *     .collect(Collectors.toList());
 *
 * // Write operations throw UnsupportedOperationException:
 * // userDao.insert(user);   // Throws UnsupportedOperationException
 * // userDao.update(user);   // Throws UnsupportedOperationException
 * // userDao.deleteById(id);   // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <ID> the type of the entity's primary key
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see UncheckedReadOnlyDao
 * @see UncheckedNoUpdateCrudDao
 * @see ReadOnlyCrudDao
 */
@Beta
public non-sealed interface UncheckedReadOnlyCrudDao<T, ID, TD extends UncheckedReadOnlyCrudDao<T, ID, TD>>
        extends UncheckedReadOnlyDao<T, TD>, UncheckedReadableCrudDao<T, ID, TD>, ReadOnlyCrudDao<T, ID, TD> {

}
