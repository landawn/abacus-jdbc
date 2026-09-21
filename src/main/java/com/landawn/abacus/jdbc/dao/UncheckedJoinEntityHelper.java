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

import java.sql.SQLException;

/**
 * Helper mix-in for loading and deleting join entities with unchecked exceptions.
 *
 * <p>Full unchecked join-entity capability, composed of the read side
 * ({@link UncheckedJoinEntityReadOps}) and the delete side
 * ({@link UncheckedJoinEntityDeleteOps}). All operations throw
 * {@link UncheckedSQLException} instead of {@link SQLException}.
 * See those interfaces for the individual operations; read-only unchecked DAOs mix in only
 * {@link UncheckedJoinEntityReadOps} (via {@link UncheckedReadOnlyJoinEntityHelper}).</p>
 *
 * @param <T> the entity type that this helper manages
 * @param <TD> the companion {@link UncheckedDao} type that owns this helper
 * @see UncheckedJoinEntityReadOps
 * @see UncheckedJoinEntityDeleteOps
 * @see JoinEntityHelper
 * @see com.landawn.abacus.annotation.JoinedBy
 */
public non-sealed interface UncheckedJoinEntityHelper<T, TD extends UncheckedDao<T, TD>> extends UncheckedJoinEntityDeleteOps<T, TD>, JoinEntityHelper<T, TD> {
}
