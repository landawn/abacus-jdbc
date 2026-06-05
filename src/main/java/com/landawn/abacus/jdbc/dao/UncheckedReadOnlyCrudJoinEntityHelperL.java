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

import com.landawn.abacus.query.SqlBuilder;

/**
 * A read-only CRUD DAO helper interface with join entity capabilities that uses {@code Long} as the ID type
 * (with primitive {@code long} convenience overloads) and throws unchecked exceptions. This interface combines
 * read-only access restrictions with join entity loading features and optimized primitive long ID handling.
 *
 * <p>This interface is ideal for read-only scenarios where:</p>
 * <ul>
 *   <li>Entities have relationships that need to be loaded</li>
 *   <li>The entity uses long/Long as the ID type</li>
 *   <li>Join entity relationships must not be deleted through this interface</li>
 *   <li>Performance is critical (avoiding ID boxing/unboxing)</li>
 *   <li>Unchecked exception handling is preferred</li>
 * </ul>
 *
 * <p>Read/load operations inherited from {@link UncheckedCrudJoinEntityHelperL} and
 * {@link UncheckedReadOnlyJoinEntityHelper} throw {@link com.landawn.abacus.exception.UncheckedSQLException}
 * instead of the checked {@link java.sql.SQLException}. All {@code deleteJoinEntities} and
 * {@code deleteAllJoinEntities} operations throw {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with primitive long IDs
 * public interface ReadOnlyUserDao extends UncheckedReadOnlyCrudJoinEntityHelperL<User, SqlBuilder.PSC, ReadOnlyUserDao> {
 *     // Inherits read-only operations with join loading and primitive long ID support
 * }
 *
 * ReadOnlyUserDao dao = JdbcUtil.createDao(ReadOnlyUserDao.class, readOnlyDataSource);
 *
 * // Read operations with join loading using primitive long - no checked exceptions
 * Optional<User> user = dao.get(123L, Order.class);   // loads user with orders
 * User userWithAll = dao.gett(123L, true);   // loads all relationships
 *
 * // Batch operations with primitive long IDs
 * List<Long> ids = Arrays.asList(123L, 456L, 789L);
 * List<User> users = dao.batchGet(ids, Order.class);
 *
 * // Get with selected properties and join entities
 * User userSelected = dao.gett(123L, Arrays.asList("id", "name", "email"), Order.class);
 *
 * // All delete-join operations are disabled:
 * // dao.deleteJoinEntities(user, Order.class);   // throws UnsupportedOperationException
 * // dao.deleteAllJoinEntities(user);   // throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <SB> the {@link SqlBuilder} type used to generate SQL scripts (must be one of {@code SqlBuilder.PSC}, {@code SqlBuilder.PAC}, {@code SqlBuilder.PLC}, or {@code SqlBuilder.PSB})
 * @param <TD> the DAO type that hosts this helper, bound to {@link UncheckedCrudDaoL}
 * @see UncheckedReadOnlyJoinEntityHelper
 * @see UncheckedCrudJoinEntityHelperL
 * @see UncheckedCrudDaoL
 */
public interface UncheckedReadOnlyCrudJoinEntityHelperL<T, SB extends SqlBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
        extends UncheckedReadOnlyJoinEntityHelper<T, SB, TD>, UncheckedCrudJoinEntityHelperL<T, SB, TD> {
    // This interface combines read-only restrictions with join entity capabilities
    // and primitive long ID support. All methods are inherited from parent interfaces.
}
