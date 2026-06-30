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
 * Unchecked-exception variant of {@link CrudJoinEntityHelper}: id-based reads with join loading
 * (throwing {@link com.landawn.abacus.exception.UncheckedSQLException}) plus the full unchecked
 * join capability (load + delete).
 *
 * <p>Composed of the read side ({@link UncheckedCrudJoinEntityReadOps}) and the base
 * {@link UncheckedJoinEntityHelper}. Read-only unchecked CRUD DAOs mix in only
 * {@link UncheckedCrudJoinEntityReadOps} (via {@link UncheckedReadOnlyCrudJoinEntityHelper}).</p>
 *
 * @param <T> the entity type that this helper manages
 * @param <ID> the ID type of the entity
 * @param <TD> the concrete DAO type, bounded by {@link UncheckedCrudDao}, that owns this helper
 * @see UncheckedCrudJoinEntityReadOps
 * @see UncheckedJoinEntityHelper
 * @see CrudJoinEntityHelper
 * @see com.landawn.abacus.annotation.JoinedBy
 */
public non-sealed interface UncheckedCrudJoinEntityHelper<T, ID, TD extends UncheckedCrudDao<T, ID, TD>>
        extends UncheckedCrudJoinEntityReadOps<T, ID, TD>, UncheckedJoinEntityHelper<T, TD>, CrudJoinEntityHelper<T, ID, TD> {
}
