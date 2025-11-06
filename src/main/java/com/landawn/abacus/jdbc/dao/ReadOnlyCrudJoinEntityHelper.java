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
 * A read-only interface for handling join entity operations with CRUD DAOs.
 * This interface combines {@link ReadOnlyJoinEntityHelper} and {@link CrudJoinEntityHelper}
 * to provide read-only access to join entity operations for CRUD-based DAOs.
 * 
 * <p>This interface is designed for scenarios where you need to read join entity
 * relationships (such as many-to-many relationships) but should not be able to
 * modify them. All delete operations for join entities are disabled and will
 * throw {@link UnsupportedOperationException}.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // For a User-Role many-to-many relationship
 * public interface UserReadOnlyDao extends ReadOnlyCrudJoinEntityHelper<User, Long, SQLBuilder, UserDao> {
 *     // Can load user's roles
 *     List<Role> loadUserRoles(User user) {
 *         return loadJoinEntities(user, Role.class);
 *     }
 *     
 *     // Cannot delete user-role associations
 *     // deleteJoinEntities(user, Role.class) throws UnsupportedOperationException
 * }
 * }</pre>
 * 
 * @param <T> the entity type managed by this DAO
 * @param <ID> the ID type of the entity
 * @param <SB> the SQLBuilder type used for query construction
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see ReadOnlyJoinEntityHelper
 * @see CrudJoinEntityHelper
 * @see CrudDao
 */
public interface ReadOnlyCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>>
        extends ReadOnlyJoinEntityHelper<T, SB, TD>, CrudJoinEntityHelper<T, ID, SB, TD> {

}