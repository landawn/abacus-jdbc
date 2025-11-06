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

import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only interface for handling join entity operations with CRUD DAOs that use
 * {@code Long} type primary keys. This interface combines {@link ReadOnlyCrudJoinEntityHelper}
 * and {@link CrudJoinEntityHelperL} to provide read-only access to join entity operations
 * specifically for entities with Long identifiers.
 * 
 * <p>This interface enforces read-only behavior by inheriting from {@link ReadOnlyCrudJoinEntityHelper},
 * which overrides all mutation operations to throw {@link UnsupportedOperationException}.
 * Only read operations for join entities are available through this interface.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with join entity support
 * public interface UserReadOnlyDao extends ReadOnlyCrudJoinEntityHelperL<User, SQLBuilder, UserDao> {
 *     // Can read join entities (e.g., user roles, permissions)
 *     // Cannot delete join entities
 * }
 * 
 * // Usage:
 * User user = dao.findById(1L);
 * List<Role> roles = dao.loadJoinEntities(user, Role.class); // Works
 * dao.deleteJoinEntities(user, Role.class); // Throws UnsupportedOperationException
 * }</pre>
 * 
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used for query construction
 * @param <TD> the DAO implementation type with Long ID (self-referencing for method chaining)
 * @see ReadOnlyCrudJoinEntityHelper
 * @see CrudJoinEntityHelperL
 * @see CrudDaoL
 */
public interface ReadOnlyCrudJoinEntityHelperL<T, SB extends SQLBuilder, TD extends CrudDaoL<T, SB, TD>>
        extends ReadOnlyCrudJoinEntityHelper<T, Long, SB, TD>, CrudJoinEntityHelperL<T, SB, TD> {

}