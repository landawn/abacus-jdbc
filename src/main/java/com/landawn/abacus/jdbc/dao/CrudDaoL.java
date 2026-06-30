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

import java.sql.SQLException;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;

/**
 * A specialized {@link CrudDao} interface that fixes the ID type to {@code Long}.
 * This interface adds convenience overloads that accept a primitive {@code long} ID,
 * in addition to all of the {@code Long}-typed methods inherited from {@code CrudDao}.
 *
 * <p>This interface is particularly useful for entities that use numeric long IDs,
 * which is a common pattern in many database schemas. Each {@code long}-taking overload
 * simply boxes the value to {@link Long} and delegates to the corresponding {@code CrudDao}
 * method.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDaoL<User, UserDao> {
 *     // Inherits all CrudDao methods with Long ID type,
 *     // plus convenience overloads that accept primitive long
 * }
 *
 * // Usage with primitive long
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * Optional<User> user = userDao.get(123L);   // Can use primitive long
 * userDao.deleteById(456L);                  // More convenient than Long.valueOf(456)
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-type of the DAO for fluent interface support
 *
 * @see CrudDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface CrudDaoL<T, TD extends CrudDaoL<T, TD>> extends CrudDao<T, Long, TD>, ReadableLongIdCrudDao<T, TD> {

    /**
     * Updates a single property of an entity identified by ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code CrudDao.update(String, Object, ID)} method.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * userDao.update("lastLoginTime", new Date(), 123L);
     * userDao.update("failedLoginAttempts", 0, 123L);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the primitive long ID of the entity to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final String propName, final Object propValue, final long id) throws SQLException {
        return update(propName, propValue, Long.valueOf(id));
    }

    /**
     * Updates multiple properties of an entity identified by ID without loading the entire entity.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code CrudDao.update(Map, ID)} method.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("status", "ACTIVE");
     * updates.put("lastModified", new Date());
     * userDao.update(updates, 123L);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the primitive long ID of the entity to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final Map<String, Object> updateProps, final long id) throws SQLException {
        return update(updateProps, Long.valueOf(id));
    }

    /**
     * Deletes an entity by its ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to {@link CrudDao#deleteById(Object)}.
     * This is more efficient than loading the entity first and then deleting it.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deletedRows = userDao.deleteById(123L);
     * if (deletedRows == 0) {
     *     System.out.println("User not found");
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to delete
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws SQLException if a database access error occurs
     */
    default int deleteById(final long id) throws SQLException {
        return deleteById(Long.valueOf(id));
    }
}
