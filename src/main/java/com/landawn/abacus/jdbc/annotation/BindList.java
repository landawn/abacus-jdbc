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

import com.landawn.abacus.annotation.Beta;

/**
 * Binds a collection or array parameter to a SQL query, automatically expanding it
 * into the appropriate number of parameter placeholders.
 * 
 * <p>This annotation is particularly useful for {@code IN} clauses where you need to
 * bind a variable number of values. The framework automatically handles the expansion
 * of the collection into individual parameter bindings.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Basic usage with IN clause
 *     @Query("SELECT * FROM users WHERE id IN ({ids})")
 *     List<User> findByIds(@BindList("ids") List<Long> userIds);
 *     
 *     // Usage: dao.findByIds(Arrays.asList(1L, 2L, 3L))
 *     // Generates: SELECT * FROM users WHERE id IN (?, ?, ?)
 *     // With values: [1, 2, 3]
 *     
 *     // Using with array parameter
 *     @Query("SELECT * FROM users WHERE status IN ({statuses})")
 *     List<User> findByStatuses(@BindList("statuses") String[] statuses);
 *     
 *     // With prefix and suffix for conditional SQL
 *     @Query("SELECT * FROM users WHERE active = true {statusFilter}")
 *     List<User> findActiveUsers(
 *         @BindList(value = "statuses", 
 *                   prefixForNonEmpty = "AND status IN (", 
 *                   suffixForNonEmpty = ")")
 *         List<String> statuses
 *     );
 *     
 *     // If statuses is empty: SELECT * FROM users WHERE active = true
 *     // If statuses has values: SELECT * FROM users WHERE active = true AND status IN (?, ?)
 * }
 * }</pre>
 * 
 * <p>The annotation automatically handles:</p>
 * <ul>
 *   <li>Empty collections (generates appropriate SQL or skips with prefix/suffix)</li>
 *   <li>Null collections (treated as empty)</li>
 *   <li>Single element collections</li>
 *   <li>Large collections (up to database parameter limits)</li>
 * </ul>
 *
 * @see Bind
 * @see DefineList
 * @since 0.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface BindList {

    /**
     * Specifies the parameter name to be used in the SQL query.
     * If not specified (empty string), the actual parameter name will be used.
     * 
     * <p>The parameter should be referenced in the SQL using curly braces: {@code {paramName}}</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Explicit name
     * @Query("SELECT * FROM users WHERE id IN ({userIds})")
     * List<User> find(@BindList("userIds") List<Long> ids);
     * 
     * // Using parameter name (when value is not specified)
     * @Query("SELECT * FROM users WHERE id IN ({ids})")
     * List<User> find(@BindList List<Long> ids);
     * }</pre>
     *
     * @return the parameter name, or empty string to use the actual parameter name
     */
    String value() default "";

    /**
     * Specifies a prefix to add before the parameter placeholder when the collection is non-empty.
     * This is useful for conditionally including SQL fragments based on whether the collection has values.
     * 
     * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may change in future versions.
     * Consider using {@link Define} annotation for complex dynamic SQL construction.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Query("SELECT * FROM users WHERE active = true {statusFilter}")
     * List<User> findUsers(
     *     @BindList(value = "statuses",
     *               prefixForNonEmpty = "AND status IN (",
     *               suffixForNonEmpty = ")")
     *     List<String> statuses
     * );
     * 
     * // With empty list: SELECT * FROM users WHERE active = true
     * // With values: SELECT * FROM users WHERE active = true AND status IN (?, ?)
     * }</pre>
     *
     * @return the prefix to add when collection is non-empty
     */
    @Beta
    String prefixForNonEmpty() default "";

    /**
     * Specifies a suffix to add after the parameter placeholder when the collection is non-empty.
     * This is used in conjunction with {@link #prefixForNonEmpty()} to wrap the parameter placeholders.
     * 
     * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may change in future versions.
     * Consider using {@link Define} annotation for complex dynamic SQL construction.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Query("SELECT * FROM products {categoryFilter} ORDER BY name")
     * List<Product> findProducts(
     *     @BindList(value = "categories",
     *               prefixForNonEmpty = "WHERE category IN (",
     *               suffixForNonEmpty = ")")
     *     Set<String> categories
     * );
     * 
     * // With empty set: SELECT * FROM products ORDER BY name
     * // With values: SELECT * FROM products WHERE category IN (?, ?, ?) ORDER BY name
     * }</pre>
     *
     * @return the suffix to add when collection is non-empty
     */
    @Beta
    String suffixForNonEmpty() default "";
}