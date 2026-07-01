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
 * Unchecked-exception infrastructure root: marker base that the unchecked capability interfaces
 * ({@link UncheckedReadOps}, {@link UncheckedInsertOps}, {@link UncheckedUpdateOps},
 * {@link UncheckedDeleteOps}) extend. The shared accessors and {@code prepare*} builders are inherited
 * unchanged from {@link DaoBase}; the unchecked side does not re-declare them.
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see DaoBase
 * @see UncheckedReadOps
 */
@Beta
sealed interface UncheckedDaoBase<T, TD extends UncheckedDaoBase<T, TD>> extends DaoBase<T, TD>
        permits UncheckedReadOps, UncheckedInsertOps, UncheckedUpdateOps, UncheckedDeleteOps {
}
