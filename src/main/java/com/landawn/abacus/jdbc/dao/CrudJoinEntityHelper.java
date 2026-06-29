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
 * CRUD-aware join-entity helper: adds id-based reads ({@code get}/{@code gett}/{@code batchGet}
 * with join loading) on top of the full {@link JoinEntityHelper} (load + delete).
 *
 * <p>This is the full CRUD join capability, composed of the read side
 * ({@link ReadableCrudJoinEntityHelper}) and the base {@link JoinEntityHelper}. Read-only CRUD
 * DAOs mix in only {@link ReadableCrudJoinEntityHelper} (via {@link ReadOnlyCrudJoinEntityHelper}).</p>
 *
 * @param <T> the entity type that this helper manages
 * @param <ID> the ID type of the entity
 * @param <TD> the companion {@link CrudDao} type that owns this helper
 *
 * @see ReadableCrudJoinEntityHelper
 * @see JoinEntityHelper
 * @see com.landawn.abacus.annotation.JoinedBy
 */
public non-sealed interface CrudJoinEntityHelper<T, ID, TD extends CrudDao<T, ID, TD>> extends ReadableCrudJoinEntityHelper<T, ID, TD>, JoinEntityHelper<T, TD> {
}
