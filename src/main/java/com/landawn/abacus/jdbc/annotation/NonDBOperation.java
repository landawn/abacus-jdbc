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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks methods in DAO interfaces that should not be treated as database operations.
 * When a method is annotated with {@code @NonDBOperation}, the framework will bypass
 * all database-related processing for that method.
 * 
 * <p>Methods annotated with {@code @NonDBOperation} will have the following behaviors disabled:</p>
 * <ul>
 *   <li>No {@code Handler} interceptors will be applied</li>
 *   <li>No SQL or performance logging will be performed</li>
 *   <li>No {@code @Transactional} annotations will be processed</li>
 * </ul>
 * 
 * <p>By default, the following utility methods in DAO interfaces are automatically treated as non-DB operations:</p>
 * <ul>
 *   <li>{@code targetEntityClass()} - Returns the entity class associated with the DAO</li>
 *   <li>{@code dataSource()} - Returns the data source used by the DAO</li>
 *   <li>{@code sqlMapper()} - Returns the SQL mapper instance</li>
 *   <li>{@code executor()} - Returns the query executor</li>
 *   <li>{@code asyncExecutor()} - Returns the async query executor</li>
 *   <li>{@code prepareQuery()} - Creates a query builder</li>
 *   <li>{@code prepareNamedQuery()} - Creates a named query builder</li>
 *   <li>{@code prepareCallableQuery()} - Creates a callable query builder</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
 *     // This method will be processed as a database operation
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     List<User> findByStatus(@Bind("status") String status);
 *     
 *     // This method will NOT be processed as a database operation
 *     @NonDBOperation
 *     default String generateCacheKey(Long userId) {
 *         return "user_" + userId;
 *     }
 *     
 *     // Utility method that doesn't interact with database
 *     @NonDBOperation
 *     default boolean isValidEmail(String email) {
 *         return email != null && email.contains("@");
 *     }
 * }
 * }</pre>
 *
 * @see Handler
 * @see Transactional
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface NonDBOperation {

}
