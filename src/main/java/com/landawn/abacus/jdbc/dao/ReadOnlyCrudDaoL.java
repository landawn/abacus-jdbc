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
import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only CRUD (Create, Read, Update, Delete) Data Access Object interface specifically
 * designed for entities with {@code Long} type primary keys. This interface combines
 * {@link ReadOnlyCrudDao} and {@link NoUpdateCrudDaoL} to provide a complete read-only
 * DAO implementation for entities using Long identifiers.
 * 
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 * 
 * <p>All mutation operations (insert, update, delete) inherited from the parent interfaces
 * will throw {@link UnsupportedOperationException}, while read operations remain functional.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends ReadOnlyCrudDaoL<User, SQLBuilder, UserDao> {
 *     // Inherits read-only operations with Long ID type
 *     // findById(Long id), exists(Long id), etc. are available
 *     // insert(), update(), delete() operations throw UnsupportedOperationException
 * }
 * }</pre>
 * 
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used for query construction
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see ReadOnlyCrudDao
 * @see NoUpdateCrudDaoL
 * @see CrudDaoL
 */
@Beta
public interface ReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends ReadOnlyCrudDaoL<T, SB, TD>>
        extends ReadOnlyCrudDao<T, Long, SB, TD>, NoUpdateCrudDaoL<T, SB, TD> {
}