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

import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only interface for handling join entity operations with CRUD DAOs.
 * This interface combines {@link ReadOnlyJoinEntityHelper} and {@link CrudJoinEntityHelper}
 * to provide read-only access to join entity operations for CRUD-based DAOs.
 *
 * <p>This interface is designed for scenarios where you need to read join entity
 * relationships (such as many-to-many relationships) but should not be able to
 * modify them. All delete operations for join entities are disabled and will
 * throw {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only CRUD DAO with join entity support
 * public interface UserReadOnlyDao extends ReadOnlyCrudJoinEntityHelper<User, Long, SQLBuilder.PSC, UserReadOnlyDao> {
 *     // Inherits both CRUD read operations and join entity loading
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource);
 *
 * // Get user by ID with join entities loaded
 * Optional<User> user = userDao.get(1L, Order.class);
 * User userWithAll = userDao.gett(1L, true);   // Load all join entities
 *
 * // Batch get with join entities
 * List<User> users = userDao.batchGet(Arrays.asList(1L, 2L, 3L), Order.class);
 *
 * // Load join entities for existing entities
 * User user = userDao.gett(1L);
 * userDao.loadJoinEntities(user, "orders");
 *
 * // All delete operations are blocked
 * try {
 *     userDao.deleteJoinEntities(user, Order.class);
 *     // Will throw UnsupportedOperationException
 * } catch (UnsupportedOperationException e) {
 *     // Expected - this is a read-only interface
 * }
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <ID> the ID type of the entity
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see ReadOnlyJoinEntityHelper
 * @see CrudJoinEntityHelper
 * @see CrudDao
 */
public interface ReadOnlyCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>>
        extends ReadOnlyJoinEntityHelper<T, SB, TD>, CrudJoinEntityHelper<T, ID, SB, TD> {

}
