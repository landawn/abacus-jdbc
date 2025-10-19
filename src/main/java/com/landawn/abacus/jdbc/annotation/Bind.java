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
 * <p>The annotation can bind:</p>
 * <ul>
 *   <li>Simple types (String, Integer, Date, etc.)</li>
 *   <li>Entity objects (properties are accessible via dot notation)</li>
 *   <li>Collections (for use with {@link BindList})</li>
 *   <li>Complex nested objects</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Simple parameter binding
 *     @Query("SELECT * FROM users WHERE email = :email")
 *     User findByEmail(@Bind("email") String email);
 *     
 *     // Multiple parameters
 *     @Query("SELECT * FROM users WHERE status = :status AND age >= :minAge")
 *     List<User> findActiveAdults(@Bind("status") String status, @Bind("minAge") int minAge);
 *     
 *     // Binding entity properties
 *     @Query("SELECT * FROM users WHERE first_name = :user.firstName AND last_name = :user.lastName")
 *     List<User> findByName(@Bind("user") User user);
 *     
 *     // Parameter name inference (when value is not specified)
 *     @Query("SELECT * FROM users WHERE department = :department")
 *     List<User> findByDepartment(@Bind String department);
 *     
 *     // Nested property access
 *     @Query("UPDATE users SET address = :addr.street, city = :addr.city WHERE id = :userId")
 *     void updateAddress(@Bind("userId") Long userId, @Bind("addr") Address address);
 * }
 * }</pre>
 * 
 * <p>When binding entity objects, all accessible properties can be referenced
 * in the SQL using dot notation:</p>
 * <pre>{@code
 * @Query("INSERT INTO users (name, email, age) VALUES (:name, :email, :age)")
 * void insertUser(User user);  // Properties bound automatically without @Bind
 * 
 * @Query("INSERT INTO users (name, email, age) VALUES (:u.name, :u.email, :u.age)")
 * void insertUser(@Bind("u") User user);  // Explicit binding with prefix
 * }</pre>
 *
 * @see BindList
 * @see Define
 * @since 0.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Bind {

    /**
     * Specifies the parameter name to be used in the SQL query.
     * If not specified (empty string), the actual parameter name will be used.
     * 
     * <p>The parameter is referenced in SQL using colon notation: {@code :paramName}</p>
     * 
     * <p>Examples:</p>
     * <pre>{@code
     * // Explicit parameter name
     * @Query("SELECT * FROM users WHERE id = :userId")
     * User findById(@Bind("userId") Long id);
     * 
     * // Using method parameter name (requires -parameters compiler flag)
     * @Query("SELECT * FROM users WHERE email = :email")
     * User findByEmail(@Bind String email);
     * 
     * // Binding object with property access
     * @Query("SELECT * FROM orders WHERE customer_id = :customer.id AND status = :orderStatus")
     * List<Order> findOrders(@Bind("customer") Customer customer, @Bind("orderStatus") String status);
     * }</pre>
     * 
     * <p>Best practices:</p>
     * <ul>
     *   <li>Use descriptive parameter names for clarity</li>
     *   <li>Be consistent with naming conventions</li>
     *   <li>Consider using explicit names for better refactoring support</li>
     * </ul>
     *
     * @return the parameter name, or empty string to use the actual parameter name
     */
    String value() default "";
}