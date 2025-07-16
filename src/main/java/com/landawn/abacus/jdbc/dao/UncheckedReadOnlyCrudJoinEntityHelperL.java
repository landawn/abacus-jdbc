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
 * A read-only CRUD DAO interface with join entity helper capabilities that uses primitive {@code long} for ID operations.
 * This interface combines read-only access restrictions with join entity loading features and optimized
 * primitive long ID handling.
 * 
 * <p>This interface is ideal for read-only scenarios where:</p>
 * <ul>
 *   <li>Entities have relationships that need to be loaded</li>
 *   <li>The entity uses long/Long as the ID type</li>
 *   <li>Data should never be modified (read-only access)</li>
 *   <li>Performance is critical (avoiding ID boxing/unboxing)</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface ReadOnlyUserDao extends UncheckedReadOnlyCrudJoinEntityHelperL<User, SQLBuilder.PSC, ReadOnlyUserDao> {
 *     // Inherits read-only operations with join loading and primitive long ID support
 * }
 * 
 * ReadOnlyUserDao dao = JdbcUtil.createDao(ReadOnlyUserDao.class, readOnlyDataSource);
 * 
 * // Read operations with join loading using primitive long:
 * Optional<User> user = dao.get(123L, Order.class);  // loads user with orders
 * User userWithAll = dao.gett(123L, true);           // loads all relationships
 * 
 * // All write operations are disabled:
 * // dao.insert(user);              // throws UnsupportedOperationException
 * // dao.update(user);              // throws UnsupportedOperationException
 * // dao.deleteById(123L);          // throws UnsupportedOperationException
 * // dao.deleteJoinEntities(...);   // throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedReadOnlyJoinEntityHelper
 * @see UncheckedCrudJoinEntityHelperL
 */
public interface UncheckedReadOnlyCrudJoinEntityHelperL<T, SB extends SQLBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
        extends UncheckedReadOnlyJoinEntityHelper<T, SB, TD>, UncheckedCrudJoinEntityHelperL<T, SB, TD> {
    // This interface combines read-only restrictions with join entity capabilities
    // and primitive long ID support. All methods are inherited from parent interfaces.
}