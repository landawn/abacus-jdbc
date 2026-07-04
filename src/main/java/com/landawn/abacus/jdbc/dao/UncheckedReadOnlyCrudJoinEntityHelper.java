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
 * A specialized interface that combines read-only operations with join entity support for CRUD DAOs with unchecked exceptions.
 * This interface provides functionality to read entities along with their related entities through joins,
 * but explicitly disables the delete operations on the joined entities.
 *
 * <p>This interface is particularly useful in scenarios where you need to fetch entities with their
 * relationships (like one-to-many or many-to-many) but want to ensure that the related entities
 * cannot be deleted through this DAO. It maintains data integrity by not exposing join-entity delete
 * operations while avoiding checked exception handling.</p>
 *
 * <p>Read/load operations inherited from {@link UncheckedCrudJoinEntityReadOps} and
 * {@link UncheckedReadOnlyJoinEntityHelper} throw {@link com.landawn.abacus.exception.UncheckedSQLException}
 * instead of the checked {@link java.sql.SQLException}. The {@code deleteJoinEntities}/{@code deleteAllJoinEntities}
 * operations are <b>absent from the type</b> — calling them is a compile error rather than a runtime
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only CRUD DAO with join entity support
 * public interface UserReadOnlyDao
 *         extends UncheckedReadOnlyCrudDao<User, Long, UserReadOnlyDao>, UncheckedReadOnlyCrudJoinEntityHelper<User, Long, UserReadOnlyDao> {
 *     // Inherits read-only CRUD and join entity operations with unchecked exceptions
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource);
 *
 * // Fetch a user with their orders - no checked exceptions
 * Optional<User> userWithOrders = userDao.get(123L, Order.class);
 * User userWithAll = userDao.gett(123L, true);   // Load all join entities
 *
 * // Batch get with join entities
 * List<User> users = userDao.batchGet(Arrays.asList(1L, 2L, 3L), Order.class);
 *
 * // The user object now contains loaded Order entities
 * if (userWithOrders.isPresent()) {
 *     User user = userWithOrders.get();
 *     List<Order> orders = user.getOrders();   // Orders are loaded
 *
 *     // But you cannot delete join entities through this DAO — the methods are absent from the type:
 *     // userDao.deleteJoinEntities(user, Order.class);   // does not compile
 * }
 * }</pre>
 *
 * <p>This interface extends {@link UncheckedReadOnlyJoinEntityHelper}, {@link ReadOnlyCrudJoinEntityHelper} and
 * {@link UncheckedCrudJoinEntityReadOps}, inheriting read operations from all of them; modification operations
 * are absent from the type (a compile error if called).</p>
 *
 * @param <T> the entity type that this helper manages
 * @param <ID> the ID type of the entity
 * @param <TD> the DAO type that hosts this helper, bound to {@link UncheckedReadOnlyCrudDao}
 * @see UncheckedReadOnlyJoinEntityHelper
 * @see UncheckedCrudJoinEntityReadOps
 * @see UncheckedCrudDao
 */
public non-sealed interface UncheckedReadOnlyCrudJoinEntityHelper<T, ID, TD extends UncheckedReadOnlyCrudDao<T, ID, TD>>
        extends UncheckedReadOnlyJoinEntityHelper<T, TD>, ReadOnlyCrudJoinEntityHelper<T, ID, TD>, UncheckedCrudJoinEntityReadOps<T, ID, TD> {
}
