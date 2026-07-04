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
 * A read-only CRUD DAO helper interface with join entity capabilities that uses {@code Long} as the ID type
 * and throws unchecked exceptions. This interface combines read-only access restrictions with join entity
 * loading features for {@code Long}-ID entities. (The primitive {@code long} no-boxing overloads live on the
 * companion DAO's plain read side, {@link UncheckedCrudLReadOps}, not on this helper — the join-loading
 * {@code get}/{@code gett}/{@code batchGet} variants here take {@code Long}.)
 *
 * <p>This interface is ideal for read-only scenarios where:</p>
 * <ul>
 *   <li>Entities have relationships that need to be loaded</li>
 *   <li>The entity uses long/Long as the ID type</li>
 *   <li>Join entity relationships must not be deleted through this interface</li>
 *   <li>Unchecked exception handling is preferred</li>
 * </ul>
 *
 * <p>Read/load operations inherited from {@link UncheckedReadOnlyCrudJoinEntityHelper} throw
 * {@link com.landawn.abacus.exception.UncheckedSQLException} instead of the checked {@link java.sql.SQLException}.
 * All {@code deleteJoinEntities} and {@code deleteAllJoinEntities} operations are <b>absent from the type</b> —
 * calling them is a compile error rather than a runtime {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with Long IDs
 * public interface ReadOnlyUserDao
 *         extends UncheckedReadOnlyCrudLDao<User, ReadOnlyUserDao>, UncheckedReadOnlyCrudLJoinEntityHelper<User, ReadOnlyUserDao> {
 *     // Inherits read-only operations with join loading for Long-ID entities
 * }
 *
 * ReadOnlyUserDao dao = JdbcUtil.createDao(ReadOnlyUserDao.class, readOnlyDataSource);
 *
 * // Read operations with join loading - no checked exceptions
 * Optional<User> user = dao.get(123L, Order.class);   // loads user with orders
 * User userWithAll = dao.gett(123L, true);   // loads all relationships
 *
 * // Batch operations
 * List<Long> ids = Arrays.asList(123L, 456L, 789L);
 * List<User> users = dao.batchGet(ids, Order.class);
 *
 * // Get with selected properties and join entities
 * User userSelected = dao.gett(123L, Arrays.asList("id", "name", "email"), Order.class);
 *
 * // All delete-join operations are absent from the type and do not compile:
 * // dao.deleteJoinEntities(user, Order.class);   // does not compile
 * // dao.deleteAllJoinEntities(user);   // does not compile
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <TD> the DAO type that hosts this helper, bound to {@link UncheckedReadOnlyCrudLDao}
 * @see UncheckedReadOnlyJoinEntityHelper
 * @see UncheckedReadOnlyCrudJoinEntityHelper
 * @see UncheckedCrudLDao
 */
@Beta
public interface UncheckedReadOnlyCrudLJoinEntityHelper<T, TD extends UncheckedReadOnlyCrudLDao<T, TD>>
        extends UncheckedReadOnlyCrudJoinEntityHelper<T, Long, TD>, ReadOnlyCrudLJoinEntityHelper<T, TD> {
}
