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
 * Read-only helper for join entity operations in DAOs.
 *
 * <p>This interface mixes in only the read side of join-entity support
 * ({@link JoinEntityReadOps}); it does <b>not</b> extend
 * {@link JoinEntityDeleteOps}, so the {@code deleteJoinEntities} and
 * {@code deleteAllJoinEntities} families are simply absent from the type. Attempting to
 * call them fails at <b>compile time</b> rather than throwing
 * {@link UnsupportedOperationException} at runtime.</p>
 *
 * <p><b>Supported Join Entity Read Operations:</b></p>
 * <ul>
 *   <li>{@code loadJoinEntities(entity, Class)} - Load associated entities for a specific join type</li>
 *   <li>{@code loadJoinEntities(entity, String)} - Load associated entities by property name</li>
 *   <li>{@code loadJoinEntities(entity, Collection)} - Load multiple join entities by property names</li>
 *   <li>{@code loadAllJoinEntities(entity)} - Load all defined join entities for an entity</li>
 *   <li>{@code loadJoinEntities(Collection, Class)} - Batch load join entities for multiple entities</li>
 *   <li>{@code loadAllJoinEntities(Collection)} - Batch load all join entities for multiple entities</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with join entity support
 * public interface UserReadOnlyDao
 *         extends ReadOnlyDao<User, UserReadOnlyDao>, ReadOnlyJoinEntityHelper<User, UserReadOnlyDao> {
 *     // All load operations work normally
 *     // Delete-join operations do not compile
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource);
 *
 * // Load join entities for a single user
 * User user = userDao.findFirst(Filters.eq("id", 1L)).orElseThrow();
 * userDao.loadJoinEntities(user, Order.class);   // Loads associated orders
 * userDao.loadAllJoinEntities(user);             // Loads all defined join entities
 *
 * // userDao.deleteJoinEntities(user, Order.class);   // Does not compile
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <TD> the companion {@link ReadOnlyDao} type that owns this helper (used for fluent
 *             method chaining and access to DAO operations)
 * @see JoinEntityReadOps
 * @see JoinEntityHelper
 * @see ReadOnlyDao
 * @see com.landawn.abacus.annotation.JoinedBy
 */
public non-sealed interface ReadOnlyJoinEntityHelper<T, TD extends ReadOnlyDao<T, TD>> extends JoinEntityReadOps<T, TD> {
}
