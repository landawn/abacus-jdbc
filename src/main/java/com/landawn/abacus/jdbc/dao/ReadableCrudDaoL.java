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
 * {@code Long}-id read capability marker for CRUD DAOs: a {@link ReadableCrudDao} pinned to
 * {@code ID = Long}. It carries no operations of its own and exists so that the framework can
 * recognize a DAO as a {@code Long}-keyed CRUD DAO (driving {@code ID = Long} inference) regardless
 * of whether it is a full, no-update, or read-only variant.
 *
 * <p>The full {@link CrudDaoL} adds the primitive-{@code long} convenience overloads; restricted
 * variants ({@code NoUpdateCrudDaoL}/{@code ReadOnlyCrudDaoL}) mix in only this marker, so the
 * primitive overloads are unavailable on them (the boxed {@code Long} overloads still apply).</p>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see ReadableCrudDao
 * @see CrudDaoL
 */
@Beta
sealed interface ReadableCrudDaoL<T, TD extends ReadableDao<T, TD>> extends ReadableCrudDao<T, Long, TD>
        permits ReadableLongIdCrudDao, UncheckedReadableCrudDaoL {
}
