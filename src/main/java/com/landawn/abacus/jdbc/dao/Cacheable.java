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
 * Marker interface identifying DAO types for which result caching is permitted.
 *
 * <p>Caching DAO query results is only safe when the DAO cannot perform
 * {@code UPDATE}/{@code DELETE} operations that would silently invalidate cached
 * rows. Interfaces that restrict a DAO to read (and optionally insert) operations
 * &mdash; namely {@link NoUpdateDao}/{@link ReadOnlyDao} and their {@code Crud},
 * {@code L}, and {@code Unchecked} variants &mdash; extend this marker so that the
 * framework can enable {@code @Cache}, {@code @CacheResult}, and
 * {@code @RefreshCache} support for them.</p>
 *
 * <p>This is a pure marker (no methods). It exists so the cache-eligibility check
 * is a single semantic predicate rather than a hard-coded dependency on a concrete
 * interface type, which keeps it correct as the DAO capability hierarchy evolves.</p>
 *
 * <p>This interface is marked as {@link Beta @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * @see NoUpdateDao
 * @see ReadOnlyDao
 */
@Beta
sealed interface Cacheable permits NoUpdateDao, ReadOnlyDao {
}
