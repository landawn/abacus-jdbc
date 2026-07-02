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

import com.landawn.abacus.exception.UncheckedSQLException;

/**
 * A read-only interface for managing join entity relationships without checked exceptions.
 *
 * <p>This interface mixes in only the read side of unchecked join-entity support
 * ({@link UncheckedJoinEntityReadOps}) together with {@link ReadOnlyJoinEntityHelper};
 * it does <b>not</b> extend {@link UncheckedJoinEntityDeleteOps}, so the
 * {@code deleteJoinEntities} and {@code deleteAllJoinEntities} families are simply absent from the
 * type. Attempting to call them fails at <b>compile time</b> rather than throwing
 * {@link UnsupportedOperationException} at runtime.</p>
 *
 * <p>Load operations (e.g., {@code loadJoinEntities}, {@code loadAllJoinEntities}) throw
 * {@link UncheckedSQLException} instead of the checked {@link java.sql.SQLException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with unchecked exceptions
 * public interface UserReadOnlyDao
 *         extends UncheckedReadOnlyDao<User, UserReadOnlyDao>, UncheckedReadOnlyJoinEntityHelper<User, UserReadOnlyDao> {
 *     // All load operations work normally with unchecked exceptions
 *     // Delete-join operations do not compile
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource);
 *
 * User user = userDao.findFirst(Filters.eq("id", 1L)).orElseThrow();
 * userDao.loadJoinEntities(user, "orders");   // Loads successfully
 *
 * // userDao.deleteJoinEntities(user, Order.class);   // Does not compile
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <TD> the DAO type that hosts this helper, bound to {@link UncheckedReadOnlyDao}
 *
 * @see UncheckedJoinEntityReadOps
 * @see ReadOnlyJoinEntityHelper
 * @see UncheckedSQLException
 */
public non-sealed interface UncheckedReadOnlyJoinEntityHelper<T, TD extends UncheckedReadOnlyDao<T, TD>>
        extends UncheckedJoinEntityReadOps<T, TD>, ReadOnlyJoinEntityHelper<T, TD> {
}
