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
 * Binds a method parameter to a named parameter in SQL queries.
 * This annotation enables the use of named parameters (e.g., {@code :paramName})
 * in SQL statements, providing better readability and maintainability compared
 * to positional parameters.
 *
 * <p>It is applied to a parameter of a DAO interface method (per its
 * {@link java.lang.annotation.ElementType#PARAMETER PARAMETER} target), typically one whose SQL is
 * declared with {@link Query @Query} using named placeholders. The {@link #value() value} names the
 * {@code :token} that this argument supplies.</p>
 *
 * <p>The annotation binds a single value (any type with a registered Abacus {@code Type}) to the
 * named parameter whose token matches {@link #value()} <i>verbatim</i>. Property paths like
 * {@code :a.b} are supported only through a single <b>unannotated</b> bean parameter (see below) —
 * an {@code @Bind} value that does not literally appear as a named parameter in the SQL fails DAO
 * initialization.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // Simple parameter binding
 *     @Query("SELECT * FROM users WHERE email = :email")
 *     User findByEmail(@Bind("email") String email) throws SQLException;
 *
 *     // Multiple parameters
 *     @Query("SELECT * FROM users WHERE status = :status AND age >= :minAge")
 *     List<User> findActiveAdults(@Bind("status") String status, @Bind("minAge") int minAge) throws SQLException;
 *
 *     // Binding entity properties: single unannotated bean parameter, :tokens resolve to its properties
 *     @Query("SELECT * FROM users WHERE first_name = :firstName AND last_name = :lastName")
 *     List<User> findByName(User probe) throws SQLException;
 *
 *     // Nested property paths also work through the single-bean form (user.getAddress().getStreet(), ...)
 *     @Query("UPDATE users SET street = :address.street, city = :address.city WHERE id = :id")
 *     int updateAddress(User user) throws SQLException;
 * }
 * }</pre>
 *
 * <p>When the method has a single bean/record/Map parameter, named placeholders are bound directly
 * to its properties without {@code @Bind}. When there are multiple parameters in a named query,
 * each parameter must carry an {@code @Bind} (or another binding annotation):</p>
 * <pre>{@code
 * // Single bean parameter: properties auto-bound, no @Bind required
 * @Query("INSERT INTO users (name, email, age) VALUES (:name, :email, :age)")
 * void insertUser(User user) throws SQLException;
 *
 * // NOT supported: prefix-binding an entity with @Bind ("@Bind("u") User user" + ":u.name" fails
 * // DAO initialization) — use the single unannotated bean parameter form above instead.
 * }</pre>
 *
 * <p>Best practices:</p>
 * <ul>
 *   <li>Use descriptive parameter names for clarity</li>
 *   <li>Be consistent with naming conventions</li>
 *   <li>Consider using explicit names for better refactoring support</li>
 *   <li>For collections, use {@link BindList} instead</li>
 *   <li>For dynamic SQL structure, use {@link SqlFragment} instead</li>
 * </ul>
 *
 * @see BindList
 * @see SqlFragment
 * @see Query
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Bind {

    /**
     * Specifies the named-parameter token (the part after {@code :}) that this method parameter
     * should be bound to in the SQL.
     *
     * <p>An explicit value is normally required: when a named-query method has more than one
     * statement parameter, every such parameter must carry an {@code @Bind} (or another binding
     * annotation) with a non-empty {@code value} that matches a named parameter in the SQL verbatim.
     * (A single bean/record/Map parameter is auto-bound by property names without any {@code @Bind}.)</p>
     *
     * <p>The parameter is referenced in SQL using colon notation: {@code :paramName}</p>
     *
     * <p>Examples:</p>
     * <pre>{@code
     * // Explicit parameter name
     * @Query("SELECT * FROM users WHERE id = :userId")
     * User findById(@Bind("userId") Long id);
     *
     * // Multiple scalar parameters, each bound by an explicit name
     * @Query("SELECT * FROM orders WHERE customer_id = :customerId AND status = :orderStatus")
     * List<Order> findOrders(@Bind("customerId") Long customerId, @Bind("orderStatus") String status);
     * }</pre>
     *
     * <p><b>Empty value:</b> an empty {@code value} (the default) is effectively invalid outside stored
     * procedures: for a named query it matches no named parameter and fails DAO initialization, and on a
     * non-procedure positional query any {@code @Bind} is rejected outright. For a stored procedure with a
     * single {@code ?} placeholder parameter, an empty-valued (or entirely absent) {@code @Bind} falls back
     * to positional binding. When a procedure has more than one statement parameter, either give
     * <i>every</i> parameter a non-empty {@code @Bind} name, or omit {@code @Bind} from all of them for
     * purely positional binding &mdash; mixing an empty-valued {@code @Bind} in among named ones is not a
     * supported combination.
     * (This differs from {@code @SqlFragment}/{@code @BindList}, where an empty value falls back to the
     * method parameter name &mdash; which requires compiling with the {@code -parameters} flag.)</p>
     *
     * @return the named-parameter token to bind to (without the leading colon); empty by default
     */
    String value() default "";
}
