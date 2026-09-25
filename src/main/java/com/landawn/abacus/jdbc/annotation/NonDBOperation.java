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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks methods in DAO interfaces that should not be treated as database operations.
 * When a method is annotated with {@code @NonDBOperation}, the framework will bypass
 * its database-related interceptors for that method. This does not prevent the method body or
 * nested DAO calls from executing SQL or using the current SQL logging configuration.
 *
 * <p>Methods annotated with {@code @NonDBOperation} will have the following behaviors disabled:</p>
 * <ul>
 *   <li>No {@code Handler} interceptors will be applied</li>
 *   <li>No DAO-scoped SQL logging configuration or whole-method performance logging is added</li>
 *   <li>No {@code @Transactional} annotations will be processed</li>
 *   <li>No result-cache lookup or invalidation will be performed</li>
 * </ul>
 *
 * <p>The framework's built-in DAO base interfaces ({@code Dao}, {@code CrudDao},
 * {@code NonUpdateDao}, etc.) already carry {@code @NonDBOperation} on the utility/accessor
 * methods they declare, including (non-exhaustive):</p>
 * <ul>
 *   <li>{@code targetEntityClass()} - Returns the entity class associated with the DAO</li>
 *   <li>{@code dataSource()} - Returns the data source used by the DAO</li>
 *   <li>{@code sqlMapper()} - Returns the SQL mapper instance</li>
 *   <li>{@code executor()} - Returns the query executor</li>
 *   <li>{@code prepareQuery(...)} - Creates a query builder</li>
 *   <li>{@code prepareNamedQuery(...)} - Creates a named query builder</li>
 *   <li>{@code prepareCallableQuery(...)} - Creates a callable query builder</li>
 * </ul>
 *
 * <p>Apply {@code @NonDBOperation} to your own {@code default} DAO methods whenever they should be
 * excluded from the DAO proxy's database-related processing (handlers, SQL/perf logging,
 * transaction handling, and caching). Interface {@code static} methods are invoked on the interface itself and
 * never pass through a DAO proxy, so annotating them has no proxy effect.</p>
 *
 * <p>This is a marker annotation: it declares no elements and carries no configuration. Its mere
 * presence on a method (per {@code @Target(METHOD)}, retained at runtime) is what signals the DAO
 * proxy to skip the database-related wiring listed above.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // This method will be processed as a database operation
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     List<User> findByStatus(@Bind("status") String status) throws SQLException;
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
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface NonDBOperation {

}
