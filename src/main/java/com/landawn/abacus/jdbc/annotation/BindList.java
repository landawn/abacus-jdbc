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
 * <p><b>Note on the name:</b> despite the {@code List} suffix, {@code @BindList} is <em>not</em> a
 * {@code @Repeatable} container (unlike {@code HandlerList} or {@code OutParameterList}, which hold
 * multiple {@code @Handler}/{@code @OutParameter} instances). It is an independent parameter
 * annotation applied to a single collection/array parameter, which it expands into the appropriate
 * number of JDBC placeholders.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
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
 *         @BindList(value = "statusFilter",
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
 *   <li>Empty collections: the {@code {name}} token is replaced with an empty string, and the
 *       {@link #prefixForNonEmpty()}/{@link #suffixForNonEmpty()} are <em>not</em> emitted. With a
 *       bare {@code IN ({ids})} this yields {@code IN ()} (a SQL syntax error), so guard against the
 *       empty case or wrap the clause entirely with the prefix/suffix so it disappears cleanly.</li>
 *   <li>Null collections (treated the same as empty)</li>
 *   <li>Single element collections</li>
 *   <li>Large collections: one placeholder is emitted per element; the caller is responsible for
 *       keeping the total within the JDBC driver's/database's maximum bind-parameter count, as this
 *       annotation imposes no limit of its own</li>
 * </ul>
 *
 * @see Bind
 * @see SqlFragmentList
 * @see Query
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface BindList {

    /**
     * Specifies the template-variable name that this collection/array parameter should expand into.
     * The value must exactly match the template variable referenced in the {@link Query} SQL
     * (for example, {@code @BindList("ids")} must correspond to {@code ... WHERE id IN ({ids})}).
     *
     * <p>If left empty, the actual method parameter name is used as the template-variable name;
     * this requires compiling with the {@code -parameters} javac flag, otherwise initialization
     * fails with {@code UnsupportedOperationException}.</p>
     *
     * <p>The parameter is referenced in the SQL using curly braces: {@code {paramName}}</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Explicit name
     * @Query("SELECT * FROM users WHERE id IN ({userIds})")
     * List<User> find(@BindList("userIds") List<Long> ids);
     *
     * // Falls back to method parameter name (requires '-parameters')
     * @Query("SELECT * FROM users WHERE id IN ({ids})")
     * List<User> find(@BindList List<Long> ids);
     * }</pre>
     *
     * @return the template-variable name; empty means use the method parameter name (requires {@code -parameters})
     */
    String value() default "";

    /**
     * Specifies a prefix to add before the parameter placeholder when the collection is non-empty.
     * This is useful for conditionally including SQL fragments based on whether the collection has values.
     *
     * <p><strong>Note:</strong> This feature is marked as {@link Beta} and may undergo changes in future versions.
     * Consider using {@link SqlFragment} annotation for complex dynamic SQL construction.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query("SELECT * FROM users WHERE active = true {statusFilter}")
     * List<User> findUsers(
     *     @BindList(value = "statusFilter",
     *               prefixForNonEmpty = "AND status IN (",
     *               suffixForNonEmpty = ")")
     *     List<String> statuses
     * );
     *
     * // With empty list: SELECT * FROM users WHERE active = true
     * // With values: SELECT * FROM users WHERE active = true AND status IN (?, ?)
     * }</pre>
     *
     * @return the prefix to add when the collection is non-empty; empty (the default) means no prefix is emitted
     */
    @Beta
    String prefixForNonEmpty() default "";

    /**
     * Specifies a suffix to add after the parameter placeholder when the collection is non-empty.
     * This is used in conjunction with {@link #prefixForNonEmpty()} to wrap the parameter placeholders.
     *
     * <p><strong>Note:</strong> This feature is marked as {@link Beta} and may undergo changes in future versions.
     * Consider using {@link SqlFragment} annotation for complex dynamic SQL construction.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Query("SELECT * FROM products {categoryFilter} ORDER BY name")
     * List<Product> findProducts(
     *     @BindList(value = "categoryFilter",
     *               prefixForNonEmpty = "WHERE category IN (",
     *               suffixForNonEmpty = ")")
     *     Set<String> categories
     * );
     *
     * // With empty set: SELECT * FROM products ORDER BY name
     * // With values: SELECT * FROM products WHERE category IN (?, ?, ?) ORDER BY name
     * }</pre>
     *
     * @return the suffix to add when the collection is non-empty; empty (the default) means no suffix is emitted
     */
    @Beta
    String suffixForNonEmpty() default "";
}
