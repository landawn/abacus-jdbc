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

/**
 * Read-only helper for join entity operations in CRUD DAOs.
 * This interface combines {@link ReadOnlyJoinEntityHelper} and {@link CrudJoinEntityHelper}
 * to provide read-only access to join entity operations for CRUD-based DAOs.
 *
 * <p>This interface is designed for scenarios where you need to read join entity
 * relationships (such as many-to-many associations) but should not be able to
 * modify them. All delete operations for join entities are disabled and throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only CRUD DAO with join entity support
 * public interface UserReadOnlyDao extends ReadOnlyCrudJoinEntityHelper<User, Long, UserReadOnlyDao> {
 *     // Inherits both CRUD read operations and join entity loading
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource, Dsl.PSC);
 *
 * // Get user by ID with join entities loaded
 * Optional<User> user = userDao.get(1L, Order.class);
 * User userWithAll = userDao.gett(1L, true);   // Load all join entities
 *
 * // Batch get with join entities
 * List<User> users = userDao.batchGet(Arrays.asList(1L, 2L, 3L), Order.class);
 *
 * // Load join entities for existing entities
 * User existingUser = userDao.gett(1L);
 * userDao.loadJoinEntities(existingUser, "orders");
 *
 * // All delete operations throw UnsupportedOperationException
 * userDao.deleteJoinEntities(existingUser, Order.class);   // Throws exception
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <ID> the type of the entity's primary key
 * @param <TD> the companion {@link CrudDao} type that owns this helper (used for fluent
 *             method chaining and access to CRUD operations)
 * @see ReadOnlyJoinEntityHelper
 * @see CrudJoinEntityHelper
 * @see CrudDao
 */
public interface ReadOnlyCrudJoinEntityHelper<T, ID, TD extends CrudDao<T, ID, TD>> extends ReadOnlyJoinEntityHelper<T, TD>, CrudJoinEntityHelper<T, ID, TD> {

}
