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
 * Defines a SQL template variable for dynamic SQL construction.
 * This annotation allows parts of SQL statements to be dynamically replaced at runtime,
 * enabling flexible query construction while maintaining SQL injection safety for the structure.
 * 
 * <p>The annotation replaces placeholders in SQL statements that are marked with curly braces {@code {placeholder}}.
 * Unlike {@link Bind} which safely binds parameter values, {@code Define} performs string substitution
 * in the SQL template itself, allowing dynamic table names, column names, and SQL fragments.</p>
 * 
 * <p><strong>Security Warning:</strong> Since this performs direct string substitution in SQL,
 * use it only with trusted input or properly validated/sanitized values to prevent SQL injection.
 * Never use user input directly with {@code Define} without validation.</p>
 * 
 * <p>Common use cases:</p>
 * <ul>
 *   <li>Dynamic table names (e.g., partitioned tables)</li>
 *   <li>Dynamic column names for flexible queries</li>
 *   <li>Conditional SQL fragments (ORDER BY, WHERE clauses)</li>
 *   <li>Database-specific SQL syntax variations</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     
 *     // Dynamic table name
 *     @Query("SELECT * FROM {tableName} WHERE id = :id")
 *     User findById(@Define("tableName") String table, @Bind("id") long id);
 *     
 *     // Dynamic column for ordering
 *     @Query("SELECT * FROM users ORDER BY {orderColumn} {orderDirection}")
 *     List<User> findAllOrdered(
 *         @Define("orderColumn") String column,
 *         @Define("orderDirection") String direction
 *     );
 *     
 *     // Custom placeholder syntax
 *     @Query("SELECT * FROM products {where -> WHERE status = 'ACTIVE'} ORDER BY name")
 *     List<Product> findProducts(@Define("{where -> WHERE status = 'ACTIVE'}") String whereClause);
 *     
 *     // Combining with Bind for safe value binding
 *     @Query("UPDATE {table} SET {column} = :value WHERE id = :id")
 *     int updateDynamic(
 *         @Define("table") String tableName,
 *         @Define("column") String columnName,
 *         @Bind("value") Object value,
 *         @Bind("id") long id
 *     );
 *     
 *     // Conditional SQL fragments
 *     @Query("SELECT * FROM orders {statusFilter} ORDER BY created_date DESC")
 *     List<Order> findOrders(@Define("statusFilter") String statusFilter);
 *     // Usage: findOrders("WHERE status IN ('PENDING', 'PROCESSING')")
 *     //     or: findOrders("") for all orders
 * }
 * }</pre>
 * 
 * <p>Best practices:</p>
 * <ul>
 *   <li>Always validate Define values against a whitelist when possible</li>
 *   <li>Use Bind for values, Define only for SQL structure</li>
 *   <li>Consider using enums or constants for Define values</li>
 *   <li>Document which values are safe for each Define parameter</li>
 * </ul>
 * 
 * @see DefineList
 * @see Bind
 * @see Select#hasDefineWithNamedParameter()
 * @see Update#hasDefineWithNamedParameter()
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface Define {

    /**
     * Specifies the name of the SQL template variable to be replaced.
     * If not specified (empty string), the parameter name will be used as the variable name.
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
     *     @Define("schema") String schemaName,
     *     @Define("table") String tableName,
     *     @Bind("id") long id
     * );
     * }</pre>
     * 
     * <p>Custom placeholder with default:</p>
     * <pre>{@code
     * // Custom syntax allows for more complex replacements
     * @Query("SELECT * FROM users {filter -> WHERE active = true}")
     * List<User> findUsers(@Define("{filter -> WHERE active = true}") String customFilter);
     * 
     * // Usage:
     * findUsers("WHERE role = 'ADMIN'"); // Replaces the entire {filter -> ...} block
     * findUsers(null); // Uses the default "WHERE active = true"
     * }</pre>
     * 
     * <p>Using parameter name when value is empty:</p>
     * <pre>{@code
     * @Query("SELECT {columns} FROM users")
     * List<Map<String, Object>> findWithColumns(@Define String columns);
     * // The parameter name "columns" is used as the placeholder name
     * }</pre>
     *
     * @return the SQL template variable name, or empty string to use parameter name
     */
    String value() default "";
}