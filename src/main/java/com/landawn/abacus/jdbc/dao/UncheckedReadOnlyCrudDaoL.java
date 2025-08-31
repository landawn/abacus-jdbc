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
 * A read-only CRUD (Create, Read, Update, Delete) DAO interface specialized for entities with Long-type identifiers,
 * providing unchecked exception handling. This interface combines the read-only CRUD capabilities with Long ID support
 * while throwing unchecked exceptions instead of checked SQLExceptions.
 * 
 * <p>This interface extends both {@link UncheckedReadOnlyCrudDao} with Long as the ID type and 
 * {@link UncheckedNoUpdateCrudDaoL} to provide a complete read-only DAO with Long IDs that uses unchecked exceptions.</p>
 * 
 * <p>All mutation operations (insert, update, delete) inherited from parent interfaces will throw 
 * {@link UnsupportedOperationException}, while read operations (select, exists, count) remain fully functional.</p>
 * 
 * <p>Example usage:
 * <pre>{@code
 * @Dao
 * public interface UserDao extends UncheckedReadOnlyCrudDaoL<User, SQLBuilder, UserDao> {
 *     // Additional query methods can be added here
 * }
 * 
 * // Usage
 * UserDao userDao = daoFactory.getUserDao();
 * 
 * // Read operations work normally
 * User user = userDao.selectById(123L);
 * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
 * boolean exists = userDao.existsById(123L);
 * long count = userDao.count();
 * 
 * // Write operations will throw UnsupportedOperationException
 * // userDao.insert(newUser); // This will fail
 * // userDao.deleteById(123L); // This will fail
 * }</pre></p>
 * 
 * <p>This interface is particularly useful when you want to:
 * <ul>
 *   <li>Enforce read-only access at compile time</li>
 *   <li>Work with entities that use Long as their primary key type</li>
 *   <li>Avoid handling checked SQLExceptions in your code</li>
 *   <li>Create separate read-only and read-write DAO interfaces for the same entity</li>
 * </ul></p>
 * 
 * @param <T> the type of the entity managed by this DAO
 * @param <SB> the type of SQLBuilder used for query construction
 * @param <TD> the type of the DAO extending this interface (self-referencing for fluent API support)
 * 
 * @see UncheckedReadOnlyCrudDao
 * @see UncheckedNoUpdateCrudDaoL
 * @see UncheckedSQLException
 * 
 * @author Haiyang Li
 * @since 3.0
 */
@Beta
public interface UncheckedReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedReadOnlyCrudDaoL<T, SB, TD>>
        extends UncheckedReadOnlyCrudDao<T, Long, SB, TD>, UncheckedNoUpdateCrudDaoL<T, SB, TD> {
}