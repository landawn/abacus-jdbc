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

/**
 * Read-only helper for join entity operations in CRUD DAOs with {@code Long} primary keys.
 * This interface specializes {@link ReadOnlyCrudJoinEntityHelper} to entities with {@code Long} identifiers,
 * providing read-only access to join entity operations. (The primitive {@code long} no-boxing overloads live
 * on the companion DAO's plain read side, {@link CrudLReadOps}, not on this helper — the join-loading
 * {@code get}/{@code gett}/{@code batchGet} variants here take {@code Long}.)
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
 *         extends ReadOnlyCrudLDao<User, UserReadOnlyDao>, ReadOnlyCrudLJoinEntityHelper<User, UserReadOnlyDao> {
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
 * @param <TD> the companion {@link ReadOnlyCrudLDao} type (with {@code Long} primary key) that owns
 *             this helper, used for fluent method chaining and access to CRUD operations
 * @see ReadOnlyCrudJoinEntityHelper
 * @see CrudLJoinEntityHelper
 * @see CrudLDao
 */
@Beta
public interface ReadOnlyCrudLJoinEntityHelper<T, TD extends ReadOnlyCrudLDao<T, TD>> extends ReadOnlyCrudJoinEntityHelper<T, Long, TD> {

}
