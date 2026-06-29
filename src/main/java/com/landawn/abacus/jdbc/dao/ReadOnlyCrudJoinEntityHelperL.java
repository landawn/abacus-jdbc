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
 * Read-only helper for join entity operations in CRUD DAOs with {@code Long} primary keys.
 * This interface specializes {@link ReadOnlyCrudJoinEntityHelper} to entities with {@code Long} identifiers,
 * providing read-only access to join entity operations.
 *
 * <p>This interface enforces read-only behavior by inheriting from {@link ReadOnlyCrudJoinEntityHelper},
 * in which the join-entity mutation operations are <b>absent from the type</b> — calling them is a compile error
 * rather than a runtime {@link UnsupportedOperationException}. Only read and load operations for join entities
 * are available.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with join entity support for Long IDs
 * public interface UserReadOnlyDao
 *         extends ReadOnlyCrudDaoL<User, UserReadOnlyDao>, ReadOnlyCrudJoinEntityHelperL<User, UserReadOnlyDao> {
 *     // Can read join entities (e.g., user roles, permissions)
 *     // Cannot delete join entities
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource);
 *
 * // Get user by primitive long ID with join entities
 * Optional<User> user = userDao.get(123L, Order.class);
 * User userWithAll = userDao.gett(123L, true);   // Load all join entities
 *
 * // Batch operations with primitive long IDs
 * List<Long> ids = Arrays.asList(123L, 456L, 789L);
 * List<User> users = userDao.batchGet(ids, Order.class);
 *
 * // Load join entities for existing entity
 * User existingUser = userDao.gett(123L);
 * userDao.loadJoinEntities(existingUser, Role.class);   // Works fine
 *
 * // Delete-join operations are absent from the type and do not compile
 * // userDao.deleteJoinEntities(existingUser, Role.class);   // does not compile
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <TD> the companion {@link CrudDaoL} type (with {@code Long} primary key) that owns
 *             this helper, used for fluent method chaining and access to CRUD operations
 * @see ReadOnlyCrudJoinEntityHelper
 * @see CrudJoinEntityHelperL
 * @see CrudDaoL
 */
public interface ReadOnlyCrudJoinEntityHelperL<T, TD extends CrudDaoL<T, TD>> extends ReadOnlyCrudJoinEntityHelper<T, Long, TD> {

}
