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
import com.landawn.abacus.jdbc.OP;

/**
 * Defines a SELECT SQL operation for a DAO method.
 * This annotation allows you to specify SQL SELECT statements either inline or by reference
 * to an external SQL mapper, along with various execution parameters.
 * 
 * <p>The annotation supports multiple ways to specify SQL:</p>
 * <ul>
 *   <li>Inline SQL using the {@link #sql()} attribute</li>
 *   <li>Reference to external SQL using the {@link #id()} attribute</li>
 *   <li>Legacy inline SQL using the deprecated {@link #value()} attribute</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Inline SQL
 *     @Select(sql = "SELECT * FROM users WHERE status = :status")
 *     List<User> findByStatus(@Bind("status") String status);
 *     
 *     // External SQL reference
 *     @Select(id = "findActiveUsersWithOrders")
 *     List<User> findActiveUsersWithOrders();
 *     
 *     // With custom fetch size for large result sets
 *     @Select(sql = "SELECT * FROM users", fetchSize = 1000)
 *     Stream<User> streamAllUsers();
 *     
 *     // With query timeout
 *     @Select(sql = "SELECT * FROM users u JOIN orders o ON u.id = o.user_id", 
 *             queryTimeout = 30)
 *     List<UserWithOrders> findUsersWithOrders();
 *     
 *     // Dynamic SQL with Define annotation
 *     @Select(sql = "SELECT * FROM users ORDER BY {sortColumn} {sortOrder}",
 *             hasDefineWithNamedParameter = true)
 *     List<User> findAllSorted(@Define("sortColumn") String column, 
 *                             @Define("sortOrder") String order);
 * }
 * }</pre>
 *
 * @see Update
 * @see Bind
 * @see Define
 * @see SqlMapper
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Select {

    /**
     * Specifies the SQL SELECT statement or SQL mapper ID.
     * 
     * @deprecated Use {@link #sql()} for inline SQL statements or {@link #id()} for SQL mapper references.
     * This attribute is maintained for backward compatibility but should not be used in new code.
     * 
     * <p>Example of deprecated usage:</p>
     * <pre>{@code
     * @Select("SELECT * FROM users")  // Deprecated
     * List<User> findAll();
     * }</pre>
     *
     * @return the SQL statement or mapper ID
     */
    @Deprecated
    String value() default "";

    /**
     * Specifies an inline SQL SELECT statement.
     * Use this attribute when you want to define the SQL directly in the annotation.
     * 
     * <p>The SQL can include:</p>
     * <ul>
     *   <li>Named parameters using :paramName syntax</li>
     *   <li>Template variables using {variableName} syntax (requires hasDefineWithNamedParameter = true)</li>
     *   <li>Standard SQL features like JOINs, subqueries, CTEs, etc.</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(sql = "SELECT u.*, COUNT(o.id) as order_count " +
     *               "FROM users u LEFT JOIN orders o ON u.id = o.user_id " +
     *               "WHERE u.created_date > :startDate " +
     *               "GROUP BY u.id")
     * List<UserStats> findUserStats(@Bind("startDate") Date startDate);
     * }</pre>
     *
     * @return the SQL SELECT statement, or empty string if not used
     */
    String sql() default "";

    /**
     * Specifies the ID of a SQL statement defined in an external SQL mapper.
     * Use this attribute when SQL is defined in XML mapper files referenced by {@link SqlMapper}.
     * 
     * <p>This approach is recommended for:</p>
     * <ul>
     *   <li>Complex multi-line SQL statements</li>
     *   <li>SQL that needs to be shared across multiple methods</li>
     *   <li>Better separation of SQL from Java code</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(id = "findUsersByComplexCriteria")
     * List<User> findUsers(@Bind("criteria") SearchCriteria criteria);
     * }</pre>
     *
     * @return the SQL mapper ID, or empty string if not used
     */
    String id() default ""; // id defined SqlMapper

    /**
     * Specifies the JDBC fetch size for the query.
     * This controls how many rows the JDBC driver retrieves from the database in each round trip.
     * 
     * <p>Setting an appropriate fetch size can significantly improve performance for large result sets
     * by reducing network round trips. However, it also increases memory usage.</p>
     * 
     * <p>Guidelines:</p>
     * <ul>
     *   <li>Default (-1): Uses the JDBC driver's default fetch size</li>
     *   <li>Small result sets (< 100 rows): Use default or small values</li>
     *   <li>Large result sets: Use larger values (100-5000) based on row size</li>
     *   <li>Streaming results: Consider using very large values</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(sql = "SELECT * FROM large_table", fetchSize = 1000)
     * Stream<LargeRecord> streamLargeData();
     * }</pre>
     *
     * @return the fetch size, or -1 to use driver default
     */
    int fetchSize() default -1;

    /**
     * Specifies the query timeout in seconds.
     * If the query doesn't complete within this time, it will be cancelled and throw an exception.
     * 
     * <p>This is useful for:</p>
     * <ul>
     *   <li>Preventing long-running queries from blocking resources</li>
     *   <li>Implementing SLA requirements</li>
     *   <li>Protecting against accidental expensive queries</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(sql = "SELECT * FROM users WHERE complex_calculation(:param) > 0", 
     *         queryTimeout = 10)
     * List<User> findByComplexCriteria(@Bind("param") String param);
     * }</pre>
     *
     * @return the timeout in seconds, or -1 for no timeout
     */
    int queryTimeout() default -1;

    /**
     * Indicates whether the method has a single parameter that should be treated as a single value
     * for array/collection database columns.
     * 
     * <p>Set to {@code true} when:</p>
     * <ul>
     *   <li>The method has only one parameter</li>
     *   <li>The parameter is a Collection or array</li>
     *   <li>The target database column is also a Collection/array type (e.g., PostgreSQL array)</li>
     * </ul>
     * 
     * <p>When {@code false} (default), collections are typically expanded for IN clauses.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(sql = "SELECT * FROM products WHERE tags @> :tags", 
     *         isSingleParameter = true)
     * List<Product> findByTags(String[] tags); // PostgreSQL array containment
     * }</pre>
     *
     * @return {@code true} if the collection/array parameter should be treated as a single value
     */
    boolean isSingleParameter() default false;

    /**
     * Indicates whether the SQL contains template variables that need to be replaced using {@link Define} annotations.
     * 
     * <p>Set to {@code true} when using dynamic SQL construction with template variables in curly braces.
     * This enables preprocessing of the SQL to replace {variableName} placeholders with values from @Define parameters.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(sql = "SELECT {columns} FROM {table} WHERE status = :status",
     *         hasDefineWithNamedParameter = true)
     * List<Map<String, Object>> dynamicQuery(
     *     @Define("columns") String columns,
     *     @Define("table") String table,
     *     @Bind("status") String status
     * );
     * }</pre>
     * 
     * @return {@code true} if the SQL contains Define template variables
     * @see Define
     * @see DefineList
     */
    @Beta
    boolean hasDefineWithNamedParameter() default false;

    /**
     * Automatically adds a timestamp parameter to the query.
     * When set to {@code true}, a named parameter {@code :now} is automatically set to the current system time.
     * 
     * <p>This is useful for queries that need to filter by the current time without manually passing it:</p>
     * <ul>
     *   <li>Finding active records based on date ranges</li>
     *   <li>Querying recent data</li>
     *   <li>Time-based filtering</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(sql = "SELECT * FROM events WHERE start_time <= :now AND end_time >= :now",
     *         timestamped = true)
     * List<Event> findCurrentEvents(); // No need to pass current time
     * }</pre>
     * 
     * @return {@code true} to automatically inject current timestamp as :now parameter
     */
    @Beta
    boolean timestamped() default false;

    /**
     * Specifies the operation type for this SELECT query.
     * This can be used to customize how the query results are processed or to indicate special handling.
     * 
     * <p>Common operation types might include:</p>
     * <ul>
     *   <li>{@link OP#DEFAULT} - Standard SELECT operation</li>
     *   <li>Custom operations defined in {@link OP} for special processing</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Select(sql = "SELECT COUNT(*) FROM users", op = OP.COUNT)
     * long countUsers();
     * }</pre>
     *
     * @return the operation type for this query
     * @see OP
     */
    OP op() default OP.DEFAULT;
}