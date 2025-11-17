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
 * Defines a query template variable for dynamic query construction.
 * This annotation allows parts of query statements to be dynamically replaced at runtime,
 * enabling flexible query construction while maintaining query injection safety for the structure.
 * 
 * <p>The annotation replaces placeholders in query statements that are marked with curly braces {@code {placeholder}}.
 * Unlike {@link Bind} which safely binds parameter values, {@code SqlFragment} performs string substitution
 * in the query template itself, allowing dynamic table names, column names, and query fragments.</p>
 * 
 * <p><strong>Security Warning:</strong> Since this performs direct string substitution in query,
 * use it only with trusted input or properly validated/sanitized values to prevent query injection.
 * Never use user input directly with {@code SqlFragment} without validation.</p>
 * 
 * <p>Common use cases:</p>
 * <ul>
 *   <li>Dynamic table names (e.g., partitioned tables)</li>
 *   <li>Dynamic column names for flexible queries</li>
 *   <li>Conditional query fragments (ORDER BY, WHERE clauses)</li>
 *   <li>Database-specific query syntax variations</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     
 *     // Dynamic table name
 *     @Query("SELECT * FROM {tableName} WHERE id = :id")
 *     User findById(@SqlFragment("tableName") String table, @Bind("id") long id);
 *     
 *     // Dynamic column for ordering
 *     @Query("SELECT * FROM users ORDER BY {orderColumn} {orderDirection}")
 *     List<User> findAllOrdered(
 *         @SqlFragment("orderColumn") String column,
 *         @SqlFragment("orderDirection") String direction
 *     );
 *     
 *     // Custom placeholder syntax
 *     @Query("SELECT * FROM products {where -> WHERE status = 'ACTIVE'} ORDER BY name")
 *     List<Product> findProducts(@SqlFragment("{where -> WHERE status = 'ACTIVE'}") String whereClause);
 *     
 *     // Combining with Bind for safe value binding
 *     @Query("UPDATE {table} SET {column} = :value WHERE id = :id")
 *     int updateDynamic(
 *         @SqlFragment("table") String tableName,
 *         @SqlFragment("column") String columnName,
 *         @Bind("value") Object value,
 *         @Bind("id") long id
 *     );
 *     
 *     // Conditional query fragments
 *     @Query("SELECT * FROM orders {statusFilter} ORDER BY created_date DESC")
 *     List<Order> findOrders(@SqlFragment("statusFilter") String statusFilter);
 *     // Usage: findOrders("WHERE status IN ('PENDING', 'PROCESSING')")
 *     //     or: findOrders("") for all orders
 * }
 * }</pre>
 * 
 * <p>Best practices:</p>
 * <ul>
 *   <li>Always validate template values against a whitelist when possible</li>
 *   <li>Use {@code Bind} for values, {@code SqlFragment} only for query structure</li>
 *   <li>Consider using enums or constants for {@code SqlFragment} values</li>
 *   <li>Document which values are safe for each {@code SqlFragment} parameter</li>
 * </ul>
 * 
 * @see SqlFragmentList
 * @see Bind
 * @see Query#fragmentContainsNamedParameters()
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface SqlFragment {

    /**
     * Specifies the name of the query template variable to be replaced.
     * If not specified (empty string), the parameter name will be used.
     * 
     * <p>The value can be:</p>
     * <ul>
     *   <li>A simple placeholder name (e.g., "tableName")</li>
     *   <li>A custom placeholder with default value syntax (e.g., "{where -> WHERE active = true}")</li>
     * </ul>
     * 
     * <p>Simple placeholder example:</p>
     * <pre>{@code
     * @Query("SELECT * FROM {schema}.{table} WHERE id = :id")
     * User findById(
     *     @SqlFragment("schema") String schemaName,
     *     @SqlFragment("table") String tableName,
     *     @Bind("id") long id
     * );
     * }</pre>
     * 
     * <p>Custom placeholder with default:</p>
     * <pre>{@code
     * // Custom syntax allows for more complex replacements
     * @Query("SELECT * FROM users {filter -> WHERE active = true}")
     * List<User> findUsers(@SqlFragment("{filter -> WHERE active = true}") String customFilter);
     * 
     * // Usage:
     * findUsers("WHERE role = 'ADMIN'"); // Replaces the entire {filter -> ...} block
     * findUsers(null); // Uses the default "WHERE active = true"
     * }</pre>
     * 
     * <p>Using parameter name when value is empty:</p>
     * <pre>{@code
     * @Query("SELECT {columns} FROM users")
     * List<Map<String, Object>> findWithColumns(@SqlFragment String columns);
     * // The parameter name "columns" is used as the placeholder name
     * }</pre>
     *
     * @return the query template variable name, or empty string if using the parameter name
     */
    String value() default "";
}
