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

/**
 * A specialized {@link UncheckedCrudDao} interface that uses {@code Long} as the ID type with unchecked exception handling.
 * This interface provides convenience methods that accept primitive {@code long} values
 * in addition to the {@code Long} object methods inherited from {@link UncheckedCrudDao}.
 *
 * <p>This interface is particularly useful for entities that use numeric long IDs,
 * which is a common pattern in many database schemas. All methods delegate to their
 * corresponding {@link UncheckedCrudDao} methods after boxing the primitive {@code long} to {@code Long}.</p>
 *
 * <p>This interface throws {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * making it easier to work with in functional programming contexts.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends UncheckedCrudLDao<User, UserDao> {
 *     // Inherits all UncheckedCrudDao methods with Long ID type
 *     // Plus convenience methods that accept primitive long
 * }
 *
 * // Usage with primitive long
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * Optional<User> user = userDao.get(123L);   // Can use primitive long
 * userDao.deleteById(456L);   // More convenient than Long.valueOf(456)
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedCrudDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface UncheckedCrudLDao<T, TD extends UncheckedCrudLDao<T, TD>>
        extends UncheckedCrudDao<T, Long, TD>, CrudLDao<T, TD>, UncheckedCrudLReadOps<T, TD> {

    /**
     * Updates a single property of an entity identified by ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao.update(String, Object, ID)} method.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int updated = userDao.update("status", "ACTIVE", 123L);
     * int updated2 = userDao.update("lastLogin", new Date(), 123L);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the primitive long ID of the entity to update
     * @return the number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UncheckedSQLException {
        return update(propName, propValue, Long.valueOf(id));
    }

    /**
     * Updates multiple properties of an entity identified by ID without loading the entire entity.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao.update(Map, ID)} method.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("email", "newemail@example.com");
     * updates.put("phone", "555-1234");
     * updates.put("modifiedDate", new Date());
     *
     * int updated = userDao.update(updates, 123L);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the primitive long ID of the entity to update
     * @return the number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UncheckedSQLException {
        return update(updateProps, Long.valueOf(id));
    }

    /**
     * Deletes an entity by its ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to {@link UncheckedCrudDao#deleteById(Object)}.
     * This is more efficient than loading the entity first and then deleting it.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deleted = userDao.deleteById(123L);
     * if (deleted > 0) {
     *     System.out.println("User deleted successfully");
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to delete
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int deleteById(final long id) throws UncheckedSQLException {
        return deleteById(Long.valueOf(id));
    }
}
