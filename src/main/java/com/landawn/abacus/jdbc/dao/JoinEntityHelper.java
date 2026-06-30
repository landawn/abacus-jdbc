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
 * Helper mix-in for loading and deleting join entities (related entities mapped with
 * {@code @JoinedBy}) on a {@link Dao}'s entity type.
 *
 * <p>This is the full join-entity capability, composed of the read side
 * ({@link JoinEntityReadOps}) and the delete side ({@link JoinEntityDeleteOps}).
 * See those interfaces for the individual operations; read-only DAOs mix in only
 * {@link JoinEntityReadOps} (via {@link ReadOnlyJoinEntityHelper}).</p>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 *
 * @see JoinEntityReadOps
 * @see JoinEntityDeleteOps
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.query.Filters
 */
public non-sealed interface JoinEntityHelper<T, TD extends Dao<T, TD>> extends JoinEntityDeleteOps<T, TD> {
}
